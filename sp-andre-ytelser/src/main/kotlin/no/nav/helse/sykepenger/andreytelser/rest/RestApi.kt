package no.nav.helse.sykepenger.andreytelser.rest

import com.github.navikt.tbd_libs.access_token.AccessTokenException
import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson3.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import no.nav.helse.sykepenger.andreytelser.rammeverk.plugins.configureCallId
import no.nav.helse.sykepenger.andreytelser.rammeverk.plugins.configureCallLogging
import no.nav.sykepenger.libs.ktor.auth.AZURE_AD_AUTHENTICATION_NAME
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.logging.navngittLogger
import tools.jackson.core.JacksonException
import java.io.IOException

private val logger = navngittLogger("no.nav.helse.sykepenger.andreytelser.rest")

internal fun Application.configureRestApi(
    personProvider: PersonPseudoIdProvider,
    tilgangskontroll: PopulasjonstilgangskontrollProvider,
    ruter: Route.(PersonTilgang) -> Unit = {},
) {
    configureCallId()
    configureCallLogging()
    install(ContentNegotiation) { jackson() }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            logger.debug("400: ugyldig forespørsel", cause)
            call.respondProblem(HttpStatusCode.BadRequest, "Ugyldig forespørsel")
        }
        exception<Throwable> { call, cause ->
            logger.error("Uventet feil ved API-kall", cause, "requestUri" to call.request.uri)
            call.respondProblem(HttpStatusCode.InternalServerError, "Intern serverfeil")
        }
    }
    routing {
        authenticate(AZURE_AD_AUTHENTICATION_NAME) {
            ruter(PersonTilgang(personProvider, tilgangskontroll))
        }
    }
}

internal class PersonTilgang(
    private val personProvider: PersonPseudoIdProvider,
    private val tilgangskontroll: PopulasjonstilgangskontrollProvider,
) {
    // Har svart med feil når den gir null, så kalleren skal bare returnere.
    suspend fun identMedTilgang(
        call: ApplicationCall,
        tilgang: Tilgang,
    ): Identitetsnummer? {
        val principal =
            call.principal<SaksbehandlerPrincipal>() ?: run {
                call.respond(HttpStatusCode.Unauthorized)
                return null
            }
        if (!principal.harTilgang(tilgang)) {
            logger.debug("403: saksbehandler mangler tilgang", "tilgang" to tilgang.name)
            call.respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
            return null
        }
        val ident =
            call.parameters["personPseudoId"]
                ?.let { runCatching { PersonPseudoId.fraString(it) }.getOrNull() }
                ?.let(personProvider::finnIdentitetsnummer)
                ?: run {
                    call.respondProblem(HttpStatusCode.NotFound, "Ikke funnet")
                    return null
                }
        val resultat =
            try {
                tilgangskontroll.kontrollerKjerneTilgang(principal.accessToken.value, ident.value)
            } catch (ex: IOException) {
                return call.respondTilgangsfeil(ex)
            } catch (ex: AccessTokenException) {
                return call.respondTilgangsfeil(ex)
            } catch (ex: JacksonException) {
                return call.respondTilgangsfeil(ex)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                return call.respondTilgangsfeil(ex)
            }
        return when (resultat) {
            TilgangskontrollResultat.Ok -> ident
            TilgangskontrollResultat.IdentIkkeFunnet -> {
                call.respondProblem(HttpStatusCode.NotFound, "Ikke funnet")
                null
            }
            is TilgangskontrollResultat.ManglerTilgang -> {
                logger.debug("403: populasjonstilgangskontrollen ga avslag", "tilgangSomMangler" to resultat.tilgangSomMangler.name)
                call.respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
                null
            }
            is TilgangskontrollResultat.UventetFeil -> {
                logger.debug("403: tilgangskontrollen feilet uventet", "forklaring" to resultat.menneskeligLesbarForklaring)
                call.respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
                null
            }
        }
    }

    private suspend fun ApplicationCall.respondTilgangsfeil(ex: Exception): Identitetsnummer? {
        logger.debug("403: tilgangskontrollen kunne ikke fullføres", ex)
        respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
        return null
    }
}

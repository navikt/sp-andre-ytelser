package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoId
import com.github.navikt.tbd_libs.personpseudoid.PersonPseudoIdProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.PopulasjonstilgangskontrollProvider
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.OpenApiConfig
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.configureOpenApiPlugin
import no.nav.helse.sykepenger.andreytelser.rest.configureRestApi
import no.nav.sykepenger.libs.ktor.auth.AZURE_AD_AUTHENTICATION_NAME
import no.nav.sykepenger.libs.ktor.auth.AccessToken
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.ktor.auth.Saksbehandler
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerOid
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val PORT = 8484
private const val LOKALT_TOKEN = "lokalt-token"

fun main() {
    val transaksjonProvider = InMemoryTransaksjonProvider()
    val server =
        embeddedServer(CIO, port = PORT) {
            installerLokalAutentisering()
            installerLokaltTokenEndepunkt()
            configureOpenApiPlugin(OpenApiConfig(eksponerOpenApi = true, tittel = "sp-andre-ytelser"))
            configureRestApi(LokalPersonPseudoIdProvider(), TillatAltTilgangskontroll()) { personTilgang ->
                endepunkter(transaksjonProvider, personTilgang)
            }
        }

    server.start(wait = false)
    println("LocalApp startet. Meldinger til Kafka blir liggende i den lokale outboxen.")
    println("OpenAPI: http://localhost:$PORT/api/openapi.json")
    println("Swagger UI: http://localhost:$PORT/api/swagger")
    Thread.currentThread().join()
}

// Lokalt finnes det ingen Azure AD, så alle slippes inn som en fiktiv saksbehandler med alle tilganger.
private fun Application.installerLokalAutentisering() {
    val principal =
        SaksbehandlerPrincipal(
            saksbehandler =
                Saksbehandler(
                    navIdent = NavIdent("Z999999"),
                    oid = SaksbehandlerOid(UUID.nameUUIDFromBytes("lokal-saksbehandler".toByteArray()).toString()),
                    navn = "Lokal Saksbehandler",
                ),
            tilganger = Tilgang.entries.toSet(),
            accessToken = AccessToken(LOKALT_TOKEN),
        )

    install(Authentication) {
        provider(AZURE_AD_AUTHENTICATION_NAME) {
            authenticate { context -> context.principal(principal) }
        }
    }
}

internal fun Application.installerLokaltTokenEndepunkt() {
    routing {
        get("/local-token") { call.respondText(LOKALT_TOKEN) }
    }
}

// ukjente pseudo-IDer blir mappet til fiktive personer
private class LokalPersonPseudoIdProvider : PersonPseudoIdProvider {
    private val personer = ConcurrentHashMap<PersonPseudoId, Identitetsnummer>()

    override fun nyPersonPseudoId(identitetsnummer: Identitetsnummer): PersonPseudoId = PersonPseudoId(UUID.randomUUID()).also { personer[it] = identitetsnummer }

    override fun finnIdentitetsnummer(personPseudoId: PersonPseudoId): Identitetsnummer = personer.getOrPut(personPseudoId) { Identitetsnummer(lagIdentitetsnummer()) }
}

private class TillatAltTilgangskontroll : PopulasjonstilgangskontrollProvider {
    override fun kontrollerKomplettTilgang(
        accessToken: String,
        fødselsnummer: String,
    ) = TilgangskontrollResultat.Ok

    override fun kontrollerKjerneTilgang(
        accessToken: String,
        fødselsnummer: String,
    ) = TilgangskontrollResultat.Ok

    override fun kontrollerKjerneTilgangForAnsatt(
        ansattId: String,
        fødselsnummer: String,
    ) = TilgangskontrollResultat.Ok
}

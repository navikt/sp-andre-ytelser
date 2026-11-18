@file:UseContextualSerialization(LocalDate::class)

package no.nav.helse.sykepenger.andreytelser.rest

import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserPeriode
import no.nav.helse.sykepenger.andreytelser.TransaksjonProvider
import no.nav.helse.sykepenger.andreytelser.Transaksjonskontekst
import no.nav.helse.sykepenger.andreytelser.YtelseMedGrad
import no.nav.helse.sykepenger.andreytelser.perioderMedTrekk
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.datoQueryparameter
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.feilsvar
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.uuidStiparameter
import no.nav.sykepenger.libs.ktor.auth.AppPrincipal
import no.nav.sykepenger.libs.ktor.auth.KlientId
import no.nav.sykepenger.libs.logging.navngittLogger
import java.time.LocalDate
import java.util.UUID

private val logger = navngittLogger("no.nav.helse.sykepenger.andreytelser.rest.GraderteAndreYtelserPerioderApi")

internal fun Route.graderteAndreYtelserPerioderApi(
    transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    sporbar: KlientId,
) {
    get("/api/graderte-andre-ytelser/versjoner/{versjon}/perioder", {
        operationId = "getGraderteAndreYtelserPerioder"
        tags("graderte-andre-ytelser")
        request {
            uuidStiparameter("versjon")
            datoQueryparameter("fom")
            datoQueryparameter("tom")
        }
        response { code(HttpStatusCode.OK) { body<List<ApiGraderteAndreYtelserPeriode>>() } }
        feilsvar(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.NotFound)
    }) {
        if (call.principal<AppPrincipal>()?.klientId != sporbar) {
            logger.debug("403: appen har ikke tilgang til periodene", "klientId" to call.principal<AppPrincipal>()?.klientId?.value)
            call.respondProblem(HttpStatusCode.Forbidden, "Mangler tilgang")
            return@get
        }
        val fom = call.request.queryParameters["fom"]?.tilDatoOrNull()
        val tom = call.request.queryParameters["tom"]?.tilDatoOrNull()
        if (fom == null || tom == null || fom > tom) {
            call.respondProblem(HttpStatusCode.BadRequest, "fom og tom må være gyldige datoer, og fom kan ikke være etter tom")
            return@get
        }
        val versjon = call.parameters["versjon"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val graderteAndreYtelser = versjon?.let { transaksjonProvider.transaksjon { it.graderteAndreYtelser.finnForVersjon(versjon) } }
        if (graderteAndreYtelser == null) {
            call.respondProblem(HttpStatusCode.NotFound, "Fant ikke versjonen")
            return@get
        }
        call.respond(graderteAndreYtelser.perioderMedTrekk(fom, tom).map { it.tilApi() })
    }
}

private fun String.tilDatoOrNull() = runCatching { LocalDate.parse(this) }.getOrNull()

@Serializable
internal data class ApiGraderteAndreYtelserPeriode(
    val fom: LocalDate,
    val tom: LocalDate,
    val ytelser: List<ApiYtelseMedGrad>,
)

@Serializable
internal data class ApiYtelseMedGrad(
    val type: ApiGraderteAndreYtelserType,
    val grad: Int,
)

private fun GraderteAndreYtelserPeriode.tilApi() =
    ApiGraderteAndreYtelserPeriode(
        fom = fom,
        tom = tom,
        ytelser = ytelser.map { it.tilApi() },
    )

private fun YtelseMedGrad.tilApi() = ApiYtelseMedGrad(type.tilApi(), grad)

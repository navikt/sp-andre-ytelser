package no.nav.helse.sykepenger.andreytelser.rest

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelser
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserDag
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEndretDag
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEvent
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType
import no.nav.helse.sykepenger.andreytelser.TransaksjonProvider
import no.nav.helse.sykepenger.andreytelser.Transaksjonskontekst
import no.nav.helse.sykepenger.andreytelser.Yrkesaktivitetstype
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

internal fun Route.graderteAndreYtelserApi(
    transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    personTilgang: PersonTilgang,
) {
    get("/api/personer/{personPseudoId}/graderte-andre-ytelser") {
        val ident = personTilgang.identMedTilgang(call, Tilgang.Les) ?: return@get
        val graderteAndreYtelser =
            transaksjonProvider.transaksjon { it.graderteAndreYtelser.finnAlle(ident) }
        call.respond(
            graderteAndreYtelser
                .map { it.tilApi() }
                .sortedBy { ytelser -> ytelser.dager.minOf { it.dato } },
        )
    }
}

internal enum class ApiGraderteAndreYtelserType {
    FORELDREPENGER,
    SVANGERSKAPSPENGER,
    OMSORGSPENGER,
    PLEIEPENGER,
    OPPLARINGSPENGER,
}

internal enum class ApiYrkesaktivitetstype {
    ARBEIDSTAKER,
    FRILANS,
    ARBEIDSLEDIG,
    SELVSTENDIG,
}

internal data class ApiGraderteAndreYtelser(
    val yrkesaktivitet: ApiYrkesaktivitetstype,
    val organisasjonsnummer: String?,
    val versjon: UUID,
    val dager: List<ApiGraderteAndreYtelserDag>,
    val events: List<ApiGraderteAndreYtelserEvent>,
)

internal data class ApiGraderteAndreYtelserDag(
    val dato: LocalDate,
    val andreYtelserGrader: Map<ApiGraderteAndreYtelserType, Int>,
    val sykdomsgrad: Int,
    val arbeidsgrad: Int,
)

internal data class ApiGraderteAndreYtelserEvent(
    val versjon: UUID,
    val tidspunkt: LocalDateTime,
    val utfortAvSaksbehandlerIdent: String,
    val notatTilBeslutter: String,
    val dager: List<ApiGraderteAndreYtelserEndretDag>,
)

internal data class ApiGraderteAndreYtelserEndretDag(
    val dato: LocalDate,
    val andreYtelserGrader: Map<ApiGraderteAndreYtelserType, Int>,
    val sykdomsgrad: Int,
    val arbeidsgrad: Int,
    val fraAndreYtelserGrader: Map<ApiGraderteAndreYtelserType, Int>?,
    val fraSykdomsgrad: Int?,
    val fraArbeidsgrad: Int?,
)

private fun GraderteAndreYtelser.tilApi() =
    ApiGraderteAndreYtelser(
        yrkesaktivitet = id.yrkesaktivitet.tilApi(),
        organisasjonsnummer = id.organisasjonsnummer,
        versjon = versjon,
        dager = dager.map { it.tilApi() },
        events = events.map { it.tilApi() },
    )

private fun GraderteAndreYtelserDag.tilApi() =
    ApiGraderteAndreYtelserDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader.mapKeys { it.key.tilApi() },
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = arbeidsgrad,
    )

private fun GraderteAndreYtelserEvent.tilApi() =
    ApiGraderteAndreYtelserEvent(
        versjon = versjon,
        tidspunkt = tidspunkt.atZone(ZoneId.of("Europe/Oslo")).toLocalDateTime(),
        utfortAvSaksbehandlerIdent = utførtAvSaksbehandlerIdent.value,
        notatTilBeslutter = notatTilBeslutter,
        dager = dager.map { it.tilApi() },
    )

private fun GraderteAndreYtelserEndretDag.tilApi() =
    ApiGraderteAndreYtelserEndretDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader.mapKeys { it.key.tilApi() },
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = arbeidsgrad,
        fraAndreYtelserGrader = fraAndreYtelserGrader?.mapKeys { it.key.tilApi() },
        fraSykdomsgrad = fraSykdomsgrad,
        fraArbeidsgrad = fraArbeidsgrad,
    )

private fun GraderteAndreYtelserType.tilApi() =
    when (this) {
        GraderteAndreYtelserType.FORELDREPENGER -> ApiGraderteAndreYtelserType.FORELDREPENGER
        GraderteAndreYtelserType.SVANGERSKAPSPENGER -> ApiGraderteAndreYtelserType.SVANGERSKAPSPENGER
        GraderteAndreYtelserType.OMSORGSPENGER -> ApiGraderteAndreYtelserType.OMSORGSPENGER
        GraderteAndreYtelserType.PLEIEPENGER -> ApiGraderteAndreYtelserType.PLEIEPENGER
        GraderteAndreYtelserType.OPPLARINGSPENGER -> ApiGraderteAndreYtelserType.OPPLARINGSPENGER
    }

private fun Yrkesaktivitetstype.tilApi() =
    when (this) {
        Yrkesaktivitetstype.ARBEIDSTAKER -> ApiYrkesaktivitetstype.ARBEIDSTAKER
        Yrkesaktivitetstype.FRILANS -> ApiYrkesaktivitetstype.FRILANS
        Yrkesaktivitetstype.ARBEIDSLEDIG -> ApiYrkesaktivitetstype.ARBEIDSLEDIG
        Yrkesaktivitetstype.SELVSTENDIG -> ApiYrkesaktivitetstype.SELVSTENDIG
    }

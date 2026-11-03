package no.nav.helse.sykepenger.andreytelser.rest

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelser
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserDag
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEndretDag
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEvent
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserId
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserValidator
import no.nav.helse.sykepenger.andreytelser.OutboxKonvolutt
import no.nav.helse.sykepenger.andreytelser.OutboxMelding
import no.nav.helse.sykepenger.andreytelser.TransaksjonProvider
import no.nav.helse.sykepenger.andreytelser.Transaksjonskontekst
import no.nav.helse.sykepenger.andreytelser.Yrkesaktivitetstype
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.logging.navngittLogger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private val logger = navngittLogger("no.nav.helse.sykepenger.andreytelser.rest.GraderteAndreYtelserApi")

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

    post("/api/personer/{personPseudoId}/graderte-andre-ytelser") {
        val ident = personTilgang.identMedTilgang(call, Tilgang.Skriv) ?: return@post
        val saksbehandlerIdent = call.principal<SaksbehandlerPrincipal>()!!.saksbehandler.navIdent
        val request = call.receive<ApiEndreGraderteAndreYtelserRequest>()
        val dager = request.dager.map { it.tilDomene() }

        valideringsfeil(GraderteAndreYtelserValidator.validerDager(dager))?.let { feil ->
            call.respondProblem(HttpStatusCode.BadRequest, feil.title, feil.name)
            return@post
        }

        val id = GraderteAndreYtelserId(ident, request.yrkesaktivitet.tilDomene(), request.organisasjonsnummer)
        val resultat =
            transaksjonProvider.transaksjon { kontekst ->
                lagreEndring(kontekst, id, request.versjon, dager, saksbehandlerIdent, request.notatTilBeslutter)
            }
        when (resultat) {
            is EndringResultat.Lagret -> call.respond(ApiEndreGraderteAndreYtelserResponse(resultat.versjon))
            EndringResultat.UtdatertTilstand ->
                ApiPostGraderteAndreYtelserFeil.UTDATERT_TILSTAND.let { call.respondProblem(HttpStatusCode.Conflict, it.title, it.name) }
        }
    }
}

private sealed interface EndringResultat {
    data class Lagret(
        val versjon: UUID,
    ) : EndringResultat

    data object UtdatertTilstand : EndringResultat
}

private fun lagreEndring(
    kontekst: Transaksjonskontekst,
    id: GraderteAndreYtelserId,
    forventetVersjon: UUID?,
    dager: List<GraderteAndreYtelserDag>,
    saksbehandlerIdent: NavIdent,
    notatTilBeslutter: String,
): EndringResultat {
    val eksisterende = kontekst.graderteAndreYtelser.finn(id)
    if (forventetVersjon != eksisterende?.versjon) {
        logger.info("Versjonen i forespørselen ($forventetVersjon) matcher ikke versjonen (${eksisterende?.versjon})")
        return EndringResultat.UtdatertTilstand
    }

    val graderteAndreYtelser =
        eksisterende ?: GraderteAndreYtelser.ny(id, dager, saksbehandlerIdent, notatTilBeslutter)
    val event =
        if (eksisterende == null) {
            graderteAndreYtelser.events.single()
        } else {
            eksisterende.endre(dager, saksbehandlerIdent, notatTilBeslutter)
        }

    if (event == null) {
        logger.info("Ingen endringer i graderte andre ytelser, lagrer ikke ny versjon")
        return EndringResultat.Lagret(graderteAndreYtelser.versjon)
    }

    kontekst.graderteAndreYtelser.lagre(graderteAndreYtelser)
    kontekst.outbox.leggTil(
        OutboxKonvolutt.ny(
            melding = OutboxMelding.GraderteAndreYtelserEndret(fom = event.dager.minOf { it.dato }),
            identitetsnummer = id.identitetsnummer,
        ),
    )
    logger.info("Lagret ny versjon av graderte andre ytelser (versjon: ${event.versjon})")
    return EndringResultat.Lagret(event.versjon)
}

private fun valideringsfeil(resultat: GraderteAndreYtelserValidator.Resultat) =
    when (resultat) {
        GraderteAndreYtelserValidator.Resultat.OK -> null
        GraderteAndreYtelserValidator.Resultat.ManglerEndringer -> ApiPostGraderteAndreYtelserFeil.MANGLER_ENDRINGER
        GraderteAndreYtelserValidator.Resultat.NegativGrad -> ApiPostGraderteAndreYtelserFeil.NEGATIV_GRAD
        GraderteAndreYtelserValidator.Resultat.TotalGradOver100 -> ApiPostGraderteAndreYtelserFeil.TOTAL_GRAD_OVER_100
    }

internal enum class ApiPostGraderteAndreYtelserFeil(
    val title: String,
) {
    TOTAL_GRAD_OVER_100("Total grad for en dag kan ikke overstige 100"),
    MANGLER_ENDRINGER("Mangler endringer"),
    NEGATIV_GRAD("Grad kan ikke være negativ"),
    UTDATERT_TILSTAND("Graderte andre ytelser er endret siden de ble hentet"),
}

internal data class ApiEndreGraderteAndreYtelserRequest(
    val yrkesaktivitet: ApiYrkesaktivitetstype,
    val organisasjonsnummer: String?,
    val versjon: UUID?,
    val behandlingId: UUID,
    val dager: List<ApiGraderteAndreYtelserDag>,
    val notatTilBeslutter: String,
)

internal data class ApiEndreGraderteAndreYtelserResponse(
    val versjon: UUID,
)

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

private fun ApiGraderteAndreYtelserDag.tilDomene() =
    GraderteAndreYtelserDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader.mapKeys { it.key.tilDomene() },
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = arbeidsgrad,
    )

private fun ApiGraderteAndreYtelserType.tilDomene() =
    when (this) {
        ApiGraderteAndreYtelserType.FORELDREPENGER -> GraderteAndreYtelserType.FORELDREPENGER
        ApiGraderteAndreYtelserType.SVANGERSKAPSPENGER -> GraderteAndreYtelserType.SVANGERSKAPSPENGER
        ApiGraderteAndreYtelserType.OMSORGSPENGER -> GraderteAndreYtelserType.OMSORGSPENGER
        ApiGraderteAndreYtelserType.PLEIEPENGER -> GraderteAndreYtelserType.PLEIEPENGER
        ApiGraderteAndreYtelserType.OPPLARINGSPENGER -> GraderteAndreYtelserType.OPPLARINGSPENGER
    }

private fun ApiYrkesaktivitetstype.tilDomene() =
    when (this) {
        ApiYrkesaktivitetstype.ARBEIDSTAKER -> Yrkesaktivitetstype.ARBEIDSTAKER
        ApiYrkesaktivitetstype.FRILANS -> Yrkesaktivitetstype.FRILANS
        ApiYrkesaktivitetstype.ARBEIDSLEDIG -> Yrkesaktivitetstype.ARBEIDSLEDIG
        ApiYrkesaktivitetstype.SELVSTENDIG -> Yrkesaktivitetstype.SELVSTENDIG
    }

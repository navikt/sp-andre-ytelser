@file:UseContextualSerialization(UUID::class, LocalDate::class, LocalDateTime::class)

package no.nav.helse.sykepenger.andreytelser.rest

import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelser
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserDag
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEndretDag
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEvent
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserId
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserValidator
import no.nav.helse.sykepenger.andreytelser.HandlingType
import no.nav.helse.sykepenger.andreytelser.OutboxKonvolutt
import no.nav.helse.sykepenger.andreytelser.OutboxMelding
import no.nav.helse.sykepenger.andreytelser.TransaksjonProvider
import no.nav.helse.sykepenger.andreytelser.Transaksjonskontekst
import no.nav.helse.sykepenger.andreytelser.Yrkesaktivitetstype
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.feilsvar
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.uuidStiparameter
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.ktor.auth.SaksbehandlerPrincipal
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.logging.MdcKey
import no.nav.sykepenger.libs.logging.medMdc
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
    get("/api/personer/{personPseudoId}/graderte-andre-ytelser", {
        operationId = "getGraderteAndreYtelser"
        tags("graderte-andre-ytelser")
        request { uuidStiparameter("personPseudoId") }
        response { code(HttpStatusCode.OK) { body<List<ApiGraderteAndreYtelser>>() } }
        feilsvar(HttpStatusCode.Forbidden, HttpStatusCode.NotFound)
    }) {
        val ident = personTilgang.identMedTilgang(call, Tilgang.Les) ?: return@get
        val graderteAndreYtelser =
            transaksjonProvider.transaksjon { it.graderteAndreYtelser.finnAlle(ident) }
        call.respond(
            graderteAndreYtelser
                .map { it.tilApi() }
                .sortedBy { ytelser -> ytelser.dager.minOf { it.dato } },
        )
    }

    post("/api/personer/{personPseudoId}/graderte-andre-ytelser", {
        operationId = "postGraderteAndreYtelser"
        tags("graderte-andre-ytelser")
        request {
            uuidStiparameter("personPseudoId")
            body<ApiEndreGraderteAndreYtelserRequest>()
        }
        response { code(HttpStatusCode.OK) { body<ApiEndreGraderteAndreYtelserResponse>() } }
        feilsvar(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.Conflict)
    }) {
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
            medMdc(MdcKey.IDENTITETSNUMMER to ident.value) {
                transaksjonProvider.transaksjon { kontekst ->
                    lagreEndring(kontekst, id, request, dager, saksbehandlerIdent)
                }
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
    request: ApiEndreGraderteAndreYtelserRequest,
    dager: List<GraderteAndreYtelserDag>,
    saksbehandlerIdent: NavIdent,
): EndringResultat {
    val eksisterende = kontekst.graderteAndreYtelser.finn(id)
    if (request.versjon != eksisterende?.versjon) {
        logger.info(
            "Versjonen i forespørselen matcher ikke gjeldende versjon av graderte andre ytelser",
            "forespurtVersjon" to request.versjon?.toString(),
            "gjeldendeVersjon" to eksisterende?.versjon?.toString(),
        )
        return EndringResultat.UtdatertTilstand
    }

    val graderteAndreYtelser =
        eksisterende ?: GraderteAndreYtelser.ny(id, dager, saksbehandlerIdent)
    val event =
        if (eksisterende == null) {
            graderteAndreYtelser.events.single()
        } else {
            eksisterende.endre(dager, saksbehandlerIdent)
        }

    if (event == null) {
        logger.info(
            "Ingen endringer i graderte andre ytelser, lagrer ikke ny versjon",
            "versjon" to graderteAndreYtelser.versjon.toString(),
        )
        return EndringResultat.Lagret(graderteAndreYtelser.versjon)
    }

    kontekst.graderteAndreYtelser.lagre(graderteAndreYtelser)
    kontekst.outbox.leggTil(
        OutboxKonvolutt.ny(
            melding =
                OutboxMelding.Saksbehandlerhandling(
                    saksbehandlerIdent = event.utførtAvSaksbehandlerIdent.value,
                    handlingId = event.versjon,
                    handlingType = HandlingType.GRADERTE_ANDRE_YTELSER_ENDRET,
                    kreverTotrinnsvurdering = true,
                    tidspunkt = event.tidspunkt,
                    behandlingId = request.behandlingId,
                ),
            identitetsnummer = id.identitetsnummer,
        ),
    )
    kontekst.outbox.leggTil(
        OutboxKonvolutt.ny(
            melding =
                OutboxMelding.GraderteAndreYtelserEndret(
                    fom = event.dager.minOf { it.dato },
                    yrkesaktivitetstype = id.yrkesaktivitet,
                    organisasjonsnummer = id.organisasjonsnummer,
                ),
            identitetsnummer = id.identitetsnummer,
        ),
    )
    logger.info(
        "Lagret ny versjon av graderte andre ytelser",
        "versjon" to event.versjon.toString(),
        "yrkesaktivitetstype" to id.yrkesaktivitet.name,
        "antallDager" to event.dager.size.toString(),
    )
    return EndringResultat.Lagret(event.versjon)
}

private fun valideringsfeil(resultat: GraderteAndreYtelserValidator.Resultat) =
    when (resultat) {
        GraderteAndreYtelserValidator.Resultat.OK -> null
        GraderteAndreYtelserValidator.Resultat.ManglerEndringer -> ApiPostGraderteAndreYtelserFeil.MANGLER_ENDRINGER
        GraderteAndreYtelserValidator.Resultat.GradUtenforGrenser -> ApiPostGraderteAndreYtelserFeil.GRAD_UTENFOR_GRENSER
        GraderteAndreYtelserValidator.Resultat.SumErIkke100 -> ApiPostGraderteAndreYtelserFeil.SUM_ER_IKKE_100
        GraderteAndreYtelserValidator.Resultat.ArbeidsgradOker -> ApiPostGraderteAndreYtelserFeil.ARBEIDSGRAD_OKER
        GraderteAndreYtelserValidator.Resultat.YtelserManglerTrekk -> ApiPostGraderteAndreYtelserFeil.YTELSER_MANGLER_TREKK
        GraderteAndreYtelserValidator.Resultat.ManglerBegrunnelse -> ApiPostGraderteAndreYtelserFeil.MANGLER_BEGRUNNELSE
    }

internal enum class ApiPostGraderteAndreYtelserFeil(
    val title: String,
) {
    MANGLER_ENDRINGER("Mangler endringer"),
    GRAD_UTENFOR_GRENSER("En grad er utenfor tillatte grenser"),
    SUM_ER_IKKE_100("Sykdomsgrad, arbeidsgrad og andre ytelser må til sammen bli 100"),
    ARBEIDSGRAD_OKER("Arbeidsgraden kan ikke øke ved fordeling av andre ytelser"),
    YTELSER_MANGLER_TREKK("Sykdomsgraden må være uendret når dagen ikke har andre ytelser"),
    MANGLER_BEGRUNNELSE("Hver dag må ha en begrunnelse"),
    UTDATERT_TILSTAND("Graderte andre ytelser er endret siden de ble hentet"),
}

@Serializable
internal data class ApiEndreGraderteAndreYtelserRequest(
    val yrkesaktivitet: ApiYrkesaktivitetstype,
    val organisasjonsnummer: String?,
    val versjon: UUID?,
    val behandlingId: UUID,
    val dager: List<ApiGraderteAndreYtelserDag>,
)

@Serializable
internal data class ApiEndreGraderteAndreYtelserResponse(
    val versjon: UUID,
)

@Serializable
internal enum class ApiGraderteAndreYtelserType {
    FORELDREPENGER,
    SVANGERSKAPSPENGER,
    OMSORGSPENGER,
    PLEIEPENGER,
    OPPLARINGSPENGER,
}

@Serializable
internal enum class ApiYrkesaktivitetstype {
    ARBEIDSTAKER,
    FRILANS,
    ARBEIDSLEDIG,
    SELVSTENDIG,
}

@Serializable
internal data class ApiGraderteAndreYtelser(
    val yrkesaktivitet: ApiYrkesaktivitetstype,
    val organisasjonsnummer: String?,
    val versjon: UUID,
    val dager: List<ApiGraderteAndreYtelserDag>,
    val events: List<ApiGraderteAndreYtelserEvent>,
)

@Serializable
internal data class ApiGraderteAndreYtelserDag(
    val dato: LocalDate,
    val andreYtelserGrader: Map<ApiGraderteAndreYtelserType, Int>,
    val sykdomsgrad: Int,
    val arbeidsgrad: Int,
    val sykdomsgradFørFordeling: Int,
    val begrunnelse: String,
)

@Serializable
internal data class ApiGraderteAndreYtelserEvent(
    val versjon: UUID,
    val tidspunkt: LocalDateTime,
    val utfortAvSaksbehandlerIdent: String,
    val dager: List<ApiGraderteAndreYtelserEndretDag>,
)

@Serializable
internal data class ApiGraderteAndreYtelserEndretDag(
    val dato: LocalDate,
    val andreYtelserGrader: Map<ApiGraderteAndreYtelserType, Int>,
    val sykdomsgrad: Int,
    val arbeidsgrad: Int,
    val sykdomsgradFørFordeling: Int,
    val begrunnelse: String,
    val fraAndreYtelserGrader: Map<ApiGraderteAndreYtelserType, Int>?,
    val fraSykdomsgrad: Int?,
    val fraArbeidsgrad: Int?,
    val fraSykdomsgradFørFordeling: Int?,
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
        sykdomsgradFørFordeling = sykdomsgradFørFordeling,
        begrunnelse = begrunnelse,
    )

private fun GraderteAndreYtelserEvent.tilApi() =
    ApiGraderteAndreYtelserEvent(
        versjon = versjon,
        tidspunkt = tidspunkt.atZone(ZoneId.of("Europe/Oslo")).toLocalDateTime(),
        utfortAvSaksbehandlerIdent = utførtAvSaksbehandlerIdent.value,
        dager = dager.map { it.tilApi() },
    )

private fun GraderteAndreYtelserEndretDag.tilApi() =
    ApiGraderteAndreYtelserEndretDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader.mapKeys { it.key.tilApi() },
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = arbeidsgrad,
        sykdomsgradFørFordeling = sykdomsgradFørFordeling,
        begrunnelse = begrunnelse,
        fraAndreYtelserGrader = fraAndreYtelserGrader?.mapKeys { it.key.tilApi() },
        fraSykdomsgrad = fraSykdomsgrad,
        fraArbeidsgrad = fraArbeidsgrad,
        fraSykdomsgradFørFordeling = fraSykdomsgradFørFordeling,
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
        sykdomsgradFørFordeling = sykdomsgradFørFordeling,
        begrunnelse = begrunnelse,
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

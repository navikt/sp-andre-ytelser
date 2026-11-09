package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal enum class HandlingType {
    GRADERTE_ANDRE_YTELSER_ENDRET,
}

internal fun graderteAndreYtelserEndret(
    identitetsnummer: Identitetsnummer,
    fom: LocalDate,
    yrkesaktivitetstype: Yrkesaktivitetstype,
    organisasjonsnummer: String?,
): String =
    JsonMessage
        .newMessage(
            "graderte_andre_ytelser_endret",
            buildMap<String, Any> {
                put("fødselsnummer", identitetsnummer.value)
                put("fom", fom)
                put("yrkesaktivitetstype", yrkesaktivitetstype.name)
                organisasjonsnummer?.let { put("organisasjonsnummer", it) }
            },
        ).toJson()

internal fun saksbehandlerhandling(
    identitetsnummer: Identitetsnummer,
    saksbehandlerIdent: String,
    handlingId: UUID,
    handlingType: HandlingType,
    kreverTotrinnsvurdering: Boolean,
    tidspunkt: Instant,
    behandlingId: UUID,
): String =
    JsonMessage
        .newMessage(
            "saksbehandlerhandling",
            mapOf(
                "fødselsnummer" to identitetsnummer.value,
                "saksbehandlerIdent" to saksbehandlerIdent,
                "handlingId" to handlingId,
                "handlingType" to handlingType.name,
                "kreverTotrinnsvurdering" to kreverTotrinnsvurdering,
                "tidspunkt" to tidspunkt,
                "behandlingId" to behandlingId,
            ),
        ).toJson()

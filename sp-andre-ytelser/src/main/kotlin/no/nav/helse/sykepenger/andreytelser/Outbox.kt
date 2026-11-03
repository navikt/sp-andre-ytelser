package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
internal value class OutboxKonvoluttId(
    val value: UUID,
) {
    override fun toString() = value.toString()

    companion object {
        fun ny() = OutboxKonvoluttId(UUID.randomUUID())
    }
}

internal data class OutboxKonvolutt(
    val id: OutboxKonvoluttId,
    val melding: OutboxMelding,
    val identitetsnummer: Identitetsnummer,
) {
    companion object {
        fun ny(
            melding: OutboxMelding,
            identitetsnummer: Identitetsnummer,
        ) = OutboxKonvolutt(
            id = OutboxKonvoluttId.ny(),
            melding = melding,
            identitetsnummer = identitetsnummer,
        )
    }
}

internal sealed interface OutboxMelding {
    data class GraderteAndreYtelserEndret(
        val fom: LocalDate,
        val yrkesaktivitetstype: Yrkesaktivitetstype,
        val organisasjonsnummer: String?,
    ) : OutboxMelding

    data class Saksbehandlerhandling(
        val saksbehandlerIdent: String,
        val handlingId: UUID,
        val handlingType: HandlingType,
        val kreverTotrinnsvurdering: Boolean,
        val tidspunkt: Instant,
        val behandlingId: UUID,
    ) : OutboxMelding
}

internal enum class HandlingType {
    GRADERTE_ANDRE_YTELSER_ENDRET,
}

internal interface Outbox {
    fun leggTil(konvolutt: OutboxKonvolutt)

    fun hentUpubliserte(maksAntall: Int = 50): List<OutboxKonvolutt>

    fun markerSomSendt(id: OutboxKonvoluttId)
}

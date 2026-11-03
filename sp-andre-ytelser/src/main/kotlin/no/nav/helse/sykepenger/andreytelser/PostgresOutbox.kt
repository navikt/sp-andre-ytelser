package no.nav.helse.sykepenger.andreytelser

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import kotliquery.Row
import kotliquery.Session
import kotliquery.queryOf
import org.intellij.lang.annotations.Language
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val objectMapper = jacksonObjectMapper()

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OutboxMeldingDto.GraderteAndreYtelserEndret::class, name = "GRADERTE_ANDRE_YTELSER_ENDRET"),
    JsonSubTypes.Type(value = OutboxMeldingDto.Saksbehandlerhandling::class, name = "SAKSBEHANDLERHANDLING"),
)
private sealed interface OutboxMeldingDto {
    data class GraderteAndreYtelserEndret(
        val fom: LocalDate,
        val yrkesaktivitetstype: YrkesaktivitetstypeDto,
        val organisasjonsnummer: String?,
    ) : OutboxMeldingDto

    data class Saksbehandlerhandling(
        val saksbehandlerIdent: String,
        val handlingId: UUID,
        val handlingType: HandlingTypeDto,
        val kreverTotrinnsvurdering: Boolean,
        val tidspunkt: Instant,
        val behandlingId: UUID,
    ) : OutboxMeldingDto
}

private enum class HandlingTypeDto {
    GRADERTE_ANDRE_YTELSER_ENDRET,
}

private enum class YrkesaktivitetstypeDto {
    ARBEIDSTAKER,
    FRILANS,
    ARBEIDSLEDIG,
    SELVSTENDIG,
}

private fun OutboxMelding.tilDto(): OutboxMeldingDto =
    when (this) {
        is OutboxMelding.GraderteAndreYtelserEndret ->
            OutboxMeldingDto.GraderteAndreYtelserEndret(
                fom = fom,
                yrkesaktivitetstype =
                    when (yrkesaktivitetstype) {
                        Yrkesaktivitetstype.ARBEIDSTAKER -> YrkesaktivitetstypeDto.ARBEIDSTAKER
                        Yrkesaktivitetstype.FRILANS -> YrkesaktivitetstypeDto.FRILANS
                        Yrkesaktivitetstype.ARBEIDSLEDIG -> YrkesaktivitetstypeDto.ARBEIDSLEDIG
                        Yrkesaktivitetstype.SELVSTENDIG -> YrkesaktivitetstypeDto.SELVSTENDIG
                    },
                organisasjonsnummer = organisasjonsnummer,
            )

        is OutboxMelding.Saksbehandlerhandling ->
            OutboxMeldingDto.Saksbehandlerhandling(
                saksbehandlerIdent = saksbehandlerIdent,
                handlingId = handlingId,
                handlingType =
                    when (handlingType) {
                        HandlingType.GRADERTE_ANDRE_YTELSER_ENDRET -> HandlingTypeDto.GRADERTE_ANDRE_YTELSER_ENDRET
                    },
                kreverTotrinnsvurdering = kreverTotrinnsvurdering,
                tidspunkt = tidspunkt,
                behandlingId = behandlingId,
            )
    }

private fun OutboxMeldingDto.tilOutboxMelding(): OutboxMelding =
    when (this) {
        is OutboxMeldingDto.GraderteAndreYtelserEndret ->
            OutboxMelding.GraderteAndreYtelserEndret(
                fom = fom,
                yrkesaktivitetstype =
                    when (yrkesaktivitetstype) {
                        YrkesaktivitetstypeDto.ARBEIDSTAKER -> Yrkesaktivitetstype.ARBEIDSTAKER
                        YrkesaktivitetstypeDto.FRILANS -> Yrkesaktivitetstype.FRILANS
                        YrkesaktivitetstypeDto.ARBEIDSLEDIG -> Yrkesaktivitetstype.ARBEIDSLEDIG
                        YrkesaktivitetstypeDto.SELVSTENDIG -> Yrkesaktivitetstype.SELVSTENDIG
                    },
                organisasjonsnummer = organisasjonsnummer,
            )

        is OutboxMeldingDto.Saksbehandlerhandling ->
            OutboxMelding.Saksbehandlerhandling(
                saksbehandlerIdent = saksbehandlerIdent,
                handlingId = handlingId,
                handlingType =
                    when (handlingType) {
                        HandlingTypeDto.GRADERTE_ANDRE_YTELSER_ENDRET -> HandlingType.GRADERTE_ANDRE_YTELSER_ENDRET
                    },
                kreverTotrinnsvurdering = kreverTotrinnsvurdering,
                tidspunkt = tidspunkt,
                behandlingId = behandlingId,
            )
    }

internal class PostgresOutbox(
    private val session: Session,
) : Outbox {
    override fun leggTil(konvolutt: OutboxKonvolutt) {
        @Language("PostgreSQL")
        val sql = """
            INSERT INTO outbox (id, melding, fødselsnummer)
            VALUES (:id, cast(:melding as jsonb), :fodselsnummer)
        """
        session.run(
            queryOf(
                sql,
                mapOf(
                    "id" to konvolutt.id.value,
                    "melding" to objectMapper.writeValueAsString(konvolutt.melding.tilDto()),
                    "fodselsnummer" to konvolutt.identitetsnummer.value,
                ),
            ).asUpdate,
        )
    }

    override fun hentUpubliserte(maksAntall: Int): List<OutboxKonvolutt> {
        @Language("PostgreSQL")
        val sql = """
            SELECT id, melding, fødselsnummer
            FROM outbox
            WHERE publisert_tidspunkt IS NULL
            ORDER BY sekvensnummer
            LIMIT :maksAntall
            FOR UPDATE
        """
        return session.run(queryOf(sql, mapOf("maksAntall" to maksAntall)).map(::tilOutboxKonvolutt).asList)
    }

    override fun markerSomSendt(id: OutboxKonvoluttId) {
        @Language("PostgreSQL")
        val sql = "UPDATE outbox SET publisert_tidspunkt = now() WHERE id = :id"
        session.run(queryOf(sql, mapOf("id" to id.value)).asUpdate)
    }

    private fun tilOutboxKonvolutt(row: Row) =
        OutboxKonvolutt(
            id = OutboxKonvoluttId(row.uuid("id")),
            melding = objectMapper.readValue<OutboxMeldingDto>(row.string("melding")).tilOutboxMelding(),
            identitetsnummer = Identitetsnummer(row.string("fødselsnummer")),
        )
}

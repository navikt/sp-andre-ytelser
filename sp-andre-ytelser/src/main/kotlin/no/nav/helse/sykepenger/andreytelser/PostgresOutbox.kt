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
import java.time.LocalDate

private val objectMapper = jacksonObjectMapper()

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OutboxMeldingDto.GraderteAndreYtelserEndret::class, name = "GRADERTE_ANDRE_YTELSER_ENDRET"),
)
private sealed interface OutboxMeldingDto {
    data class GraderteAndreYtelserEndret(
        val fom: LocalDate,
    ) : OutboxMeldingDto
}

private fun OutboxMelding.tilDto(): OutboxMeldingDto =
    when (this) {
        is OutboxMelding.GraderteAndreYtelserEndret -> OutboxMeldingDto.GraderteAndreYtelserEndret(fom = fom)
    }

private fun OutboxMeldingDto.tilOutboxMelding(): OutboxMelding =
    when (this) {
        is OutboxMeldingDto.GraderteAndreYtelserEndret -> OutboxMelding.GraderteAndreYtelserEndret(fom = fom)
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

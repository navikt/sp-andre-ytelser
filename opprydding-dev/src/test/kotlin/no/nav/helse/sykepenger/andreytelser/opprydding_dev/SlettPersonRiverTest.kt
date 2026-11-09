package no.nav.helse.sykepenger.andreytelser.opprydding_dev

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import kotliquery.queryOf
import kotliquery.sessionOf
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

internal class SlettPersonRiverTest {
    private val rapid = TestRapid().apply { SlettPersonRiver(this, Database.dataSource) }

    @BeforeEach
    fun beforeEach() {
        Database.reset()
        rapid.reset()
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun shutdown() {
            Database.shutdown()
        }
    }

    @Test
    fun `sletter graderte andre ytelser for personen`() {
        val fødselsnummer = lagIdentitetsnummer()
        val annenPerson = lagIdentitetsnummer()
        lagreEvent(fødselsnummer)
        lagreEvent(fødselsnummer)
        lagreEvent(annenPerson)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(0, antallEvents(fødselsnummer))
        assertEquals(1, antallEvents(annenPerson))
    }

    @Test
    fun `sletter outbox-meldinger for personen`() {
        val fødselsnummer = lagIdentitetsnummer()
        val annenPerson = lagIdentitetsnummer()
        lagreOutboxMelding(fødselsnummer)
        lagreOutboxMelding(annenPerson)

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(0, antallOutboxMeldinger(fødselsnummer))
        assertEquals(1, antallOutboxMeldinger(annenPerson))
    }

    @Test
    fun `publiserer person_slettet etter sletting`() {
        val fødselsnummer = lagIdentitetsnummer()

        rapid.sendTestMessage(slettPersonMelding(fødselsnummer))

        assertEquals(1, rapid.inspektør.size)
        val melding = rapid.inspektør.message(0)
        assertEquals("person_slettet", melding["@event_name"].asString())
        assertEquals(fødselsnummer, melding["fødselsnummer"].asString())
        assertEquals(fødselsnummer, rapid.inspektør.key(0))
    }

    private fun lagreEvent(fødselsnummer: String) {
        sessionOf(Database.dataSource).use { session ->
            session.run(
                queryOf(
                    """
                    INSERT INTO graderte_andre_ytelser_events (
                      versjon, fødselsnummer, yrkesaktivitetstype, organisasjonsnummer, tidspunkt,
                      utført_av_saksbehandler_ident, data_json
                    )
                    VALUES (:versjon, :fodselsnummer, 'FRILANS', NULL, now(), 'A123456', '{"dager":[]}'::jsonb)
                    """.trimIndent(),
                    mapOf("versjon" to UUID.randomUUID(), "fodselsnummer" to fødselsnummer),
                ).asUpdate,
            )
        }
    }

    private fun lagreOutboxMelding(fødselsnummer: String) {
        sessionOf(Database.dataSource).use { session ->
            session.run(
                queryOf(
                    "INSERT INTO outbox (id, fodselsnummer, melding) VALUES (gen_random_uuid(), :fodselsnummer, '{}'::jsonb)",
                    mapOf("fodselsnummer" to fødselsnummer),
                ).asUpdate,
            )
        }
    }

    private fun antallOutboxMeldinger(fødselsnummer: String): Int =
        sessionOf(Database.dataSource).use { session ->
            session.run(
                queryOf(
                    "SELECT COUNT(*) AS antall FROM outbox WHERE fodselsnummer = :fodselsnummer",
                    mapOf("fodselsnummer" to fødselsnummer),
                ).map { it.int("antall") }.asSingle,
            )!!
        }

    private fun antallEvents(fødselsnummer: String): Int =
        sessionOf(Database.dataSource).use { session ->
            session.run(
                queryOf(
                    "SELECT COUNT(*) AS antall FROM graderte_andre_ytelser_events WHERE fødselsnummer = :fodselsnummer",
                    mapOf("fodselsnummer" to fødselsnummer),
                ).map { it.int("antall") }.asSingle,
            )!!
        }

    private fun slettPersonMelding(fødselsnummer: String) =
        """
        {
            "@event_name": "slett_person",
            "@id": "${UUID.randomUUID()}",
            "fødselsnummer": "$fødselsnummer"
        }
        """.trimIndent()
}

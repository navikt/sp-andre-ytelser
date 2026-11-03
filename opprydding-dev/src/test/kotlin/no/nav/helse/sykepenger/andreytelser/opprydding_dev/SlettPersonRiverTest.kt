package no.nav.helse.sykepenger.andreytelser.opprydding_dev

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import kotliquery.queryOf
import kotliquery.sessionOf
import no.nav.sykepenger.libs.postgres.TestDatabase
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SlettPersonRiverTest {
    private val testDatabase = TestDatabase.start().also { it.migrer() }
    private val rapid = TestRapid().apply { SlettPersonRiver(this, testDatabase.dataSource) }

    @BeforeEach
    fun beforeEach() {
        testDatabase.tøm("graderte_andre_ytelser_events")
        rapid.reset()
    }

    @AfterAll
    fun shutdown() {
        testDatabase.stop()
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
        sessionOf(testDatabase.dataSource).use { session ->
            session.run(
                queryOf(
                    """
                    INSERT INTO graderte_andre_ytelser_events (
                      versjon, fødselsnummer, yrkesaktivitetstype, organisasjonsnummer, tidspunkt,
                      utført_av_saksbehandler_ident, notat_til_beslutter, data_json
                    )
                    VALUES (:versjon, :fodselsnummer, 'FRILANS', NULL, now(), 'A123456', 'notat', '{"dager":[]}'::jsonb)
                    """.trimIndent(),
                    mapOf("versjon" to UUID.randomUUID(), "fodselsnummer" to fødselsnummer),
                ).asUpdate,
            )
        }
    }

    private fun antallEvents(fødselsnummer: String): Int =
        sessionOf(testDatabase.dataSource).use { session ->
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

package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import kotliquery.sessionOf
import no.nav.sykepenger.libs.postgres.TestDatabase
import no.nav.sykepenger.libs.testing.testdata.jan
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PostgresOutboxTest {
    private val testDatabase = TestDatabase.start().also { it.migrer() }

    @BeforeEach
    fun tømDatabasen() {
        testDatabase.tøm("outbox")
    }

    @AfterAll
    fun stoppDatabasen() {
        testDatabase.stop()
    }

    @Test
    fun `henter upubliserte meldinger i rekkefølgen de ble lagt til`() {
        val konvolutter = (1..3).map { konvolutt(fom = it jan 2024) }
        medOutbox { outbox -> konvolutter.forEach(outbox::leggTil) }

        assertEquals(konvolutter, medOutbox { it.hentUpubliserte() })
        assertEquals(konvolutter.take(2), medOutbox { it.hentUpubliserte(maksAntall = 2) })
    }

    @Test
    fun `meldinger som er markert som sendt hentes ikke igjen`() {
        val (første, andre) = (1..2).map { konvolutt(fom = it jan 2024) }
        medOutbox { outbox ->
            outbox.leggTil(første)
            outbox.leggTil(andre)
            outbox.markerSomSendt(første.id)
        }

        assertEquals(listOf(andre), medOutbox { it.hentUpubliserte() })
        assertEquals(2, testDatabase.antallRader("outbox"))
    }

    @Test
    fun `lagrer og leser saksbehandlerhandling`() {
        val konvolutt =
            OutboxKonvolutt.ny(
                melding =
                    OutboxMelding.Saksbehandlerhandling(
                        saksbehandlerIdent = "A123456",
                        handlingId = UUID.randomUUID(),
                        handlingType = HandlingType.GRADERTE_ANDRE_YTELSER_ENDRET,
                        kreverTotrinnsvurdering = true,
                        tidspunkt = Instant.parse("2024-01-01T12:00:00.123456Z"),
                        behandlingId = UUID.randomUUID(),
                    ),
                identitetsnummer = Identitetsnummer(lagIdentitetsnummer()),
            )
        medOutbox { it.leggTil(konvolutt) }

        assertEquals(listOf(konvolutt), medOutbox { it.hentUpubliserte() })
    }

    @Test
    fun `lagrer og leser endringsmelding med organisasjonsnummer`() {
        val konvolutt =
            OutboxKonvolutt.ny(
                melding = OutboxMelding.GraderteAndreYtelserEndret(1 jan 2024, Yrkesaktivitetstype.ARBEIDSTAKER, lagOrganisasjonsnummer()),
                identitetsnummer = Identitetsnummer(lagIdentitetsnummer()),
            )
        medOutbox { it.leggTil(konvolutt) }

        assertEquals(listOf(konvolutt), medOutbox { it.hentUpubliserte() })
    }

    private fun konvolutt(fom: LocalDate) =
        OutboxKonvolutt.ny(
            melding = OutboxMelding.GraderteAndreYtelserEndret(fom, Yrkesaktivitetstype.FRILANS, organisasjonsnummer = null),
            identitetsnummer = Identitetsnummer(lagIdentitetsnummer()),
        )

    private fun <T> medOutbox(block: (PostgresOutbox) -> T): T = sessionOf(testDatabase.dataSource).use { block(PostgresOutbox(it)) }
}

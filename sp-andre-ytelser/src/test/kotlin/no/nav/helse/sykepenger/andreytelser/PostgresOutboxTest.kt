package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import kotliquery.sessionOf
import no.nav.sykepenger.libs.leaderelection.LeaderElection
import no.nav.sykepenger.libs.outbox.OutboxPubliseringsjobb
import no.nav.sykepenger.libs.postgres.TestDatabase
import no.nav.sykepenger.libs.testing.testdata.jan
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PostgresOutboxTest {
    private val testDatabase = TestDatabase.start().also { it.migrer() }
    private val identitetsnummer = Identitetsnummer(lagIdentitetsnummer())

    @BeforeEach
    fun tømDatabasen() {
        testDatabase.tøm("outbox")
    }

    @AfterAll
    fun stoppDatabasen() {
        testDatabase.stop()
    }

    @Test
    fun `publiseringsjobben fra sykepenger-libs publiserer meldingene i rekkefølge med identitetsnummeret som nøkkel`() {
        val meldinger = (1..3).map { endret(fom = it jan 2024) }
        sessionOf(testDatabase.dataSource).use { session ->
            session.transaction { transaksjon ->
                val outbox = PostgresOutbox(transaksjon)
                meldinger.forEach { outbox.leggTil(identitetsnummer, it) }
            }
        }
        val rapid = TestRapid()

        OutboxPubliseringsjobb(testDatabase.dataSource, rapid, LeaderElection { true }).publiserUpubliserte()

        assertEquals(
            listOf("2024-01-01", "2024-01-02", "2024-01-03"),
            (0 until rapid.inspektør.size).map {
                rapid.inspektør
                    .message(it)
                    .path("fom")
                    .asString()
            },
        )
        assertEquals(List(3) { identitetsnummer.value }, (0 until rapid.inspektør.size).map { rapid.inspektør.key(it) })
    }

    @Test
    fun `meldingen lagres ikke når transaksjonen rulles tilbake`() {
        runCatching {
            sessionOf(testDatabase.dataSource).use { session ->
                session.transaction { transaksjon ->
                    PostgresOutbox(transaksjon).leggTil(identitetsnummer, endret(fom = 1 jan 2024))
                    error("Ruller tilbake")
                }
            }
        }

        assertEquals(0, testDatabase.antallRader("outbox"))
    }

    private fun endret(fom: LocalDate) = graderteAndreYtelserEndret(identitetsnummer, fom, Yrkesaktivitetstype.FRILANS, organisasjonsnummer = null)
}

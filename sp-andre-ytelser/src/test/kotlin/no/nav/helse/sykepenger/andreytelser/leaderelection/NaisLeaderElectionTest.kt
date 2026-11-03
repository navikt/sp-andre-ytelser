package no.nav.helse.sykepenger.andreytelser.leaderelection

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import no.nav.helse.sykepenger.andreytelser.TestElector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@Timeout(10)
internal class NaisLeaderElectionTest {
    private val hostname = "sp-andre-ytelser-test"
    private val elector = TestElector(hostname)
    private val rapid = TestRapid()
    private val leaderElection = NaisLeaderElection(elector.url, hostname, 10.milliseconds)

    @AfterEach
    fun ryddOpp() {
        leaderElection.onShutdownSignal(rapid)
        elector.close()
    }

    @Test
    fun `er ikke leader før elector har sendt en hendelse`() {
        assertFalse(leaderElection.isLeader())
    }

    @Test
    fun `er leader når elector sender vårt hostname`() {
        leaderElection.lesStrømmenTilDenLukkes()

        assertTrue(leaderElection.isLeader())
    }

    @Test
    fun `er ikke leader når elector sender en annen pod`() {
        elector.hendelser = listOf("""{"name":"en-annen-pod"}""")

        leaderElection.lesStrømmenTilDenLukkes()

        assertFalse(leaderElection.isLeader())
    }

    @Test
    fun `siste hendelse fra elector gjelder`() {
        elector.hendelser = listOf("""{"name":"$hostname"}""", """{"name":"en-annen-pod"}""")

        leaderElection.lesStrømmenTilDenLukkes()

        assertFalse(leaderElection.isLeader())
    }

    @Test
    fun `er ikke leader når elector ikke har valgt noen`() {
        elector.hendelser = listOf("{}")

        leaderElection.lesStrømmenTilDenLukkes()

        assertFalse(leaderElection.isLeader())
    }

    @Test
    fun `beholder kjent leader når elector svarer med feil`() {
        leaderElection.lesStrømmenTilDenLukkes()
        elector.statuskode = 503

        assertThrows<IllegalStateException> { leaderElection.lesStrømmenTilDenLukkes() }

        assertTrue(leaderElection.isLeader())
    }

    @Test
    fun `ugyldig JSON gir feil og gjør ikke poden til leader`() {
        elector.hendelser = listOf("ugyldig JSON")

        assertThrows<JacksonException> { leaderElection.lesStrømmenTilDenLukkes() }

        assertFalse(leaderElection.isLeader())
    }

    @Test
    fun `kobler til igjen etter at strømmen lukkes og følger ny leader`() {
        leaderElection.onStartup(rapid)
        ventTil { leaderElection.isLeader() }
        elector.hendelser = listOf("""{"name":"en-annen-pod"}""")

        ventTil { !leaderElection.isLeader() }

        assertTrue(elector.tilkoblinger.get() >= 2)
    }

    @Test
    fun `prøver igjen etter tilkoblingsfeil`() {
        elector.statuskode = 503
        leaderElection.onStartup(rapid)
        ventTil { elector.tilkoblinger.get() >= 2 }
        assertFalse(leaderElection.isLeader())

        elector.statuskode = 200

        ventTil { leaderElection.isLeader() }
    }

    @Test
    fun `stopper abonnementet selv om SSE-strømmen fortsatt er åpen`() {
        TestElector(hostname, holdStrømÅpen = true).use { åpenElector ->
            val abonnement = NaisLeaderElection(åpenElector.url, hostname)
            try {
                abonnement.onStartup(rapid)
                ventTil { abonnement.isLeader() }
            } finally {
                abonnement.onShutdownSignal(rapid)
            }

            assertFalse(abonnement.isLeader())
        }
    }

    private fun ventTil(betingelse: () -> Boolean) {
        val frist = Instant.now().plusSeconds(5)
        while (!betingelse() && Instant.now() < frist) Thread.sleep(10)
        assertTrue(betingelse(), "Ventet for lenge på leader-endring")
    }
}

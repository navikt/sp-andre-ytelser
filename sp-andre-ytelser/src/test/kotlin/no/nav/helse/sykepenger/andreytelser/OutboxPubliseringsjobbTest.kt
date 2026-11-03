package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import com.github.navikt.tbd_libs.rapids_and_rivers_api.FailedMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.OutgoingMessage
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import com.github.navikt.tbd_libs.rapids_and_rivers_api.SentMessage
import no.nav.sykepenger.libs.testing.testdata.jan
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class OutboxPubliseringsjobbTest {
    private val transaksjon = InMemoryTransaksjonProvider()
    private val identitetsnummer = Identitetsnummer(lagIdentitetsnummer())

    private fun nyKonvolutt(fom: LocalDate = 1 jan 2024) = OutboxKonvolutt.ny(melding = OutboxMelding.GraderteAndreYtelserEndret(fom = fom), identitetsnummer = identitetsnummer)

    @Test
    fun `publiserer graderte_andre_ytelser_endret og markerer meldingen som publisert`() {
        val rapid = TestRapid()
        transaksjon.outbox.leggTil(nyKonvolutt(fom = 12 jan 2024))

        OutboxPubliseringsjobb(rapid, transaksjon).kjørEnRunde()

        assertEquals(1, rapid.inspektør.size)
        val publisert = rapid.inspektør.message(0)
        assertEquals("graderte_andre_ytelser_endret", publisert.path("@event_name").asString())
        assertEquals(identitetsnummer.value, publisert.path("fødselsnummer").asString())
        assertEquals("2024-01-12", publisert.path("fom").asString())
        assertEquals(identitetsnummer.value, rapid.inspektør.key(0))
        assertTrue(transaksjon.outbox.hentUpubliserte().isEmpty())
    }

    @Test
    fun `publiserer saksbehandlerhandling i formatet Spesialist forventer`() {
        val rapid = TestRapid()
        val handlingId = UUID.randomUUID()
        val behandlingId = UUID.randomUUID()
        transaksjon.outbox.leggTil(
            OutboxKonvolutt.ny(
                melding =
                    OutboxMelding.Saksbehandlerhandling(
                        saksbehandlerIdent = "A123456",
                        handlingId = handlingId,
                        handlingType = HandlingType.GRADERTE_ANDRE_YTELSER_ENDRET,
                        kreverTotrinnsvurdering = true,
                        tidspunkt = Instant.parse("2024-01-01T12:00:00Z"),
                        behandlingId = behandlingId,
                    ),
                identitetsnummer = identitetsnummer,
            ),
        )

        OutboxPubliseringsjobb(rapid, transaksjon).kjørEnRunde()

        val publisert = rapid.inspektør.message(0)
        assertEquals("saksbehandlerhandling", publisert.path("@event_name").asString())
        assertEquals(identitetsnummer.value, publisert.path("fødselsnummer").asString())
        assertEquals("A123456", publisert.path("saksbehandlerIdent").asString())
        assertEquals(handlingId.toString(), publisert.path("handlingId").asString())
        assertEquals("GRADERTE_ANDRE_YTELSER_ENDRET", publisert.path("handlingType").asString())
        assertEquals(true, publisert.path("kreverTotrinnsvurdering").asBoolean())
        assertEquals(Instant.parse("2024-01-01T12:00:00Z"), Instant.parse(publisert.path("tidspunkt").asString()))
        assertEquals(behandlingId.toString(), publisert.path("behandlingId").asString())
        assertTrue(publisert.path("skjæringstidspunkt").isMissingNode)
        assertEquals(identitetsnummer.value, rapid.inspektør.key(0))
    }

    @Test
    fun `flere meldinger publiseres i rekkefølge`() {
        val rapid = TestRapid()
        listOf(1 jan 2024, 2 jan 2024, 3 jan 2024).forEach { transaksjon.outbox.leggTil(nyKonvolutt(fom = it)) }

        OutboxPubliseringsjobb(rapid, transaksjon).kjørEnRunde()

        assertEquals(
            listOf("2024-01-01", "2024-01-02", "2024-01-03"),
            (0 until rapid.inspektør.size).map {
                rapid.inspektør
                    .message(it)
                    .path("fom")
                    .asString()
            },
        )
        assertTrue(transaksjon.outbox.hentUpubliserte().isEmpty())
    }

    @Test
    fun `ingen upubliserte meldinger gir ingen publisering`() {
        val rapid = TestRapid()

        OutboxPubliseringsjobb(rapid, transaksjon).kjørEnRunde()

        assertEquals(0, rapid.inspektør.size)
    }

    @Test
    fun `feil under publisering markerer ikke meldingen som publisert, og jobben kastes ikke videre`() {
        val konvolutt = nyKonvolutt()
        transaksjon.outbox.leggTil(konvolutt)

        OutboxPubliseringsjobb(SvikterVedPubliseringNrRapid(feilerPå = 1), transaksjon).kjørEnRunde()

        assertEquals(listOf(konvolutt), transaksjon.outbox.hentUpubliserte())
    }

    @Test
    fun `feil ved publisering av melding nummer to lar senere meldinger upublisert`() {
        val konvolutter = (1..3).map { nyKonvolutt() }
        konvolutter.forEach(transaksjon.outbox::leggTil)

        OutboxPubliseringsjobb(SvikterVedPubliseringNrRapid(feilerPå = 2), transaksjon).kjørEnRunde()

        assertEquals(konvolutter.drop(1), transaksjon.outbox.hentUpubliserte())
    }

    private class SvikterVedPubliseringNrRapid(
        private val feilerPå: Int,
    ) : RapidsConnection() {
        private var publiseringer = 0

        override fun publish(message: String) {
            publish(listOf(OutgoingMessage(message)))
        }

        override fun publish(
            key: String,
            message: String,
        ) {
            publish(listOf(OutgoingMessage(message, key)))
        }

        override fun publish(messages: List<OutgoingMessage>): Pair<List<SentMessage>, List<FailedMessage>> {
            publiseringer++
            val feiledeMeldinger =
                if (publiseringer == feilerPå) {
                    messages.mapIndexed { index, message -> FailedMessage(index, message, RuntimeException("Kafka er nede")) }
                } else {
                    emptyList()
                }
            return emptyList<SentMessage>() to feiledeMeldinger
        }

        override fun rapidName() = "svikter-rapid"

        override fun start() = Unit

        override fun stop() = Unit
    }
}

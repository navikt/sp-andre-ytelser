package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import no.nav.sykepenger.libs.testing.testdata.jan
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagNavIdent
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class RapidmeldingerTest {
    private val identitetsnummer = Identitetsnummer(lagIdentitetsnummer())

    @Test
    fun `graderte_andre_ytelser_endret har formatet Spleis forventer`() {
        val organisasjonsnummer = lagOrganisasjonsnummer()

        val melding =
            les(graderteAndreYtelserEndret(identitetsnummer, 12 jan 2024, Yrkesaktivitetstype.ARBEIDSTAKER, organisasjonsnummer))

        assertEquals("graderte_andre_ytelser_endret", melding.path("@event_name").asString())
        assertEquals(identitetsnummer.value, melding.path("fødselsnummer").asString())
        assertEquals("2024-01-12", melding.path("fom").asString())
        assertEquals("ARBEIDSTAKER", melding.path("yrkesaktivitetstype").asString())
        assertEquals(organisasjonsnummer, melding.path("organisasjonsnummer").asString())
        assertFalse(melding.path("@id").isMissingNode)
    }

    @Test
    fun `graderte_andre_ytelser_endret utelater organisasjonsnummer når yrkesaktiviteten ikke har det`() {
        val melding = les(graderteAndreYtelserEndret(identitetsnummer, 1 jan 2024, Yrkesaktivitetstype.FRILANS, organisasjonsnummer = null))

        assertEquals("FRILANS", melding.path("yrkesaktivitetstype").asString())
        assertFalse(melding.has("organisasjonsnummer"))
    }

    @Test
    fun `saksbehandlerhandling har formatet Spesialist forventer`() {
        val saksbehandlerIdent = lagNavIdent()
        val handlingId = UUID.randomUUID()
        val behandlingId = UUID.randomUUID()

        val melding =
            les(
                saksbehandlerhandling(
                    identitetsnummer = identitetsnummer,
                    saksbehandlerIdent = saksbehandlerIdent,
                    handlingId = handlingId,
                    handlingType = HandlingType.GRADERTE_ANDRE_YTELSER_ENDRET,
                    kreverTotrinnsvurdering = true,
                    tidspunkt = Instant.parse("2024-01-01T12:00:00Z"),
                    behandlingId = behandlingId,
                ),
            )

        assertEquals("saksbehandlerhandling", melding.path("@event_name").asString())
        assertEquals(identitetsnummer.value, melding.path("fødselsnummer").asString())
        assertEquals(saksbehandlerIdent, melding.path("saksbehandlerIdent").asString())
        assertEquals(handlingId.toString(), melding.path("handlingId").asString())
        assertEquals("GRADERTE_ANDRE_YTELSER_ENDRET", melding.path("handlingType").asString())
        assertEquals(true, melding.path("kreverTotrinnsvurdering").asBoolean())
        assertEquals(Instant.parse("2024-01-01T12:00:00Z"), Instant.parse(melding.path("tidspunkt").asString()))
        assertEquals(behandlingId.toString(), melding.path("behandlingId").asString())
        assertTrue(melding.path("skjæringstidspunkt").isMissingNode)
    }

    private fun les(melding: String) = jacksonObjectMapper().readTree(melding)
}

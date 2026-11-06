package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.FORELDREPENGER
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.OMSORGSPENGER
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.testing.testdata.jan
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagNavIdent
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraderteAndreYtelserTest {
    private val id =
        GraderteAndreYtelserId(
            identitetsnummer = Identitetsnummer(lagIdentitetsnummer()),
            yrkesaktivitet = Yrkesaktivitetstype.ARBEIDSTAKER,
            organisasjonsnummer = lagOrganisasjonsnummer(),
        )
    private val saksbehandlerIdent = NavIdent(lagNavIdent())

    @Test
    fun `kan opprette graderte andre ytelser`() {
        val dager = listOf(dag(1 jan 2024), dag(2 jan 2024))

        val graderteAndreYtelser = ny(dager)

        assertEquals(id, graderteAndreYtelser.id)
        assertEquals(dager, graderteAndreYtelser.dager)
        val event = graderteAndreYtelser.events.single()
        assertEquals(dager, event.dager.map { it.tilGraderteAndreYtelserDag() })
        assertEquals(saksbehandlerIdent, event.utførtAvSaksbehandlerIdent)
        assertEquals(event.versjon, graderteAndreYtelser.versjon)
    }

    @Test
    fun `endring erstatter bare dagene som er med i eventet`() {
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024), dag(2 jan 2024), dag(3 jan 2024)))

        val event = graderteAndreYtelser.endre(listOf(dag(2 jan 2024, sykdomsgrad = 20)), saksbehandlerIdent)

        assertEquals(listOf(dag(2 jan 2024, sykdomsgrad = 20)), event?.dager?.map { it.tilGraderteAndreYtelserDag() })
        assertEquals(
            listOf(dag(1 jan 2024), dag(2 jan 2024, sykdomsgrad = 20), dag(3 jan 2024)),
            graderteAndreYtelser.dager,
        )
        assertEquals(2, graderteAndreYtelser.events.size)
        assertEquals(event?.versjon, graderteAndreYtelser.versjon)
    }

    @Test
    fun `endring kan legge til nye dager`() {
        val graderteAndreYtelser = ny(listOf(dag(2 jan 2024)))

        graderteAndreYtelser.endre(listOf(dag(1 jan 2024)), saksbehandlerIdent)

        assertEquals(listOf(dag(1 jan 2024), dag(2 jan 2024)), graderteAndreYtelser.dager)
    }

    @Test
    fun `dager som er like dagens tilstand tas ikke med i eventet`() {
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024), dag(2 jan 2024)))

        val event =
            graderteAndreYtelser.endre(
                listOf(dag(1 jan 2024), dag(2 jan 2024, andreYtelserGrader = mapOf(OMSORGSPENGER to 50))),
                saksbehandlerIdent,
            )

        assertEquals(
            listOf(dag(2 jan 2024, andreYtelserGrader = mapOf(OMSORGSPENGER to 50))),
            event?.dager?.map { it.tilGraderteAndreYtelserDag() },
        )
    }

    @Test
    fun `eventet tar med verdiene dagen hadde før endringen`() {
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024)))

        val event =
            graderteAndreYtelser.endre(
                listOf(dag(1 jan 2024, andreYtelserGrader = mapOf(OMSORGSPENGER to 40), sykdomsgrad = 60, arbeidsgrad = 0)),
                saksbehandlerIdent,
            )

        assertEquals(
            listOf(
                GraderteAndreYtelserEndretDag(
                    dato = 1 jan 2024,
                    andreYtelserGrader = mapOf(OMSORGSPENGER to 40),
                    sykdomsgrad = 60,
                    arbeidsgrad = 0,
                    sykdomsgradFørFordeling = 100,
                    begrunnelse = "en begrunnelse",
                    fraAndreYtelserGrader = mapOf(FORELDREPENGER to 50),
                    fraSykdomsgrad = 30,
                    fraArbeidsgrad = 20,
                    fraSykdomsgradFørFordeling = 80,
                ),
            ),
            event?.dager,
        )
    }

    @Test
    fun `eventet har ingen fra-verdier for dager som ikke fantes fra før`() {
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024)))

        val event = graderteAndreYtelser.endre(listOf(dag(2 jan 2024)), saksbehandlerIdent)

        val endretDag = event?.dager?.single()
        assertEquals(2 jan 2024, endretDag?.dato)
        assertNull(endretDag?.fraAndreYtelserGrader)
        assertNull(endretDag?.fraSykdomsgrad)
        assertNull(endretDag?.fraArbeidsgrad)
        assertNull(endretDag?.fraSykdomsgradFørFordeling)
    }

    @Test
    fun `fra-verdiene er null når strømmen opprettes`() {
        val event = ny(listOf(dag(1 jan 2024))).events.single()

        assertTrue(
            event.dager.all {
                it.fraAndreYtelserGrader == null && it.fraSykdomsgrad == null && it.fraArbeidsgrad == null && it.fraSykdomsgradFørFordeling == null
            },
        )
    }

    @Test
    fun `ny begrunnelse uten endret fordeling gir et nytt event`() {
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024)))

        val event =
            graderteAndreYtelser.endre(listOf(dag(1 jan 2024, begrunnelse = "ny begrunnelse")), saksbehandlerIdent)

        assertEquals(listOf("ny begrunnelse"), event?.dager?.map { it.begrunnelse })
        assertEquals(listOf(dag(1 jan 2024, begrunnelse = "ny begrunnelse")), graderteAndreYtelser.dager)
    }

    @Test
    fun `ingen event når alle dagene er like dagens tilstand`() {
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024)))
        val versjonFør = graderteAndreYtelser.versjon

        val event = graderteAndreYtelser.endre(listOf(dag(1 jan 2024)), saksbehandlerIdent)

        assertNull(event)
        assertEquals(1, graderteAndreYtelser.events.size)
        assertEquals(versjonFør, graderteAndreYtelser.versjon)
    }

    @Test
    fun `versjoner sorteres etter tidspunkt`() {
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024)))
        graderteAndreYtelser.endre(listOf(dag(1 jan 2024, sykdomsgrad = 10)), saksbehandlerIdent)
        graderteAndreYtelser.endre(listOf(dag(1 jan 2024, sykdomsgrad = 20)), saksbehandlerIdent)

        val versjoner = graderteAndreYtelser.events.map { it.versjon }
        assertEquals(versjoner.sorted(), versjoner)
        assertTrue(versjoner.all { it.version() == 7 })
    }

    @Test
    fun `senere event overskriver tidligere for samme dato`() {
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024)))
        graderteAndreYtelser.endre(listOf(dag(1 jan 2024, sykdomsgrad = 10)), saksbehandlerIdent)
        graderteAndreYtelser.endre(listOf(dag(1 jan 2024, sykdomsgrad = 20)), saksbehandlerIdent)

        assertEquals(listOf(dag(1 jan 2024, sykdomsgrad = 20)), graderteAndreYtelser.dager)
    }

    @Test
    fun `fraLagring gir samme tilstand som originalen, uavhengig av rekkefølgen eventene kommer i`() {
        val original = ny(listOf(dag(1 jan 2024), dag(2 jan 2024)))
        original.endre(listOf(dag(2 jan 2024, sykdomsgrad = 10)), saksbehandlerIdent)
        original.endre(listOf(dag(2 jan 2024, sykdomsgrad = 20), dag(3 jan 2024)), saksbehandlerIdent)

        val gjenoppbygd = GraderteAndreYtelser.fraLagring(id, original.events.reversed())

        assertEquals(original.dager, gjenoppbygd.dager)
        assertEquals(original.events, gjenoppbygd.events)
        assertEquals(original.versjon, gjenoppbygd.versjon)
    }

    @Test
    fun `finnEvent finner eventet med gitt versjon`() {
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024)))
        val event = graderteAndreYtelser.endre(listOf(dag(1 jan 2024, sykdomsgrad = 10)), saksbehandlerIdent)!!

        assertEquals(event, graderteAndreYtelser.finnEvent(event.versjon))
        assertNull(graderteAndreYtelser.finnEvent(UUID.randomUUID()))
    }

    private fun ny(dager: List<GraderteAndreYtelserDag>) =
        GraderteAndreYtelser.ny(
            id = id,
            dager = dager,
            saksbehandlerIdent = saksbehandlerIdent,
        )

    private fun dag(
        dato: LocalDate,
        andreYtelserGrader: Map<GraderteAndreYtelserType, Grad> = mapOf(FORELDREPENGER to 50),
        sykdomsgrad: Grad = 30,
        arbeidsgrad: Grad = 20,
        sykdomsgradFørFordeling: Grad = sykdomsgrad + andreYtelserGrader.values.sum(),
        begrunnelse: String = "en begrunnelse",
    ) = GraderteAndreYtelserDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader,
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = arbeidsgrad,
        sykdomsgradFørFordeling = sykdomsgradFørFordeling,
        begrunnelse = begrunnelse,
    )
}

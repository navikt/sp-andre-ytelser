package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.FORELDREPENGER
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.OMSORGSPENGER
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.PLEIEPENGER
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.testing.testdata.jan
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagNavIdent
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class GraderteAndreYtelserPerioderTest {
    private val id =
        GraderteAndreYtelserId(
            identitetsnummer = Identitetsnummer(lagIdentitetsnummer()),
            yrkesaktivitet = Yrkesaktivitetstype.ARBEIDSTAKER,
            organisasjonsnummer = lagOrganisasjonsnummer(),
        )
    private val saksbehandlerIdent = NavIdent(lagNavIdent())

    @Test
    fun `dager etter hverandre med samme ytelser blir én periode`() {
        // 1. jan 2024 er mandag
        val graderteAndreYtelser = ny(listOf(dag(1 jan 2024), dag(2 jan 2024), dag(3 jan 2024)))

        assertEquals(
            listOf(GraderteAndreYtelserPeriode(1 jan 2024, 3 jan 2024, listOf(YtelseMedGrad(FORELDREPENGER, 50)))),
            graderteAndreYtelser.perioderMedTrekk(1 jan 2024, 31 jan 2024),
        )
    }

    @Test
    fun `bare dager mellom fom og tom og med trekk fra sykdomsgraden er med`() {
        val graderteAndreYtelser =
            ny(
                listOf(
                    dag(1 jan 2024),
                    dag(2 jan 2024),
                    // ytelsen er bare tatt fra arbeidet, så sykepengene er ikke redusert
                    dag(3 jan 2024, sykdomsgrad = 50, arbeidsgrad = 0, sykdomsgradFørFordeling = 50),
                    dag(4 jan 2024),
                    dag(5 jan 2024),
                ),
            )

        assertEquals(
            listOf(
                GraderteAndreYtelserPeriode(2 jan 2024, 2 jan 2024, listOf(YtelseMedGrad(FORELDREPENGER, 50))),
                GraderteAndreYtelserPeriode(4 jan 2024, 4 jan 2024, listOf(YtelseMedGrad(FORELDREPENGER, 50))),
            ),
            graderteAndreYtelser.perioderMedTrekk(2 jan 2024, 4 jan 2024),
        )
    }

    @Test
    fun `dager med andre ytelser eller grader blir en ny periode`() {
        val graderteAndreYtelser =
            ny(
                listOf(
                    dag(1 jan 2024, andreYtelserGrader = mapOf(FORELDREPENGER to 20, PLEIEPENGER to 30), sykdomsgrad = 0),
                    dag(2 jan 2024, andreYtelserGrader = linkedMapOf(PLEIEPENGER to 30, FORELDREPENGER to 20), sykdomsgrad = 0),
                    dag(3 jan 2024, andreYtelserGrader = mapOf(FORELDREPENGER to 30)),
                    dag(4 jan 2024, andreYtelserGrader = mapOf(PLEIEPENGER to 30)),
                ),
            )

        assertEquals(
            listOf((1 jan 2024) to (2 jan 2024), (3 jan 2024) to (3 jan 2024), (4 jan 2024) to (4 jan 2024)),
            graderteAndreYtelser.perioderMedTrekk(1 jan 2024, 31 jan 2024).map { it.fom to it.tom },
        )
    }

    @Test
    fun `lørdag og søndag uten fordeling deler ikke en periode, men en hverdag uten fordeling gjør det`() {
        // fredag 5. jan, mandag 8. jan og onsdag 10. jan
        val graderteAndreYtelser = ny(listOf(dag(5 jan 2024), dag(8 jan 2024), dag(10 jan 2024)))

        assertEquals(
            listOf((5 jan 2024) to (8 jan 2024), (10 jan 2024) to (10 jan 2024)),
            graderteAndreYtelser.perioderMedTrekk(1 jan 2024, 31 jan 2024).map { it.fom to it.tom },
        )
    }

    @Test
    fun `ytelser med høyest grad kommer først, og lik grad følger rekkefølgen i tabellen`() {
        val graderteAndreYtelser =
            ny(
                listOf(
                    dag(
                        1 jan 2024,
                        andreYtelserGrader = linkedMapOf(OMSORGSPENGER to 10, PLEIEPENGER to 20, FORELDREPENGER to 20),
                        sykdomsgrad = 0,
                    ),
                ),
            )

        assertEquals(
            listOf(YtelseMedGrad(FORELDREPENGER, 20), YtelseMedGrad(PLEIEPENGER, 20), YtelseMedGrad(OMSORGSPENGER, 10)),
            graderteAndreYtelser.perioderMedTrekk(1 jan 2024, 31 jan 2024).single().ytelser,
        )
    }

    private fun ny(dager: List<GraderteAndreYtelserDag>) = GraderteAndreYtelser.ny(id, dager, saksbehandlerIdent)

    private fun dag(
        dato: LocalDate,
        andreYtelserGrader: Map<GraderteAndreYtelserType, Grad> = mapOf(FORELDREPENGER to 50),
        sykdomsgrad: Grad = 30,
        arbeidsgrad: Grad = 20,
        sykdomsgradFørFordeling: Grad = sykdomsgrad + andreYtelserGrader.values.sum(),
    ) = GraderteAndreYtelserDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader,
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = arbeidsgrad,
        sykdomsgradFørFordeling = sykdomsgradFørFordeling,
        begrunnelse = "en begrunnelse",
    )
}

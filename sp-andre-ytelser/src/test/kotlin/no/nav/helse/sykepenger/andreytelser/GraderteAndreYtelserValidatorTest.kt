package no.nav.helse.sykepenger.andreytelser

import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.FORELDREPENGER
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.OMSORGSPENGER
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserValidator.Resultat
import no.nav.sykepenger.libs.testing.testdata.jan
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class GraderteAndreYtelserValidatorTest {
    @Test
    fun `gyldige dager`() {
        assertEquals(Resultat.OK, valider(dag(1 jan 2024), dag(2 jan 2024)))
    }

    @Test
    fun `mangler endringer`() {
        assertEquals(Resultat.ManglerEndringer, GraderteAndreYtelserValidator.validerDager(emptyList()))
    }

    @Test
    fun `ytelsen kan tas fra både sykdom og arbeid`() {
        assertEquals(Resultat.OK, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 60), sykdomsgrad = 30, arbeidsgrad = 10, førFordeling = 50)))
    }

    @Test
    fun `alt kan tas fra arbeid`() {
        assertEquals(Resultat.OK, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 50), sykdomsgrad = 50, arbeidsgrad = 0, førFordeling = 50)))
    }

    @Test
    fun `flere ytelser på samme dag`() {
        assertEquals(
            Resultat.OK,
            valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 30, OMSORGSPENGER to 20), sykdomsgrad = 50, arbeidsgrad = 0, førFordeling = 100)),
        )
    }

    @Test
    fun `sykdomsgrad før fordeling på grensene`() {
        assertEquals(Resultat.OK, valider(dag(1 jan 2024, ytelser = emptyMap(), sykdomsgrad = 1, arbeidsgrad = 99, førFordeling = 1)))
        assertEquals(Resultat.OK, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 99), sykdomsgrad = 1, arbeidsgrad = 0, førFordeling = 100)))
    }

    @Test
    fun `sykdomsgrad før fordeling utenfor 1 til 100`() {
        assertEquals(Resultat.GradUtenforGrenser, valider(dag(1 jan 2024, sykdomsgrad = 0, arbeidsgrad = 50, førFordeling = 0)))
        assertEquals(Resultat.GradUtenforGrenser, valider(dag(1 jan 2024, førFordeling = 101)))
    }

    @Test
    fun `ytelsesgrad utenfor 1 til 99`() {
        assertEquals(Resultat.GradUtenforGrenser, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 0), sykdomsgrad = 50, arbeidsgrad = 50, førFordeling = 50)))
        assertEquals(Resultat.GradUtenforGrenser, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 100), sykdomsgrad = 0, arbeidsgrad = 0, førFordeling = 100)))
    }

    @Test
    fun `sykdomsgrad utenfor 1 til sykdomsgrad før fordeling`() {
        assertEquals(Resultat.GradUtenforGrenser, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 99), sykdomsgrad = 0, arbeidsgrad = 1, førFordeling = 99)))
        assertEquals(Resultat.GradUtenforGrenser, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 10), sykdomsgrad = 60, arbeidsgrad = 30, førFordeling = 50)))
    }

    @Test
    fun `negativ arbeidsgrad`() {
        assertEquals(Resultat.GradUtenforGrenser, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 60), sykdomsgrad = 50, arbeidsgrad = -10, førFordeling = 50)))
    }

    @Test
    fun `grader som ikke summerer til 100`() {
        assertEquals(Resultat.SumErIkke100, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 50), sykdomsgrad = 30, arbeidsgrad = 21, førFordeling = 80)))
        assertEquals(Resultat.SumErIkke100, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 50), sykdomsgrad = 30, arbeidsgrad = 19, førFordeling = 80)))
    }

    @Test
    fun `arbeidsgraden kan ikke øke`() {
        assertEquals(Resultat.OK, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 30), sykdomsgrad = 40, arbeidsgrad = 30, førFordeling = 70)))
        assertEquals(Resultat.ArbeidsgradOker, valider(dag(1 jan 2024, ytelser = mapOf(FORELDREPENGER to 30), sykdomsgrad = 39, arbeidsgrad = 31, førFordeling = 70)))
    }

    @Test
    fun `dag uten ytelser må ha uendret sykdomsgrad`() {
        assertEquals(Resultat.OK, valider(dag(1 jan 2024, ytelser = emptyMap(), sykdomsgrad = 80, arbeidsgrad = 20, førFordeling = 80)))
        assertEquals(Resultat.YtelserManglerTrekk, valider(dag(1 jan 2024, ytelser = emptyMap(), sykdomsgrad = 70, arbeidsgrad = 30, førFordeling = 80)))
    }

    @Test
    fun `første ugyldige dag avgjør feilen`() {
        assertEquals(
            Resultat.SumErIkke100,
            valider(
                dag(1 jan 2024),
                dag(2 jan 2024, ytelser = mapOf(FORELDREPENGER to 50), sykdomsgrad = 30, arbeidsgrad = 21, førFordeling = 80),
                dag(3 jan 2024, førFordeling = 101),
            ),
        )
    }

    private fun valider(vararg dager: GraderteAndreYtelserDag) = GraderteAndreYtelserValidator.validerDager(dager.toList())

    private fun dag(
        dato: LocalDate,
        ytelser: Map<GraderteAndreYtelserType, Grad> = mapOf(FORELDREPENGER to 50),
        sykdomsgrad: Grad = 30,
        arbeidsgrad: Grad = 20,
        førFordeling: Grad = 80,
        begrunnelse: String = "en begrunnelse",
    ) = GraderteAndreYtelserDag(
        dato = dato,
        andreYtelserGrader = ytelser,
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = arbeidsgrad,
        sykdomsgradFørFordeling = førFordeling,
        begrunnelse = begrunnelse,
    )
}

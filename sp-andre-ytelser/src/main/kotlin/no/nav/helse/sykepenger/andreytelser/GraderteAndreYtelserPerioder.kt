package no.nav.helse.sykepenger.andreytelser

import java.time.DayOfWeek
import java.time.LocalDate

data class GraderteAndreYtelserPeriode(
    val fom: LocalDate,
    val tom: LocalDate,
    val ytelser: List<YtelseMedGrad>,
)

data class YtelseMedGrad(
    val type: GraderteAndreYtelserType,
    val grad: Grad,
)

fun GraderteAndreYtelser.perioderMedTrekk(
    fom: LocalDate,
    tom: LocalDate,
): List<GraderteAndreYtelserPeriode> =
    dager
        .filter { it.dato in fom..tom && it.trekkFraSykdomsgrad > 0 }
        .fold(emptyList()) { perioder, dag ->
            val forrige = perioder.lastOrNull()
            val ytelser =
                dag.andreYtelserGrader
                    .map { (type, grad) -> YtelseMedGrad(type, grad) }
                    .sortedWith(compareByDescending<YtelseMedGrad> { it.grad }.thenBy { it.type })
            if (forrige != null && forrige.tom.hengerSammenMed(dag.dato) && forrige.ytelser == ytelser) {
                perioder.dropLast(1) + forrige.copy(tom = dag.dato)
            } else {
                perioder + GraderteAndreYtelserPeriode(dag.dato, dag.dato, ytelser)
            }
        }

// Dagene mellom er ikke med i periodene. Er alle helgedager, deler de ikke perioden.
private fun LocalDate.hengerSammenMed(neste: LocalDate) =
    plusDays(1)
        .datesUntil(neste)
        .allMatch { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }

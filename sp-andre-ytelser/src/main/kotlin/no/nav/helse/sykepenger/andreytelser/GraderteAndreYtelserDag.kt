package no.nav.helse.sykepenger.andreytelser

import java.time.LocalDate

typealias Grad = Int

data class GraderteAndreYtelserDag(
    val dato: LocalDate,
    val andreYtelserGrader: Map<GraderteAndreYtelserType, Grad>,
    val sykdomsgrad: Grad,
    val arbeidsgrad: Grad,
    val sykdomsgradFørFordeling: Grad,
    val begrunnelse: String,
) {
    val trekkFraSykdomsgrad: Grad get() = sykdomsgradFørFordeling - sykdomsgrad
}

package no.nav.helse.sykepenger.andreytelser

object GraderteAndreYtelserValidator {
    fun validerDager(dager: List<GraderteAndreYtelserDag>): Resultat {
        if (dager.isEmpty()) {
            return Resultat.ManglerEndringer
        }
        return dager.firstNotNullOfOrNull(::validerDag) ?: Resultat.OK
    }

    private fun validerDag(dag: GraderteAndreYtelserDag): Resultat? {
        val førFordeling = dag.sykdomsgradFørFordeling
        val ytelser = dag.andreYtelserGrader.values
        return when {
            dag.begrunnelse.isBlank() -> Resultat.ManglerBegrunnelse

            førFordeling !in 1..100 ||
                ytelser.any { it !in 1..99 } ||
                dag.sykdomsgrad !in 1..førFordeling ||
                dag.arbeidsgrad < 0 -> Resultat.GradUtenforGrenser

            dag.sykdomsgrad + dag.arbeidsgrad + ytelser.sum() != 100 -> Resultat.SumErIkke100

            // Følger egentlig av reglene over og under, men gir en mer presis feil når saksbehandler fjerner ytelsene
            ytelser.isEmpty() && dag.sykdomsgrad != førFordeling -> Resultat.YtelserManglerTrekk

            // Arbeidsgraden før fordeling er 100 - sykdomsgradFørFordeling, og ytelsene kan bare trekke den ned
            dag.arbeidsgrad > 100 - førFordeling -> Resultat.ArbeidsgradOker

            else -> null
        }
    }

    sealed interface Resultat {
        data object OK : Resultat

        data object ManglerEndringer : Resultat

        data object GradUtenforGrenser : Resultat

        data object SumErIkke100 : Resultat

        data object ArbeidsgradOker : Resultat

        data object YtelserManglerTrekk : Resultat

        data object ManglerBegrunnelse : Resultat
    }
}

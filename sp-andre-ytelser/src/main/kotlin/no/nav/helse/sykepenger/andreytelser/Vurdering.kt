package no.nav.helse.sykepenger.andreytelser

import java.time.Instant
import java.time.LocalDate

data class Vurdering(
    val vilkårskode: Vilkårskode,
    val lovreferanse: Lovreferanse,
    val vurdertTidspunkt: Instant,
) {
    companion object {
        fun forDag(
            dag: GraderteAndreYtelserDag,
            tidspunkt: Instant,
        ): Vurdering? =
            if (dag.andreYtelserGrader.isEmpty()) {
                null
            } else {
                Vurdering(
                    vilkårskode = Vilkårskode.GRADERT_ANNEN_YTELSE_REDUSERER_UTMÅLING,
                    lovreferanse = Vilkårskode.GRADERT_ANNEN_YTELSE_REDUSERER_UTMÅLING.lovreferanse,
                    vurdertTidspunkt = tidspunkt,
                )
            }
    }
}

enum class Vilkårskode(
    val lovreferanse: Lovreferanse,
) {
    GRADERT_ANNEN_YTELSE_REDUSERER_UTMÅLING(Lovreferanse.`§ 8-13 andre avsnitt`()),
}

data class Lovreferanse(
    val lov: String,
    val paragraf: String,
    val avsnitt: Int?,
    val setning: Int?,
    val bokstav: String?,
    val iKraftFra: LocalDate,
) {
    companion object {
        fun `§ 8-13 andre avsnitt`(): Lovreferanse =
            Lovreferanse(
                lov = "folketrygdloven",
                paragraf = "8-13",
                avsnitt = 2,
                setning = null,
                bokstav = null,
                iKraftFra = LocalDate.of(1997, 5, 1),
            )
    }
}

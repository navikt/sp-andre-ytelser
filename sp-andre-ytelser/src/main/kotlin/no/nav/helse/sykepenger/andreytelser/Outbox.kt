package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer

internal interface Outbox {
    /** `melding` er hele rapid-meldingen som JSON. Den publiseres uendret, med identitetsnummeret som nøkkel. */
    fun leggTil(
        identitetsnummer: Identitetsnummer,
        melding: String,
    )
}

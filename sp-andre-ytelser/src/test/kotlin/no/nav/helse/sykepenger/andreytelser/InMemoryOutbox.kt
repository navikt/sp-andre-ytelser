package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

internal class InMemoryOutbox : Outbox {
    private val _meldinger = mutableListOf<Melding>()
    internal val meldinger: List<Melding> get() = _meldinger.toList()

    override fun leggTil(
        identitetsnummer: Identitetsnummer,
        melding: String,
    ) {
        _meldinger.add(Melding(identitetsnummer, melding))
    }

    internal data class Melding(
        val identitetsnummer: Identitetsnummer,
        val melding: String,
    ) {
        val json: JsonNode get() = jacksonObjectMapper().readTree(melding)
    }
}

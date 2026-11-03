package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer

internal class InMemoryGraderteAndreYtelserRepository : GraderteAndreYtelserRepository {
    private val eventsPerId = mutableMapOf<GraderteAndreYtelserId, List<GraderteAndreYtelserEvent>>()

    override fun finnAlle(identitetsnummer: Identitetsnummer): List<GraderteAndreYtelser> = eventsPerId.keys.filter { it.identitetsnummer == identitetsnummer }.mapNotNull(::finn)

    override fun finn(id: GraderteAndreYtelserId): GraderteAndreYtelser? = eventsPerId[id]?.let { GraderteAndreYtelser.fraLagring(id, it) }

    override fun lagre(graderteAndreYtelser: GraderteAndreYtelser) {
        eventsPerId[graderteAndreYtelser.id] = graderteAndreYtelser.events.toList()
    }
}

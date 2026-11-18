package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import java.util.UUID

internal class InMemoryGraderteAndreYtelserRepository : GraderteAndreYtelserRepository {
    private val eventsPerId = mutableMapOf<GraderteAndreYtelserId, List<GraderteAndreYtelserEvent>>()

    override fun finnAlle(identitetsnummer: Identitetsnummer): List<GraderteAndreYtelser> = eventsPerId.keys.filter { it.identitetsnummer == identitetsnummer }.mapNotNull(::finn)

    override fun finn(id: GraderteAndreYtelserId): GraderteAndreYtelser? = eventsPerId[id]?.let { GraderteAndreYtelser.fraLagring(id, it) }

    override fun finnForVersjon(versjon: UUID): GraderteAndreYtelser? =
        eventsPerId.entries
            .firstOrNull { (_, events) -> events.any { it.versjon == versjon } }
            ?.let { (id, events) -> GraderteAndreYtelser.fraLagring(id, events).tilstandVedVersjon(versjon) }

    override fun lagre(graderteAndreYtelser: GraderteAndreYtelser) {
        eventsPerId[graderteAndreYtelser.id] = graderteAndreYtelser.events.toList()
    }
}

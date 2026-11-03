package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer

internal interface GraderteAndreYtelserRepository {
    fun finnAlle(identitetsnummer: Identitetsnummer): List<GraderteAndreYtelser>

    fun finn(id: GraderteAndreYtelserId): GraderteAndreYtelser?

    fun lagre(graderteAndreYtelser: GraderteAndreYtelser)
}

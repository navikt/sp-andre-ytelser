package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import java.util.UUID

internal interface GraderteAndreYtelserRepository {
    fun finnAlle(identitetsnummer: Identitetsnummer): List<GraderteAndreYtelser>

    fun finn(id: GraderteAndreYtelserId): GraderteAndreYtelser?

    fun finnForVersjon(versjon: UUID): GraderteAndreYtelser?

    fun lagre(graderteAndreYtelser: GraderteAndreYtelser)
}

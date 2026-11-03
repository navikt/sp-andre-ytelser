package no.nav.helse.sykepenger.andreytelser

import no.nav.sykepenger.libs.ktor.auth.NavIdent
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class GraderteAndreYtelserEvent(
    val versjon: UUID,
    val tidspunkt: Instant,
    val utførtAvSaksbehandlerIdent: NavIdent,
    val notatTilBeslutter: String,
    val dager: List<GraderteAndreYtelserEndretDag>,
)

data class GraderteAndreYtelserEndretDag(
    val dato: LocalDate,
    val andreYtelserGrader: Map<GraderteAndreYtelserType, Grad>,
    val sykdomsgrad: Grad,
    val arbeidsgrad: Grad,
    val fraAndreYtelserGrader: Map<GraderteAndreYtelserType, Grad>?,
    val fraSykdomsgrad: Grad?,
    val fraArbeidsgrad: Grad?,
) {
    fun tilGraderteAndreYtelserDag() =
        GraderteAndreYtelserDag(
            dato = dato,
            andreYtelserGrader = andreYtelserGrader,
            sykdomsgrad = sykdomsgrad,
            arbeidsgrad = arbeidsgrad,
        )
}

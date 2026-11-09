package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

data class GraderteAndreYtelserId(
    val identitetsnummer: Identitetsnummer,
    val yrkesaktivitet: Yrkesaktivitetstype,
    val organisasjonsnummer: String?,
)

class GraderteAndreYtelser private constructor(
    val id: GraderteAndreYtelserId,
) {
    private val _events = mutableListOf<GraderteAndreYtelserEvent>()
    val events: List<GraderteAndreYtelserEvent> get() = _events

    private var dagerPerDato: Map<LocalDate, GraderteAndreYtelserDag> = emptyMap()
    val dager: List<GraderteAndreYtelserDag> get() = dagerPerDato.values.sortedBy { it.dato }

    val versjon: UUID get() = _events.last().versjon

    fun endre(
        dager: List<GraderteAndreYtelserDag>,
        saksbehandlerIdent: NavIdent,
    ): GraderteAndreYtelserEvent? {
        val dagerMedEndring = dager.filter { dag -> dagerPerDato[dag.dato] != dag }
        if (dagerMedEndring.isEmpty()) return null

        val tidspunkt = Instant.now().truncatedTo(ChronoUnit.MICROS) // mikrosekunder slik at vurdertTidspunkt i data_json blir likt det som blir lagret i databasen
        val event =
            GraderteAndreYtelserEvent(
                versjon = nyVersjon(),
                tidspunkt = tidspunkt,
                utførtAvSaksbehandlerIdent = saksbehandlerIdent,
                dager = dagerMedEndring.map { dag -> dag.endretFra(dagerPerDato[dag.dato], tidspunkt) },
            )
        apply(event)
        return event
    }

    fun finnEvent(versjon: UUID): GraderteAndreYtelserEvent? = _events.find { it.versjon == versjon }

    private fun apply(event: GraderteAndreYtelserEvent) {
        dagerPerDato = dagerPerDato + event.dager.map { it.tilGraderteAndreYtelserDag() }.associateBy { it.dato }
        _events.add(event)
    }

    private fun GraderteAndreYtelserDag.endretFra(
        fra: GraderteAndreYtelserDag?,
        tidspunkt: Instant,
    ) = GraderteAndreYtelserEndretDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader,
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = arbeidsgrad,
        sykdomsgradFørFordeling = sykdomsgradFørFordeling,
        begrunnelse = begrunnelse,
        fraAndreYtelserGrader = fra?.andreYtelserGrader,
        fraSykdomsgrad = fra?.sykdomsgrad,
        fraArbeidsgrad = fra?.arbeidsgrad,
        fraSykdomsgradFørFordeling = fra?.sykdomsgradFørFordeling,
        vurdering = Vurdering.forDag(this, tidspunkt),
    )

    companion object {
        fun ny(
            id: GraderteAndreYtelserId,
            dager: List<GraderteAndreYtelserDag>,
            saksbehandlerIdent: NavIdent,
        ): GraderteAndreYtelser =
            GraderteAndreYtelser(id).also {
                it.endre(dager, saksbehandlerIdent)
            }

        fun fraLagring(
            id: GraderteAndreYtelserId,
            events: List<GraderteAndreYtelserEvent>,
        ): GraderteAndreYtelser =
            GraderteAndreYtelser(id).also { graderteAndreYtelser ->
                events.sortedBy { it.versjon }.forEach(graderteAndreYtelser::apply)
            }

        @OptIn(ExperimentalUuidApi::class)
        private fun nyVersjon(): UUID = Uuid.generateV7().toJavaUuid()
    }
}

enum class GraderteAndreYtelserType {
    FORELDREPENGER,
    SVANGERSKAPSPENGER,
    OMSORGSPENGER,
    PLEIEPENGER,
    OPPLARINGSPENGER,
}

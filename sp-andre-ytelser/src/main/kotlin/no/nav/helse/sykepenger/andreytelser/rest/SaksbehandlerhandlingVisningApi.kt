package no.nav.helse.sykepenger.andreytelser.rest

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import no.nav.helse.sykepenger.andreytelser.Grad
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEndretDag
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEvent
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType
import no.nav.helse.sykepenger.andreytelser.TransaksjonProvider
import no.nav.helse.sykepenger.andreytelser.Transaksjonskontekst
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

internal fun Route.saksbehandlerhandlingVisningApi(
    transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    personTilgang: PersonTilgang,
) {
    get("/api/personer/{personPseudoId}/saksbehandlerhandlinger/{handlingId}/visning") {
        val ident = personTilgang.identMedTilgang(call, Tilgang.Les) ?: return@get
        // Ugyldig id og id som tilhører en annen person gir samme svar som ukjent id, så svaret ikke avslører noe
        val handlingId = call.parameters["handlingId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val event =
            handlingId?.let { versjon ->
                transaksjonProvider.transaksjon { kontekst ->
                    kontekst.graderteAndreYtelser.finnAlle(ident).firstNotNullOfOrNull { it.finnEvent(versjon) }
                }
            }
        if (event == null) {
            call.respondProblem(HttpStatusCode.NotFound, "Fant ikke detaljer")
            return@get
        }
        call.respond(event.tilVisning())
    }
}

internal data class ApiSaksbehandlerhandlingVisningResponse(
    val felter: List<ApiVisningsfelt>,
)

internal sealed interface ApiVisningsfelt {
    val type: ApiVisningsfelttype
    val tittel: String

    @JsonPropertyOrder("type", "tittel", "tekst")
    data class Tekst(
        override val tittel: String,
        val tekst: String,
    ) : ApiVisningsfelt {
        override val type = ApiVisningsfelttype.TEKST
    }

    @JsonPropertyOrder("type", "tittel", "grupper")
    data class Endringer(
        override val tittel: String,
        val grupper: List<ApiEndringsgruppe>,
    ) : ApiVisningsfelt {
        override val type = ApiVisningsfelttype.ENDRINGER
    }
}

internal enum class ApiVisningsfelttype {
    TEKST,
    ENDRINGER,
}

internal data class ApiEndringsgruppe(
    val rader: List<ApiEndringsrad>,
)

internal data class ApiEndringsrad(
    val navn: String,
    val fra: String?,
    val til: String,
)

private val datoformat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun GraderteAndreYtelserEvent.tilVisning() =
    ApiSaksbehandlerhandlingVisningResponse(
        felter =
            listOfNotNull(
                notatTilBeslutter.takeIf { it.isNotBlank() }?.let {
                    ApiVisningsfelt.Tekst(tittel = "Begrunnelse", tekst = it)
                },
                ApiVisningsfelt.Endringer(
                    tittel = "Dager endret",
                    grupper = grupperSammenhengendeLikeDager(dager).map { it.tilEndringsgruppe() },
                ),
            ),
    )

private class Daggruppe(
    val fom: LocalDate,
    val tom: LocalDate,
    val endring: GraderteAndreYtelserEndretDag,
)

private fun grupperSammenhengendeLikeDager(dager: List<GraderteAndreYtelserEndretDag>): List<Daggruppe> =
    dager.sortedBy { it.dato }.fold(emptyList()) { grupper, dag ->
        val forrige = grupper.lastOrNull()
        if (forrige != null && forrige.tom.plusDays(1) == dag.dato && forrige.endring == dag.copy(dato = forrige.endring.dato)) {
            grupper.dropLast(1) + Daggruppe(forrige.fom, dag.dato, forrige.endring)
        } else {
            grupper + Daggruppe(dag.dato, dag.dato, dag)
        }
    }

private fun Daggruppe.tilEndringsgruppe(): ApiEndringsgruppe {
    val datotekst = if (fom == tom) fom.format(datoformat) else "${fom.format(datoformat)} - ${tom.format(datoformat)}"
    val fraYtelser = endring.fraAndreYtelserGrader
    val ytelsestyper = (endring.andreYtelserGrader.keys + fraYtelser?.keys.orEmpty()).sorted()
    return ApiEndringsgruppe(
        rader =
            listOf(ApiEndringsrad(navn = "Dato", fra = null, til = datotekst)) +
                ytelsestyper.map { type ->
                    endringsrad(type.visningsnavn(), fraYtelser?.let { it[type] ?: 0 }, endring.andreYtelserGrader[type] ?: 0)
                } +
                endringsrad("Sykdomsgrad", endring.fraSykdomsgrad, endring.sykdomsgrad) +
                endringsrad("Arbeidsgrad", endring.fraArbeidsgrad, endring.arbeidsgrad),
    )
}

private fun endringsrad(
    navn: String,
    fra: Grad?,
    til: Grad,
) = ApiEndringsrad(
    navn = navn,
    fra = fra?.takeIf { it != til }?.let(::prosent),
    til = prosent(til),
)

private fun prosent(grad: Grad) = "$grad %"

private fun GraderteAndreYtelserType.visningsnavn() =
    when (this) {
        GraderteAndreYtelserType.FORELDREPENGER -> "Foreldrepenger"
        GraderteAndreYtelserType.SVANGERSKAPSPENGER -> "Svangerskapspenger"
        GraderteAndreYtelserType.OMSORGSPENGER -> "Omsorgspenger"
        GraderteAndreYtelserType.PLEIEPENGER -> "Pleiepenger"
        GraderteAndreYtelserType.OPPLARINGSPENGER -> "Opplæringspenger"
    }

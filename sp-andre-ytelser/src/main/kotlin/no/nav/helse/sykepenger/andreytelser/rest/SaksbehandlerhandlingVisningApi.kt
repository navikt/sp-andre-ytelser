package no.nav.helse.sykepenger.andreytelser.rest

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import no.nav.helse.sykepenger.andreytelser.Grad
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEndretDag
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserEvent
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType
import no.nav.helse.sykepenger.andreytelser.TransaksjonProvider
import no.nav.helse.sykepenger.andreytelser.Transaksjonskontekst
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.feilsvar
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.uuidStiparameter
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

internal fun Route.saksbehandlerhandlingVisningApi(
    transaksjonProvider: TransaksjonProvider<Transaksjonskontekst>,
    personTilgang: PersonTilgang,
) {
    get("/api/personer/{personPseudoId}/saksbehandlerhandlinger/{handlingId}/visning", {
        operationId = "getSaksbehandlerhandlingVisning"
        tags("saksbehandlerhandlinger")
        request {
            uuidStiparameter("personPseudoId")
            uuidStiparameter("handlingId")
        }
        response { code(HttpStatusCode.OK) { body<ApiSaksbehandlerhandlingVisningResponse>() } }
        feilsvar(HttpStatusCode.Forbidden, HttpStatusCode.NotFound)
    }) {
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

@Serializable
internal data class ApiSaksbehandlerhandlingVisningResponse(
    val felter: List<ApiVisningsfelt>,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(value = ApiVisningsfelt.Endringer::class, name = "ENDRINGER"),
)
@Serializable
internal sealed interface ApiVisningsfelt {
    val type: ApiVisningsfelttype
    val tittel: String

    @Serializable
    @JsonPropertyOrder("type", "tittel", "grupper")
    data class Endringer(
        override val tittel: String,
        val grupper: List<ApiEndringsgruppe>,
    ) : ApiVisningsfelt {
        override val type = ApiVisningsfelttype.ENDRINGER
    }
}

@Serializable
internal enum class ApiVisningsfelttype {
    ENDRINGER,
}

@Serializable
internal data class ApiEndringsgruppe(
    val rader: List<ApiEndringsrad>,
)

@Serializable
internal data class ApiEndringsrad(
    val navn: String,
    val fra: String?,
    val til: String,
)

private val datoformat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun GraderteAndreYtelserEvent.tilVisning() =
    ApiSaksbehandlerhandlingVisningResponse(
        felter =
            listOf(
                ApiVisningsfelt.Endringer(
                    tittel = "Dager endret",
                    grupper = grupperLikeDager(dager).map { it.tilEndringsgruppe() },
                ),
            ),
    )

private class Periode(
    val fom: LocalDate,
    val tom: LocalDate,
) {
    override fun toString() = if (fom == tom) fom.format(datoformat) else "${fom.format(datoformat)} - ${tom.format(datoformat)}"
}

private class Daggruppe(
    val perioder: List<Periode>,
    val endring: GraderteAndreYtelserEndretDag,
)

private fun GraderteAndreYtelserEndretDag.innhold() = copy(dato = LocalDate.MIN)

private fun grupperLikeDager(dager: List<GraderteAndreYtelserEndretDag>): List<Daggruppe> =
    dager
        .sortedBy { it.dato }
        .fold(emptyList<Pair<Periode, GraderteAndreYtelserEndretDag>>()) { perioder, dag ->
            val forrige = perioder.lastOrNull()
            if (forrige != null && forrige.first.tom.plusDays(1) == dag.dato && forrige.second.innhold() == dag.innhold()) {
                perioder.dropLast(1) + (Periode(forrige.first.fom, dag.dato) to forrige.second)
            } else {
                perioder + (Periode(dag.dato, dag.dato) to dag)
            }
        }.groupBy { (_, endring) -> endring.innhold() }
        .values
        .map { perioder -> Daggruppe(perioder.map { (periode, _) -> periode }, perioder.first().second) }

private fun Daggruppe.tilEndringsgruppe(): ApiEndringsgruppe {
    val datotekst = perioder.joinToString(", ")
    // Uten tidligere fordeling vises dagen slik den var før fordeling, som for andre tidslinjeoverstyringer
    val fraYtelser = endring.fraAndreYtelserGrader.orEmpty()
    val fraSykdomsgrad = endring.fraSykdomsgrad ?: endring.sykdomsgradFørFordeling
    val fraArbeidsgrad = endring.fraArbeidsgrad ?: (100 - endring.sykdomsgradFørFordeling)
    val ytelsestyper = (endring.andreYtelserGrader.keys + fraYtelser.keys).sorted()
    return ApiEndringsgruppe(
        rader =
            listOf(ApiEndringsrad(navn = "Dato", fra = null, til = datotekst)) +
                ytelsestyper.map { type ->
                    endringsrad(type.visningsnavn(), fraYtelser[type] ?: 0, endring.andreYtelserGrader[type] ?: 0)
                } +
                endringsrad("Sykdomsgrad", fraSykdomsgrad, endring.sykdomsgrad) +
                endringsrad("Arbeidsgrad", fraArbeidsgrad, endring.arbeidsgrad) +
                ApiEndringsrad(navn = "Begrunnelse", fra = null, til = endring.begrunnelse),
    )
}

private fun endringsrad(
    navn: String,
    fra: Grad,
    til: Grad,
) = ApiEndringsrad(
    navn = navn,
    fra = fra.takeIf { it != til }?.let(::prosent),
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

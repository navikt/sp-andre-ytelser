package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.andreytelser.rest.configureRestApi
import no.nav.helse.sykepenger.andreytelser.rest.graderteAndreYtelserApi
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagNavIdent
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import no.nav.sykepenger.libs.testing.testdata.sep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class GraderteAndreYtelserPostApiTest {
    private val oauth = MockOAuth2Server().also { it.start() }
    private val personProvider = FakePersonProvider()
    private val identitetsnummer = Identitetsnummer(lagIdentitetsnummer())
    private val pseudoId = personProvider.nyPersonPseudoId(identitetsnummer).value
    private val saksbehandlerIdent = lagNavIdent()
    private val organisasjonsnummer = lagOrganisasjonsnummer()
    private var transaksjonProvider = InMemoryTransaksjonProvider()

    @BeforeEach
    fun nullstill() {
        transaksjonProvider = InMemoryTransaksjonProvider()
    }

    @AfterAll
    fun stopp() {
        oauth.shutdown()
    }

    @Test
    fun `første post oppretter strømmen og legger endringsmelding i outboxen`() {
        val svar =
            send(
                request(
                    versjon = null,
                    dager = listOf(dag(23 sep 2024, begrunnelse = "første dag"), dag(24 sep 2024, begrunnelse = "andre dag")),
                ),
            )

        assertEquals(HttpStatusCode.OK, svar.status)
        val lagret = lagret()
        assertNotNull(lagret)
        assertEquals(lagret.versjon.toString(), svar.json["versjon"].asString())
        assertEquals(NavIdent(saksbehandlerIdent), lagret.events.single().utførtAvSaksbehandlerIdent)
        assertEquals(
            listOf("første dag", "andre dag"),
            lagret.events
                .single()
                .dager
                .map { it.begrunnelse },
        )
        assertEquals(listOf(23 sep 2024), endringsmeldinger().map { it.fom() })
        endringsmeldinger().single().also {
            assertEquals("ARBEIDSTAKER", it["yrkesaktivitetstype"].asString())
            assertEquals(organisasjonsnummer, it["organisasjonsnummer"].asString())
            assertEquals(identitetsnummer.value, it["fødselsnummer"].asString())
        }
        assertEquals(
            listOf(identitetsnummer, identitetsnummer),
            transaksjonProvider.outbox.meldinger.map { it.identitetsnummer },
        )
    }

    @Test
    fun `legger saksbehandlerhandling i outboxen før endringsmeldingen`() {
        val behandlingId = UUID.randomUUID()

        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024)), behandlingId = behandlingId))

        val lagret = lagret()
        assertNotNull(lagret)
        val event = lagret.events.single()
        assertEquals(UUID.fromString(svar.json["versjon"].asString()), event.versjon)
        assertEquals(
            listOf("saksbehandlerhandling", "graderte_andre_ytelser_endret"),
            outboxmeldinger().map { it["@event_name"].asString() },
        )
        saksbehandlerhandlinger().single().also {
            assertEquals(saksbehandlerIdent, it["saksbehandlerIdent"].asString())
            assertEquals(event.versjon, it.uuid("handlingId"))
            assertEquals("GRADERTE_ANDRE_YTELSER_ENDRET", it["handlingType"].asString())
            assertEquals(true, it["kreverTotrinnsvurdering"].asBoolean())
            assertEquals(event.tidspunkt, Instant.parse(it["tidspunkt"].asString()))
            assertEquals(behandlingId, it.uuid("behandlingId"))
        }
    }

    @Test
    fun `andre post endrer bare dagene den inneholder og gir ny versjon`() {
        val førsteVersjon = UUID.fromString(send(request(versjon = null, dager = listOf(dag(23 sep 2024), dag(24 sep 2024)))).json["versjon"].asString())

        val andre = send(request(versjon = førsteVersjon, dager = listOf(dag(24 sep 2024, sykdomsgrad = 10))))

        assertEquals(HttpStatusCode.OK, andre.status)
        val andreVersjon = UUID.fromString(andre.json["versjon"].asString())
        assertNotEquals(førsteVersjon, andreVersjon)
        val lagret = lagret()
        assertNotNull(lagret)
        assertEquals(andreVersjon, lagret.versjon)
        assertEquals(2, lagret.events.size)
        assertEquals(listOf(dag(23 sep 2024).tilDomene(), dag(24 sep 2024, sykdomsgrad = 10).tilDomene()), lagret.dager)
        assertEquals(
            listOf(23 sep 2024, 24 sep 2024),
            endringsmeldinger().map { it.fom() },
        )
        assertEquals(listOf(førsteVersjon, andreVersjon), saksbehandlerhandlinger().map { it.uuid("handlingId") })
    }

    @Test
    fun `post uten faktiske endringer lagrer ingenting og returnerer eksisterende versjon`() {
        val første = send(request(versjon = null, dager = listOf(dag(23 sep 2024))))
        val førsteVersjon = UUID.fromString(første.json["versjon"].asString())

        val andre = send(request(versjon = førsteVersjon, dager = listOf(dag(23 sep 2024))))

        assertEquals(HttpStatusCode.OK, andre.status)
        assertEquals(førsteVersjon.toString(), andre.json["versjon"].asString())
        assertEquals(1, lagret()?.events?.size)
        assertEquals(1, endringsmeldinger().size)
        assertEquals(1, saksbehandlerhandlinger().size)
    }

    @Test
    fun `avviser endring med utdatert versjon`() {
        val førsteVersjon = UUID.fromString(send(request(versjon = null, dager = listOf(dag(23 sep 2024)))).json["versjon"].asString())
        send(request(versjon = førsteVersjon, dager = listOf(dag(23 sep 2024, sykdomsgrad = 10))))

        val svar = send(request(versjon = førsteVersjon, dager = listOf(dag(23 sep 2024, sykdomsgrad = 20))))

        assertFeil(svar, "UTDATERT_TILSTAND", HttpStatusCode.Conflict)
        assertEquals(2, lagret()?.events?.size)
        assertEquals(2, endringsmeldinger().size)
        assertEquals(2, saksbehandlerhandlinger().size)
    }

    @Test
    fun `avviser endring uten versjon når graderte andre ytelser finnes`() {
        send(request(versjon = null, dager = listOf(dag(23 sep 2024))))

        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024, sykdomsgrad = 10))))

        assertFeil(svar, "UTDATERT_TILSTAND", HttpStatusCode.Conflict)
        assertEquals(1, lagret()?.events?.size)
        assertEquals(1, endringsmeldinger().size)
        assertEquals(1, saksbehandlerhandlinger().size)
    }

    @Test
    fun `avviser versjon når graderte andre ytelser ikke finnes`() {
        val svar = send(request(versjon = UUID.randomUUID(), dager = listOf(dag(23 sep 2024))))

        assertFeil(svar, "UTDATERT_TILSTAND", HttpStatusCode.Conflict)
        assertIngentingLagret()
    }

    @Test
    fun `ulike organisasjonsnumre gir hver sin strøm`() {
        val annetOrganisasjonsnummer = lagOrganisasjonsnummer()

        send(request(versjon = null, dager = listOf(dag(23 sep 2024))))
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024)), organisasjonsnummer = annetOrganisasjonsnummer))

        assertEquals(HttpStatusCode.OK, svar.status)
        assertEquals(
            setOf(organisasjonsnummer, annetOrganisasjonsnummer),
            transaksjonProvider.graderteAndreYtelser
                .finnAlle(identitetsnummer)
                .map { it.id.organisasjonsnummer }
                .toSet(),
        )
    }

    @Test
    fun `godtar frilans uten organisasjonsnummer`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024)), yrkesaktivitet = "FRILANS", organisasjonsnummer = null))

        assertEquals(HttpStatusCode.OK, svar.status)
        assertNotNull(transaksjonProvider.graderteAndreYtelser.finn(GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.FRILANS, null)))
    }

    @Test
    fun `godtar dag der ytelsen er tatt fra både sykdom og arbeid`() {
        val dag = dag(23 sep 2024, foreldrepenger = 60, sykdomsgrad = 30, arbeidsgrad = 10, sykdomsgradFørFordeling = 50)
        val svar = send(request(versjon = null, dager = listOf(dag)))

        assertEquals(HttpStatusCode.OK, svar.status)
        assertEquals(
            listOf(dag.tilDomene()),
            transaksjonProvider.graderteAndreYtelser
                .finnAlle(identitetsnummer)
                .single()
                .dager,
        )
    }

    @Test
    fun `godtar dag uten ytelser når sykdomsgraden er uendret`() {
        val svar = send(request(versjon = null, dager = listOf(dagUtenYtelser(23 sep 2024, sykdomsgrad = 80, sykdomsgradFørFordeling = 80))))

        assertEquals(HttpStatusCode.OK, svar.status)
    }

    @Test
    fun `avviser dag med grad utenfor grensene`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024, sykdomsgradFørFordeling = 101))))

        assertFeil(svar, "GRAD_UTENFOR_GRENSER")
        assertIngentingLagret()
    }

    @Test
    fun `avviser dag der gradene ikke summerer til 100`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024, foreldrepenger = 50, sykdomsgrad = 30, arbeidsgrad = 21))))

        assertFeil(svar, "SUM_ER_IKKE_100")
        assertIngentingLagret()
    }

    @Test
    fun `avviser dag der arbeidsgraden øker`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024, foreldrepenger = 30, sykdomsgrad = 30, arbeidsgrad = 40, sykdomsgradFørFordeling = 70))))

        assertFeil(svar, "ARBEIDSGRAD_OKER")
        assertIngentingLagret()
    }

    @Test
    fun `avviser dag uten ytelser der sykdomsgraden er endret`() {
        val svar = send(request(versjon = null, dager = listOf(dagUtenYtelser(23 sep 2024, sykdomsgrad = 70, sykdomsgradFørFordeling = 80))))

        assertFeil(svar, "YTELSER_MANGLER_TREKK")
        assertIngentingLagret()
    }

    @Test
    fun `avviser dag uten sykdomsgrad før fordeling`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024, sykdomsgradFørFordeling = null))))

        assertEquals(HttpStatusCode.BadRequest, svar.status)
        assertIngentingLagret()
    }

    @Test
    fun `validerer før versjonen sjekkes`() {
        val svar = send(request(versjon = UUID.randomUUID(), dager = listOf(dag(23 sep 2024, sykdomsgradFørFordeling = 101))))

        assertFeil(svar, "GRAD_UTENFOR_GRENSER")
        assertIngentingLagret()
    }

    @Test
    fun `avviser dag med blank begrunnelse`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024), dag(24 sep 2024, begrunnelse = " "))))

        assertFeil(svar, "MANGLER_BEGRUNNELSE")
        assertIngentingLagret()
    }

    @Test
    fun `avviser forespørsel uten dager`() {
        val svar = send(request(versjon = null, dager = emptyList()))

        assertFeil(svar, "MANGLER_ENDRINGER")
        assertIngentingLagret()
    }

    @Test
    fun `avviser ugyldig request-body`() {
        val svar = send("""{ "yrkesaktivitet": "ARBEIDSTAKER" }""")

        assertEquals(HttpStatusCode.BadRequest, svar.status)
        assertIngentingLagret()
    }

    @Test
    fun `krever skrivetilgang`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024))), grupper = listOf("les"))

        assertEquals(HttpStatusCode.Forbidden, svar.status)
        assertIngentingLagret()
    }

    private data class Svar(
        val status: HttpStatusCode,
        val body: String,
    ) {
        val json: JsonNode get() = jacksonObjectMapper().readTree(body)
    }

    private fun send(
        body: String,
        grupper: List<String> = listOf("les", "skriv"),
    ): Svar {
        lateinit var svar: Svar
        testApplication {
            application {
                configureJwtAuthentication(
                    AzureAdConfig("sp-andre-ytelser", oauth.issuerUrl("azure").toString(), oauth.jwksUrl("azure").toString()),
                    TilgangsgrupperTilTilganger.fraEnv(mapOf("TILGANG_LES" to "les", "TILGANG_SKRIV" to "skriv")),
                )
                configureRestApi(personProvider, FakeTilgangskontroll()) { personTilgang ->
                    graderteAndreYtelserApi(transaksjonProvider, personTilgang)
                }
            }
            val token =
                oauth
                    .issueToken(
                        issuerId = "azure",
                        audience = "sp-andre-ytelser",
                        claims = mapOf("NAVident" to saksbehandlerIdent, "oid" to UUID.randomUUID().toString(), "groups" to grupper),
                    ).serialize()
            val response =
                client.post("/api/personer/$pseudoId/graderte-andre-ytelser") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            svar = Svar(response.status, response.bodyAsText())
        }
        return svar
    }

    private fun assertFeil(
        svar: Svar,
        forventetKode: String,
        forventetStatus: HttpStatusCode = HttpStatusCode.BadRequest,
    ) {
        assertEquals(forventetStatus, svar.status)
        assertEquals(forventetKode, svar.json["code"].asString())
    }

    private fun assertIngentingLagret() {
        assertTrue(transaksjonProvider.graderteAndreYtelser.finnAlle(identitetsnummer).isEmpty())
        assertTrue(outboxmeldinger().isEmpty())
    }

    private fun lagret() =
        transaksjonProvider.graderteAndreYtelser.finn(
            GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.ARBEIDSTAKER, organisasjonsnummer),
        )

    private fun outboxmeldinger() = transaksjonProvider.outbox.meldinger.map { it.json }

    private fun outboxmeldinger(eventName: String) = outboxmeldinger().filter { it["@event_name"].asString() == eventName }

    private fun endringsmeldinger() = outboxmeldinger("graderte_andre_ytelser_endret")

    private fun saksbehandlerhandlinger() = outboxmeldinger("saksbehandlerhandling")

    private fun JsonNode.fom() = LocalDate.parse(this["fom"].asString())

    private fun JsonNode.uuid(felt: String) = UUID.fromString(this[felt].asString())

    private fun request(
        versjon: UUID?,
        dager: List<Dag>,
        yrkesaktivitet: String = "ARBEIDSTAKER",
        organisasjonsnummer: String? = this.organisasjonsnummer,
        behandlingId: UUID = UUID.randomUUID(),
    ) = """
        {
          "yrkesaktivitet": "$yrkesaktivitet",
          "organisasjonsnummer": ${organisasjonsnummer?.let { "\"$it\"" }},
          "versjon": ${versjon?.let { "\"$it\"" }},
          "behandlingId": "$behandlingId",
          "dager": [${dager.joinToString(",") { it.json() }}]
        }
        """.trimIndent()

    private data class Dag(
        val dato: LocalDate,
        val andreYtelserGrader: Map<String, Int>,
        val sykdomsgrad: Int,
        val arbeidsgrad: Int,
        val sykdomsgradFørFordeling: Int?,
        val begrunnelse: String,
    ) {
        fun json(): String {
            val ytelser = andreYtelserGrader.entries.joinToString(", ") { (type, grad) -> "\"$type\": $grad" }
            val førFordeling = sykdomsgradFørFordeling?.let { """, "sykdomsgradFørFordeling": $it""" } ?: ""
            return """{ "dato": "$dato", "andreYtelserGrader": { $ytelser }, "sykdomsgrad": $sykdomsgrad, "arbeidsgrad": $arbeidsgrad$førFordeling, "begrunnelse": "$begrunnelse" }"""
        }

        fun tilDomene() =
            GraderteAndreYtelserDag(
                dato = dato,
                andreYtelserGrader = andreYtelserGrader.mapKeys { GraderteAndreYtelserType.valueOf(it.key) },
                sykdomsgrad = sykdomsgrad,
                arbeidsgrad = arbeidsgrad,
                sykdomsgradFørFordeling = sykdomsgradFørFordeling!!,
                begrunnelse = begrunnelse,
            )
    }

    // Standard: summen er 100, og ytelsene er tatt fra sykdommen
    private fun dag(
        dato: LocalDate,
        foreldrepenger: Int = 50,
        sykdomsgrad: Int = 30,
        arbeidsgrad: Int = 100 - foreldrepenger - sykdomsgrad,
        sykdomsgradFørFordeling: Int? = 100 - arbeidsgrad,
        begrunnelse: String = "en begrunnelse",
    ) = Dag(dato, mapOf("FORELDREPENGER" to foreldrepenger), sykdomsgrad, arbeidsgrad, sykdomsgradFørFordeling, begrunnelse)

    private fun dagUtenYtelser(
        dato: LocalDate,
        sykdomsgrad: Int,
        sykdomsgradFørFordeling: Int,
    ) = Dag(dato, emptyMap(), sykdomsgrad, 100 - sykdomsgrad, sykdomsgradFørFordeling, "en begrunnelse")
}

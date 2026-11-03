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
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024), dag(24 sep 2024))))

        assertEquals(HttpStatusCode.OK, svar.status)
        val lagret = lagret()
        assertNotNull(lagret)
        assertEquals(lagret.versjon.toString(), svar.json["versjon"].asString())
        assertEquals(NavIdent(saksbehandlerIdent), lagret.events.single().utførtAvSaksbehandlerIdent)
        assertEquals("notat til beslutter", lagret.events.single().notatTilBeslutter)
        assertEquals(listOf(OutboxMelding.GraderteAndreYtelserEndret(23 sep 2024)), outboxmeldinger())
        assertEquals(identitetsnummer, transaksjonProvider.outbox.upubliserteMeldinger.single().identitetsnummer)
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
            listOf(OutboxMelding.GraderteAndreYtelserEndret(23 sep 2024), OutboxMelding.GraderteAndreYtelserEndret(24 sep 2024)),
            outboxmeldinger(),
        )
    }

    @Test
    fun `post uten faktiske endringer lagrer ingenting og returnerer eksisterende versjon`() {
        val første = send(request(versjon = null, dager = listOf(dag(23 sep 2024))))
        val førsteVersjon = UUID.fromString(første.json["versjon"].asString())

        val andre = send(request(versjon = førsteVersjon, dager = listOf(dag(23 sep 2024))))

        assertEquals(HttpStatusCode.OK, andre.status)
        assertEquals(førsteVersjon.toString(), andre.json["versjon"].asString())
        assertEquals(1, lagret()?.events?.size)
        assertEquals(1, outboxmeldinger().size)
    }

    @Test
    fun `avviser endring med utdatert versjon`() {
        val førsteVersjon = UUID.fromString(send(request(versjon = null, dager = listOf(dag(23 sep 2024)))).json["versjon"].asString())
        send(request(versjon = førsteVersjon, dager = listOf(dag(23 sep 2024, sykdomsgrad = 10))))

        val svar = send(request(versjon = førsteVersjon, dager = listOf(dag(23 sep 2024, sykdomsgrad = 20))))

        assertFeil(svar, "UTDATERT_TILSTAND", HttpStatusCode.Conflict)
        assertEquals(2, lagret()?.events?.size)
        assertEquals(2, outboxmeldinger().size)
    }

    @Test
    fun `avviser endring uten versjon når graderte andre ytelser finnes`() {
        send(request(versjon = null, dager = listOf(dag(23 sep 2024))))

        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024, sykdomsgrad = 10))))

        assertFeil(svar, "UTDATERT_TILSTAND", HttpStatusCode.Conflict)
        assertEquals(1, lagret()?.events?.size)
        assertEquals(1, outboxmeldinger().size)
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
            transaksjonProvider.graderteAndreYtelser.finnAlle(identitetsnummer).map { it.id.organisasjonsnummer }.toSet(),
        )
    }

    @Test
    fun `godtar frilans uten organisasjonsnummer`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024)), yrkesaktivitet = "FRILANS", organisasjonsnummer = null))

        assertEquals(HttpStatusCode.OK, svar.status)
        assertNotNull(transaksjonProvider.graderteAndreYtelser.finn(GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.FRILANS, null)))
    }

    @Test
    fun `avviser dag der total grad overstiger 100`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024, foreldrepenger = 50, sykdomsgrad = 30, arbeidsgrad = 21))))

        assertFeil(svar, "TOTAL_GRAD_OVER_100")
        assertIngentingLagret()
    }

    @Test
    fun `godtar dag der total grad er nøyaktig 100`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024, foreldrepenger = 50, sykdomsgrad = 30, arbeidsgrad = 20))))

        assertEquals(HttpStatusCode.OK, svar.status)
        assertEquals(1, transaksjonProvider.graderteAndreYtelser.finnAlle(identitetsnummer).size)
    }

    @Test
    fun `avviser forespørsel uten dager`() {
        val svar = send(request(versjon = null, dager = emptyList()))

        assertFeil(svar, "MANGLER_ENDRINGER")
        assertIngentingLagret()
    }

    @Test
    fun `avviser dag med negativ grad`() {
        val svar = send(request(versjon = null, dager = listOf(dag(23 sep 2024, sykdomsgrad = -1))))

        assertFeil(svar, "NEGATIV_GRAD")
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

    private fun outboxmeldinger() = transaksjonProvider.outbox.upubliserteMeldinger.map { it.melding }

    private fun request(
        versjon: UUID?,
        dager: List<Dag>,
        yrkesaktivitet: String = "ARBEIDSTAKER",
        organisasjonsnummer: String? = this.organisasjonsnummer,
    ) = """
        {
          "yrkesaktivitet": "$yrkesaktivitet",
          "organisasjonsnummer": ${organisasjonsnummer?.let { "\"$it\"" }},
          "versjon": ${versjon?.let { "\"$it\"" }},
          "behandlingId": "${UUID.randomUUID()}",
          "dager": [${dager.joinToString(",") { it.json() }}],
          "notatTilBeslutter": "notat til beslutter"
        }
        """.trimIndent()

    private data class Dag(
        val dato: LocalDate,
        val foreldrepenger: Int,
        val sykdomsgrad: Int,
        val arbeidsgrad: Int,
    ) {
        fun json() =
            """{ "dato": "$dato", "andreYtelserGrader": { "FORELDREPENGER": $foreldrepenger }, "sykdomsgrad": $sykdomsgrad, "arbeidsgrad": $arbeidsgrad }"""

        fun tilDomene() = GraderteAndreYtelserDag(dato, mapOf(GraderteAndreYtelserType.FORELDREPENGER to foreldrepenger), sykdomsgrad, arbeidsgrad)
    }

    private fun dag(
        dato: LocalDate,
        foreldrepenger: Int = 50,
        sykdomsgrad: Int = 30,
        arbeidsgrad: Int = 20,
    ) = Dag(dato, foreldrepenger, sykdomsgrad, arbeidsgrad)
}

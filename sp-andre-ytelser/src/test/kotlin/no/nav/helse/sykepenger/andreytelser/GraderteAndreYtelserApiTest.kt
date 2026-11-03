package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.andreytelser.rest.configureRestApi
import no.nav.helse.sykepenger.andreytelser.rest.graderteAndreYtelserApi
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import no.nav.sykepenger.libs.testing.assertions.assertJsonEquals
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagNavIdent
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import no.nav.sykepenger.libs.testing.testdata.sep
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class GraderteAndreYtelserApiTest {
    private val oauth = MockOAuth2Server().also { it.start() }
    private var transaksjonProvider = InMemoryTransaksjonProvider()
    private val personProvider = FakePersonProvider()
    private val identitetsnummer = Identitetsnummer(lagIdentitetsnummer())
    private val pseudoId = personProvider.nyPersonPseudoId(identitetsnummer).value
    private val saksbehandlerIdent = lagNavIdent()

    @BeforeEach
    fun nullstill() {
        transaksjonProvider = InMemoryTransaksjonProvider()
    }

    @AfterAll
    fun stopp() {
        oauth.shutdown()
    }

    @Test
    fun `tom liste hvis det ikke er lagt inn noe for personen`() {
        val response = hent()

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals("[]", response.body)
    }

    @Test
    fun `henter graderte andre ytelser med dagene og eventene`() {
        val organisasjonsnummer = lagOrganisasjonsnummer()
        val graderteAndreYtelser =
            GraderteAndreYtelser.ny(
                id = GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.ARBEIDSTAKER, organisasjonsnummer),
                dager = listOf(dag(12 sep 2024), dag(13 sep 2024)),
                saksbehandlerIdent = NavIdent(saksbehandlerIdent),
                notatTilBeslutter = "et notat til beslutter",
            )
        graderteAndreYtelser.endre(
            dager = listOf(dag(13 sep 2024, svangerskapspenger = 50, sykdomsgrad = 50)),
            saksbehandlerIdent = NavIdent(saksbehandlerIdent),
            notatTilBeslutter = "et nytt notat",
        )
        lagre(graderteAndreYtelser)
        val (førsteEvent, andreEvent) = graderteAndreYtelser.events

        val response = hent()

        assertEquals(HttpStatusCode.OK, response.status)
        assertJsonEquals(
            """
            [
              {
                "yrkesaktivitet": "ARBEIDSTAKER",
                "organisasjonsnummer": "$organisasjonsnummer",
                "versjon": "${andreEvent.versjon}",
                "dager": [
                  { "dato": "2024-09-12", "andreYtelserGrader": { "SVANGERSKAPSPENGER": 66 }, "sykdomsgrad": 34, "arbeidsgrad": 0 },
                  { "dato": "2024-09-13", "andreYtelserGrader": { "SVANGERSKAPSPENGER": 50 }, "sykdomsgrad": 50, "arbeidsgrad": 0 }
                ],
                "events": [
                  {
                    "versjon": "${førsteEvent.versjon}",
                    "utfortAvSaksbehandlerIdent": "$saksbehandlerIdent",
                    "notatTilBeslutter": "et notat til beslutter",
                    "dager": [
                      {
                        "dato": "2024-09-12", "andreYtelserGrader": { "SVANGERSKAPSPENGER": 66 }, "sykdomsgrad": 34, "arbeidsgrad": 0,
                        "fraAndreYtelserGrader": null, "fraSykdomsgrad": null, "fraArbeidsgrad": null
                      },
                      {
                        "dato": "2024-09-13", "andreYtelserGrader": { "SVANGERSKAPSPENGER": 66 }, "sykdomsgrad": 34, "arbeidsgrad": 0,
                        "fraAndreYtelserGrader": null, "fraSykdomsgrad": null, "fraArbeidsgrad": null
                      }
                    ]
                  },
                  {
                    "versjon": "${andreEvent.versjon}",
                    "utfortAvSaksbehandlerIdent": "$saksbehandlerIdent",
                    "notatTilBeslutter": "et nytt notat",
                    "dager": [
                      {
                        "dato": "2024-09-13", "andreYtelserGrader": { "SVANGERSKAPSPENGER": 50 }, "sykdomsgrad": 50, "arbeidsgrad": 0,
                        "fraAndreYtelserGrader": { "SVANGERSKAPSPENGER": 66 }, "fraSykdomsgrad": 34, "fraArbeidsgrad": 0
                      }
                    ]
                  }
                ]
              }
            ]
            """.trimIndent(),
            response.body,
            bortsettFraStier = setOf("events.tidspunkt"),
        )
        val tidspunkt = LocalDateTime.parse(jacksonObjectMapper().readTree(response.body)[0]["events"][0]["tidspunkt"].asString())
        assertTrue(tidspunkt.isAfter(LocalDateTime.now().minusMinutes(1)), "tidspunkt er norsk lokal tid: $tidspunkt")
    }

    @Test
    fun `sorterer etter første dag`() {
        val tidlig = GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.FRILANS, null)
        val sen = GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.ARBEIDSTAKER, lagOrganisasjonsnummer())
        lagre(ny(sen, dag(13 sep 2024)))
        lagre(ny(tidlig, dag(12 sep 2024)))

        val response = hent()

        val body = jacksonObjectMapper().readTree(response.body)
        val yrkesaktiviteter: List<String> = (0 until body.size()).map { body[it]["yrkesaktivitet"].asString() }
        assertEquals(listOf("FRILANS", "ARBEIDSTAKER"), yrkesaktiviteter)
    }

    @Test
    fun `viser ikke andre personers graderte andre ytelser`() {
        val annenPerson = Identitetsnummer(lagIdentitetsnummer())
        lagre(ny(GraderteAndreYtelserId(annenPerson, Yrkesaktivitetstype.FRILANS, null), dag(12 sep 2024)))

        val response = hent()

        assertJsonEquals("[]", response.body)
    }

    @Test
    fun `krever lesetilgang`() {
        val response = hent(grupper = emptyList())

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    private data class Svar(
        val status: HttpStatusCode,
        val body: String,
    )

    private fun hent(grupper: List<String> = listOf("les")): Svar {
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
            val response: HttpResponse = client.get("/api/personer/$pseudoId/graderte-andre-ytelser") { bearerAuth(token) }
            svar = Svar(response.status, response.bodyAsText())
        }
        return svar
    }

    private fun lagre(graderteAndreYtelser: GraderteAndreYtelser) {
        transaksjonProvider.graderteAndreYtelser.lagre(graderteAndreYtelser)
    }

    private fun ny(
        id: GraderteAndreYtelserId,
        dag: GraderteAndreYtelserDag,
    ) = GraderteAndreYtelser.ny(id, listOf(dag), NavIdent(saksbehandlerIdent), "notat")

    private fun dag(
        dato: LocalDate,
        svangerskapspenger: Int = 66,
        sykdomsgrad: Int = 34,
    ) = GraderteAndreYtelserDag(
        dato = dato,
        andreYtelserGrader = mapOf(GraderteAndreYtelserType.SVANGERSKAPSPENGER to svangerskapspenger),
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = 0,
    )
}

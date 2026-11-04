package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.OMSORGSPENGER
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.SVANGERSKAPSPENGER
import no.nav.helse.sykepenger.andreytelser.rest.configureRestApi
import no.nav.helse.sykepenger.andreytelser.rest.saksbehandlerhandlingVisningApi
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
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SaksbehandlerhandlingVisningApiTest {
    private val oauth = MockOAuth2Server().also { it.start() }
    private var transaksjonProvider = InMemoryTransaksjonProvider()
    private val personProvider = FakePersonProvider()
    private val identitetsnummer = Identitetsnummer(lagIdentitetsnummer())
    private val pseudoId = personProvider.nyPersonPseudoId(identitetsnummer).value
    private val saksbehandlerIdent = NavIdent(lagNavIdent())

    @BeforeEach
    fun nullstill() {
        transaksjonProvider = InMemoryTransaksjonProvider()
    }

    @AfterAll
    fun stopp() {
        oauth.shutdown()
    }

    @Test
    fun `viser notat og endrede dager gruppert`() {
        val graderteAndreYtelser =
            GraderteAndreYtelser.ny(
                id = GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.ARBEIDSTAKER, lagOrganisasjonsnummer()),
                dager = listOf(dag(23 sep 2024), dag(24 sep 2024), dag(25 sep 2024)),
                saksbehandlerIdent = saksbehandlerIdent,
                notatTilBeslutter = "første notat",
            )
        val event =
            graderteAndreYtelser.endre(
                dager =
                    listOf(
                        dag(23 sep 2024, andreYtelserGrader = mapOf(SVANGERSKAPSPENGER to 30), sykdomsgrad = 70),
                        dag(24 sep 2024, andreYtelserGrader = mapOf(SVANGERSKAPSPENGER to 30), sykdomsgrad = 70),
                        dag(25 sep 2024, andreYtelserGrader = mapOf(OMSORGSPENGER to 50)),
                        dag(27 sep 2024, andreYtelserGrader = mapOf(SVANGERSKAPSPENGER to 20), sykdomsgrad = 80),
                        dag(28 sep 2024, andreYtelserGrader = mapOf(SVANGERSKAPSPENGER to 20), sykdomsgrad = 80),
                    ),
                saksbehandlerIdent = saksbehandlerIdent,
                notatTilBeslutter = "endret etter ny dokumentasjon",
            )!!
        lagre(graderteAndreYtelser)

        val svar = hent(event.versjon.toString())

        assertEquals(HttpStatusCode.OK, svar.status)
        assertJsonEquals(
            """
            {
              "felter": [
                {
                  "type": "TEKST",
                  "tittel": "Begrunnelse",
                  "tekst": "endret etter ny dokumentasjon"
                },
                {
                  "type": "ENDRINGER",
                  "tittel": "Dager endret",
                  "grupper": [
                    {
                      "rader": [
                        { "navn": "Dato", "fra": null, "til": "23.09.2024 - 24.09.2024" },
                        { "navn": "Svangerskapspenger", "fra": "50 %", "til": "30 %" },
                        { "navn": "Sykdomsgrad", "fra": "50 %", "til": "70 %" },
                        { "navn": "Arbeidsgrad", "fra": null, "til": "0 %" }
                      ]
                    },
                    {
                      "rader": [
                        { "navn": "Dato", "fra": null, "til": "25.09.2024" },
                        { "navn": "Svangerskapspenger", "fra": "50 %", "til": "0 %" },
                        { "navn": "Omsorgspenger", "fra": "0 %", "til": "50 %" },
                        { "navn": "Sykdomsgrad", "fra": null, "til": "50 %" },
                        { "navn": "Arbeidsgrad", "fra": null, "til": "0 %" }
                      ]
                    },
                    {
                      "rader": [
                        { "navn": "Dato", "fra": null, "til": "27.09.2024 - 28.09.2024" },
                        { "navn": "Svangerskapspenger", "fra": null, "til": "20 %" },
                        { "navn": "Sykdomsgrad", "fra": null, "til": "80 %" },
                        { "navn": "Arbeidsgrad", "fra": null, "til": "0 %" }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
            svar.body,
        )
    }

    @Test
    fun `viser første versjon og utelater tom begrunnelse`() {
        val graderteAndreYtelser = ny(identitetsnummer, notatTilBeslutter = " ")
        lagre(graderteAndreYtelser)

        val svar = hent(graderteAndreYtelser.versjon.toString())

        assertEquals(HttpStatusCode.OK, svar.status)
        val felter = jacksonObjectMapper().readTree(svar.body)["felter"]
        assertEquals(listOf("Dager endret"), felter.values().map { it["tittel"].asString() })
    }

    @Test
    fun `ukjent handlingId gir 404`() {
        lagre(ny(identitetsnummer))

        val svar = hent(UUID.randomUUID().toString())

        assertEquals(HttpStatusCode.NotFound, svar.status)
        assertEquals("Fant ikke detaljer", jacksonObjectMapper().readTree(svar.body)["title"].asString())
    }

    @Test
    fun `handling som tilhører en annen person gir samme svar som ukjent handlingId`() {
        val andres = ny(Identitetsnummer(lagIdentitetsnummer()))
        lagre(andres)

        val annenPerson = hent(andres.versjon.toString())
        val ukjent = hent(UUID.randomUUID().toString())

        assertEquals(HttpStatusCode.NotFound, annenPerson.status)
        assertEquals(ukjent.status, annenPerson.status)
        assertEquals(ukjent.utenInstance(), annenPerson.utenInstance())
    }

    @Test
    fun `ugyldig handlingId gir samme svar som ukjent handlingId`() {
        val ugyldig = hent("ikke-en-uuid")
        val ukjent = hent(UUID.randomUUID().toString())

        assertEquals(HttpStatusCode.NotFound, ugyldig.status)
        assertEquals(ukjent.utenInstance(), ugyldig.utenInstance())
    }

    @Test
    fun `krever lesetilgang`() {
        val graderteAndreYtelser = ny(identitetsnummer)
        lagre(graderteAndreYtelser)

        val svar = hent(graderteAndreYtelser.versjon.toString(), grupper = emptyList())

        assertEquals(HttpStatusCode.Forbidden, svar.status)
    }

    private data class Svar(
        val status: HttpStatusCode,
        val body: String,
    ) {
        fun utenInstance(): Map<String, String> =
            jacksonObjectMapper()
                .readTree(body)
                .properties()
                .filter { it.key != "instance" }
                .associate { it.key to it.value.toString() }
    }

    private fun hent(
        handlingId: String,
        grupper: List<String> = listOf("les"),
    ): Svar {
        lateinit var svar: Svar
        testApplication {
            application {
                configureJwtAuthentication(
                    AzureAdConfig("sp-andre-ytelser", oauth.issuerUrl("azure").toString(), oauth.jwksUrl("azure").toString()),
                    TilgangsgrupperTilTilganger.fraEnv(mapOf("TILGANG_LES" to "les", "TILGANG_SKRIV" to "skriv")),
                )
                configureRestApi(personProvider, FakeTilgangskontroll()) { personTilgang ->
                    saksbehandlerhandlingVisningApi(transaksjonProvider, personTilgang)
                }
            }
            val token =
                oauth
                    .issueToken(
                        issuerId = "azure",
                        audience = "sp-andre-ytelser",
                        claims = mapOf("NAVident" to saksbehandlerIdent.value, "oid" to UUID.randomUUID().toString(), "groups" to grupper),
                    ).serialize()
            val response = client.get("/api/personer/$pseudoId/saksbehandlerhandlinger/$handlingId/visning") { bearerAuth(token) }
            svar = Svar(response.status, response.bodyAsText())
        }
        return svar
    }

    private fun lagre(graderteAndreYtelser: GraderteAndreYtelser) {
        transaksjonProvider.graderteAndreYtelser.lagre(graderteAndreYtelser)
    }

    private fun ny(
        identitetsnummer: Identitetsnummer,
        notatTilBeslutter: String = "notat",
    ) = GraderteAndreYtelser.ny(
        id = GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.FRILANS, null),
        dager = listOf(dag(23 sep 2024)),
        saksbehandlerIdent = saksbehandlerIdent,
        notatTilBeslutter = notatTilBeslutter,
    )

    private fun dag(
        dato: LocalDate,
        andreYtelserGrader: Map<GraderteAndreYtelserType, Int> = mapOf(SVANGERSKAPSPENGER to 50),
        sykdomsgrad: Int = 50,
    ) = GraderteAndreYtelserDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader,
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = 0,
        sykdomsgradFørFordeling = sykdomsgrad + andreYtelserGrader.values.sum(),
    )
}

package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.OMSORGSPENGER
import no.nav.helse.sykepenger.andreytelser.GraderteAndreYtelserType.PLEIEPENGER
import no.nav.helse.sykepenger.andreytelser.rest.configureRestApi
import no.nav.helse.sykepenger.andreytelser.rest.graderteAndreYtelserApi
import no.nav.helse.sykepenger.andreytelser.rest.graderteAndreYtelserPerioderApi
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.KlientId
import no.nav.sykepenger.libs.ktor.auth.NavIdent
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureAppAuthentication
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import no.nav.sykepenger.libs.testing.assertions.assertJsonEquals
import no.nav.sykepenger.libs.testing.testdata.jan
import no.nav.sykepenger.libs.testing.testdata.lagIdentitetsnummer
import no.nav.sykepenger.libs.testing.testdata.lagNavIdent
import no.nav.sykepenger.libs.testing.testdata.lagOrganisasjonsnummer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class GraderteAndreYtelserPerioderApiTest {
    private val oauth = MockOAuth2Server().also { it.start() }
    private var transaksjonProvider = InMemoryTransaksjonProvider()
    private val personProvider = FakePersonProvider()
    private val identitetsnummer = Identitetsnummer(lagIdentitetsnummer())
    private val saksbehandlerIdent = NavIdent(lagNavIdent())
    private val id = GraderteAndreYtelserId(identitetsnummer, Yrkesaktivitetstype.ARBEIDSTAKER, lagOrganisasjonsnummer())

    @BeforeEach
    fun nullstill() {
        transaksjonProvider = InMemoryTransaksjonProvider()
    }

    @AfterAll
    fun stopp() {
        oauth.shutdown()
    }

    @Test
    fun `gir periodene med trekk slik de var i versjonen`() {
        val graderteAndreYtelser =
            GraderteAndreYtelser.ny(
                id,
                listOf(
                    dag(1 jan 2024, mapOf(PLEIEPENGER to 20)),
                    dag(2 jan 2024, mapOf(PLEIEPENGER to 20)),
                    dag(3 jan 2024, mapOf(OMSORGSPENGER to 10, PLEIEPENGER to 30)),
                ),
                saksbehandlerIdent,
            )
        val versjon = graderteAndreYtelser.versjon
        graderteAndreYtelser.endre(listOf(dag(2 jan 2024, mapOf(PLEIEPENGER to 40))), saksbehandlerIdent)
        transaksjonProvider.graderteAndreYtelser.lagre(graderteAndreYtelser)

        val svar = hent("/api/graderte-andre-ytelser/versjoner/$versjon/perioder?fom=2024-01-01&tom=2024-01-31")

        assertEquals(HttpStatusCode.OK, svar.status)
        assertJsonEquals(
            """
            [
              { "fom": "2024-01-01", "tom": "2024-01-02", "ytelser": [ { "type": "PLEIEPENGER", "grad": 20 } ] },
              { "fom": "2024-01-03", "tom": "2024-01-03", "ytelser": [ { "type": "PLEIEPENGER", "grad": 30 }, { "type": "OMSORGSPENGER", "grad": 10 } ] }
            ]
            """.trimIndent(),
            svar.body,
        )
    }

    @Test
    fun `app-token fra en annen app enn sporbar gir 403`() {
        val svar = hent(stiTil(lagre()), token = appToken(azp = "speil-klient-id"))

        assertEquals(HttpStatusCode.Forbidden, svar.status)
        assertEquals("application/problem+json", svar.contentType)
    }

    @ParameterizedTest
    @ValueSource(strings = ["tom=2024-01-31", "fom=2024-01-01", "fom=ugyldig&tom=2024-01-31", "fom=2024-01-01&tom=31.01.2024", "fom=2024-02-01&tom=2024-01-31"])
    fun `manglende eller ugyldig fom og tom gir 400`(query: String) {
        val svar = hent(stiTil(lagre(), query))

        assertEquals(HttpStatusCode.BadRequest, svar.status)
        assertEquals("application/problem+json", svar.contentType)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ikke-en-uuid", "0198a1b2-0000-7000-8000-000000000000"])
    fun `ugyldig eller ukjent versjon gir 404`(versjon: String) {
        lagre()

        val svar = hent(stiTil(versjon))

        assertEquals(HttpStatusCode.NotFound, svar.status)
        assertEquals("application/problem+json", svar.contentType)
    }

    @Test
    fun `tom liste når ingen dager i perioden har trekk`() {
        val svar = hent(stiTil(lagre(), "fom=2024-02-01&tom=2024-02-29"))

        assertEquals(HttpStatusCode.OK, svar.status)
        assertJsonEquals("[]", svar.body)
    }

    @Test
    fun `uten token gir 401`() {
        assertEquals(HttpStatusCode.Unauthorized, hent(stiTil(lagre()), token = null).status)
    }

    @Test
    fun `saksbehandlertoken gir 401 på periodene`() {
        assertEquals(HttpStatusCode.Unauthorized, hent(stiTil(lagre()), token = saksbehandlerToken()).status)
    }

    @Test
    fun `app-token fra sporbar gir 401 på endepunktene for saksbehandlere`() {
        val pseudoId = personProvider.nyPersonPseudoId(identitetsnummer).value
        lagre()

        assertEquals(HttpStatusCode.Unauthorized, hent("/api/personer/$pseudoId/graderte-andre-ytelser").status)
        assertEquals(HttpStatusCode.OK, hent("/api/personer/$pseudoId/graderte-andre-ytelser", token = saksbehandlerToken()).status)
    }

    private fun saksbehandlerToken() =
        oauth
            .issueToken(
                issuerId = ISSUER_ID,
                audience = CLIENT_ID,
                claims = mapOf("NAVident" to saksbehandlerIdent.value, "oid" to UUID.randomUUID().toString(), "groups" to listOf("les")),
            ).serialize()

    private fun lagre(): UUID {
        val graderteAndreYtelser = GraderteAndreYtelser.ny(id, listOf(dag(1 jan 2024, mapOf(PLEIEPENGER to 20))), saksbehandlerIdent)
        transaksjonProvider.graderteAndreYtelser.lagre(graderteAndreYtelser)
        return graderteAndreYtelser.versjon
    }

    private fun stiTil(
        versjon: Any,
        query: String = "fom=2024-01-01&tom=2024-01-31",
    ) = "/api/graderte-andre-ytelser/versjoner/$versjon/perioder?$query"

    private data class Svar(
        val status: HttpStatusCode,
        val contentType: String?,
        val body: String,
    )

    private fun hent(
        sti: String,
        token: String? = appToken(),
    ): Svar {
        lateinit var svar: Svar
        testApplication {
            application {
                val azureAdConfig = AzureAdConfig(CLIENT_ID, oauth.issuerUrl(ISSUER_ID).toString(), oauth.jwksUrl(ISSUER_ID).toString())
                configureJwtAuthentication(azureAdConfig, TilgangsgrupperTilTilganger.fraEnv(mapOf("TILGANG_LES" to "les", "TILGANG_SKRIV" to "skriv")))
                configureAppAuthentication(azureAdConfig)
                configureRestApi(
                    personProvider,
                    FakeTilgangskontroll(),
                    ruter = { personTilgang -> graderteAndreYtelserApi(transaksjonProvider, personTilgang) },
                    appRuter = { graderteAndreYtelserPerioderApi(transaksjonProvider, KlientId(SPORBAR_KLIENT_ID)) },
                )
            }
            val response = client.get(sti) { if (token != null) bearerAuth(token) }
            svar = Svar(response.status, response.headers["Content-Type"]?.substringBefore(";"), response.bodyAsText())
        }
        return svar
    }

    private fun appToken(azp: String = SPORBAR_KLIENT_ID) = oauth.anyToken(oauth.issuerUrl(ISSUER_ID), mapOf("aud" to CLIENT_ID, "idtyp" to "app", "azp" to azp)).serialize()

    private fun dag(
        dato: LocalDate,
        andreYtelserGrader: Map<GraderteAndreYtelserType, Grad>,
        sykdomsgrad: Grad = 30,
    ) = GraderteAndreYtelserDag(
        dato = dato,
        andreYtelserGrader = andreYtelserGrader,
        sykdomsgrad = sykdomsgrad,
        arbeidsgrad = 100 - sykdomsgrad - andreYtelserGrader.values.sum(),
        sykdomsgradFørFordeling = sykdomsgrad + andreYtelserGrader.values.sum(),
        begrunnelse = "en begrunnelse",
    )

    private companion object {
        const val CLIENT_ID = "sp-andre-ytelser"
        const val ISSUER_ID = "azure"
        const val SPORBAR_KLIENT_ID = "sporbar-klient-id"
    }
}

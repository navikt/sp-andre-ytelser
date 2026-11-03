package no.nav.helse.sykepenger.andreytelser

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.OpenApiConfig
import no.nav.helse.sykepenger.andreytelser.rammeverk.openapi.configureOpenApiPlugin
import no.nav.helse.sykepenger.andreytelser.rest.configureRestApi
import no.nav.helse.sykepenger.andreytelser.rest.graderteAndreYtelserApi
import no.nav.helse.sykepenger.andreytelser.rest.saksbehandlerhandlingVisningApi
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class OpenApiSpecTest {
    private val oauth = MockOAuth2Server().also { it.start() }

    @AfterAll
    fun stopp() {
        oauth.shutdown()
    }

    @Test
    fun `dokumenterer alle endepunktene med operationId`() {
        val paths = spec()["paths"]

        assertEquals("getGraderteAndreYtelser", paths["/api/personer/{personPseudoId}/graderte-andre-ytelser"]["get"]["operationId"].asString())
        assertEquals("postGraderteAndreYtelser", paths["/api/personer/{personPseudoId}/graderte-andre-ytelser"]["post"]["operationId"].asString())
        assertEquals(
            "getSaksbehandlerhandlingVisning",
            paths["/api/personer/{personPseudoId}/saksbehandlerhandlinger/{handlingId}/visning"]["get"]["operationId"].asString(),
        )
    }

    @Test
    fun `stiparametrene er typet som uuid`() {
        val parametere = spec()["paths"]["/api/personer/{personPseudoId}/saksbehandlerhandlinger/{handlingId}/visning"]["get"]["parameters"]

        assertEquals(
            listOf("personPseudoId" to "uuid", "handlingId" to "uuid"),
            parametere.values().map { it["name"].asString() to it["schema"]["format"].asString() },
        )
    }

    @Test
    fun `dokumenterer feilsvarene som problem+json`() {
        val responses = spec()["paths"]["/api/personer/{personPseudoId}/graderte-andre-ytelser"]["post"]["responses"]

        listOf("400", "403", "404", "409").forEach { statuskode ->
            assertTrue(responses[statuskode]["content"].has("application/problem+json"), "Mangler $statuskode: $responses")
        }
    }

    // Speil bruker type-feltet for å skille visningsfeltene fra hverandre. JSON-svaret lages av Jackson,
    // mens OpenAPI-spesifikasjonen lages fra kotlinx-annotasjonene. Testen fanger det hvis de to ikke lenger stemmer overens.
    @Test
    fun `alle visningsfelt har type-feltet i OpenAPI-spesifikasjonen`() {
        val schemas = spec()["components"]["schemas"]

        listOf("ApiVisningsfelt.Tekst", "ApiVisningsfelt.Endringer").forEach { skjema ->
            assertTrue(schemas[skjema]?.get("properties")?.has("type") == true, "Mangler type i $skjema: ${schemas[skjema]}")
        }
    }

    private fun spec(): JsonNode {
        lateinit var spec: JsonNode
        testApplication {
            application {
                configureJwtAuthentication(
                    AzureAdConfig("sp-andre-ytelser", oauth.issuerUrl("azure").toString(), oauth.jwksUrl("azure").toString()),
                    TilgangsgrupperTilTilganger.fraEnv(mapOf("TILGANG_LES" to "les", "TILGANG_SKRIV" to "skriv")),
                )
                configureOpenApiPlugin(OpenApiConfig(eksponerOpenApi = true, tittel = "sp-andre-ytelser"))
                configureRestApi(FakePersonProvider(), FakeTilgangskontroll()) { personTilgang ->
                    graderteAndreYtelserApi(InMemoryTransaksjonProvider(), personTilgang)
                    saksbehandlerhandlingVisningApi(InMemoryTransaksjonProvider(), personTilgang)
                }
            }
            startApplication()
            val response = client.get("/api/openapi.json")
            assertEquals(HttpStatusCode.OK, response.status)
            spec = jacksonObjectMapper().readTree(response.bodyAsText())
        }
        return spec
    }
}

package no.nav.helse.sykepenger.andreytelser.rammeverk.openapi

import io.github.smiley4.ktoropenapi.get
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ConfigureOpenApiPluginTest {
    @Test
    fun `openapi-json svarer 404 når eksponerOpenApi er false`() =
        testApplication {
            application { configureOpenApiPlugin(OpenApiConfig(eksponerOpenApi = false, tittel = "test")) }

            val response = client.get("/api/openapi.json")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `openapi-json dokumenterer rutene når eksponerOpenApi er true`() =
        testApplication {
            application {
                configureOpenApiPlugin(OpenApiConfig(eksponerOpenApi = true, tittel = "test"))
                routing {
                    get("/api/test-endepunkt", {}) { call.respondText("ok") }
                }
            }
            startApplication()

            val response = client.get("/api/openapi.json")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"title\" : \"test\""), body)
            assertTrue(body.contains("/api/test-endepunkt"), body)
        }

    @Test
    fun `EKSPONER_OPENAPI styrer eksponeringen og er av som standard`() {
        assertEquals(false, OpenApiConfig.fraEnv("test", emptyMap()).eksponerOpenApi)
        assertEquals(true, OpenApiConfig.fraEnv("test", mapOf("EKSPONER_OPENAPI" to "true")).eksponerOpenApi)
    }
}

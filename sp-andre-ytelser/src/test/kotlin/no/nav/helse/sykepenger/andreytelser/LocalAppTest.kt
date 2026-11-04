package no.nav.helse.sykepenger.andreytelser

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

internal class LocalAppTest {
    @Test
    fun `tilbyr et lokalt token for Speils proxy`() =
        testApplication {
            application { installerLokaltTokenEndepunkt() }

            val response = client.get("/local-token")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("lokalt-token", response.bodyAsText())
        }
}

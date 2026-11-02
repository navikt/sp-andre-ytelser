package no.nav.helse.sykepenger.andreytelser

import com.github.navikt.tbd_libs.personpseudoid.Identitetsnummer
import com.github.navikt.tbd_libs.populasjonstilgang.api.TilgangskontrollResultat
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import no.nav.helse.sykepenger.andreytelser.rest.configureRestApi
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.sykepenger.libs.ktor.auth.AzureAdConfig
import no.nav.sykepenger.libs.ktor.auth.Tilgang
import no.nav.sykepenger.libs.ktor.auth.TilgangsgrupperTilTilganger
import no.nav.sykepenger.libs.ktor.auth.configureJwtAuthentication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TilgangTest {
    private val oauth = MockOAuth2Server().also { it.start() }
    private val fødselsnummer = "12345678901"
    private val claims = mapOf("NAVident" to "Z999999", "oid" to UUID.randomUUID().toString(), "groups" to listOf("les"))

    @AfterAll
    fun stoppUtsteder() {
        oauth.shutdown()
    }

    @Test
    fun `gyldig Azure-token med tilgang slipper gjennom`() = sjekk(HttpStatusCode.OK)

    @Test
    fun `skrivegruppe alene gir også lesetilgang`() = sjekk(HttpStatusCode.OK, claims + ("groups" to listOf("skriv")))

    @Test
    fun `lesegruppe gir ikke skrivetilgang`() = sjekk(HttpStatusCode.Forbidden, tilgang = Tilgang.Skriv)

    @Test
    fun `token uten tilgangsgruppe gir 403`() = sjekk(HttpStatusCode.Forbidden, claims - "groups")

    @Test
    fun `ukjent pseudo-ID gir 404`() = sjekk(HttpStatusCode.NotFound, kjentPerson = false)

    @Test
    fun `avslag fra tilgangsmaskinen gir 403`() = sjekk(HttpStatusCode.Forbidden, tilgangsresultat = TilgangskontrollResultat.UventetFeil("feil"))

    @Test
    fun `ident som tilgangsmaskinen ikke finner gir 404`() = sjekk(HttpStatusCode.NotFound, tilgangsresultat = TilgangskontrollResultat.IdentIkkeFunnet)

    @Test
    fun `feil audience gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, audience = "annen-tjeneste")

    @Test
    fun `feil issuer gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, issuer = "annen-utsteder")

    @Test
    fun `mangler NAVident gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, claims - "NAVident")

    @Test
    fun `mangler oid gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, claims - "oid")

    @Test
    fun `mangler token gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, utenToken = true)

    @Test
    fun `ugyldig token gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, rawToken = "ugyldig")

    @Test
    fun `ugyldig gruppeclaim gir 401 uten body`() = sjekk(HttpStatusCode.Unauthorized, claims + ("groups" to "les"))

    @Test
    fun `ugyldig pseudo-ID gir 404`() = sjekk(HttpStatusCode.NotFound, pseudoIdIUrl = "ikke-en-uuid")

    private fun sjekk(
        forventetStatus: HttpStatusCode,
        tokenClaims: Map<String, Any> = claims,
        audience: String = "sp-andre-ytelser",
        issuer: String = "azure",
        utenToken: Boolean = false,
        rawToken: String? = null,
        tilgang: Tilgang = Tilgang.Les,
        kjentPerson: Boolean = true,
        tilgangsresultat: TilgangskontrollResultat = TilgangskontrollResultat.Ok,
        pseudoIdIUrl: String? = null,
    ) = testApplication {
        val personProvider = FakePersonProvider()
        val pseudoId =
            if (kjentPerson) {
                personProvider.nyPersonPseudoId(Identitetsnummer(fødselsnummer)).value
            } else {
                UUID.randomUUID()
            }
        application {
            configureJwtAuthentication(
                AzureAdConfig("sp-andre-ytelser", oauth.issuerUrl("azure").toString(), oauth.jwksUrl("azure").toString()),
                TilgangsgrupperTilTilganger.fraEnv(
                    mapOf("TILGANG_LES" to "annen-lesegruppe, les ", "TILGANG_SKRIV" to "annen-skrivegruppe, skriv "),
                ),
            )
            configureRestApi(personProvider, FakeTilgangskontroll(tilgangsresultat)) { personTilgang ->
                get("/test/personer/{personPseudoId}") {
                    val ident = personTilgang.identMedTilgang(call, tilgang) ?: return@get
                    call.respondText(ident.value)
                }
            }
        }
        val token =
            rawToken ?: oauth
                .issueToken(
                    issuerId = issuer,
                    audience = audience,
                    claims = tokenClaims,
                ).serialize()

        val response =
            client.get("/test/personer/${pseudoIdIUrl ?: pseudoId}") {
                if (!utenToken) bearerAuth(token)
            }

        assertEquals(forventetStatus, response.status)
        if (forventetStatus == HttpStatusCode.OK) assertEquals(fødselsnummer, response.bodyAsText())
        if (forventetStatus == HttpStatusCode.Unauthorized) assertEquals("", response.bodyAsText())
    }
}

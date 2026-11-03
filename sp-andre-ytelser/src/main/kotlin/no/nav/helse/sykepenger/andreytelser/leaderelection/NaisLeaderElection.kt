package no.nav.helse.sykepenger.andreytelser.leaderelection

import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import no.nav.sykepenger.libs.logging.loggError
import no.nav.sykepenger.libs.logging.loggInfo
import no.nav.sykepenger.libs.logging.loggWarn
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

internal class NaisLeaderElection(
    electorSseUrl: String,
    private val hostname: String,
    private val ventetidFørNyTilkobling: Duration = 5.seconds,
) : LeaderElection,
    RapidsConnection.StatusListener {
    private val objectMapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder().connectTimeout(1.seconds.toJavaDuration()).build()
    private val request = HttpRequest.newBuilder(URI(electorSseUrl)).header("Accept", "text/event-stream").GET().build()
    private var jobb: Thread? = null

    @Volatile
    private var leader: String? = null

    @Volatile
    private var stoppet = false

    @Volatile
    private var strøm: InputStream? = null

    override fun isLeader(): Boolean = !stoppet && leader == hostname

    override fun onStartup(rapidsConnection: RapidsConnection) {
        jobb = thread(name = "leader-election", isDaemon = true) { lyttPåElector() }
    }

    override fun onShutdownSignal(rapidsConnection: RapidsConnection) {
        stoppet = true
        httpClient.shutdownNow()
        try {
            strøm?.close()
        } finally {
            jobb?.interrupt()
            jobb?.join()
        }
    }

    private fun lyttPåElector() {
        var feiledeTilkoblingerPåRad = 0
        while (!stoppet) {
            try {
                lesStrømmenTilDenLukkes()
                feiledeTilkoblingerPåRad = 0
                if (!stoppet) loggInfo("Elector lukket SSE-strømmen. Kobler til på nytt")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                if (stoppet) return
                feiledeTilkoblingerPåRad++
                if (feiledeTilkoblingerPåRad >= TILKOBLINGSFEIL_FØR_ERROR) {
                    loggError("Har ikke klart å lytte på leader-endringer fra elector på en stund", e)
                } else {
                    loggWarn("Mistet eller fikk ikke kontakt med elector. Prøver igjen", e)
                }
            }
            try {
                Thread.sleep(ventetidFørNyTilkobling.inWholeMilliseconds)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    internal fun lesStrømmenTilDenLukkes() {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            strøm = body
            try {
                check(response.statusCode() == 200) { "Fikk HTTP ${response.statusCode()} fra elector" }
                if (stoppet) return
                body.bufferedReader().useLines { linjer ->
                    linjer.filter { it.startsWith("data:") }.forEach { oppdaterLeader(it.removePrefix("data:").trim()) }
                }
            } finally {
                strøm = null
            }
        }
    }

    private fun oppdaterLeader(data: String) {
        val nyLeader = objectMapper.readTree(data).path("name").asString().ifBlank { null }
        if (nyLeader != leader) {
            loggInfo("Leader er endret", "leader" to nyLeader, "erLeader" to (nyLeader == hostname).toString())
        }
        // Elector velger leader til poden slettes, så siste kjente leader gjelder også under brudd i SSE-strømmen.
        leader = nyLeader
    }

    private companion object {
        const val TILKOBLINGSFEIL_FØR_ERROR = 12
    }
}

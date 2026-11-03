package no.nav.helse.sykepenger.andreytelser

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

internal class TestElector(
    hostname: String,
    private val holdStrømÅpen: Boolean = false,
) : AutoCloseable {
    @Volatile
    var statuskode = 200

    @Volatile
    var hendelser = listOf("""{"name":"$hostname"}""")

    val tilkoblinger = AtomicInteger()
    private val lukkStrøm = CountDownLatch(1)
    private val server =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/sse") { exchange ->
                exchange.use {
                    tilkoblinger.incrementAndGet()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(statuskode, 0)
                    exchange.responseBody.use { body ->
                        body.write(hendelser.joinToString("") { "data: $it\n\n" }.toByteArray())
                        body.flush()
                        if (holdStrømÅpen) lukkStrøm.await()
                    }
                }
            }
            start()
        }

    val url = "http://localhost:${server.address.port}/sse"

    override fun close() {
        lukkStrøm.countDown()
        server.stop(0)
    }
}

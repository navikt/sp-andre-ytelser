package no.nav.helse.sykepenger.andreytelser

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch

internal class TestElector(
    hostname: String,
) : AutoCloseable {
    private val lukkStrøm = CountDownLatch(1)
    private val server =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/sse") { exchange ->
                exchange.use {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { body ->
                        body.write("data: {\"name\":\"$hostname\"}\n\n".toByteArray())
                        body.flush()
                        lukkStrøm.await()
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

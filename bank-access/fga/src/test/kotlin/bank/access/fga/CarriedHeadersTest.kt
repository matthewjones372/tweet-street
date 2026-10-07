package bank.access.fga

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/** lark-bank spec 0024: a call to OpenFGA sends what its caller carries, so OpenFGA's spans join its trace. */
class CarriedHeadersTest {
    @Test
    fun `every call sends the headers the caller carries, the store's lookup included`() {
        val seen = ConcurrentLinkedQueue<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seen += exchange.requestHeaders.getFirst("traceparent")
                val body = if (exchange.requestURI.path == "/stores") """{"stores":[{"name":"bank","id":"s1"}]}""" else """{"allowed":true}"""
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        try {
            val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
            val fga = Fga("http://127.0.0.1:${server.address.port}", "key", "bank", carried = { mapOf("traceparent" to traceparent) })

            fga.check("person:ada", "owner", "account:a1") shouldBe true

            seen.toList() shouldContainOnly listOf(traceparent)
        } finally {
            server.stop(0)
        }
    }
}

package io.github.gihoon1123.quant

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class ExchangeRateTest {
    @Test
    fun `invalid source falls through to numeric KRW and all failures use default`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        server.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path == "/valid") """{"rates":{"KRW":1350.5}}""" else """{"rates":{"KRW":"1350"}}"""
            calls.incrementAndGet()
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val env = MockEnvironment().withProperty("EXCHANGE_RATE_URLS", "$base/invalid|$base/valid")
            assertEquals(1350.5, ExchangeRate(env).rate())
            assertEquals(2, calls.get())
            env.setProperty("EXCHANGE_RATE_URLS", "$base/invalid")
            assertEquals(1330.0, ExchangeRate(env).rate())
        } finally {
            server.stop(0)
        }
    }
}

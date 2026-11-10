package io.github.gihoon1123.quant

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

class BinanceHttpTest {
    private data class Request(
        val method: String,
        val path: String,
        val query: String?,
        val body: String,
        val key: String?,
    )

    @Test
    fun `spot query signing and futures form signing match original wire contracts`() {
        val requests = CopyOnWriteArrayList<Request>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests +=
                Request(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestURI.rawQuery,
                    exchange.requestBody.readAllBytes().decodeToString(),
                    exchange.requestHeaders.getFirst("X-MBX-APIKEY"),
                )
            val response =
                if (exchange.requestURI.path.endsWith(
                        "account",
                    )
                ) {
                    """{"balances":[{"asset":"USDT","free":"10.5","locked":"2"},""" +
                        """{"asset":"BTC","free":"0","locked":"0"}]}"""
                } else {
                    """{"orderId":42}"""
                }
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val env =
                MockEnvironment()
                    .withProperty(
                        "BINANCE_SPOT_BASE_URL",
                        base,
                    ).withProperty(
                        "BINANCE_FUTURES_BASE_URL",
                        base,
                    ).withProperty("BINANCE_API_KEY", "fixture-key")
                    .withProperty("BINANCE_API_SECRET", "fixture-secret")
            val http = BinanceHttp(env, Clock.fixed(Instant.ofEpochMilli(1234567890), ZoneOffset.UTC))
            val spot = BinanceSpot(http)
            spot.market("BTCUSDT", "BUY", 20.0)
            spot.market("BTCUSDT", "SELL", 0.0002)
            spot.limit("BTCUSDT", "BUY", 0.0002, 50000.0)
            spot.cancel("BTCUSDT", 42)
            assertEquals(listOf(Balance("USDT", 10.5, 2.0)), spot.balances())
            val futures = BinanceFutures(http)
            futures.open("BTCUSDT", "BUY", 0.002)
            futures.close("BTCUSDT", "LONG", null)
            futures.reduce("BTCUSDT", "SHORT", 0.001)
            futures.transfer("USDT", 20.0, "SPOT", "FUTURES")
            assertEquals("POST", requests[0].method)
            val query = "symbol=BTCUSDT&side=BUY&type=MARKET&timestamp=1234567890&quoteOrderQty=20"
            assertEquals("$query&signature=${BinanceHttp.signature(query, "fixture-secret")}", requests[0].query)
            assertTrue(requests[1].query!!.contains("quantity=0.0002"))
            assertEquals("DELETE", requests[3].method)
            assertEquals("fixture-key", requests[0].key)
            assertEquals("/fapi/v1/order", requests[5].path)
            assertEquals(null, requests[5].query)
            assertTrue(requests[5].body.startsWith("symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.002&timestamp=1234567890&signature="))
            assertTrue(requests[6].body.contains("timestamp=1234567890&reduceOnly=true"))
            assertTrue(requests[7].body.contains("side=BUY&type=MARKET&quantity=0.001&reduceOnly=true"))
            assertEquals("/sapi/v1/futures/transfer", requests[8].path)
            assertTrue(requests[8].query!!.contains("type=1"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `unconfigured production trading is blocked before network access`() {
        val http = BinanceHttp(MockEnvironment(), Clock.systemUTC())
        assertEquals(
            503,
            org.junit.jupiter.api
                .assertThrows<ApiException> { BinanceSpot(http).market("BTCUSDT", "BUY", 10.0) }
                .status,
        )
    }

    @Test
    fun `Binance quantity errors preserve step size detail`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = """{"code":-1013,"msg":"Filter failure: LOT_SIZE"}""".toByteArray()
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val env =
                MockEnvironment()
                    .withProperty(
                        "BINANCE_SPOT_BASE_URL",
                        "http://127.0.0.1:${server.address.port}",
                    ).withProperty("BINANCE_API_KEY", "fixture")
                    .withProperty("BINANCE_API_SECRET", "fixture")
            val error =
                org.junit.jupiter.api.assertThrows<ApiException> {
                    BinanceSpot(BinanceHttp(env, Clock.systemUTC())).market("BTCUSDT", "SELL", 1.0)
                }
            assertEquals(400, error.status)
            assertEquals("주문 수량이 거래 단위(stepSize)의 배수가 아닙니다. (0.00001 단위로만 주문 가능합니다) (Filter failure: LOT_SIZE)", error.message)
        } finally {
            server.stop(0)
        }
    }
}

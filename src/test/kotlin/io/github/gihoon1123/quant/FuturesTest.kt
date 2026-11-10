package io.github.gihoon1123.quant

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

class FuturesTest {
    private val client = mockk<BinanceFutures>()
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = Futures(client, events, Clock.systemUTC())
    private val mapper = JsonMapper.builder().build()
    private val position =
        mapper.readTree(
            """[{"symbol":"BTCUSDT","positionAmt":"-0.002","markPrice":"50000","entryPrice":"49000","unRealizedProfit":"-2",
        "liquidationPrice":"55000","leverage":"5","marginType":"isolated","notional":"-100","isolatedMargin":"20",
        "maintMarginRatio":"0.85","updateTime":1700000000000}]""",
        )
    private val order =
        mapper.readTree(
            """{"orderId":42,"symbol":"BTCUSDT","side":"BUY","origQty":"0.001","executedQty":"0.001",
        "avgPrice":"50000","cumQuote":"50","status":"FILLED","updateTime":1700000000000}""",
        )

    @Test
    fun `short position mapping keeps positive quantity and unrealized percentage`() {
        every { client.positions(null) } returns position
        val result = service.positions().single()
        assertEquals(0.002, result["quantity"])
        assertEquals("SHORT", result["side"])
        assertEquals("ISOLATED", result["marginType"])
        assertEquals(-2.0 / 98 * 100, result["unrealizedPnlPercent"])
        assertEquals(1, service.positions(risk = 0.8).size)
        assertEquals(0, service.positions(risk = 0.9).size)
    }

    @Test
    fun `close quantity cannot exceed held amount and partial close has opposite side`() {
        every { client.positions("BTCUSDT") } returns position
        assertEquals(
            400,
            org.junit.jupiter.api
                .assertThrows<ApiException> { service.close("BTCUSDT", 0.003) }
                .status,
        )
        every { client.close("BTCUSDT", "SHORT", 0.001) } returns order
        assertEquals(50.0, service.close("BTCUSDT", 0.001)["totalAmount"])
        verify(exactly = 1) { client.close("BTCUSDT", "SHORT", 0.001) }
    }

    @Test
    fun `position margin check rejects before any order submission`() {
        every { client.leverage("BTCUSDT", 5) } returns mapper.readTree("{}")
        every { client.positions("BTCUSDT") } returns position
        every { client.balances() } returns mapper.readTree("""[{"asset":"USDT","balance":"1","availableBalance":"1"}]""")
        assertEquals(
            400,
            org.junit.jupiter.api
                .assertThrows<ApiException> { service.open("BTCUSDT", "LONG", 0.002, 5) }
                .status,
        )
        verify(exactly = 0) { client.open(any(), any(), any()) }
    }

    @Test
    fun `position switch uses combined quantity and original generic error status`() {
        every { client.positions("BTCUSDT") } returns position
        every { client.switch("BTCUSDT", "SHORT", 0.002, 0.001) } returns order
        assertEquals(42L, service.switch("BTCUSDT", "LONG", 0.001)["orderId"])
        assertEquals(
            500,
            org.junit.jupiter.api
                .assertThrows<ApiException> { service.switch("BTCUSDT", "SHORT", 0.001) }
                .status,
        )
    }
}

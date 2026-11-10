package io.github.gihoon1123.quant

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

class OrdersTest {
    private val client = mockk<BinanceSpot>()
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = Orders(client, events, Clock.systemUTC())
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `weighted fills produce original response strings and executed event`() {
        every { client.balances() } returns listOf(Balance("USDT", 1000.0, 0.0))
        every { client.market("BTCUSDT", "BUY", 100.0) } returns
            mapper.readTree(
                """{"symbol":"BTCUSDT","orderId":42,"clientOrderId":"fixture","origQuoteOrderQty":"100","executedQty":"3","status":"FILLED",
            "fills":[{"price":"20","qty":"1","commission":"0.01","commissionAsset":"USDT"},
            {"price":"40","qty":"2","commission":"0.02","commissionAsset":"USDT"}],"unexpected":"excluded"}""",
            )
        val result = service.market("BTCUSDT", "BUY", 100.0, "account", "user")
        assertEquals("33.33333333", result["avgPrice"])
        assertFalse(result.containsKey("unexpected"))
        val captured = slot<MarketEvent>()
        verify(exactly = 1) { events.publishEvent(capture(captured)) }
        assertEquals("trade.executed", captured.captured.name)
        assertEquals(0.03, captured.captured.data["fee"])
        assertEquals("account", captured.captured.data["accountId"])
    }

    @Test
    fun `balance failures reject before submitting order`() {
        every { client.balances() } returns listOf(Balance("BTC", 0.001, 0.0))
        val error =
            org.junit.jupiter.api
                .assertThrows<ApiException> { service.market("BTCUSDT", "SELL", 0.002) }
        assertEquals(400, error.status)
        verify(exactly = 0) { client.market(any(), any(), any()) }
    }

    @Test
    fun `limit buy preserves original price as feeRate bug pending explicit change`() {
        every { client.balances() } returns listOf(Balance("USDT", 1000.0, 0.0))
        val error =
            org.junit.jupiter.api
                .assertThrows<ApiException> { service.limit("BTCUSDT", "BUY", 0.02, 50000.0) }
        assertEquals(400, error.status)
        org.junit.jupiter.api.Assertions
            .assertTrue(error.message.contains("0.01999000"))
        verify(exactly = 0) { client.limit(any(), any(), any(), any()) }
    }
}

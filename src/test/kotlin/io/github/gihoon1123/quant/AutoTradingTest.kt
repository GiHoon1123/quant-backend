package io.github.gihoon1123.quant

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.mock.env.MockEnvironment
import java.time.Clock

class AutoTradingTest {
    private val futures = mockk<Futures>()
    private val cache = Cache(Clock.systemUTC())
    private val service =
        AutoTrading(futures, mockk(), cache, MockEnvironment(), mockk<ApplicationEventPublisher>(relaxed = true), Clock.systemUTC())

    @Test
    fun `source asymmetry requires two long conditions but one short condition`() {
        val analysis =
            mapOf(
                "currentPrice" to 100.0,
                "indicators" to mapOf("support1" to 100.0, "resistance1" to 100.0, "rsi" to 50.0, "volumeRatio" to 1.0),
            )
        assertEquals(false, service.conditions(analysis, true))
        assertEquals(true, service.conditions(analysis, false))
        assertEquals(false, service.conditions(analysis, false, true))
    }

    @Test
    fun `ATR overrides percentage fallback and quantity reflects configured leverage`() {
        assertEquals(98.0, service.exit("BTCUSDT", 100.0, true, true))
        assertEquals(104.0, service.exit("BTCUSDT", 100.0, true, false))
        cache.set("atr:BTCUSDT", mapOf("atr" to 2.0))
        assertEquals(94.4, service.exit("BTCUSDT", 100.0, true, true))
        assertEquals(97.4, service.exit("BTCUSDT", 100.0, false, false))
        every { futures.balances() } returns listOf(mapOf("asset" to "USDT", "availableBalance" to 100.0))
        assertEquals(3.0, service.positionSize(100.0))
        every { futures.balances() } throws IllegalStateException("unavailable")
        assertEquals(0.001, service.positionSize(100.0))
    }
}

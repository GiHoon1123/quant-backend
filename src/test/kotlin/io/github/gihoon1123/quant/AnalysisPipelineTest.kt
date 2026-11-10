package io.github.gihoon1123.quant

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock

class AnalysisPipelineTest {
    @Test
    fun `saved candle publishes local technical event and does not merge with global auto trading bus`() {
        val repository = mockk<CandleRepository>()
        val strategies = mockk<Strategies>()
        val analysis = mockk<TechnicalAnalysis>()
        val rate = mockk<ExchangeRate>()
        val cache = Cache(Clock.systemUTC())
        val events = mutableListOf<MarketEvent>()
        every { repository.latest("BTCUSDT", limit = any()) } returns emptyList()
        every { strategies.basic(any(), any(), any()) } returns emptyList()
        every { strategies.advanced(any(), any(), any()) } returns emptyList()
        every { analysis.summary("BTCUSDT", "15m") } throws IllegalStateException("insufficient candles")
        every { rate.rate() } returns 1330.0
        val pipeline =
            AnalysisPipeline(
                repository,
                strategies,
                analysis,
                Indicators(),
                cache,
                rate,
                ApplicationEventPublisher {
                    events +=
                        it as MarketEvent
                },
                Clock.systemUTC(),
            )
        pipeline.saved(MarketEvent("candle.saved", mapOf("symbol" to "BTCUSDT", "timeframe" to "15m", "isNewCandle" to true)))
        val event = events.single()
        assertEquals("technical", event.channel)
        assertEquals("analysis.completed", event.name)
        @Suppress("UNCHECKED_CAST")
        val result = event.data["analysisResult"] as Map<String, Any>
        assertEquals("HOLD", result["overallSignal"])
        assertEquals(0, result["currentPrice"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("N/A", (result["indicators"] as Map<String, Any>)["SMA10"])
        val futures = mockk<Futures>()
        val client = mockk<BinanceFutures>()
        AutoTrading(
            futures,
            client,
            cache,
            org.springframework.mock.env
                .MockEnvironment(),
            mockk(relaxed = true),
            Clock.systemUTC(),
        ).event(event)
        verify(exactly = 0) { client.positions(any()) }
    }
}

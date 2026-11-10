package io.github.gihoon1123.quant

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock

class TradeLifecycleTest {
    @Test
    fun `persistence failure emits failure event without changing successful order result`() {
        val repository = mockk<TradeRepository>()
        val bitcoin = mockk<BitcoinFixtureRepository>()
        val events = mockk<ApplicationEventPublisher>(relaxed = true)
        every { repository.save(any(), false) } throws IllegalStateException("database unavailable")
        TradeLifecycle(repository, bitcoin, events, Clock.systemUTC()).executed(
            mapOf(
                "orderId" to "42",
                "symbol" to "ETHUSDT",
                "side" to "BUY",
            ),
        )
        val captured = slot<MarketEvent>()
        verify { events.publishEvent(capture(captured)) }
        assertEquals("transaction.trade.save.failed", captured.captured.name)
        assertEquals("database unavailable", captured.captured.data["error"])
        verify(exactly = 0) { repository.save(any(), true) }
    }
}

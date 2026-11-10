package io.github.gihoon1123.quant

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class NotificationsTest {
    @Test
    fun `local notifications skip HOLD and deduplicate same minute without muting completion`() {
        val telegram = mockk<Telegram>(relaxed = true)
        val report = mockk<TelegramReport>()
        every { report.objective(any(), any(), any(), any(), any()) } returns "report"
        val events = mutableListOf<MarketEvent>()
        val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
        val service = Notifications(telegram, report, ApplicationEventPublisher { events += it as MarketEvent }, clock)
        val data =
            mapOf(
                "symbol" to "BTCUSDT",
                "analyzedAt" to clock.instant(),
                "analysisResult" to mapOf("signal" to "BUY", "currentPrice" to 50000.0),
            )
        service.event(MarketEvent("analysis.completed", data))
        assertEquals(0, service.statistics()["totalSent"])
        service.event(MarketEvent("analysis.completed", data, channel = "technical"))
        service.event(MarketEvent("analysis.completed", data, channel = "technical"))
        assertEquals(1, service.statistics()["totalSent"])
        assertEquals(2, events.size)
        verify(exactly = 1) { telegram.text("report") }
        service.analysis(data + ("analysisResult" to mapOf("signal" to "HOLD")))
        assertEquals(2, events.size)
    }
}

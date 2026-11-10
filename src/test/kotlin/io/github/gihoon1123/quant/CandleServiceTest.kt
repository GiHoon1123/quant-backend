package io.github.gihoon1123.quant

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CandleServiceTest {
    private val repository = mockk<CandleRepository>(relaxed = true)
    private val emitted = mutableListOf<Any>()
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val service = CandleService(repository, ApplicationEventPublisher { emitted.add(it) }, clock)
    private val candle = CandleData(1000, 2000, 100.0, 103.0, 99.0, 102.0, 2.0)

    @Test
    fun `Binance numeric strings and timestamp fields parse and invalid fields fail`() {
        every { repository.validate(any(), any(), any()) } answers { callOriginal() }
        val mapper = JsonMapper.builder().build()
        val node =
            mapper.readTree(
                """
                {"s":"BTCUSDT","k":{"t":1000,"T":2000,"o":"100","h":"103","l":"99",
                "c":"102","v":"2","q":"200","V":"1","Q":"100","n":7,"x":true}}
                """.trimIndent(),
            )
        assertNotNull(service.parse(node))
        assertEquals(7, service.parse(node)?.trades)
        assertNull(service.parse(mapper.readTree("""{"k":{"t":1000}}""")))
    }

    @Test
    fun `ongoing updates replace same candle and completed event survives DB failure`() {
        every { repository.save(any(), any(), any()) } throws IllegalStateException("database down")
        service.accept("BTCUSDT", candle, false)
        service.accept("BTCUSDT", candle.copy(close = 101.0), true)
        assertEquals(1, service.memory("BTCUSDT").size)
        assertEquals(101.0, service.latest("BTCUSDT")?.close)
        val names = emitted.filterIsInstance<MarketEvent>().map { it.name }
        assertTrue("candle.save.failed" in names)
        assertTrue("candle.15m.completed" in names)
        verify(exactly = 1) { repository.save("BTCUSDT", "FUTURES", any()) }
    }

    @Test
    fun `subscription emits connection changes and preserves source empty Map JSON shape`() {
        service.subscribe("BTCUSDT")
        assertTrue("BTCUSDT" in service.subscribed())
        assertEquals(emptyMap<String, Any>(), service.status()["connectionStatus"])
        service.unsubscribe("BTCUSDT")
        assertTrue(service.subscribed().isEmpty())
        assertEquals(listOf(SubscriptionChanged("BTCUSDT", true), SubscriptionChanged("BTCUSDT", false)), emitted)
    }
}

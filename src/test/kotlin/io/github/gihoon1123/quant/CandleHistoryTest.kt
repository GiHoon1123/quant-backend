package io.github.gihoon1123.quant

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CandleHistoryTest {
    private val repository = mockk<CandleRepository>()
    private val http = mockk<BinanceHttp>()
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val service = CandleHistory(repository, http, clock)
    private val start = Instant.ofEpochMilli(900000)
    private val end = Instant.ofEpochMilli(2700000)
    private val response =
        JsonMapper.builder().build().readTree(
            """[[900000,"10","12","9","11","20",1799999,"210",3,"10","110"],
        [1800000,"11","13","10","12","25",2699999,"300",4,"12","144"]]""",
        )

    @Test
    fun `resumed history counts duplicates and writes only missing candles`() {
        every { repository.bounds("BTCUSDT") } returns Triple(0L, null, null)
        every { http.public(any(), any(), true) } returns response
        every { repository.contains("BTCUSDT", 900000) } returns true
        every { repository.contains("BTCUSDT", 1800000) } returns false
        every { repository.save("BTCUSDT", "FUTURES", any()) } returns 42
        val result = service.collect("BTCUSDT", start, end) {}
        assertEquals(true, result["success"])
        assertEquals(2, result["totalCandles"])
        assertEquals(1, result["newCandles"])
        assertEquals(1, result["duplicateCandles"])
        verify(exactly = 1) { repository.save("BTCUSDT", "FUTURES", match { it.openTime == 1800000L && it.close == 12.0 }) }
    }

    @Test
    fun `initial batch error remains visible after retry succeeds`() {
        every { repository.bounds("BTCUSDT") } returns Triple(0L, null, null)
        every { http.public(any(), any(), true) } throws ApiException(429, "rate") andThen response
        every { repository.contains(any(), any()) } returns false
        every { repository.save(any(), any(), any()) } returns 1
        val result = service.collect("BTCUSDT", start, end) {}
        assertEquals(true, result["success"])
        assertEquals(2, result["newCandles"])
        assertEquals(1, (result["errors"] as List<*>).size)
        verify(exactly = 2) { http.public(any(), any(), true) }
    }

    @Test
    fun `already recent history returns without network request`() {
        every { repository.bounds("BTCUSDT") } returns Triple(2L, 0, 1800000)
        assertEquals(0, service.collect("BTCUSDT", start, end) {}["totalCandles"])
        verify(exactly = 0) { http.public(any(), any(), any()) }
    }

    @Test
    fun `invalid date and symbol return original failure object rather than throwing`() {
        val controller = HistoryController(service, clock)
        assertEquals(false, controller.range("BTCUSDT", "2026-99-01", "2026-01-01")["success"])
        assertEquals(false, controller.all("BTC-USDT")["success"])
    }
}

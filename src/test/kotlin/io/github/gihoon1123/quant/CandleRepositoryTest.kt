package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@EnabledIfEnvironmentVariable(named = "QUANT_DATABASE_TESTS", matches = "true")
class CandleRepositoryTest {
    private val repository = CandleRepository()
    private val candle = CandleData(1700000000000, 1700000899999, 100.1, 103.0, 99.0, 101.5, 21.2, 2000.0, 15, 10.0, 1000.0)

    @Test
    fun `concurrent same candle writes retain one row and subsequent prices update`() {
        repository.connection().use { it.createStatement().execute("TRUNCATE candle_15m RESTART IDENTITY") }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val ids = executor.invokeAll((1..8).map { Callable { repository.save("BTCUSDT", "FUTURES", candle) } }).map { it.get() }
            assertEquals(1, ids.toSet().size)
            repository.save("BTCUSDT", "FUTURES", candle.copy(close = 102.0))
            val rows = repository.latest("BTCUSDT")
            assertEquals(1, rows.size)
            assertEquals(102.0, rows.single().close)
            assertEquals(1L, repository.statistics()["totalCount"])
            val record = repository.record("BTCUSDT", candle.openTime)!!
            assertEquals("102.00000000", record["close"])
            assertEquals(java.time.Instant.ofEpochMilli(candle.openTime), record["openTime"])
            org.junit.jupiter.api.Assertions
                .assertNotNull(record["createdAt"])
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `invalid OHLC cannot reach persistence`() {
        assertThrows(IllegalArgumentException::class.java) { repository.save("BTCUSDT", "FUTURES", candle.copy(high = 50.0)) }
    }
}

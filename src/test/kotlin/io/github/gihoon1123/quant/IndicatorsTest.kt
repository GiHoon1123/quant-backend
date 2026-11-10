package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IndicatorsTest {
    private val indicators = Indicators()

    private fun candles(size: Int) =
        (1..size).map { i ->
            CandleData(
                i.toLong(),
                i.toLong(),
                i.toDouble(),
                i + 2.0,
                i - 0.5,
                i.toDouble(),
                i * 10.0,
            )
        }

    @Test
    fun `SMA and EMA seed from same window and retain timestamps`() {
        val source = candles(8)
        assertEquals(listOf(2.0, 3.0, 4.0, 5.0, 6.0, 7.0), indicators.sma(source, 3).map { it.value })
        assertEquals(indicators.sma(source, 3).map { it.value }, indicators.ema(source, 3).map { it.value })
        assertEquals(3L, indicators.sma(source, 3).first().timestamp)
    }

    @Test
    fun `RSI preserves original zero loss behavior`() {
        assertEquals(100.0 - 100.0 / 101.0, indicators.rsi(candles(20)).last().value, 1e-12)
    }

    @Test
    fun `ATR averages only recent ranges rather than Wilder smoothing`() {
        assertEquals(3.0, indicators.atr(candles(20)), 1e-12)
    }

    @Test
    fun `MACD linear prices have constant line and near zero histogram`() {
        val result = indicators.macd(candles(60)).last()
        assertEquals(7.0, result.macdLine, 1e-10)
        assertEquals(0.0, result.histogram, 1e-10)
    }

    @Test
    fun `VWAP handles empty and zero volume input`() {
        assertTrue(indicators.vwap(emptyList()).isEmpty())
        assertEquals(0.0, indicators.vwap(listOf(candles(1).first().copy(volume = 0.0))).first().value)
    }

    @Test
    fun `indicators reject insufficient history`() {
        assertThrows(IllegalArgumentException::class.java) { indicators.rsi(candles(14)) }
        assertThrows(IllegalArgumentException::class.java) { indicators.sma(candles(2), 3) }
    }

    @Test
    fun `Bollinger zero width retains non finite intermediate value`() {
        val flat = candles(20).map { it.copy(close = 4.0) }
        assertTrue(
            indicators
                .bands(flat)
                .first()
                .percentB
                .isNaN(),
        )
    }

    @Test
    fun `volume OBV begins at first output window matching source`() {
        assertEquals(200.0, indicators.volume(candles(20)).first().obv)
    }
}

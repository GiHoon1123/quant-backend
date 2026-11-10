package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class IndicatorParityTest {
    @Test
    fun `all indicator outputs agree with original TypeScript on four market sequences`() {
        val cases = JsonMapper.builder().build().readTree(javaClass.getResourceAsStream("/indicator-reference.json"))
        val calculator = Indicators()
        for (case in cases) {
            val candles =
                case["candles"].toList().map {
                    CandleData(
                        it["openTime"].asLong(),
                        it["closeTime"].asLong(),
                        it["open"].asDouble(),
                        it["high"].asDouble(),
                        it["low"].asDouble(),
                        it["close"].asDouble(),
                        it["volume"].asDouble(),
                    )
                }
            val expected = case["expected"]
            compare(expected["sma"], calculator.sma(candles, 20).map { it.timestamp to listOf(it.value) })
            compare(expected["ema"], calculator.ema(candles, 20).map { it.timestamp to listOf(it.value) })
            compare(expected["rsi"], calculator.rsi(candles).map { it.timestamp to listOf(it.value) })
            compare(expected["vwap"], calculator.vwap(candles).map { it.timestamp to listOf(it.value) })
            compare(
                expected["macd"],
                calculator.macd(candles).map {
                    it.timestamp to listOf(it.value, it.signalLine, it.histogram)
                },
                listOf("value", "signalLine", "histogram"),
            )
            compare(
                expected["bands"],
                calculator.bands(candles).map {
                    it.timestamp to listOf(it.middle, it.upper, it.lower, it.bandwidth)
                },
                listOf("middle", "upper", "lower", "bandwidth"),
            )
            compare(
                expected["volume"],
                calculator.volume(candles).map {
                    it.timestamp to listOf(it.value, it.volumeMA, it.obv)
                },
                listOf("value", "volumeMA", "obv"),
            )
            assertEquals(expected["atr"].asDouble(), calculator.atr(candles), 1e-9)
        }
    }

    private fun compare(
        expected: JsonNode,
        actual: List<Pair<Long, List<Double>>>,
        fields: List<String> = listOf("value"),
    ) {
        assertEquals(expected.size(), actual.size)
        expected.zip(actual).forEach { (node, result) ->
            assertEquals(node["timestamp"].asLong(), result.first)
            fields.forEachIndexed { index, field -> assertEquals(node[field].asDouble(), result.second[index], 1e-9) }
        }
    }
}

package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StrategyParityTest {
    @Test
    fun `ten strategies match original signals conditions and numeric outputs`() {
        val mapper = JsonMapper.builder().build()
        val candles = mapper.readTree(javaClass.getResourceAsStream("/indicator-reference.json"))
        val expected = mapper.readTree(javaClass.getResourceAsStream("/strategy-reference.json"))
        val strategies = Strategies(Indicators(), Clock.fixed(Instant.ofEpochMilli(1794268800000), ZoneOffset.UTC))
        for (index in 0 until candles.size()) {
            val source =
                candles[index]["candles"].toList().map {
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
            val actual = strategies.basic("BTCUSDT", "15m", source) + strategies.advanced("BTCUSDT", "15m", source)
            compare(expected[index]["expected"], mapper.readTree(mapper.writeValueAsString(actual)), candles[index]["scenario"].asString())
        }
    }

    private fun compare(
        expected: JsonNode,
        actual: JsonNode,
        path: String,
    ) {
        when {
            expected.isNumber -> assertEquals(expected.asDouble(), actual.asDouble(), 1e-9, path)
            expected.isArray -> {
                assertEquals(expected.size(), actual.size(), path)
                for (i in 0 until expected.size()) compare(expected[i], actual[i], "$path[$i]")
            }
            expected.isObject -> {
                assertEquals(expected.propertyNames().toSet(), actual.propertyNames().toSet(), path)
                for (key in expected.propertyNames()) compare(expected[key], actual[key], "$path.$key")
            }
            expected.isNull && actual.isString && actual.asString() in listOf("NaN", "Infinity", "-Infinity") -> Unit
            else -> assertEquals(expected, actual, path)
        }
    }
}

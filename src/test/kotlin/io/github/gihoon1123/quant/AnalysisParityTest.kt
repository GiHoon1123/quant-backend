package io.github.gihoon1123.quant

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AnalysisParityTest {
    private val mapper = JsonMapper.builder().addModule(JsonConfiguration().javascriptNumbers()).build()

    @Test
    fun `analysis aggregation and indicator summary retain original output`() {
        val inputs = mapper.readTree(javaClass.getResourceAsStream("/indicator-reference.json"))
        val expected = mapper.readTree(javaClass.getResourceAsStream("/analysis-reference.json"))
        val clock = Clock.fixed(Instant.ofEpochMilli(1794268800000), ZoneOffset.UTC)
        for (i in 0 until inputs.size()) {
            val candles =
                inputs[i]["candles"].toList().map {
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
            val repository = mockk<CandleRepository>()
            every { repository.latest("BTCUSDT", any(), any()) } returns candles
            val indicators = Indicators()
            val service = TechnicalAnalysis(repository, indicators, Strategies(indicators, clock), ApplicationEventPublisher { }, clock)
            compare(expected[i]["analysis"], mapper.readTree(mapper.writeValueAsString(service.analyze("BTCUSDT"))), "analysis[$i]")
            compare(expected[i]["summary"], mapper.readTree(mapper.writeValueAsString(service.summary("BTCUSDT", "1h"))), "summary[$i]")
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
            else -> assertEquals(expected, actual, path)
        }
    }
}

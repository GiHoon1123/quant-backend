package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

class ComprehensiveReportTest {
    @Test
    fun `comprehensive reports match original indicator service for four sequences`() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
            val sequences = mapper.readTree(javaClass.getResourceAsStream("/indicator-reference.json"))
            val expected = mapper.readTree(javaClass.getResourceAsStream("/comprehensive-reference.json"))
            val report = ComprehensiveReport(Indicators(), Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC))
            for (index in 0 until expected.size()) {
                val rows = mapper.treeToValue(sequences[index]["candles"], Array<CandleData>::class.java).toList()
                assertEquals(expected[index]["expected"].asString(), report.generate(rows), expected[index]["scenario"].asString())
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}

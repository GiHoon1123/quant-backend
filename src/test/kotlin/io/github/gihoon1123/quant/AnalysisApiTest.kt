package io.github.gihoon1123.quant

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AnalysisApiTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val controller = AnalysisController(mockk(), clock)

    @Test
    fun `zero stop distance serializes as null like JavaScript JSON`() {
        val mapper =
            JsonMapper
                .builder()
                .addModule(kotlinModule())
                .addModule(JsonConfiguration().javascriptNumbers())
                .build()
        val output = mapper.readTree(mapper.writeValueAsString(controller.positionSize(PositionSizeRequest(1000.0, 1.0, 10.0, 10.0))))
        assertEquals(true, output["data"]["recommendedPositionSize"].isNull)
        assertFalse(output.toString().contains("Infinity"))
    }

    @Test
    fun `position size limits and original USDT case validation are preserved`() {
        for (request in listOf(PositionSizeRequest(0.0, 1.0, 10.0, 9.0), PositionSizeRequest(1000.0, 11.0, 10.0, 9.0))) {
            val error =
                org.junit.jupiter.api
                    .assertThrows<ApiException> { controller.positionSize(request) }
            assertEquals(400, error.status)
        }
        assertEquals(
            400,
            org.junit.jupiter.api
                .assertThrows<ApiException> { controller.analyze("btcusdt", null, null) }
                .status,
        )
    }
}

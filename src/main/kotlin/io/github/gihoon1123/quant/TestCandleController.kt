package io.github.gihoon1123.quant

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class TestCandleRequest(
    val symbol: String,
    val candleData: CandleData,
)

@RestController
class TestCandleController(
    private val candles: CandleService,
) {
    @PostMapping("/api/v1/test/candles/15m")
    @ResponseStatus(HttpStatus.CREATED)
    fun candle(
        @RequestBody request: TestCandleRequest,
    ) = mapOf(
        "success" to true,
        "result" to candles.testCandle(request.symbol, request.candleData),
    )
}

package io.github.gihoon1123.quant

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
@RequestMapping("/auto-trading")
class AutoTradingController(
    private val clock: Clock,
) {
    // These values and the no-op manual endpoint are explicitly TODO fixtures in the source.
    @GetMapping("/status")
    fun status() =
        CommonResponse(
            200,
            "자동 매매 시스템 상태 조회 완료",
            mapOf(
                "isActive" to true,
                "lastAnalysisTime" to clock.instant(),
                "totalSignals" to 150,
                "successfulTrades" to 120,
                "failedTrades" to 30,
                "winRate" to 80.0,
                "settings" to mapOf("minVolumeRatio" to 1.2, "positionSizePercent" to 2, "stopLossPercent" to -2, "takeProfitPercent" to 4),
            ),
        )

    @PostMapping("/manual-signal")
    @ResponseStatus(HttpStatus.CREATED)
    fun manual(
        @RequestBody request: Map<String, Any>,
    ) = CommonResponse(
        200,
        "수동 매매 신호가 성공적으로 발생했습니다.",
        mapOf("signalId" to "manual_${clock.millis()}", "timestamp" to clock.instant()) +
            request.filterKeys { it in setOf("symbol", "signal") },
    )

    @GetMapping("/backtest/{symbol}")
    fun backtest(
        @PathVariable symbol: String,
    ) = CommonResponse(
        200,
        "백테스팅 결과 조회 완료",
        mapOf(
            "symbol" to symbol,
            "period" to "30일",
            "totalTrades" to 45,
            "winningTrades" to 32,
            "losingTrades" to 13,
            "winRate" to 71.1,
            "totalPnl" to 1250.5,
            "maxDrawdown" to -350.25,
            "sharpeRatio" to 1.85,
            "averageReturn" to 2.78,
        ),
    )
}

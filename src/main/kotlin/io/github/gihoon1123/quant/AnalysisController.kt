package io.github.gihoon1123.quant

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import kotlin.math.abs

data class ScreeningRequest(
    val symbols: List<String>? = null,
    val strategies: List<String>? = null,
    val timeframes: List<String>? = null,
)

data class StrategyScreeningRequest(
    val strategy: String,
    val timeframe: String,
    val symbols: List<String>? = null,
)

data class PositionSizeRequest(
    val accountBalance: Double,
    val riskPercentage: Double,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val symbol: String? = null,
)

@RestController
@RequestMapping("/api/v1/analysis/technical")
class AnalysisController(
    private val analysis: TechnicalAnalysis,
    private val clock: Clock,
) {
    private val timeframes = setOf("1m", "15m", "1h", "1d")
    private val strategyNames =
        setOf(
            "MA_20_BREAKOUT",
            "MA_50_BREAKOUT",
            "MA_100_BREAKOUT",
            "MA_150_BREAKOUT",
            "MA_200_BREAKOUT",
            "GOLDEN_CROSS_5_20",
            "GOLDEN_CROSS_20_60",
            "GOLDEN_CROSS_50_200",
            "MA_CROSSOVER",
            "RSI_OVERSOLD_BOUNCE",
            "RSI_MOMENTUM_70",
            "RSI_DIVERGENCE",
            "MACD_GOLDEN_CROSS",
            "MACD_ZERO_CROSS",
            "MACD_HISTOGRAM_TURN",
            "MACD_CROSSOVER",
            "BOLLINGER_UPPER_BREAK",
            "BOLLINGER_LOWER_BOUNCE",
            "BOLLINGER_SQUEEZE",
            "BOLLINGER_REVERSAL",
            "PIVOT_REVERSAL",
            "VOLUME_SURGE_UP",
            "VOLUME_MA_BREAK",
            "OBV_TREND",
            "TRIPLE_CONFIRMATION",
            "MOMENTUM_BREAKOUT",
            "TREND_FOLLOWING",
            "SMART_MONEY_FLOW",
            "MULTI_TIMEFRAME_TREND",
            "PATTERN_RECOGNITION",
            "ELLIOTT_WAVE",
            "DAY_TRADING_STRATEGY",
            "SWING_TRADING",
            "POSITION_TRADING",
            "MEAN_REVERSION",
        )

    private fun symbols(value: String?): List<String> = value?.split(',')?.map { it.trim().uppercase() } ?: analysis.defaultSymbols

    private fun checkSymbol(
        symbol: String,
        message: String = "유효한 USDT 페어 심볼을 입력해주세요",
    ) {
        if (!symbol.endsWith("USDT")) throw ApiException(400, message)
    }

    private fun success(
        message: String,
        data: Any,
    ) = mapOf("success" to true, "message" to message, "data" to data)

    private fun guarded(
        prefix: String,
        action: () -> Map<String, Any>,
    ): Map<String, Any> =
        try {
            action()
        } catch (error: Exception) {
            throw ApiException(400, "$prefix: ${error.message}")
        }

    @GetMapping("/analyze/{symbol}")
    fun analyze(
        @PathVariable symbol: String,
        @RequestParam(required = false) strategies: String?,
        @RequestParam(required = false) timeframes: String?,
    ): Map<String, Any> {
        checkSymbol(symbol, "유효한 USDT 페어 심볼을 입력해주세요 (예: BTCUSDT)")
        if (strategies != null &&
            strategies.split(',').none { it.trim() in strategyNames }
        ) {
            throw ApiException(400, "전략 파라미터가 잘못되었습니다: 유효한 전략이 없습니다")
        }
        val frames = timeframes?.split(',')?.map { it.trim() }?.filter { it in this.timeframes }
        if (timeframes != null && frames.isNullOrEmpty()) throw ApiException(400, "시간봉 파라미터가 잘못되었습니다: 유효한 시간봉이 없습니다")
        return guarded("분석에 실패했습니다") {
            success(
                "분석이 성공적으로 완료되었습니다",
                mapOf(
                    "symbol" to symbol.uppercase(),
                    "timestamp" to clock.millis(),
                    "analysis" to analysis.analyze(symbol.uppercase(), frames?.firstOrNull() ?: "15m"),
                ),
            )
        }
    }

    @PostMapping("/screen")
    fun screen(
        @RequestBody dto: ScreeningRequest,
    ): Map<String, Any> =
        guarded("스크리닝에 실패했습니다") {
            val results = analysis.screen(dto.symbols ?: analysis.defaultSymbols, dto.timeframes?.firstOrNull() ?: "15m")
            success(
                "${results.size}개 심볼 스크리닝이 완료되었습니다",
                mapOf(
                    "totalSymbols" to (dto.symbols?.size?.takeIf { it > 0 } ?: 10),
                    "validResults" to results.size,
                    "timestamp" to clock.millis(),
                    "results" to results.map { mapOf("symbol" to it.key, "analysis" to it.value) },
                ),
            )
        }

    @GetMapping("/buy-signals")
    fun buys(
        @RequestParam(required = false) symbols: String?,
    ): Map<String, Any> =
        guarded("검색에 실패했습니다") {
            val results = analysis.screen(symbols(symbols)).filterValues { it["overallSignal"] in setOf("BUY", "STRONG_BUY") }
            val signals =
                results.map { (symbol, result) ->
                    @Suppress("UNCHECKED_CAST")
                    val strategies = result["strategies"] as List<StrategyResult>
                    mapOf(
                        "symbol" to symbol,
                        "signal" to result["overallSignal"],
                        "consensus" to result["consensus"],
                        "topStrategies" to
                            strategies.filter { it.signal != "NEUTRAL" }.take(3).map {
                                mapOf(
                                    "strategy" to it.strategy,
                                    "signal" to it.signal,
                                    "timeframe" to it.timeframe,
                                )
                            },
                    )
                }
            success(
                "${signals.size}개의 강한 매수 신호를 발견했습니다",
                mapOf("count" to signals.size, "timestamp" to clock.millis(), "signals" to signals),
            )
        }

    @GetMapping("/monitor")
    fun monitor(
        @RequestParam(required = false) symbols: String?,
        @RequestParam(defaultValue = "80") alertThreshold: Int,
    ): Map<String, Any> =
        guarded("모니터링에 실패했습니다") {
            val alerts =
                analysis.screen(symbols(symbols), "1m").mapNotNull { (symbol, result) ->
                    val message =
                        when (result["overallSignal"]) {
                            "STRONG_BUY" -> "🚀 강한 매수 신호"
                            "BUY" -> "📈 매수 신호"
                            "STRONG_SELL" -> "🔴 강한 매도 신호"
                            else -> return@mapNotNull null
                        }
                    mapOf(
                        "symbol" to symbol,
                        "alertMessage" to message,
                        "signal" to result["overallSignal"],
                        "consensus" to result["consensus"],
                    )
                }
            success(
                "시장 모니터링 완료: ${alerts.size}개 알림",
                mapOf(
                    "alertCount" to alerts.size,
                    "alertThreshold" to alertThreshold,
                    "timestamp" to clock.millis(),
                    "alerts" to alerts,
                ),
            )
        }

    @GetMapping("/indicators/{symbol}")
    fun indicators(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "1h") timeframe: String,
    ): Map<String, Any> {
        checkSymbol(symbol)
        if (timeframe !in timeframes) throw ApiException(400, "유효한 시간봉을 입력해주세요")
        return guarded("조회에 실패했습니다") { success("지표 요약 조회가 완료되었습니다", analysis.summary(symbol.uppercase(), timeframe)) }
    }

    @PostMapping("/strategy-scan")
    fun scan(
        @RequestBody dto: StrategyScreeningRequest,
    ): Map<String, Any> =
        guarded("스캔에 실패했습니다") {
            val results = analysis.scan(dto.strategy, dto.timeframe, dto.symbols ?: analysis.defaultSymbols)
            success(
                "전략 스캔 완료: ${results.size}개 결과",
                mapOf(
                    "strategy" to dto.strategy,
                    "timeframe" to dto.timeframe,
                    "totalSymbols" to (dto.symbols?.size?.takeIf { it > 0 } ?: 10),
                    "resultCount" to results.size,
                    "timestamp" to clock.millis(),
                    "results" to
                        results.map {
                            mapOf(
                                "symbol" to it.symbol,
                                "signal" to it.signal,
                                "reasoning" to "분석 완료",
                                "indicators" to emptyMap<String, Any>(),
                            )
                        },
                ),
            )
        }

    @GetMapping("/advanced/{symbol}")
    fun advanced(
        @PathVariable symbol: String,
        @RequestParam(required = false) strategy: String?,
        @RequestParam(defaultValue = "1h") timeframe: String,
    ): Map<String, Any> {
        checkSymbol(symbol)
        return guarded("실행에 실패했습니다") {
            success(
                "고급 전략 실행이 완료되었습니다",
                mapOf(
                    "symbol" to symbol.uppercase(),
                    "strategy" to (strategy ?: "ALL_ADVANCED"),
                    "timeframe" to timeframe,
                    "timestamp" to clock.millis(),
                    "result" to analysis.advanced(symbol.uppercase(), timeframe),
                ),
            )
        }
    }

    @PostMapping("/risk/position-size")
    fun positionSize(
        @RequestBody dto: PositionSizeRequest,
    ): Map<String, Any> {
        if (dto.accountBalance <= 0) throw ApiException(400, "계좌 잔고는 0보다 커야 합니다")
        if (dto.riskPercentage <= 0 || dto.riskPercentage > 10) throw ApiException(400, "리스크 비율은 0-10% 사이여야 합니다")
        if (dto.entryPrice <= 0 || dto.stopLossPrice <= 0) throw ApiException(400, "진입가와 손절가는 0보다 커야 합니다")
        val risk = dto.accountBalance * dto.riskPercentage / 100
        val distance = abs(dto.entryPrice - dto.stopLossPrice)
        return success(
            "포지션 사이즈 계산이 완료되었습니다",
            mapOf(
                "timestamp" to clock.millis(),
                "input" to dto,
                "recommendedPositionSize" to risk / distance,
                "riskAmount" to risk,
                "stopLossDistance" to distance,
                "stopLossPercentage" to distance / dto.entryPrice * 100,
            ),
        )
    }

    @GetMapping("/health")
    fun health(): Map<String, Any> =
        success(
            "기술적 분석 서비스가 정상적으로 작동 중입니다",
            mapOf(
                "timestamp" to clock.millis(),
                "status" to "healthy",
                "version" to "2.0.0",
                "features" to
                    listOf(
                        "단일 심볼 분석",
                        "다중 심볼 스크리닝",
                        "강한 매수 신호 검색",
                        "실시간 시장 모니터링",
                        "기술적 지표 요약",
                        "전략별 심볼 스캔",
                        "🆕 완전한 전략 구현 (20+ 전략)",
                        "🆕 RSI 다이버전스 분석",
                        "🆕 MACD 히스토그램 전환",
                        "🆕 볼린저밴드 하단 반등",
                    ),
                "strategies" to
                    listOf(
                        "MA_20_BREAKOUT",
                        "MA_50_BREAKOUT",
                        "MA_200_BREAKOUT",
                        "GOLDEN_CROSS_50_200",
                        "RSI_OVERSOLD_BOUNCE",
                        "RSI_MOMENTUM_70",
                        "RSI_DIVERGENCE",
                        "MACD_GOLDEN_CROSS",
                        "MACD_ZERO_CROSS",
                        "MACD_HISTOGRAM_TURN",
                        "BOLLINGER_UPPER_BREAK",
                        "BOLLINGER_LOWER_BOUNCE",
                        "VOLUME_SURGE_UP",
                        "TRIPLE_CONFIRMATION",
                    ),
            ),
        )
}

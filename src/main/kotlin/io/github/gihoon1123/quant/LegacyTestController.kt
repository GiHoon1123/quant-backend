package io.github.gihoon1123.quant

import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import kotlin.random.Random

@RestController
@RequestMapping("/api/v1/test/legacy")
class LegacyTestController(
    private val candles: CandleService,
    private val repository: CandleRepository,
    private val analysis: TechnicalAnalysis,
    private val notifications: Notifications,
    private val report: ComprehensiveReport,
    private val indicators: Indicators,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private fun id(prefix: String) = "$prefix-${clock.millis()}"

    private fun failed(
        id: String,
        error: Exception,
    ) = mapOf("success" to false, "testId" to id, "error" to error.message)

    private fun random(): CandleData {
        val open = 50000 + (Random.nextDouble() - 0.5) * 1000
        val variation = Random.nextDouble() * 500
        return CandleData(
            clock.millis() - 900000,
            clock.millis() - 1,
            open,
            open + variation,
            open - variation,
            open - variation + Random.nextDouble() * variation * 2,
            Random.nextDouble() * 1000 + 100,
            Random.nextDouble() * 50000000 + 1000000,
            Random.nextInt(1000) + 100,
            Random.nextDouble() * 500 + 50,
            Random.nextDouble() * 25000000 + 500000,
        )
    }

    @PostMapping("/candle15m")
    @ResponseStatus(HttpStatus.CREATED)
    fun candle(
        @RequestBody request: TestCandleRequest,
    ) = mapOf(
        "success" to true,
        "result" to candles.testCandle(request.symbol, request.candleData),
    )

    @PostMapping("/event-chain/{symbol}")
    @ResponseStatus(HttpStatus.CREATED)
    fun chain(
        @PathVariable symbol: String,
    ): Map<String, Any?> {
        val id = id("test")
        val started = clock.millis()
        return try {
            val candle = random()
            val saved = candles.testCandle(symbol, candle)
            Thread.sleep(2000)
            val result = analysis.summary(symbol, "15m")
            val stats = notifications.statistics()
            val duration = clock.millis() - started
            mapOf(
                "success" to true,
                "testId" to id,
                "symbol" to symbol,
                "duration" to duration,
                "steps" to
                    mapOf(
                        "candleProcessed" to "✅ 완료",
                        "eventChainTriggered" to "✅ 완료",
                        "analysisExecuted" to "✅ 완료",
                        "notificationProcessed" to
                            if ((stats["totalSent"] as Int) >
                                0
                            ) {
                                "✅ 완료"
                            } else {
                                "⚠️ 미확인"
                            },
                    ),
                "data" to
                    mapOf(
                        "testCandleData" to candle,
                        "analysisResult" to result,
                        "notificationStats" to stats,
                        "marketDataResult" to saved,
                    ),
                "performance" to
                    mapOf("totalDuration" to "${duration}ms", "avgStepDuration" to "${duration / 4.0}ms"),
                "timestamp" to clock.instant(),
            )
        } catch (error: Exception) {
            failed(id, error) + mapOf("symbol" to symbol, "timestamp" to clock.instant())
        }
    }

    @PostMapping("/candle/generate/{symbol}")
    @ResponseStatus(HttpStatus.CREATED)
    fun generate(
        @PathVariable symbol: String,
        @RequestBody(required = false) custom: Map<String, Any>?,
    ): Map<String, Any?> {
        val id = id("candle-test")
        return try {
            val base = random()

            fun value(
                key: String,
                fallback: Double,
            ) = (custom?.get(key) as? Number)?.toDouble() ?: fallback
            val candle =
                base.copy(
                    openTime = (custom?.get("openTime") as? Number)?.toLong() ?: base.openTime,
                    closeTime =
                        (custom?.get("closeTime") as? Number)?.toLong() ?: base.closeTime,
                    open = value("open", base.open),
                    high = value("high", base.high),
                    low = value("low", base.low),
                    close = value("close", base.close),
                    volume = value("volume", base.volume),
                    quoteVolume = value("quoteVolume", base.quoteVolume),
                    trades =
                        (custom?.get("trades") as? Number)?.toInt() ?: base.trades,
                    takerBuyBaseVolume = value("takerBuyBaseVolume", base.takerBuyBaseVolume),
                    takerBuyQuoteVolume = value("takerBuyQuoteVolume", base.takerBuyQuoteVolume),
                )
            repository.save(symbol, "FUTURES", candle)
            val event =
                mapOf(
                    "type" to "candle.saved",
                    "symbol" to symbol,
                    "market" to "FUTURES",
                    "timeframe" to "15m",
                    "candleData" to candle,
                    "timestamp" to clock.millis(),
                )
            // The original TestService emits this on its own unconnected EventEmitter.
            mapOf("success" to true, "testId" to id, "message" to "테스트 캔들 생성 및 이벤트 발송 완료", "data" to candle, "event" to event)
        } catch (error: Exception) {
            failed(id, error)
        }
    }

    @PostMapping("/analysis/{symbol}")
    @ResponseStatus(HttpStatus.CREATED)
    fun analysis(
        @PathVariable symbol: String,
    ): Map<String, Any?> {
        val id = id("analysis-test")
        val started = clock.millis()
        return try {
            mapOf(
                "success" to true,
                "testId" to id,
                "symbol" to symbol,
                "duration" to "${clock.millis() - started}ms",
                "result" to analysis.summary(symbol, "15m"),
                "message" to "기술적 분석 테스트 완료",
            )
        } catch (error: Exception) {
            failed(id, error)
        }
    }

    @PostMapping("/notification")
    @ResponseStatus(HttpStatus.CREATED)
    fun notification(
        @RequestBody(required = false) body: Map<String, Any>?,
    ): Map<String, Any> =
        mapOf(
            "success" to true,
            "testId" to id("notification-test"),
            "message" to "알림 테스트 이벤트 발송 완료",
            "event" to
                mapOf(
                    "type" to "analysis.completed",
                    "symbol" to (body?.get("symbol") ?: "BTCUSDT"),
                    "timeframe" to "15m",
                    "signal" to (body?.get("signal") ?: "BUY"),
                    "analysis" to mapOf("rsi" to 65, "macd" to 0.02, "signal" to "매수 신호 감지"),
                    "timestamp" to clock.millis(),
                ),
        )

    @PostMapping("/telegram/{symbol}")
    @ResponseStatus(HttpStatus.CREATED)
    fun telegram(
        @PathVariable symbol: String,
        @RequestBody(required = false) body: Map<String, Any>?,
    ): Map<String, Any?> {
        val id = id("telegram-test")
        return try {
            val signal = body?.get("signal")?.toString() ?: "BUY"
            val indicators =
                mapOf(
                    "SMA5" to 43250.5,
                    "SMA10" to 43100.25,
                    "SMA20" to 42950.75,
                    "RSI" to 72.5,
                    "MACD" to if (signal == "BUY") "BULLISH" else "BEARISH",
                    "Volume" to 1250.45,
                    "AvgVolume" to 850.3,
                    "VolumeRatio" to 1.47,
                )
            val result = mapOf("signal" to signal, "indicators" to indicators, "price" to 43000.8, "timestamp" to clock.instant())
            val event =
                mapOf(
                    "type" to "analysis.completed",
                    "symbol" to symbol,
                    "analysisResult" to result.filterKeys { it != "timestamp" },
                    "analyzedAt" to clock.instant(),
                )
            events.publishEvent(MarketEvent("analysis.completed", event, channel = "technical"))
            mapOf(
                "success" to true,
                "testId" to id,
                "message" to "이벤트 기반 알림이 성공적으로 발송되었습니다",
                "data" to mapOf("symbol" to symbol, "signal" to signal, "analysisResult" to result, "event" to event),
            )
        } catch (error: Exception) {
            failed(id, error) + ("message" to "텔레그램 알림 발송에 실패했습니다")
        }
    }

    @PostMapping("/multi-symbol")
    @ResponseStatus(HttpStatus.CREATED)
    fun multiple(
        @RequestBody symbols: List<String>,
    ): Map<String, Any> {
        val id = id("multi-symbol")
        val results =
            symbols.map { symbol ->
                val result = telegram(symbol, null)
                Thread.sleep(2000)
                mapOf("symbol" to symbol) + result
            }
        return mapOf("success" to true, "testId" to id, "message" to "${symbols.size}개 심볼 테스트 완료", "results" to results)
    }

    @GetMapping("/performance/{symbol}")
    fun performance(
        @PathVariable symbol: String,
    ): Map<String, Any> {
        val id = id("performance")
        val started = clock.millis()
        chain(symbol)
        val duration = clock.millis() - started
        return mapOf("success" to true, "testId" to id, "symbol" to symbol, "duration" to duration, "message" to "성능 측정 완료: ${duration}ms")
    }

    @GetMapping("/status")
    fun status() =
        mapOf(
            "timestamp" to clock.instant(),
            "domains" to
                mapOf(
                    "marketData" to
                        mapOf(
                            "status" to "✅ Active",
                            "description" to "Market-data 도메인 - 캔들 데이터 수집/저장",
                            "events" to listOf("candle.saved"),
                        ),
                    "technicalAnalysis" to
                        mapOf(
                            "status" to "✅ Active",
                            "description" to "Technical-analysis 도메인 - 기술적 분석",
                            "events" to listOf("candle.saved (수신)", "analysis.completed (발송)"),
                        ),
                    "notification" to
                        mapOf(
                            "status" to "✅ Active",
                            "description" to "Notification 도메인 - 알림 발송",
                            "events" to listOf("analysis.completed (수신)"),
                        ),
                ),
            "eventEmitter" to mapOf("listenerCount" to 0, "maxListeners" to 10),
            "database" to mapOf("status" to "✅ Connected", "description" to "15분봉 데이터 저장소"),
            "integrations" to mapOf("binance" to "✅ Available", "telegram" to "✅ Available"),
        )

    @PostMapping("/cleanup")
    @ResponseStatus(HttpStatus.CREATED)
    fun cleanup() =
        mapOf(
            "success" to true,
            "message" to "테스트 데이터 정리 완료",
            "cleaned" to mapOf("testCandles" to "테스트 캔들 데이터", "performanceData" to "성능 측정 데이터", "temporaryEvents" to "임시 이벤트 데이터"),
            "timestamp" to clock.instant(),
        )

    @PostMapping("/real-event-chain/{symbol}")
    @ResponseStatus(HttpStatus.CREATED)
    fun real(
        @PathVariable symbol: String,
    ): Map<String, Any?> {
        val id = id("real-chain")
        return try {
            repository.save(symbol, "FUTURES", random())
            mapOf(
                "success" to true,
                "testId" to id,
                "message" to "실제 이벤트 체인 테스트 시작",
                "note" to "아직 구현 중입니다. testTelegramNotification을 사용해주세요.",
            )
        } catch (error: Exception) {
            failed(id, error)
        }
    }

    @PostMapping("/comprehensive-report/{symbol}")
    @ResponseStatus(HttpStatus.CREATED)
    fun comprehensive(
        @PathVariable symbol: String,
    ): Map<String, Any?> {
        val id = id("comprehensive-report")
        return try {
            val rows = repository.latest(symbol, limit = 20000)
            check(rows.isNotEmpty()) { "캔들 데이터가 없습니다." }
            val current = rows.last().close
            val values = listOf(5, 20, 50, 200).associate { period -> "sma$period" to indicators.sma(rows, period).lastOrNull()?.value }
            mapOf(
                "success" to true,
                "testId" to id,
                "message" to "종합 리포트가 성공적으로 생성되었습니다",
                "data" to
                    mapOf(
                        "symbol" to symbol,
                        "report" to report.generate(rows),
                        "currentPrice" to current,
                        "smaValues" to (mapOf("currentPrice" to current) + values),
                    ),
            )
        } catch (error: Exception) {
            failed(id, error) + ("message" to "종합 리포트 생성 실패: ${error.message}")
        }
    }
}

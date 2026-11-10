package io.github.gihoon1123.quant

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@Service
class CandleHistory(
    private val repository: CandleRepository,
    private val http: BinanceHttp,
    private val clock: Clock,
) {
    private val interval = 900000L

    fun collect(
        symbol: String,
        start: Instant,
        end: Instant,
        pause: (Long) -> Unit = Thread::sleep,
    ): Map<String, Any> {
        val started = clock.millis()
        var total = 0
        var new = 0
        var duplicates = 0
        val errors = mutableListOf<Map<String, Any>>()
        var effectiveStart = start
        val pending = mutableListOf<CandleData>()

        fun result(success: Boolean) =
            mapOf(
                "success" to success,
                "totalCandles" to total,
                "newCandles" to new,
                "duplicateCandles" to duplicates,
                "startTime" to effectiveStart,
                "endTime" to end,
                "duration" to clock.millis() - started,
                "errors" to errors,
            )

        fun save(candles: List<CandleData>) {
            candles.forEach { candle ->
                runCatching {
                    if (repository.contains(symbol, candle.openTime)) {
                        duplicates++
                    } else {
                        repository.save(symbol, "FUTURES", candle)
                        new++
                    }
                }
            }
        }
        try {
            val bounds = runCatching { repository.bounds(symbol) }.getOrNull()
            val first = bounds?.second
            val last = bounds?.third
            if (first != null && last != null && first <= start.toEpochMilli() && last < end.toEpochMilli()) {
                if (end.toEpochMilli() - last <= interval) return result(true)
                effectiveStart = Instant.ofEpochMilli(last + interval)
            }
            var current = effectiveStart.toEpochMilli()
            val until = end.toEpochMilli()
            var batch = 0

            fun fetch(): List<CandleData> {
                val raw =
                    http.public(
                        "/fapi/v1/klines",
                        listOf(
                            "symbol" to symbol,
                            "interval" to "15m",
                            "startTime" to current.toString(),
                            "endTime" to minOf(current + 1500 * interval, until).toString(),
                            "limit" to "1500",
                        ),
                        futures = true,
                    )
                require(raw.isArray) { "Invalid response format from Binance API" }
                return (0 until raw.size()).map { index ->
                    val row = raw[index]
                    CandleData(
                        row[0].asLong(),
                        row[6].asLong(),
                        row[1].asString().toDouble(),
                        row[2].asString().toDouble(),
                        row[3].asString().toDouble(),
                        row[4].asString().toDouble(),
                        row[5].asString().toDouble(),
                        row[7].asString().toDouble(),
                        row[8].asInt(),
                        row[9].asString().toDouble(),
                        row[10].asString().toDouble(),
                    )
                }
            }
            while (current < until) {
                batch++
                try {
                    val candles = fetch()
                    if (candles.isEmpty()) break
                    pending += candles
                    total += candles.size
                    if (pending.size >= 500) {
                        val chunk = pending.take(500)
                        repeat(500) { pending.removeAt(0) }
                        save(chunk)
                    }
                    val next = candles.last().openTime + interval
                    check(next > current) { "Non-advancing Binance candle batch" }
                    current = next
                    pause(200)
                } catch (error: Exception) {
                    errors +=
                        mapOf("batchCount" to batch, "timestamp" to Instant.ofEpochMilli(current), "error" to (error.message ?: "Unknown"))
                    var success = false
                    for (attempt in 1..3) {
                        pause(attempt * 1000L)
                        val candles = runCatching { fetch() }.getOrNull()
                        if (!candles.isNullOrEmpty() && candles.last().openTime + interval > current) {
                            pending += candles
                            total += candles.size
                            current = candles.last().openTime + interval
                            success = true
                            break
                        }
                    }
                    if (!success) current += 1500 * interval
                }
            }
            save(pending)
            return result(true)
        } catch (error: Exception) {
            effectiveStart = start
            errors += mapOf("error" to (error.message ?: "Unknown"), "timestamp" to clock.instant())
            return result(false)
        }
    }

    fun statistics(symbol: String): Map<String, Any> {
        val (total, first, last) = repository.bounds(symbol)
        val result = linkedMapOf<String, Any>("totalCandles" to total, "dataGaps" to emptyList<Any>())
        if (first != null) result["firstCandle"] = Instant.ofEpochMilli(first)
        if (last != null) result["lastCandle"] = Instant.ofEpochMilli(last)
        if (first != null &&
            last != null &&
            total > 0
        ) {
            result["estimatedCompleteness"] = (total.toDouble() / ((last - first) / interval) * 100).coerceIn(0.0, 100.0)
        }
        return result
    }
}

@RestController
@RequestMapping("/api/v1/market/history/15m")
class HistoryController(
    private val history: CandleHistory,
    private val clock: Clock,
) {
    private fun symbol(value: String): String {
        require(Regex("^[A-Z]+USDT$").matches(value.uppercase())) { "유효하지 않은 심볼 형식입니다. (예: BTCUSDT)" }
        return value.uppercase()
    }

    private fun failure(
        prefix: String,
        error: Exception,
    ): Map<String, Any> =
        mapOf(
            "success" to false,
            "totalCandles" to 0,
            "newCandles" to 0,
            "duplicateCandles" to 0,
            "startTime" to clock.instant(),
            "endTime" to clock.instant(),
            "duration" to 0,
            "errors" to listOf(mapOf("error" to (error.message ?: "Unknown"), "timestamp" to clock.instant())),
            "message" to "$prefix: ${error.message}",
        )

    @PostMapping("/collect/{symbol}")
    @ResponseStatus(HttpStatus.CREATED)
    fun all(
        @PathVariable symbol: String,
    ): Map<String, Any> =
        try {
            val name = symbol(symbol)
            val now = clock.instant()
            val result = history.collect(name, now.atZone(ZoneOffset.UTC).minusYears(4).toInstant(), now)
            result +
                ("message" to if (result["success"] == true) "$name 히스토리컬 데이터 수집이 성공적으로 완료되었습니다." else "$name 히스토리컬 데이터 수집 중 오류가 발생했습니다.")
        } catch (error: Exception) {
            failure("히스토리컬 데이터 수집 실패", error)
        }

    @PostMapping("/collect/{symbol}/range")
    @ResponseStatus(HttpStatus.CREATED)
    fun range(
        @PathVariable symbol: String,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?,
    ): Map<String, Any> =
        try {
            val name = symbol(symbol)
            require(!startDate.isNullOrBlank() && !endDate.isNullOrBlank()) { "시작일과 종료일을 모두 입력해주세요. (YYYY-MM-DD 형식)" }
            val start: Instant
            val end: Instant
            try {
                start = LocalDate.parse(startDate).atStartOfDay().toInstant(ZoneOffset.UTC)
                end = LocalDate.parse(endDate).atTime(23, 59, 59).toInstant(ZoneOffset.UTC)
            } catch (error: Exception) {
                throw IllegalArgumentException("유효하지 않은 날짜 형식입니다. YYYY-MM-DD 형식으로 입력해주세요.")
            }
            require(start < end) { "시작일은 종료일보다 이전이어야 합니다." }
            require(end <= clock.instant()) { "종료일은 오늘 날짜보다 이전이어야 합니다." }
            val result = history.collect(name, start, end)
            result +
                (
                    "message" to
                        if (result["success"] == true) "$name $startDate~$endDate 기간 데이터 수집이 완료되었습니다." else "$name 기간별 데이터 수집 중 오류가 발생했습니다."
                )
        } catch (error: Exception) {
            failure("기간별 데이터 수집 실패", error)
        }

    @GetMapping("/stats/{symbol}")
    fun statistics(
        @PathVariable symbol: String,
    ): Map<String, Any> =
        try {
            val name = symbol(symbol)
            val stats = history.statistics(name)
            val count = stats["totalCandles"] as Long
            mapOf("symbol" to name) + stats +
                (
                    "message" to
                        if (count >
                            0
                        ) {
                            "$name 데이터가 ${String.format(Locale.US, "%,d", count)}개 저장되어 있습니다."
                        } else {
                            "$name 데이터가 없습니다. 히스토리컬 데이터 수집을 먼저 실행해주세요."
                        }
                )
        } catch (error: Exception) {
            mapOf(
                "symbol" to symbol.uppercase(),
                "totalCandles" to 0,
                "dataGaps" to emptyList<Any>(),
                "message" to "데이터 통계 조회 실패: ${error.message}",
            )
        }

    @GetMapping("/symbols")
    fun symbols(): Map<String, Any> =
        mapOf(
            "symbols" to
                listOf("BTCUSDT", "ETHUSDT", "ADAUSDT", "SOLUSDT", "DOGEUSDT", "XRPUSDT", "DOTUSDT", "AVAXUSDT", "MATICUSDT", "LINKUSDT"),
            "totalCount" to 10,
            "message" to "총 10개의 심볼에 대해 히스토리컬 데이터 수집이 가능합니다.",
        )
}

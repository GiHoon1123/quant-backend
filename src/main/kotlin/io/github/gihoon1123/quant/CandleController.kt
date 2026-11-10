package io.github.gihoon1123.quant

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
@RequestMapping("/api/v1/market/candles/15m")
class CandleController(
    private val service: CandleService,
    private val repository: CandleRepository,
    private val clock: Clock,
) {
    @GetMapping("/latest/{symbol}")
    fun latest(
        @PathVariable symbol: String,
    ): Map<String, Any?> {
        val candle =
            service.latest(symbol) ?: return mapOf("success" to false, "message" to "${symbol}의 캔들 데이터를 찾을 수 없습니다.", "data" to null)
        return success("최신 캔들 데이터 조회 성공", mapOf("symbol" to symbol, "candle" to candle, "retrievedAt" to clock.instant()))
    }

    @GetMapping("/latest")
    fun latest(): Map<String, Any?> {
        val candles = service.allLatest()
        return success(
            "모든 심볼의 최신 캔들 데이터 조회 성공",
            mapOf("candles" to candles, "symbolCount" to candles.size, "retrievedAt" to clock.instant()),
        )
    }

    @GetMapping("/history/{symbol}")
    fun history(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) startTime: Long?,
        @RequestParam(required = false) endTime: Long?,
    ): Map<String, Any?> =
        handled("히스토리 캔들 데이터 조회 실패") {
            val candles = service.history(symbol, limit)
            val options = mutableMapOf<String, Any>("limit" to limit)
            if (startTime != null) options["startTime"] = startTime
            if (endTime != null) options["endTime"] = endTime
            // The original endpoint reports these bounds but only applies the limit.
            success(
                "히스토리 캔들 데이터 조회 성공",
                mapOf(
                    "symbol" to symbol,
                    "candles" to candles,
                    "count" to candles.size,
                    "queryOptions" to options,
                    "retrievedAt" to clock.instant(),
                ),
            )
        }

    @GetMapping("/statistics")
    fun statistics(): Map<String, Any?> =
        handled("캔들 통계 조회 실패") {
            success(
                "캔들 통계 조회 성공",
                mapOf(
                    "statistics" to repository.statistics(),
                    "retrievedAt" to clock.instant(),
                ),
            )
        }

    @GetMapping("/subscription/status")
    fun status(): Map<String, Any?> = success("구독 상태 조회 성공", service.status() + ("retrievedAt" to clock.instant()))

    @PostMapping("/subscription/add/{symbol}")
    fun subscribe(
        @PathVariable symbol: String,
    ): Map<String, Any?> {
        service.subscribe(symbol)
        return success("$symbol 구독이 추가되었습니다.", mapOf("symbol" to symbol, "addedAt" to clock.instant()))
    }

    @PostMapping("/subscription/remove/{symbol}")
    fun unsubscribe(
        @PathVariable symbol: String,
    ): Map<String, Any?> {
        service.unsubscribe(symbol)
        return success("$symbol 구독이 해제되었습니다.", mapOf("symbol" to symbol, "removedAt" to clock.instant()))
    }

    @GetMapping("/ongoing/{symbol}")
    fun ongoing(
        @PathVariable symbol: String,
    ): Map<String, Any?> {
        val candle =
            service.ongoing(symbol) ?: return mapOf("success" to false, "message" to "${symbol}의 진행 중인 캔들 데이터가 없습니다.", "data" to null)
        return success("진행 중인 캔들 데이터 조회 성공", mapOf("symbol" to symbol, "candle" to candle, "retrievedAt" to clock.instant()))
    }

    @PostMapping("/trigger/complete/{symbol}")
    fun trigger(
        @PathVariable symbol: String,
    ): Map<String, Any?> = success("${symbol}의 캔들 완성 이벤트가 트리거되었습니다.", mapOf("symbol" to symbol, "triggeredAt" to clock.instant()))

    private fun success(
        message: String,
        data: Any?,
    ): Map<String, Any?> = mapOf("success" to true, "message" to message, "data" to data)

    private fun handled(
        message: String,
        action: () -> Map<String, Any?>,
    ): Map<String, Any?> =
        try {
            action()
        } catch (error: Exception) {
            mapOf(
                "success" to false,
                "message" to message,
                "error" to error.message,
            )
        }
}

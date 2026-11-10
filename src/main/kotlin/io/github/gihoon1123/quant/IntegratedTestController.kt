package io.github.gihoon1123.quant

import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

data class ExitRequest(
    val entryPrice: Double,
    val stopLoss: ExitConfig,
    val takeProfit: ExitConfig,
    val positionSide: PositionSide = PositionSide.LONG,
)

data class DefaultsRequest(
    val entryPrice: Double,
    val tradingType: String,
    val positionSide: PositionSide = PositionSide.LONG,
)

@RestController
@RequestMapping("/api/v1/test")
@ResponseStatus(HttpStatus.CREATED)
class IntegratedTestController(
    private val calculator: ExitPriceCalculator,
    private val environment: Environment,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @PostMapping("/spot/stop-loss-take-profit")
    fun spot(
        @RequestBody request: ExitRequest,
    ) = calculation(request, false)

    @PostMapping("/futures/stop-loss-take-profit")
    fun futures(
        @RequestBody request: ExitRequest,
    ) = calculation(request, true)

    private fun calculation(
        request: ExitRequest,
        futures: Boolean,
    ): Map<String, Any?> {
        val validation = calculator.validate(request.stopLoss, request.takeProfit)
        if (!validation.isValid) return mapOf("success" to false, "errors" to validation.errors)
        val result =
            calculator.futures(
                request.entryPrice,
                if (futures) request.positionSide else PositionSide.LONG,
                request.stopLoss,
                request.takeProfit,
            )
        return mapOf(
            "success" to true,
            "entryPrice" to request.entryPrice,
            "stopLossPrice" to result.stopLossPrice,
            "takeProfitPrice" to result.takeProfitPrice,
            "calculations" to
                mapOf(
                    "stopLoss" to
                        mapOf(
                            "type" to request.stopLoss.type,
                            "value" to request.stopLoss.value,
                            "calculatedPrice" to result.stopLossPrice,
                        ),
                    "takeProfit" to
                        mapOf(
                            "type" to request.takeProfit.type,
                            "value" to request.takeProfit.value,
                            "calculatedPrice" to result.takeProfitPrice,
                        ),
                ),
        ) +
            if (futures) mapOf("positionSide" to request.positionSide) else emptyMap()
    }

    @PostMapping("/monitoring/open")
    fun open(
        @RequestBody body: Map<String, Any>,
    ) = monitoring(body, true)

    @PostMapping("/monitoring/close")
    fun close(
        @RequestBody body: Map<String, Any>,
    ) = monitoring(body, false)

    private fun monitoring(
        body: Map<String, Any>,
        opened: Boolean,
    ): Map<String, Any> {
        val name = if (opened) "futures.position.opened" else "futures.position.closed"
        val event =
            mapOf(
                "eventId" to "test_${clock.millis()}",
                "timestamp" to clock.instant(),
                "symbol" to body["symbol"],
                "service" to "IntegratedTestController",
                "side" to (body["side"] ?: "LONG"),
                "quantity" to (body["quantity"] ?: 0),
                "source" to "IntegratedTestController",
                "metadata" to mapOf("test" to true),
            ) +
                if (opened) mapOf("leverage" to (body["leverage"] ?: 1), "notional" to (body["notional"] ?: 0)) else emptyMap()
        events.publishEvent(MarketEvent(name, event))
        return mapOf("success" to true, "emitted" to name, "event" to event)
    }

    private fun config(kind: String): Map<String, Double> =
        mapOf(
            "stopLossPercent" to
                (
                    environment.getProperty("${kind}_DEFAULT_STOP_LOSS_PERCENT")?.toDoubleOrNull() ?: if (kind ==
                        "SPOT"
                    ) {
                        0.03
                    } else {
                        0.02
                    }
                ),
            "takeProfitPercent" to
                (
                    environment.getProperty("${kind}_DEFAULT_TAKE_PROFIT_PERCENT")?.toDoubleOrNull()
                        ?: if (kind == "SPOT") 0.06 else 0.04
                ),
        )

    @PostMapping("/config/defaults")
    fun defaults() = mapOf("spot" to config("SPOT"), "futures" to config("FUTURES"))

    @PostMapping("/auto-trading/calculate")
    fun calculate(
        @RequestBody request: DefaultsRequest,
    ): Map<String, Any?> {
        val config = config(if (request.tradingType == "SPOT") "SPOT" else "FUTURES")
        val result =
            calculator.futures(
                request.entryPrice,
                if (request.tradingType ==
                    "SPOT"
                ) {
                    PositionSide.LONG
                } else {
                    request.positionSide
                },
                ExitConfig(ExitType.PERCENT, config["stopLossPercent"]),
                ExitConfig(ExitType.PERCENT, config["takeProfitPercent"]),
            )
        return mapOf(
            "success" to true,
            "tradingType" to request.tradingType,
            "positionSide" to request.positionSide,
            "entryPrice" to request.entryPrice,
            "appliedDefaults" to config,
            "stopLossPrice" to result.stopLossPrice,
            "takeProfitPrice" to result.takeProfitPrice,
        )
    }

    @PostMapping("/health")
    fun health(): Map<String, Any> =
        mapOf(
            "success" to true,
            "message" to "모든 서비스가 정상 작동 중입니다.",
            "services" to
                mapOf(
                    "tradingConfig" to (mapOf("status" to "healthy") + defaults()),
                    "calculator" to
                        mapOf(
                            "status" to "healthy",
                            "testResult" to calculator.spot(50000.0, ExitConfig(ExitType.PERCENT, 0.05), ExitConfig(ExitType.PERCENT, 0.1)),
                        ),
                ),
            "timestamp" to clock.instant(),
        )
}

package io.github.gihoon1123.quant

import org.springframework.stereotype.Service

enum class ExitType { NONE, PRICE, PERCENT }

enum class PositionSide { LONG, SHORT }

data class ExitConfig(
    val type: ExitType,
    val value: Double? = null,
)

data class ExitPrices(
    val stopLossPrice: Double?,
    val takeProfitPrice: Double?,
)

data class ExitValidation(
    val isValid: Boolean,
    val errors: List<String>,
)

@Service
class ExitPriceCalculator {
    fun spot(
        entryPrice: Double,
        stopLoss: ExitConfig,
        takeProfit: ExitConfig,
    ): ExitPrices = futures(entryPrice, PositionSide.LONG, stopLoss, takeProfit)

    fun futures(
        entryPrice: Double,
        side: PositionSide,
        stopLoss: ExitConfig,
        takeProfit: ExitConfig,
    ): ExitPrices = ExitPrices(price(entryPrice, side, stopLoss, true), price(entryPrice, side, takeProfit, false))

    private fun price(
        entryPrice: Double,
        side: PositionSide,
        config: ExitConfig,
        stop: Boolean,
    ): Double? {
        val value = config.value ?: return null
        if (config.type == ExitType.NONE || value == 0.0) return null
        if (config.type == ExitType.PRICE) return value
        val lower = (side == PositionSide.LONG) == stop
        return entryPrice * if (lower) 1 - value else 1 + value
    }

    fun validate(
        stopLoss: ExitConfig,
        takeProfit: ExitConfig,
    ): ExitValidation {
        val errors = mutableListOf<String>()
        for ((config, name) in listOf(stopLoss to "손절", takeProfit to "익절")) {
            if (config.type == ExitType.NONE) continue
            if (config.value == null || config.value <= 0 || config.value.isNaN()) errors += "$name 값은 0보다 커야 합니다."
            if (config.type == ExitType.PERCENT && config.value != null && config.value >= 1) errors += "$name 퍼센트는 100% 미만이어야 합니다."
        }
        return ExitValidation(errors.isEmpty(), errors)
    }
}

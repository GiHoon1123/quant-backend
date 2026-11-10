package io.github.gihoon1123.quant

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

@RestController
@RequestMapping("/auto-trading/atr")
class AtrConfigController(
    private val cache: Cache,
    private val clock: Clock,
) {
    private fun number(
        key: String,
        env: String,
        default: Double,
    ): Double {
        val cached = (cache.get(key) as? Number)?.toDouble()
        return cached?.takeIf { it != 0.0 } ?: System.getenv(env)?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: default
    }

    @GetMapping("/config")
    fun config(): Map<String, Any> =
        mapOf(
            "success" to true,
            "data" to
                mapOf(
                    "atr" to
                        mapOf(
                            "stopLossMultiplier" to number("config:atr_stop_loss_multiplier", "ATR_STOP_LOSS_MULTIPLIER", 2.8),
                            "takeProfitMultiplier" to number("config:atr_take_profit_multiplier", "ATR_TAKE_PROFIT_MULTIPLIER", 1.3),
                            "riskRewardRatio" to number("config:atr_risk_reward_ratio", "ATR_RISK_REWARD_RATIO", 2.0),
                        ),
                    "emergency" to
                        mapOf(
                            "stopLossPercent" to number("config:emergency_stop_loss", "EMERGENCY_STOP_LOSS_PERCENT", 0.03),
                            "takeProfitPercent" to number("config:emergency_take_profit", "EMERGENCY_TAKE_PROFIT_PERCENT", 0.06),
                            "enabled" to (cache.get("config:emergency_enabled") != false || System.getenv("EMERGENCY_ENABLED") == "true"),
                        ),
                    "timestamp" to clock.instant(),
                ),
        )

    @PutMapping("/config")
    fun update(
        @RequestBody values: Map<String, Any>,
    ): Map<String, Any> {
        val mapping =
            mapOf(
                "stopLossMultiplier" to "config:atr_stop_loss_multiplier",
                "takeProfitMultiplier" to "config:atr_take_profit_multiplier",
                "riskRewardRatio" to "config:atr_risk_reward_ratio",
                "stopLossPercent" to "config:emergency_stop_loss",
                "takeProfitPercent" to "config:emergency_take_profit",
                "enabled" to "config:emergency_enabled",
            )
        values.forEach { (key, value) -> mapping[key]?.let { cache.set(it, value) } }
        return mapOf("success" to true, "message" to "ATR 설정이 업데이트되었습니다.", "data" to mapOf("updatedAt" to clock.instant()))
    }

    @GetMapping("/cache/status")
    fun status(): Map<String, Any> {
        val atr = cache.getByPattern("atr:")
        return mapOf(
            "success" to true,
            "data" to
                mapOf(
                    "atrEntries" to atr.size,
                    "configEntries" to cache.getByPattern("config:").size,
                    "totalCacheSize" to cache.size(),
                    "atrSymbols" to atr.keys.map { it.replaceFirst("atr:", "") },
                    "timestamp" to clock.instant(),
                ),
        )
    }
}

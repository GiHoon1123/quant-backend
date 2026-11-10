package io.github.gihoon1123.quant

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

@Service
class Futures(
    private val client: BinanceFutures,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private fun JsonNode.number(key: String): Double = this[key]?.asString()?.toDoubleOrNull() ?: Double.NaN

    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { this[it] }

    private fun amount(value: Double): String =
        java.math.BigDecimal
            .valueOf(value)
            .stripTrailingZeros()
            .toPlainString()

    private fun active(symbol: String? = null) = client.positions(symbol).items().filter { it.number("positionAmt") != 0.0 }

    private fun current(symbol: String): JsonNode? =
        runCatching {
            active(symbol).firstOrNull { it["symbol"].asString() == symbol }
        }.getOrNull()

    private fun side(position: JsonNode): String = if (position.number("positionAmt") > 0) "LONG" else "SHORT"

    private fun mappedOrder(raw: JsonNode): Map<String, Any> =
        mapOf(
            "orderId" to raw["orderId"].asLong(),
            "symbol" to raw["symbol"].asString(),
            "side" to raw["side"].asString(),
            "quantity" to raw.number("origQty"),
            "executedQuantity" to raw.number("executedQty"),
            "avgPrice" to raw.number("avgPrice"),
            "totalAmount" to raw.number("cumQuote"),
            "status" to raw["status"].asString(),
            "timestamp" to Instant.ofEpochMilli(raw["updateTime"].asLong()),
        )

    private fun trade(
        raw: JsonNode,
        symbol: String,
        side: String,
        quantity: Double,
        metadata: Map<String, Any>,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        events.publishEvent(
            MarketEvent(
                "trade.executed",
                mapOf(
                    "eventId" to UUID.randomUUID().toString(),
                    "timestamp" to clock.instant(),
                    "service" to "FuturesService",
                    "symbol" to symbol,
                    "orderId" to raw["orderId"].asLong(),
                    "clientOrderId" to (raw["clientOrderId"]?.asString() ?: ""),
                    "side" to side,
                    "type" to "MARKET",
                    "quantity" to quantity,
                    "price" to (raw.number("avgPrice").takeIf { it.isFinite() } ?: 0.0),
                    "totalAmount" to (raw.number("cumQuote").takeIf { it.isFinite() } ?: 0.0),
                    "fee" to 0,
                    "feeAsset" to "USDT",
                    "feeRate" to 0,
                    "status" to raw["status"].asString(),
                    "executedAt" to clock.instant(),
                    "source" to "FuturesService",
                    "metadata" to (metadata + ("orderId" to raw["orderId"].asLong())),
                ) + extra,
            ),
        )
    }

    fun leverage(
        symbol: String,
        leverage: Int,
    ): Any {
        if (leverage !in 1..125) throw ApiException(400, "레버리지는 1배에서 125배 사이여야 합니다.")
        try {
            return client.leverage(symbol, leverage)
        } catch (error: Exception) {
            if (listOf("leverage not modified", "No need to change leverage").any { error.message?.contains(it) == true }) {
                return mapOf(
                    "symbol" to symbol,
                    "leverage" to leverage,
                    "status" to "already_set",
                )
            }
            throw ApiException(400, "레버리지 설정에 실패했습니다: ${error.message}")
        }
    }

    fun margin(
        symbol: String,
        type: String,
    ): Any =
        try {
            client.margin(symbol, type)
        } catch (error: Exception) {
            if (error.message?.contains("No need to change margin type") ==
                true
            ) {
                mapOf("symbol" to symbol, "marginType" to type, "status" to "already_set")
            } else {
                throw ApiException(400, "마진 모드 설정에 실패했습니다: ${error.message}")
            }
        }

    fun open(
        symbol: String,
        side: String,
        quantity: Double,
        leverage: Int,
        accountId: String? = null,
        userId: String? = null,
    ): Map<String, Any> {
        if (symbol.isBlank()) throw ApiException(400, "❌ 거래 심볼을 입력해주세요.\n💡 예시: BTCUSDT, ETHUSDT, ADAUSDT")
        if (quantity <= 0) throw ApiException(400, "❌ 포지션 수량은 0보다 커야 합니다.\n💡 최소 수량: 0.001\n📊 권장 수량: 리스크 관리를 고려한 적정 수량")
        if (leverage !in
            1..125
        ) {
            throw ApiException(
                400,
                "❌ 레버리지는 1배에서 125배 사이여야 합니다.\n\n🎯 레버리지별 권장사항:\n• 1~5배: 🟢 안전 (초보자 권장)\n• 6~20배: 🟡 중간 위험 (경험자)\n• 21~50배: 🟠 높은 위험 (전문가)\n• 51~125배: 🔴 극도로 위험 (비추천)\n\n💡 팁: 처음에는 1~3배로 시작하세요!",
            )
        }
        if (quantity <
            0.001
        ) {
            throw ApiException(
                400,
                "❌ 최소 포지션 수량 미달\n📊 입력 수량: ${amount(
                    quantity,
                )}\n📏 최소 수량: 0.001\n\n💡 해결 방법:\n1. 수량을 0.001 이상으로 증가\n2. 더 높은 가격의 코인 선택\n3. 레버리지 조정으로 포지션 크기 조절",
            )
        }
        leverage(symbol, leverage)
        val price = estimatedPrice(symbol)
        val required = quantity * price / leverage
        val balance =
            client.balances().items().find { it["asset"].asString() == "USDT" && it.number("balance") > 0 }
                ?: throw ApiException(400, "포지션 진입 불가: USDT 잔고가 없습니다")
        val available = balance.number("availableBalance")
        if (available <
            required
        ) {
            throw ApiException(
                400,
                "포지션 진입 불가: 사용 가능한 USDT 잔고(${String.format(Locale.US, "%.2f", available)})가 필요 마진(${amount(required)})보다 부족합니다",
            )
        }
        active(symbol)
        if (quantity * price <
            5
        ) {
            throw ApiException(400, "선물 포지션은 최소 5 USDT 이상이어야 합니다. (현재: ${String.format(Locale.US, "%.2f", quantity * price)} USDT)")
        }
        try {
            val raw = client.open(symbol, if (side == "LONG") "BUY" else "SELL", quantity)
            trade(
                raw,
                symbol,
                if (side ==
                    "LONG"
                ) {
                    "BUY"
                } else {
                    "SELL"
                },
                quantity,
                mapOf("leverage" to leverage, "positionSide" to side, "marginType" to "ISOLATED"),
                mapOf(
                    "accountId" to accountId,
                    "userId" to userId,
                ),
            )
            events.publishEvent(
                MarketEvent(
                    "futures.position.opened",
                    mapOf(
                        "eventId" to UUID.randomUUID().toString(),
                        "timestamp" to clock.instant(),
                        "symbol" to symbol,
                        "service" to "FuturesService",
                        "side" to side,
                        "quantity" to quantity,
                        "leverage" to leverage,
                        "notional" to raw.number("cumQuote"),
                        "source" to "FuturesService",
                        "metadata" to mapOf("orderId" to raw["orderId"].asLong()),
                    ),
                ),
            )
            return mappedOrder(raw)
        } catch (error: Exception) {
            throw ApiException(400, "포지션 진입에 실패했습니다: ${error.message}")
        }
    }

    private fun estimatedPrice(symbol: String): Double {
        try {
            val raw = client.positions(symbol).items()
            val price = raw.firstOrNull()?.number("markPrice")
            if (price != null && price > 0 && price.isFinite()) return price
            throw ApiException(
                400,
                "❌ $symbol 현재 가격 조회 실패\n\n🔍 가능한 원인:\n1. 해당 심볼이 존재하지 않음\n2. 거래 일시 중단 상태\n3. 네트워크 연결 문제\n4. 바이낸스 서버 일시적 오류\n\n💡 해결 방법:\n1. 심볼명 확인 (예: BTCUSDT)\n2. 바이낸스 거래 가능 여부 확인\n3. 잠시 후 재시도\n4. 다른 심볼로 테스트",
            )
        } catch (error: Exception) {
            val message = error.message ?: ""
            if (message.contains(
                    "symbol",
                )
            ) {
                throw ApiException(400, "❌ 잘못된 심볼: $symbol\n💡 올바른 형식: BTCUSDT, ETHUSDT, ADAUSDT\n📋 지원 심볼 확인: 바이낸스 선물 거래 페이지 참조")
            }
            if (message.contains("network") ||
                message.contains("timeout")
            ) {
                throw ApiException(400, "❌ 네트워크 연결 문제\n🌐 인터넷 연결 상태를 확인해주세요\n⏰ 잠시 후 다시 시도해주세요")
            }
            throw ApiException(
                400,
                "시장가 조회 실패: $message\n\n💡 일반적인 해결 방법:\n1. 심볼명 정확성 확인\n2. 네트워크 연결 상태 점검\n3. 바이낸스 서비스 상태 확인\n4. API 키 권한 설정 확인",
            )
        }
    }

    fun close(
        symbol: String,
        quantity: Double?,
    ): Map<String, Any> {
        val positions = active(symbol)
        if (positions.isEmpty()) throw ApiException(400, "$symbol 에 청산할 포지션이 없습니다.".replace(" 에", "에"))
        if (positions.size > 1 &&
            (quantity == null || quantity == 0.0)
        ) {
            throw ApiException(400, "${symbol}에 여러 포지션이 있습니다. 청산할 수량을 명시하거나 개별적으로 청산해주세요.")
        }
        val position = positions.first()
        val side = side(position)
        val held = abs(position.number("positionAmt"))
        if (quantity != null && quantity > held) throw ApiException(400, "청산 수량(${amount(quantity)})이 보유 포지션 수량(${amount(held)})을 초과합니다.")
        try {
            val raw = client.close(symbol, side, quantity)
            trade(
                raw,
                symbol,
                if (side ==
                    "LONG"
                ) {
                    "SELL"
                } else {
                    "BUY"
                },
                quantity?.takeIf { it != 0.0 } ?: held,
                mapOf(
                    "positionSide" to side,
                    "isClosing" to true,
                    "closeType" to if (quantity != null && quantity != 0.0) "PARTIAL" else "FULL",
                ),
            )
            events.publishEvent(
                MarketEvent(
                    "futures.position.closed",
                    mapOf(
                        "eventId" to UUID.randomUUID().toString(),
                        "timestamp" to clock.instant(),
                        "symbol" to symbol,
                        "service" to "FuturesService",
                        "side" to side,
                        "quantity" to (quantity ?: held),
                        "source" to "FuturesService",
                        "metadata" to mapOf("orderId" to raw["orderId"].asLong()),
                    ),
                ),
            )
            return mappedOrder(raw)
        } catch (error: Exception) {
            throw ApiException(400, "포지션 청산에 실패했습니다: ${error.message}")
        }
    }

    fun positions(
        symbol: String? = null,
        risk: Double? = null,
    ): List<Map<String, Any>> =
        try {
            active(symbol).filter { risk == null || it.number("maintMarginRatio") >= risk }.map { raw ->
                val quantity = raw.number("positionAmt")
                val entry = raw.number("entryPrice")
                val pnl = raw.number("unRealizedProfit")
                mapOf(
                    "symbol" to raw["symbol"].asString(),
                    "quantity" to abs(quantity),
                    "side" to side(raw),
                    "entryPrice" to entry,
                    "markPrice" to raw.number("markPrice"),
                    "unrealizedPnl" to pnl,
                    "unrealizedPnlPercent" to
                        if (entry >
                            0
                        ) {
                            pnl / (entry * abs(quantity)) * 100
                        } else {
                            0.0
                        },
                    "liquidationPrice" to raw.number("liquidationPrice"),
                    "leverage" to raw.number("leverage").toInt(),
                    "marginType" to raw["marginType"].asString().uppercase(),
                    "notionalValue" to abs(raw.number("notional")),
                    "isolatedMargin" to raw.number("isolatedMargin"),
                    "maintMarginRatio" to raw.number("maintMarginRatio"),
                    "updateTime" to Instant.ofEpochMilli(raw["updateTime"].asLong()),
                )
            }
        } catch (error: Exception) {
            throw ApiException(400, "${if (risk != null) "위험 포지션" else "포지션 정보"} 조회에 실패했습니다: ${error.message}")
        }

    fun balances(): List<Map<String, Any>> =
        try {
            client.balances().items().filter { it.number("balance") > 0 }.map { raw ->
                mapOf(
                    "asset" to raw["asset"].asString(),
                    "totalBalance" to raw.number("balance"),
                    "availableBalance" to raw.number("availableBalance"),
                    "maxWithdrawAmount" to raw.number("maxWithdrawAmount"),
                    "crossWalletBalance" to raw.number("crossWalletBalance"),
                    "crossUnrealizedPnl" to raw.number("crossUnPnl"),
                    "marginAvailable" to raw["marginAvailable"].asBoolean(),
                    "updateTime" to Instant.ofEpochMilli(raw["updateTime"].asLong()),
                )
            }
        } catch (error: Exception) {
            throw ApiException(400, "선물 잔고 조회에 실패했습니다: ${error.message}")
        }

    fun switch(
        symbol: String,
        newSide: String,
        quantity: Double,
    ): Map<String, Any> {
        try {
            val position = current(symbol) ?: throw ApiException(400, "${symbol}에 기존 포지션이 없어서 스위칭할 수 없습니다.")
            val heldSide = side(position)
            val held = abs(position.number("positionAmt"))
            if (heldSide == newSide) throw ApiException(400, "현재 이미 $heldSide 포지션입니다. 스위칭이 불필요합니다.")
            val raw = client.switch(symbol, heldSide, held, quantity)
            trade(
                raw,
                symbol,
                if (heldSide ==
                    "LONG"
                ) {
                    "SELL"
                } else {
                    "BUY"
                },
                held,
                mapOf("positionSide" to heldSide, "isClosing" to true, "closeType" to "SWITCH"),
                mapOf(
                    "orderId" to "${raw["orderId"].asLong()}_close",
                    "clientOrderId" to "",
                    "totalAmount" to 0,
                    "status" to "FILLED",
                ),
            )
            trade(
                raw,
                symbol,
                if (newSide ==
                    "LONG"
                ) {
                    "BUY"
                } else {
                    "SELL"
                },
                quantity,
                mapOf("positionSide" to newSide, "isSwitch" to true, "previousSide" to heldSide),
            )
            return mappedOrder(raw)
        } catch (error: Exception) {
            throw ApiException(500, "포지션 스위칭 실패")
        }
    }

    fun adjust(
        symbol: String,
        quantity: Double,
        reduce: Boolean,
    ): Map<String, Any> {
        try {
            val position =
                current(symbol)
                    ?: throw ApiException(
                        400,
                        if (reduce) "${symbol}에 청산할 포지션이 없습니다." else "${symbol}에 기존 포지션이 없어서 추가할 수 없습니다. 새로운 포지션을 진입해주세요.",
                    )
            val held = abs(position.number("positionAmt"))
            val heldSide = side(position)
            if (reduce && quantity >= held) throw ApiException(400, "청산 수량이 너무 큽니다. 현재 포지션: ${amount(held)}, 요청 청산: ${amount(quantity)}")
            val raw = if (reduce) client.reduce(symbol, heldSide, quantity) else client.add(symbol, heldSide, quantity)
            val metadata =
                if (reduce) {
                    mapOf(
                        "positionSide" to heldSide,
                        "isPartialClose" to true,
                        "originalQuantity" to held,
                        "remainingQuantity" to held - quantity,
                    )
                } else {
                    mapOf("positionSide" to heldSide, "isAddition" to true)
                }
            trade(raw, symbol, if ((heldSide == "LONG") != reduce) "BUY" else "SELL", quantity, metadata)
            return mappedOrder(raw)
        } catch (error: Exception) {
            throw ApiException(500, if (reduce) "포지션 부분 청산 실패" else "포지션 추가 실패")
        }
    }

    fun closeAll(
        symbol: String,
        memo: String?,
    ): Map<String, Any?> {
        val positions = active(symbol)
        if (positions.isEmpty()) throw ApiException(400, "${symbol}에 청산할 포지션이 없습니다.")
        if (positions.size >
            1
        ) {
            throw ApiException(
                400,
                "${symbol}에 여러 포지션이 있습니다: ${positions.joinToString(
                    ", ",
                ) { "${side(it)} ${amount(abs(it.number("positionAmt")))}" }}. 개별적으로 청산해주세요.",
            )
        }
        val position = positions.first()
        val held = abs(position.number("positionAmt"))
        try {
            val response = close(symbol, held)
            return mapOf(
                "symbol" to symbol,
                "originalSide" to side(position),
                "originalQuantity" to held,
                "closedQuantity" to held,
                "avgPrice" to response["avgPrice"],
                "totalAmount" to response["totalAmount"],
                "orderId" to response["orderId"],
                "status" to response["status"],
                "timestamp" to response["timestamp"],
            ) +
                if (memo == null) emptyMap() else mapOf("memo" to memo)
        } catch (error: Exception) {
            throw ApiException(400, "포지션 전체 청산에 실패했습니다: ${error.message}")
        }
    }

    fun transfer(
        asset: String,
        amount: Double,
        from: String,
        to: String,
    ): Map<String, Any> {
        if (asset.isBlank()) throw ApiException(400, "자산 심볼을 입력해주세요. (예: USDT, BTC)")
        if (amount <= 0) throw ApiException(400, "이체 금액은 0보다 커야 합니다.")
        if (from == to) throw ApiException(400, "출발 계좌와 도착 계좌가 동일합니다.")
        try {
            val raw = client.transfer(asset, amount, from, to)
            return mapOf(
                "asset" to asset,
                "amount" to amount,
                "fromAccount" to from,
                "toAccount" to to,
                "transferId" to (raw["tranId"] ?: raw["id"] ?: "unknown"),
                "timestamp" to clock.instant(),
            )
        } catch (error: Exception) {
            if (error.message?.contains("insufficient") ==
                true
            ) {
                throw ApiException(
                    400,
                    "❌ 잔고 부족: $from 계좌의 $asset 잔고가 부족합니다.\n💰 이체 요청 금액: ${this.amount(amount)} $asset\n💡 해결 방법: 잔고를 확인하고 이체 금액을 조정하세요.",
                )
            }
            throw ApiException(
                400,
                "자금 이체에 실패했습니다: ${error.message}\n\n💡 가능한 원인:\n1. 잔고 부족\n2. 최소 이체 금액 미달\n3. 포지션에 사용 중인 자금\n4. API 키 권한 문제\n5. 네트워크 연결 문제",
            )
        }
    }
}

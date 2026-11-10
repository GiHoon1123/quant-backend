package io.github.gihoon1123.quant

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt

@Service
class Orders(
    private val client: BinanceSpot,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private fun fixed(
        value: Double,
        digits: Int,
    ): String = String.format(Locale.US, "%.${digits}f", value)

    private fun number(value: Double): String =
        java.math.BigDecimal
            .valueOf(value)
            .stripTrailingZeros()
            .toPlainString()

    private fun positive(
        value: Double,
        message: String,
    ) {
        if (value.isNaN()) throw ApiException(400, "$message (숫자가 아닙니다)")
        if (value <= 0) throw ApiException(400, "$message (0보다 커야 합니다)")
        if (!value.isFinite()) throw ApiException(400, "$message (유한한 숫자여야 합니다)")
    }

    private fun insufficientCoin(
        symbol: String,
        quantity: Double,
    ) {
        val coin = symbol.replace("USDT", "")
        val balance = client.balances().find { it.asset == coin }?.free ?: 0.0
        if (quantity > balance) {
            throw ApiException(
                400,
                "❌ $coin 보유 수량 부족\n💰 현재 보유: ${number(balance)} $coin\n💸 매도 요청: ${number(quantity)} $coin\n" +
                    "📊 부족 수량: ${fixed(quantity - balance, 8)} $coin\n\n💡 해결 방법:\n" +
                    "1. 매도 수량을 ${number(balance)} $coin 이하로 조정\n2. $coin 추가 매수 후 재시도\n3. 전체 보유량 매도: ${number(balance)} $coin",
            )
        }
    }

    private fun select(
        raw: JsonNode,
        keys: List<String>,
    ): Map<String, JsonNode> = keys.filter { raw.has(it) }.associateWith { raw[it] }

    fun market(
        symbol: String,
        side: String,
        value: Double,
        accountId: String? = null,
        userId: String? = null,
    ): Map<String, Any> {
        positive(value, if (side == "BUY") "매수 금액은 0보다 커야 합니다." else "매도 수량은 0보다 커야 합니다.")
        if (side == "BUY") {
            if (value < 10) throw ApiException(400, "❌ 바이낸스 현물 거래 최소 금액: 10 USDT (입력값: ${number(value)} USDT)\n💡 팁: 10 USDT 이상으로 주문해주세요.")
            val balance = client.balances().find { it.asset == "USDT" }?.free ?: 0.0
            if (value > balance) {
                throw ApiException(
                    400,
                    "❌ USDT 잔고 부족\n💰 현재 잔고: ${fixed(balance, 2)} USDT\n💸 주문 금액: ${number(value)} USDT\n" +
                        "📊 부족 금액: ${fixed(value - balance, 2)} USDT\n\n💡 해결 방법:\n" +
                        "1. 주문 금액을 ${fixed(balance, 2)} USDT 이하로 조정\n2. USDT 입금 후 재시도",
                )
            }
        } else {
            insufficientCoin(symbol, value)
        }
        try {
            val raw = client.market(symbol, side, value)
            val fills = raw["fills"]
            val entries = if (fills == null) emptyList() else (0 until fills.size()).map { fills[it] }
            val quantity = entries.sumOf { it["qty"].asString().toDouble() }
            val average =
                if (quantity ==
                    0.0
                ) {
                    "0"
                } else {
                    fixed(entries.sumOf { it["price"].asString().toDouble() * it["qty"].asString().toDouble() } / quantity, 8)
                }
            val keys =
                listOf(
                    "symbol",
                    "orderId",
                    "clientOrderId",
                    if (side ==
                        "BUY"
                    ) {
                        "origQuoteOrderQty"
                    } else {
                        "cummulativeQuoteQty"
                    },
                    "executedQty",
                    "status",
                    "fills",
                )
            val response =
                select(raw, keys) +
                    mapOf(
                        "avgPrice" to average,
                        "fills" to (
                            fills ?: tools.jackson.databind.json.JsonMapper
                                .builder()
                                .build()
                                .createArrayNode()
                        ),
                    )
            val executed = raw["executedQty"].asString().toDouble()
            val event =
                linkedMapOf<String, Any?>(
                    "eventId" to UUID.randomUUID().toString(),
                    "timestamp" to clock.instant(),
                    "service" to "OrderService",
                    "symbol" to symbol,
                    "orderId" to raw["orderId"].asString(),
                    "clientOrderId" to raw["clientOrderId"].asString(),
                    "side" to side,
                    "type" to "MARKET",
                    "quantity" to executed,
                    "price" to average.toDouble(),
                    "totalAmount" to executed * average.toDouble(),
                    "fee" to entries.sumOf { it["commission"]?.asString()?.toDoubleOrNull() ?: 0.0 },
                    "feeAsset" to (entries.firstOrNull()?.get("commissionAsset")?.asString() ?: "USDT"),
                    "feeRate" to 0.001,
                    "status" to raw["status"].asString(),
                    "executedAt" to clock.instant(),
                    "source" to "API",
                    "metadata" to mapOf("rawResponse" to raw, "fills" to fills),
                )
            if (side == "BUY") {
                if (accountId != null) event["accountId"] = accountId
                if (userId != null) event["userId"] = userId
            }
            events.publishEvent(MarketEvent("trade.executed", event))
            return response
        } catch (error: Exception) {
            val causes =
                if (side == "BUY") {
                    "1. 네트워크 연결 문제\n2. 바이낸스 서버 일시적 오류\n3. 해당 심볼 거래 일시 중단\n4. API 키 권한 문제"
                } else {
                    "1. 최소 주문 금액 미달 (10 USDT 미만)\n2. 네트워크 연결 문제\n3. 바이낸스 서버 일시적 오류\n4. 해당 심볼 거래 일시 중단\n5. API 키 권한 문제"
                }
            throw ApiException(400, "시장가 ${if (side == "BUY") "매수" else "매도"} 실패: ${error.message}\n\n🔍 가능한 원인:\n$causes")
        }
    }

    fun limit(
        symbol: String,
        side: String,
        quantity: Double,
        price: Double,
    ): Map<String, Any> {
        positive(quantity, "${if (side == "BUY") "매수" else "매도"} 수량은 0보다 커야 합니다.")
        positive(price, "지정가는 0보다 커야 합니다.")
        val notional = quantity * price
        val coin = symbol.replace("USDT", "")
        if (notional <
            10
        ) {
            throw ApiException(
                400,
                "❌ 최소 주문 금액 미달\n📊 현재 주문 금액: ${fixed(notional, 2)} USDT\n📏 최소 주문 금액: 10 USDT\n💡 해결 방법:\n" +
                    "1. 수량 증가: ${fixed(10 / price, 8)} $coin 이상\n2. 가격 상향: ${fixed(10 / quantity, 2)} USDT 이상",
            )
        }
        if (side == "BUY") {
            val balance = client.balances().find { it.asset == "USDT" }?.free ?: 0.0
            // Preserve the original call, which passes price into the feeRate parameter.
            val step =
                mapOf("BTCUSDT" to 0.00001, "ETHUSDT" to 0.0001, "SOLUSDT" to 0.01)[symbol.uppercase()]
                    ?: throw IllegalArgumentException("stepSize 정보가 존재하지 않습니다: $symbol")
            val precision = (-log10(step)).roundToInt()
            val maximum = fixed(floor(balance / (1 + price) / step) * step, precision).toDouble()
            if (quantity > maximum) {
                throw ApiException(
                    400,
                    "❌ USDT 잔고 부족 (수수료 포함)\n💰 현재 USDT 잔고: ${fixed(balance, 2)} USDT\n" +
                        "💸 필요 금액: ${fixed(quantity * price * 1.001, 2)} USDT (수수료 포함)\n📊 최대 매수 가능: ${fixed(maximum, 8)} $coin\n\n" +
                        "💡 해결 방법:\n1. 수량을 ${fixed(maximum, 8)} 이하로 조정\n2. USDT 추가 입금 후 재시도\n3. 더 낮은 가격으로 주문",
                )
            }
        } else {
            insufficientCoin(symbol, quantity)
        }
        try {
            val raw = client.limit(symbol, side, quantity, price)
            return select(raw, listOf("symbol", "orderId", "executedQty", "price", "type", "side", "status")) +
                ("quantity" to raw["origQty"])
        } catch (error: Exception) {
            throw ApiException(
                400,
                "지정가 ${if (side == "BUY") "매수" else "매도"} 주문 실패: ${error.message}\n\n🔍 가능한 원인:\n" +
                    "1. 가격 정밀도 오류 (소수점 자릿수 확인)\n2. 수량 정밀도 오류 (최소 단위 확인)\n3. 시장 시간 외 거래 시도\n4. 네트워크 연결 문제\n5. API 키 권한 문제",
            )
        }
    }

    fun cancel(
        symbol: String,
        orderId: Long,
    ): Map<String, Any> {
        if (symbol.isBlank()) throw ApiException(400, "거래 심볼을 입력해주세요.")
        if (orderId <= 0) throw ApiException(400, "유효한 주문 ID를 입력해주세요.")
        try {
            return select(client.cancel(symbol, orderId), listOf("symbol", "orderId", "status", "side", "type", "executedQty"))
        } catch (
            error: Exception,
        ) {
            throw ApiException(
                400,
                "주문 취소 실패: ${error.message}\n\n🔍 가능한 원인:\n1. 주문이 이미 체결됨 (FILLED)\n2. 주문이 이미 취소됨 (CANCELED)\n" +
                    "3. 존재하지 않는 주문 ID\n4. 네트워크 연결 문제\n5. API 키 권한 문제\n\n💡 해결 방법:\n1. 주문 상태를 먼저 확인\n2. 올바른 주문 ID 사용\n3. 잠시 후 재시도",
            )
        }
    }

    fun balances(): List<Balance> =
        try {
            client.balances()
        } catch (error: Exception) {
            throw ApiException(
                400,
                "잔고 조회 실패: ${error.message}\n\n🔍 가능한 원인:\n1. 네트워크 연결 문제\n2. 바이낸스 서버 일시적 오류\n" +
                    "3. API 키 권한 문제 (READ 권한 필요)\n4. API 키 만료\n5. IP 화이트리스트 설정 문제\n\n💡 해결 방법:\n1. 네트워크 연결 상태 확인\n2. API 키 권한 설정 확인\n3. 잠시 후 재시도",
            )
        }
}

data class MarketBuyRequest(
    @field:NotEmpty(message = "symbol should not be empty") val symbol: String,
    @field:DecimalMin(value = "10", message = "주문 금액은 최소 10 USDT 이상이어야 합니다.") val usdtAmount: Double,
    val accountId: String? = null,
    val userId: String? = null,
    val stopLoss: ExitConfig? = null,
    val takeProfit: ExitConfig? = null,
)

data class MarketSellRequest(
    @field:NotEmpty(message = "symbol should not be empty") val symbol: String,
    @field:DecimalMin(value = "0.000001", message = "quantity는 최소 0.000001 이상이어야 합니다.") val quantity: Double,
    val accountId: String? = null,
    val userId: String? = null,
)

data class LimitRequest(
    @field:NotEmpty(message = "symbol should not be empty") val symbol: String,
    @field:DecimalMin(value = "0.000001", message = "quantity는 최소 0.000001 이상이어야 합니다.") val quantity: Double,
    @field:DecimalMin(value = "0.000001", message = "price는 0보다 커야 합니다.") val price: Double,
    val stopLoss: ExitConfig? = null,
    val takeProfit: ExitConfig? = null,
)

data class CancelRequest(
    @field:jakarta.validation.constraints.Pattern(
        regexp = "BTCUSDT|ETHUSDT|SOLUSDT|BNBUSDT|XRPUSDT|ADABTC|ADAUSDT|DOGEUSDT|AVAXUSDT|DOTUSDT|LINKUSDT|MATICUSDT|SHIBUSDT|LTCUSDT|TRXUSDT|OPUSDT|ARBUSDT|ATOMUSDT|NEARUSDT|BCHUSDT|ETCUSDT|UNIBTC|UNIUSDT|XLMUSDT|XMRUSDT|EOSUSDT|FILUSDT|ICPUSDT|MKRUSDT|SUSHIUSDT|AAVEUSDT|GRTUSDT|DOGEBTC|BTCBUSD|ETHBUSD|USDTUSD|BUSDUSDT",
        flags = [jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE],
        message = "지원하지 않는 코인 심볼입니다.",
    )
    @field:NotEmpty(message = "symbol should not be empty") val symbol: String,
    @field:Positive(message = "orderId는 양수여야 합니다.") val orderId: Long,
)

@RestController
@RequestMapping("/api/v1/trading/orders")
class OrderController(
    private val orders: Orders,
) {
    @PostMapping("/market/buy")
    @ResponseStatus(HttpStatus.CREATED)
    fun buy(
        @Valid @RequestBody dto: MarketBuyRequest,
    ) = CommonResponse(
        200,
        "${dto.symbol} 시장가 매수 주문이 완료되었습니다.",
        orders.market(dto.symbol, "BUY", dto.usdtAmount, dto.accountId, dto.userId),
    )

    @PostMapping("/market/sell")
    @ResponseStatus(HttpStatus.CREATED)
    fun sell(
        @Valid @RequestBody dto: MarketSellRequest,
    ) = CommonResponse(200, "${dto.symbol} 시장가 매도 주문이 완료되었습니다.", orders.market(dto.symbol, "SELL", dto.quantity))

    @PostMapping("/limit/buy")
    @ResponseStatus(HttpStatus.CREATED)
    fun limitBuy(
        @Valid @RequestBody dto: LimitRequest,
    ) = CommonResponse(200, "${dto.symbol} 지정가 매수 주문이 완료되었습니다.", orders.limit(dto.symbol, "BUY", dto.quantity, dto.price))

    @PostMapping("/limit/sell")
    @ResponseStatus(HttpStatus.CREATED)
    fun limitSell(
        @Valid @RequestBody dto: LimitRequest,
    ) = CommonResponse(200, "${dto.symbol} 지정가 매도 주문이 완료되었습니다.", orders.limit(dto.symbol, "SELL", dto.quantity, dto.price))

    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    fun cancel(
        @Valid @RequestBody dto: CancelRequest,
    ) = CommonResponse(200, "주문이 취소되었습니다.", orders.cancel(dto.symbol, dto.orderId))

    @GetMapping("/balances")
    fun balances() = CommonResponse(200, "현재 잔고입니다.", orders.balances())
}

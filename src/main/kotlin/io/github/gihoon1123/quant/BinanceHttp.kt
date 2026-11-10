package io.github.gihoon1123.quant

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class BinanceHttp(
    private val environment: Environment,
    private val clock: Clock,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val mapper = JsonMapper.builder().build()

    fun number(value: Double): String =
        java.math.BigDecimal
            .valueOf(value)
            .stripTrailingZeros()
            .toPlainString()

    fun signed(
        method: String,
        path: String,
        parameters: List<Pair<String, String>>,
        futures: Boolean = false,
        form: Boolean = false,
        fallback: String,
        symbol: String? = null,
    ): JsonNode {
        val base =
            environment.getProperty(if (futures) "BINANCE_FUTURES_BASE_URL" else "BINANCE_SPOT_BASE_URL")
                ?: if (futures) "https://fapi.binance.com" else "https://api.binance.com"
        val endpoint = URI.create(base)
        if (!environment.getProperty("TRADING_ENABLED", Boolean::class.java, false) &&
            endpoint.host !in setOf("localhost", "127.0.0.1", "::1")
        ) {
            throw ApiException(503, "거래 호출이 비활성화되어 있습니다.")
        }
        val key = environment.getProperty("BINANCE_API_KEY") ?: throw ApiException(503, "BINANCE_API_KEY가 필요합니다.")
        val secret = environment.getProperty("BINANCE_API_SECRET") ?: throw ApiException(503, "BINANCE_API_SECRET가 필요합니다.")
        val query = encode(parameters)
        val signed = "$query&signature=${signature(query, secret)}"
        val builder =
            HttpRequest
                .newBuilder(URI.create(base + path + if (form) "" else "?$signed"))
                .timeout(Duration.ofSeconds(10))
                .header("X-MBX-APIKEY", key)
        if (form) builder.header("Content-Type", "application/x-www-form-urlencoded")
        builder.method(method, if (form) HttpRequest.BodyPublishers.ofString(signed) else HttpRequest.BodyPublishers.noBody())
        return execute(builder.build(), fallback, symbol)
    }

    fun public(
        path: String,
        parameters: List<Pair<String, String>>,
        futures: Boolean = false,
    ): JsonNode {
        val base =
            environment.getProperty(if (futures) "BINANCE_FUTURES_BASE_URL" else "BINANCE_SPOT_BASE_URL")
                ?: if (futures) "https://fapi.binance.com" else "https://api.binance.com"
        val request =
            HttpRequest
                .newBuilder(URI.create("$base$path?${encode(parameters)}"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        return execute(request, "Binance 데이터 조회 실패", null)
    }

    private fun execute(
        request: HttpRequest,
        fallback: String,
        symbol: String?,
    ): JsonNode {
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                throw ApiException(500, fallback)
            }
        val body = runCatching { mapper.readTree(response.body()) }.getOrElse { throw ApiException(500, fallback) }
        if (response.statusCode() in 200..299) return body
        val message = body["msg"]?.asString() ?: ""
        when (body["code"]?.asInt()) {
            -1013 -> {
                if (listOf("LOT_SIZE", "stepSize", "not a valid quantity").any { message.contains(it) }) {
                    val step = mapOf("BTCUSDT" to "0.00001", "ETHUSDT" to "0.0001", "SOLUSDT" to "0.01")[symbol?.uppercase()]
                    throw ApiException(400, "주문 수량이 거래 단위(stepSize)의 배수가 아닙니다.${step?.let { " ($it 단위로만 주문 가능합니다)" } ?: ""} ($message)")
                }
                throw ApiException(400, "주문 수량 또는 가격 조건이 Binance의 거래 조건을 만족하지 않습니다. ($message)")
            }
            -2010 -> throw ApiException(400, "거래 요청이 실패했습니다. ($message)")
            -2011 -> throw ApiException(404, "해당 주문을 찾을 수 없습니다. ($message)")
            -2015 -> throw ApiException(401, "API 키 또는 권한 오류입니다. ($message)")
        }
        if (response.statusCode() == 401) throw ApiException(401, "인증 실패: $message")
        if (response.statusCode() == 429) throw ApiException(429, "Binance 요청 제한을 초과했습니다.")
        throw ApiException(500, fallback)
    }

    fun timestamp(): Pair<String, String> = "timestamp" to clock.millis().toString()

    companion object {
        fun encode(parameters: List<Pair<String, String>>): String =
            parameters.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }

        fun signature(
            query: String,
            secret: String,
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(query.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}

@Component
class BinanceSpot(
    private val http: BinanceHttp,
) {
    fun market(
        symbol: String,
        side: String,
        amount: Double,
    ): JsonNode =
        http.signed(
            "POST",
            "/api/v3/order",
            listOf(
                "symbol" to symbol,
                "side" to side,
                "type" to "MARKET",
                http.timestamp(),
                (if (side == "BUY") "quoteOrderQty" else "quantity") to http.number(amount),
            ),
            fallback = "시장가 주문에 실패했습니다. 나중에 다시 시도해주세요.",
            symbol = symbol,
        )

    fun limit(
        symbol: String,
        side: String,
        quantity: Double,
        price: Double,
    ): JsonNode =
        http.signed(
            "POST",
            "/api/v3/order",
            listOf(
                "symbol" to symbol,
                "side" to side,
                "type" to "LIMIT",
                "timeInForce" to "GTC",
                "quantity" to http.number(quantity),
                "price" to http.number(price),
                http.timestamp(),
            ),
            fallback = "지정가 주문에 실패했습니다. 나중에 다시 시도해주세요.",
            symbol = symbol,
        )

    fun cancel(
        symbol: String,
        orderId: Long,
    ): JsonNode =
        http.signed(
            "DELETE",
            "/api/v3/order",
            listOf("symbol" to symbol, "orderId" to orderId.toString(), http.timestamp()),
            fallback = "주문 취소에 실패했습니다. 나중에 다시 시도해주세요.",
            symbol = symbol,
        )

    fun balances(): List<Balance> {
        val balances =
            http.signed(
                "GET",
                "/api/v3/account",
                listOf(http.timestamp()),
                fallback = "잔고 조회에 실패했습니다. 나중에 다시 시도해주세요.",
            )["balances"]
        return (0 until balances.size())
            .map { index ->
                val item = balances[index]
                Balance(item["asset"].asString(), item["free"].asString().toDouble(), item["locked"].asString().toDouble())
            }.filter { it.free > 0 || it.locked > 0 }
    }
}

data class Balance(
    val asset: String,
    val free: Double,
    val locked: Double,
)

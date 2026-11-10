package io.github.gihoon1123.quant

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

@Component
class BinanceFutures(
    private val http: BinanceHttp,
) {
    private fun post(
        path: String,
        params: List<Pair<String, String>>,
        fallback: String,
    ): JsonNode =
        http.signed(
            "POST",
            path,
            params + http.timestamp(),
            futures = true,
            form = true,
            fallback = fallback,
        )

    fun open(
        symbol: String,
        side: String,
        quantity: Double,
    ): JsonNode =
        post(
            "/fapi/v1/order",
            listOf("symbol" to symbol, "side" to side, "type" to "MARKET", "quantity" to http.number(quantity)),
            "선물 포지션 진입",
        )

    fun close(
        symbol: String,
        currentSide: String,
        quantity: Double?,
    ): JsonNode {
        val params =
            listOf("symbol" to symbol, "side" to if (currentSide == "LONG") "SELL" else "BUY", "type" to "MARKET", http.timestamp()) +
                if (quantity != null && quantity != 0.0) listOf("quantity" to http.number(quantity)) else listOf("reduceOnly" to "true")
        return http.signed("POST", "/fapi/v1/order", params, futures = true, form = true, fallback = "선물 포지션 청산")
    }

    fun switch(
        symbol: String,
        currentSide: String,
        currentQuantity: Double,
        newQuantity: Double,
    ): JsonNode =
        post(
            "/fapi/v1/order",
            listOf(
                "symbol" to symbol,
                "side" to
                    if (currentSide ==
                        "LONG"
                    ) {
                        "SELL"
                    } else {
                        "BUY"
                    },
                "type" to "MARKET",
                "quantity" to http.number(currentQuantity + newQuantity),
            ),
            "포지션 스위칭",
        )

    fun add(
        symbol: String,
        currentSide: String,
        quantity: Double,
    ): JsonNode =
        open(
            symbol,
            if (currentSide ==
                "LONG"
            ) {
                "BUY"
            } else {
                "SELL"
            },
            quantity,
        )

    fun reduce(
        symbol: String,
        currentSide: String,
        quantity: Double,
    ): JsonNode =
        post(
            "/fapi/v1/order",
            listOf(
                "symbol" to symbol,
                "side" to
                    if (currentSide ==
                        "LONG"
                    ) {
                        "SELL"
                    } else {
                        "BUY"
                    },
                "type" to "MARKET",
                "quantity" to http.number(quantity),
                "reduceOnly" to "true",
            ),
            "포지션 부분 청산",
        )

    fun leverage(
        symbol: String,
        leverage: Int,
    ): JsonNode =
        post(
            "/fapi/v1/leverage",
            listOf(
                "symbol" to symbol,
                "leverage" to leverage.toString(),
            ),
            "레버리지 설정",
        )

    fun margin(
        symbol: String,
        marginType: String,
    ): JsonNode =
        post(
            "/fapi/v1/marginType",
            listOf(
                "symbol" to symbol,
                "marginType" to marginType,
            ),
            "마진 모드 설정",
        )

    fun positions(symbol: String? = null): JsonNode =
        http.signed(
            "GET",
            "/fapi/v2/positionRisk",
            listOf(http.timestamp()) +
                if (symbol ==
                    null
                ) {
                    emptyList()
                } else {
                    listOf("symbol" to symbol)
                },
            futures = true,
            fallback = "포지션 정보 조회",
        )

    fun balances(): JsonNode = http.signed("GET", "/fapi/v2/balance", listOf(http.timestamp()), futures = true, fallback = "선물 잔고 조회")

    fun transfer(
        asset: String,
        amount: Double,
        from: String,
        to: String,
    ): JsonNode =
        http.signed(
            "POST",
            "/sapi/v1/futures/transfer",
            listOf(
                "asset" to asset,
                "amount" to http.number(amount),
                "type" to if (from == "SPOT") "1" else "2",
                http.timestamp(),
            ),
            fallback = "자금 이체 ($from → $to)",
            symbol = "$asset ${http.number(amount)}",
        )
}

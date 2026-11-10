package io.github.gihoon1123.quant

import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

@Repository
class TradeRepository(
    private val database: CandleRepository,
    private val clock: Clock,
) {
    private val mapper = JsonMapper.builder().build()
    private val baseKeys =
        listOf(
            "accountId",
            "userId",
            "symbol",
            "orderId",
            "clientOrderId",
            "side",
            "type",
            "quantity",
            "price",
            "totalAmount",
            "fee",
            "feeAsset",
            "feeRate",
            "status",
            "source",
            "executedAt",
            "metadata",
        )
    private val futuresKeys =
        listOf(
            "leverage",
            "marginType",
            "initialMargin",
            "maintenanceMargin",
            "positionSide",
            "liquidationPrice",
            "markPrice",
            "marginRatio",
            "isClosed",
            "isLiquidated",
        )
    private val numeric =
        setOf(
            "quantity",
            "price",
            "totalAmount",
            "fee",
            "feeRate",
            "netAmount",
            "leverage",
            "initialMargin",
            "maintenanceMargin",
            "liquidationPrice",
            "markPrice",
            "marginRatio",
        )

    fun save(
        event: Map<String, Any?>,
        futures: Boolean,
    ): Long {
        val values = baseKeys.associateWith { event[it] }.toMutableMap()
        values["accountId"] = event["accountId"]?.toString()?.takeIf { it.isNotEmpty() }
        values["userId"] = event["userId"]?.toString()?.takeIf { it.isNotEmpty() }
        values["clientOrderId"] = event["clientOrderId"] ?: ""
        values["feeRate"] = event["feeRate"] ?: 0
        if (futures) {
            @Suppress("UNCHECKED_CAST")
            val metadata = event["metadata"] as? Map<String, Any?> ?: emptyMap()
            futuresKeys.forEach { key ->
                values[key] =
                    when (key) {
                        "marginType", "positionSide" -> metadata[key] ?: ""
                        "isClosed", "isLiquidated" -> false
                        else -> metadata[key] ?: 0
                    }
            }
        } else {
            values["netAmount"] = (event["totalAmount"] as Number).toDouble() - (event["fee"] as Number).toDouble()
        }
        val table = if (futures) "futures_trade_records" else "spot_trade_records"
        val keys = values.keys.toList()
        val sql = "INSERT INTO $table (${keys.joinToString(
            ",",
        ) { "\"$it\"" }}) VALUES (${keys.joinToString(",") { if (it == "metadata") "?::json" else "?" }}) RETURNING id"
        return database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                keys.forEachIndexed { index, key ->
                    val value = values[key]
                    when {
                        value == null -> statement.setObject(index + 1, null)
                        key == "metadata" -> statement.setString(index + 1, mapper.writeValueAsString(value))
                        key == "executedAt" ->
                            statement.setTimestamp(
                                index + 1,
                                Timestamp.from(if (value is Instant) value else Instant.parse(value.toString())),
                            )
                        key == "orderId" -> statement.setLong(index + 1, value.toString().toLong())
                        key in numeric -> statement.setBigDecimal(index + 1, BigDecimal(value.toString()))
                        else -> statement.setObject(index + 1, value)
                    }
                }
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        }
    }

    fun close(event: Map<String, Any?>) {
        database.connection().use { connection ->
            connection
                .prepareStatement(
                    "UPDATE futures_trade_records SET \"closeType\"=?,\"closePrice\"=?,\"closeQuantity\"=?,pnl=?,\"pnlPercent\"=?,roe=?,\"closeOrderId\"=?,\"closedAt\"=?,\"isClosed\"=true,\"isLiquidated\"=?,\"holdingDuration\"=floor(extract(epoch FROM (?::timestamp-\"executedAt\"))),\"updatedAt\"=now() WHERE id=(SELECT id FROM futures_trade_records WHERE \"orderId\"=? LIMIT 1)",
                ).use { statement ->
                    val fields = listOf("closeType", "closePrice", "closeQuantity", "pnl", "pnlPercent", "roe", "closeOrderId")
                    fields.forEachIndexed { index, key ->
                        when (key) {
                            "closeType" -> statement.setString(index + 1, event[key].toString())
                            "closeOrderId" -> statement.setLong(index + 1, event[key].toString().toLong())
                            else -> statement.setBigDecimal(index + 1, BigDecimal(event[key].toString()))
                        }
                    }
                    val now = Timestamp.from(clock.instant())
                    statement.setTimestamp(8, now)
                    statement.setBoolean(9, event["closeType"] == "LIQUIDATION")
                    statement.setTimestamp(10, now)
                    statement.setLong(11, event["originalOrderId"].toString().toLong())
                    check(statement.executeUpdate() > 0) { "포지션을 찾을 수 없습니다: ${event["originalOrderId"]}" }
                }
        }
    }
}

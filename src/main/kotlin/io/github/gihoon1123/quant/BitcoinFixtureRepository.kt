package io.github.gihoon1123.quant

import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import kotlin.random.Random

@Repository
class BitcoinFixtureRepository(
    private val database: CandleRepository,
    private val clock: Clock,
) {
    private val mapper = JsonMapper.builder().build()

    // The source explicitly creates dummy parser data; this does not query or parse a Bitcoin node.
    fun saveDummy(event: Map<String, Any?>): Map<String, Any?> {
        fun random() =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)

        fun address() = "bc1q${random()}"
        val quantity = (event["quantity"] as Number).toDouble()
        val values =
            linkedMapOf<String, Any?>(
                "accountId" to event["accountId"],
                "userId" to event["userId"],
                "txid" to "tx_${clock.millis()}_${random()}",
                "blockHeight" to 800000 + Random.nextInt(1000),
                "blockHash" to "000000000000000000${random()}",
                "confirmations" to 3,
                "timestamp" to clock.instant(),
                "size" to 250 + Random.nextInt(500),
                "vsize" to 200 + Random.nextInt(400),
                "weight" to 800 + Random.nextInt(1600),
                "fee" to 0.0001 + Random.nextDouble() * 0.0005,
                "feeRate" to 10 + Random.nextDouble() * 50,
                "purpose" to "exchange_deposit",
                "netAmount" to quantity,
                "isIncoming" to true,
                "isOutgoing" to false,
                "relatedExchange" to "binance",
                "tags" to listOf("exchange", "binance", event["side"].toString().lowercase()),
                "inputAddresses" to listOf(address()),
                "outputAddresses" to listOf(address()),
                "primaryInputAddress" to address(),
                "primaryOutputAddress" to address(),
                "inputs" to listOf(mapOf("address" to address(), "value" to quantity * 1.1)),
                "outputs" to listOf(mapOf("address" to address(), "value" to quantity)),
                "rawData" to mapOf("original_event" to event, "relatedOrderId" to event["orderId"]),
                "isParsed" to true,
                "parsedAt" to clock.instant(),
                "parsedBy" to "dummy-parser-v1",
            )
        val json = setOf("tags", "inputAddresses", "outputAddresses", "inputs", "outputs", "rawData")
        val keys = values.keys.toList()
        val sql = "INSERT INTO bitcoin_transactions (${keys.joinToString(",") { "\"$it\"" }}) VALUES (${keys.joinToString(",") {
            when {
                it in json -> "?::json"
                it == "purpose" -> "?::bitcoin_transactions_purpose_enum"
                else -> "?"
            }
        }}) RETURNING id,\"createdAt\",\"updatedAt\""
        database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                keys.forEachIndexed { index, key ->
                    val value = values[key]
                    when {
                        key in json -> statement.setString(index + 1, mapper.writeValueAsString(value))
                        value is java.time.Instant -> statement.setTimestamp(index + 1, Timestamp.from(value))
                        else -> statement.setObject(index + 1, value)
                    }
                }
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    values["id"] = rows.getLong(1)
                    values["createdAt"] = rows.getTimestamp(2).toInstant()
                    values["updatedAt"] = rows.getTimestamp(3).toInstant()
                }
            }
        }
        return values
    }
}

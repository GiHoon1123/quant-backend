package io.github.gihoon1123.quant

import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant

@Repository
class TradeQueries(
    private val database: CandleRepository,
) {
    enum class Model(
        val table: String,
    ) {
        SPOT("spot_trade_records"),
        FUTURES("futures_trade_records"),
        BITCOIN("bitcoin_transactions"),
    }

    data class Filter(
        val symbol: String? = null,
        val accountId: String? = null,
        val source: String? = null,
        val startDate: Instant? = null,
        val endDate: Instant? = null,
        val positionSide: String? = null,
        val isClosed: Boolean? = null,
        val limit: Int = 100,
        val offset: Int = 0,
    )

    private val mapper = JsonMapper.builder().build()

    private fun conditions(
        model: Model,
        filter: Filter,
    ): Pair<String, List<Any>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        for ((key, value) in listOf(
            "symbol" to filter.symbol,
            "accountId" to filter.accountId,
            "source" to filter.source,
            "positionSide" to filter.positionSide,
            "isClosed" to filter.isClosed,
        )) {
            if (value != null) {
                require(model != Model.BITCOIN || key == "accountId")
                require(key !in setOf("positionSide", "isClosed") || model == Model.FUTURES)
                clauses += "\"$key\"=?"
                args += value
            }
        }
        if (filter.startDate != null &&
            filter.endDate != null
        ) {
            clauses += "\"${if (model == Model.BITCOIN) "timestamp" else "executedAt"}\" BETWEEN ? AND ?"
            args +=
                Timestamp.from(filter.startDate)
            args += Timestamp.from(filter.endDate)
        }
        return (if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")) to args
    }

    fun history(
        model: Model,
        filter: Filter = Filter(),
    ): List<Map<String, Any?>> {
        require(filter.limit >= 0 && filter.offset >= 0)
        val (where, args) = conditions(model, filter)
        val date = if (model == Model.BITCOIN) "timestamp" else "executedAt"
        return query(
            "SELECT * FROM ${model.table}$where ORDER BY \"$date\" DESC LIMIT ? OFFSET ?",
            args + listOf(filter.limit, filter.offset),
        )
    }

    fun byId(
        model: Model,
        id: Long,
    ): Map<String, Any?>? = query("SELECT * FROM ${model.table} WHERE id=? LIMIT 1", listOf(id)).firstOrNull()

    fun byOrderId(
        model: Model,
        orderId: String,
    ): Map<String, Any?>? {
        require(model != Model.BITCOIN)
        return query("SELECT * FROM ${model.table} WHERE \"orderId\"=? LIMIT 1", listOf(orderId.toLong())).firstOrNull()
    }

    fun bitcoin(txid: String): Map<String, Any?>? =
        query("SELECT * FROM bitcoin_transactions WHERE txid=? LIMIT 1", listOf(txid)).firstOrNull()

    fun active(symbol: String? = null) = history(Model.FUTURES, Filter(symbol = symbol, isClosed = false, limit = Int.MAX_VALUE))

    fun closed(
        symbol: String? = null,
        limit: Int = 100,
    ) = history(Model.FUTURES, Filter(symbol = symbol, isClosed = true, limit = limit))

    fun statistics(
        model: Model,
        filter: Filter = Filter(),
    ): Map<String, Any?> {
        require(model != Model.BITCOIN)
        val (where, args) = conditions(model, filter)
        val fields =
            if (model == Model.SPOT) {
                listOf(
                    "COUNT(*) AS \"totalTrades\"",
                    "COALESCE(SUM(\"totalAmount\"),0) AS \"totalVolume\"",
                    "COALESCE(SUM(fee),0) AS \"totalFees\"",
                    "COUNT(*) FILTER(WHERE side='BUY') AS \"buyTrades\"",
                    "COUNT(*) FILTER(WHERE side='SELL') AS \"sellTrades\"",
                    "COALESCE(AVG(\"totalAmount\"),0) AS \"avgTradeSize\"",
                )
            } else {
                listOf(
                    "COUNT(*) AS \"totalTrades\"",
                    "COUNT(*) FILTER(WHERE NOT \"isClosed\") AS \"activeTrades\"",
                    "COUNT(*) FILTER(WHERE \"isClosed\") AS \"closedTrades\"",
                    "COALESCE(SUM(\"totalAmount\"),0) AS \"totalVolume\"",
                    "COALESCE(SUM(pnl),0) AS \"totalPnl\"",
                    "COALESCE(SUM(fee),0) AS \"totalFees\"",
                    "CASE WHEN COUNT(*) FILTER(WHERE \"isClosed\")>0 THEN COUNT(*) FILTER(WHERE pnl>0)*100.0/COUNT(*) FILTER(WHERE \"isClosed\") ELSE 0 END AS \"winRate\"",
                    "COALESCE(AVG(COALESCE(\"holdingDuration\",0)),0) AS \"avgHoldingTime\"",
                    "COUNT(*) FILTER(WHERE \"isLiquidated\") AS \"liquidationCount\"",
                )
            }
        // TypeORM's original raw aliases are unquoted; PostgreSQL folds them to lowercase.
        // Preserve its current camel-case lookup behavior instead of silently fixing the exported statistics.
        val originalFields = fields.map { it.replace(Regex("AS \"([A-Za-z]+)\""), "AS $1") }
        val row = query("SELECT ${originalFields.joinToString(",")} FROM ${model.table}$where", args).single()
        val keys =
            if (model ==
                Model.SPOT
            ) {
                listOf("totalTrades", "totalVolume", "totalFees", "buyTrades", "sellTrades", "avgTradeSize")
            } else {
                listOf(
                    "totalTrades",
                    "activeTrades",
                    "closedTrades",
                    "totalVolume",
                    "totalPnl",
                    "totalFees",
                    "winRate",
                    "avgHoldingTime",
                    "liquidationCount",
                )
            }
        return keys.associateWith { key -> row[key]?.toString()?.toDoubleOrNull() ?: 0.0 }
    }

    private fun query(
        sql: String,
        args: List<Any>,
    ): List<Map<String, Any?>> =
        database.connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                (1..rows.metaData.columnCount).associate { index ->
                                    val value = rows.getObject(index)
                                    rows.metaData.getColumnName(index) to
                                        when {
                                            value is Timestamp -> value.toInstant()
                                            rows.metaData.getColumnTypeName(index) in setOf("numeric", "int8") -> rows.getString(index)
                                            rows.metaData.getColumnTypeName(
                                                index,
                                            ) in setOf("json", "jsonb") -> value?.toString()?.let(mapper::readTree)
                                            else -> value
                                        }
                                },
                            )
                        }
                    }
                }
            }
        }
}

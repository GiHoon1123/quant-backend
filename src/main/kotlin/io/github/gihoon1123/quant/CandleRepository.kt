package io.github.gihoon1123.quant

import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class CandleRepository {
    private val environment = System.getenv()

    fun connection(): Connection {
        val host = environment["DATABASE_HOST"] ?: "localhost"
        val port = environment["DATABASE_PORT"] ?: "5432"
        val database = environment["DATABASE_NAME"] ?: "quant_engine"
        val url = environment["DATABASE_URL"] ?: "jdbc:postgresql://$host:$port/$database"
        return DriverManager.getConnection(url, environment["DATABASE_USERNAME"] ?: "root", environment["DATABASE_PASSWORD"] ?: "1234")
    }

    fun save(
        symbol: String,
        market: String,
        candle: CandleData,
    ): Long {
        validate(symbol, market, candle)
        return connection().use { connection ->
            connection.autoCommit = false
            try {
                // Serialize same-candle upserts without adding a new database constraint.
                connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
                    it.setString(1, "$symbol:$market:${candle.openTime}")
                    it.execute()
                }
                val existing =
                    connection.prepareStatement("SELECT id FROM candle_15m WHERE symbol=? AND market=? AND \"openTime\"=?").use {
                        it.setString(1, symbol)
                        it.setString(2, market)
                        it.setTimestamp(3, Timestamp(candle.openTime))
                        it.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
                    }
                val columns =
                    listOf(
                        "openTime",
                        "closeTime",
                        "open",
                        "high",
                        "low",
                        "close",
                        "volume",
                        "quoteVolume",
                        "trades",
                        "takerBuyBaseVolume",
                        "takerBuyQuoteVolume",
                    )
                val columnNames = columns.joinToString(",") { "\"$it\"" }
                val placeholders = columns.joinToString(",") { "?" }
                val updates = columns.joinToString(",") { "\"$it\"=?" }
                val sql =
                    if (existing == null) {
                        "INSERT INTO candle_15m (symbol,market,timeframe,$columnNames) VALUES (?,?,'15m',$placeholders) RETURNING id"
                    } else {
                        "UPDATE candle_15m SET $updates,\"updatedAt\"=now() WHERE id=? RETURNING id"
                    }
                val id =
                    connection.prepareStatement(sql).use { statement ->
                        var index = 1
                        if (existing == null) {
                            statement.setString(index++, symbol)
                            statement.setString(index++, market)
                        }
                        statement.setTimestamp(index++, Timestamp(candle.openTime))
                        statement.setTimestamp(index++, Timestamp(candle.closeTime))
                        for (value in listOf(
                            candle.open,
                            candle.high,
                            candle.low,
                            candle.close,
                            candle.volume,
                            candle.quoteVolume,
                        )) {
                            statement.setBigDecimal(index++, BigDecimal.valueOf(value))
                        }
                        statement.setInt(index++, candle.trades)
                        statement.setBigDecimal(index++, BigDecimal.valueOf(candle.takerBuyBaseVolume))
                        statement.setBigDecimal(index++, BigDecimal.valueOf(candle.takerBuyQuoteVolume))
                        if (existing != null) statement.setLong(index, existing)
                        statement.executeQuery().use { result ->
                            check(result.next())
                            result.getLong(1)
                        }
                    }
                connection.commit()
                id
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    fun latest(
        symbol: String,
        market: String = "FUTURES",
        limit: Int = 100,
    ): List<CandleData> {
        require(limit >= 0)
        return connection().use { connection ->
            connection
                .prepareStatement(
                    "SELECT * FROM candle_15m WHERE symbol=? AND market=? AND timeframe='15m' ORDER BY \"openTime\" DESC LIMIT ?",
                ).use {
                    it.setString(1, symbol)
                    it.setString(2, market)
                    it.setInt(3, limit)
                    it.executeQuery().use { rows -> buildList { while (rows.next()) add(row(rows)) }.reversed() }
                }
        }
    }

    fun contains(
        symbol: String,
        openTime: Long,
    ): Boolean =
        connection().use { connection ->
            connection.prepareStatement("SELECT 1 FROM candle_15m WHERE symbol=? AND market='FUTURES' AND \"openTime\"=? LIMIT 1").use {
                it.setString(1, symbol)
                it.setTimestamp(2, Timestamp(openTime))
                it.executeQuery().use { rows -> rows.next() }
            }
        }

    fun bounds(symbol: String): Triple<Long, Long?, Long?> =
        connection().use { connection ->
            connection
                .prepareStatement(
                    "SELECT count(*) AS total,min(\"openTime\") AS first,max(\"openTime\") AS last FROM candle_15m WHERE symbol=? AND market='FUTURES' AND timeframe='15m'",
                ).use {
                    it.setString(1, symbol)
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        Triple(rows.getLong("total"), rows.getTimestamp("first")?.time, rows.getTimestamp("last")?.time)
                    }
                }
        }

    fun statistics(): Map<String, Any?> =
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT count(*) AS total,count(DISTINCT symbol) AS symbols,min(\"openTime\") AS oldest,max(\"openTime\") AS newest,avg(volume) AS average,max(volume) AS maximum,min(volume) AS minimum FROM candle_15m WHERE timeframe='15m'",
                    ).use {
                        check(it.next())
                        mapOf(
                            "totalCount" to it.getLong("total"),
                            "symbolCount" to it.getLong("symbols"),
                            "oldestTime" to it.getTimestamp("oldest")?.toInstant(),
                            "newestTime" to it.getTimestamp("newest")?.toInstant(),
                            "averageVolume" to it.getDouble("average"),
                            "maxVolume" to it.getDouble("maximum"),
                            "minVolume" to it.getDouble("minimum"),
                        )
                    }
            }
        }

    private fun row(result: ResultSet): CandleData =
        CandleData(
            result.getTimestamp("openTime").time,
            result.getTimestamp("closeTime").time,
            result.getDouble("open"),
            result.getDouble("high"),
            result.getDouble("low"),
            result.getDouble("close"),
            result.getDouble("volume"),
            result.getDouble("quoteVolume"),
            result.getInt("trades"),
            result.getDouble("takerBuyBaseVolume"),
            result.getDouble("takerBuyQuoteVolume"),
        )

    fun validate(
        symbol: String,
        market: String,
        candle: CandleData,
    ) {
        require(symbol.isNotBlank()) { "[Candle15M] 심볼은 필수 입력 항목입니다." }
        require(market in setOf("SPOT", "FUTURES"))
        require(listOf(candle.open, candle.high, candle.low, candle.close).all { it.isFinite() && it > 0 })
        require(candle.high >= maxOf(candle.open, candle.close) && candle.low <= minOf(candle.open, candle.close))
        require(candle.volume >= 0 && candle.quoteVolume >= 0 && candle.trades >= 0)
        require(candle.openTime < candle.closeTime)
    }
}

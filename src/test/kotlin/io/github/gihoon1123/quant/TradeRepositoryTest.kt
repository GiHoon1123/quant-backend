package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@EnabledIfEnvironmentVariable(named = "QUANT_DATABASE_TESTS", matches = "true")
class TradeRepositoryTest {
    private val database = CandleRepository()
    private val executed = Instant.parse("2026-11-10T00:00:00Z")
    private val repository = TradeRepository(database, Clock.fixed(executed.plusSeconds(65), ZoneOffset.UTC))
    private val event =
        mapOf<String, Any?>(
            "symbol" to "ETHUSDT",
            "orderId" to "42",
            "side" to "BUY",
            "type" to "MARKET",
            "quantity" to 2.5,
            "price" to 20.0,
            "totalAmount" to 50.0,
            "fee" to 0.05,
            "feeAsset" to "USDT",
            "status" to "FILLED",
            "source" to "API",
            "executedAt" to executed,
            "metadata" to mapOf("leverage" to 5, "positionSide" to "LONG", "marginType" to "ISOLATED"),
        )

    @Test
    fun `source dummy Bitcoin parser stores explicitly labelled fixture data`() {
        val result = BitcoinFixtureRepository(database, Clock.fixed(executed, ZoneOffset.UTC)).saveDummy(event + ("symbol" to "BTCUSDT"))
        assertEquals("dummy-parser-v1", result["parsedBy"])
        assertEquals("exchange_deposit", result["purpose"])
        org.junit.jupiter.api.Assertions
            .assertTrue(result["txid"].toString().startsWith("tx_"))
        database.connection().use { connection ->
            connection.prepareStatement("DELETE FROM bitcoin_transactions WHERE id=?").use {
                it.setLong(1, result["id"] as Long)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    @Test
    fun `original schema stores decimal spot and futures fields and closes positions`() {
        database.connection().use {
            it.createStatement().execute(
                "TRUNCATE spot_trade_records,futures_trade_records RESTART IDENTITY CASCADE",
            )
        }
        repository.save(event, false)
        repository.save(event, true)
        database.connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM spot_trade_records").use { rows ->
                    check(rows.next())
                    assertEquals("42", rows.getString("orderId"))
                    assertEquals("49.95000000", rows.getBigDecimal("netAmount").toPlainString())
                    assertEquals(null, rows.getString("accountId"))
                }
            }
        }
        repository.close(
            mapOf(
                "originalOrderId" to "42",
                "closeOrderId" to "43",
                "closeType" to "TP",
                "closePrice" to 22,
                "closeQuantity" to 2.5,
                "pnl" to 5,
                "pnlPercent" to 10,
                "roe" to 50,
            ),
        )
        database.connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM futures_trade_records").use { rows ->
                    check(rows.next())
                    assertEquals(true, rows.getBoolean("isClosed"))
                    assertEquals(false, rows.getBoolean("isLiquidated"))
                    assertEquals(65, rows.getInt("holdingDuration"))
                    assertEquals("5.00", rows.getBigDecimal("leverage").toPlainString())
                    assertEquals("LONG", rows.getString("positionSide"))
                }
            }
        }
    }
}

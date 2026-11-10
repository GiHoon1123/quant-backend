package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Clock
import java.time.Instant

@EnabledIfEnvironmentVariable(named = "QUANT_DATABASE_TESTS", matches = "true")
class TradeQueriesTest {
    @Test
    fun `filtered history retains original decimal strings and raw aggregate alias behavior`() {
        val database = CandleRepository()
        val repository = TradeRepository(database, Clock.systemUTC())
        val now = Instant.parse("2026-11-10T00:00:00Z")
        val event =
            mapOf(
                "symbol" to "FIXTUREUSDT",
                "orderId" to "9988001",
                "side" to "BUY",
                "type" to "MARKET",
                "quantity" to 2.0,
                "price" to 25.0,
                "totalAmount" to 50.0,
                "fee" to 0.1,
                "feeAsset" to "USDT",
                "source" to "QUERY_FIXTURE",
                "status" to "FILLED",
                "executedAt" to now,
            )
        val id = repository.save(event, false)
        try {
            val queries = TradeQueries(database)
            val filter =
                TradeQueries.Filter(
                    symbol = "FIXTUREUSDT",
                    source = "QUERY_FIXTURE",
                    startDate = now.minusSeconds(1),
                    endDate = now.plusSeconds(1),
                )
            val rows = queries.history(TradeQueries.Model.SPOT, filter)
            assertTrue(rows.isNotEmpty())
            assertEquals("50.00000000", rows.first()["totalAmount"])
            assertEquals("9988001", queries.byId(TradeQueries.Model.SPOT, id)?.get("orderId"))
            assertEquals(0.0, queries.statistics(TradeQueries.Model.SPOT, filter)["totalTrades"])
        } finally {
            database.connection().use { connection ->
                connection.prepareStatement("DELETE FROM spot_trade_records WHERE id=?").use {
                    it.setLong(1, id)
                    it.executeUpdate()
                }
            }
        }
    }
}

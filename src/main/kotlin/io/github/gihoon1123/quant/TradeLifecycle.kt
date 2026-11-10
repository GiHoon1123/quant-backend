package io.github.gihoon1123.quant

import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class TradeLifecycle(
    private val repository: TradeRepository,
    private val bitcoin: BitcoinFixtureRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @EventListener
    fun handle(event: MarketEvent) {
        when (event.name) {
            "trade.executed" -> executed(event.data)
            "trade.closed" -> closed(event.data)
        }
    }

    fun executed(event: Map<String, Any?>) {
        try {
            // The original saves every execution in both tables, without a trade-type discriminator.
            repository.save(event, false)
            repository.save(event, true)
            if (event["symbol"].toString().replace(Regex("(USDT|BTC|ETH|BNB)$"), "") == "BTC") {
                try {
                    val transaction = bitcoin.saveDummy(event)
                    events.publishEvent(MarketEvent("bitcoin.transaction.parsed", base() + ("transaction" to transaction)))
                } catch (error: Exception) {
                    events.publishEvent(MarketEvent("bitcoin.transaction.parse.failed", base() + ("error" to error.message)))
                }
            }
            events.publishEvent(
                MarketEvent(
                    "transaction.trade.saved",
                    event + base() +
                        mapOf(
                            "source" to "TransactionService",
                            "clientOrderId" to (event["clientOrderId"] ?: ""),
                            "metadata" to (event["metadata"] ?: emptyMap<String, Any>()),
                        ),
                ),
            )
        } catch (error: Exception) {
            events.publishEvent(
                MarketEvent(
                    "transaction.trade.save.failed",
                    base() + event.filterKeys { it in setOf("orderId", "symbol", "side") } + ("error" to error.message),
                ),
            )
        }
    }

    fun closed(event: Map<String, Any?>) {
        try {
            repository.close(event)
            events.publishEvent(
                MarketEvent(
                    "transaction.position.closed",
                    base() + event.filterKeys { it in setOf("closeOrderId", "symbol", "closeType") },
                ),
            )
        } catch (error: Exception) {
            events.publishEvent(
                MarketEvent(
                    "transaction.position.close.failed",
                    base() + event.filterKeys { it in setOf("closeOrderId", "symbol", "closeType") } + ("error" to error.message),
                ),
            )
        }
    }

    private fun base(): Map<String, Any> =
        mapOf(
            "eventId" to UUID.randomUUID().toString(),
            "timestamp" to clock.instant(),
            "service" to "TransactionService",
        )
}

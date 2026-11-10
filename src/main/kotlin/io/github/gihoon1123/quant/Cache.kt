package io.github.gihoon1123.quant

import org.springframework.stereotype.Service
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

@Service
class Cache(
    private val clock: Clock,
) {
    private data class Entry(
        val value: Any,
        val timestamp: Long,
        val ttl: Long,
    )

    private val values = ConcurrentHashMap<String, Entry>()

    fun set(
        key: String,
        value: Any,
        ttl: Long = 3600000,
    ) {
        values[key] = Entry(value, clock.millis(), ttl)
    }

    fun get(key: String): Any? {
        val entry = values[key] ?: return null
        if (clock.millis() - entry.timestamp > entry.ttl) {
            values.remove(key, entry)
            return null
        }
        return entry.value
    }

    fun delete(key: String) {
        values.remove(key)
    }

    fun has(key: String): Boolean = get(key) != null

    fun getByPattern(pattern: String): Map<String, Any> =
        values.keys
            .filter { it.contains(pattern) }
            .mapNotNull { key ->
                get(key)?.let {
                    key to
                        it
                }
            }.toMap()

    fun clear() {
        values.clear()
    }

    fun size(): Int = values.size

    fun cleanup() {
        values.keys.forEach(::get)
    }
}

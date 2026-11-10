package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CacheTest {
    private class MutableClock(
        var now: Long = 0,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    @Test
    fun `entry expires strictly after TTL and not at equality`() {
        val clock = MutableClock()
        val cache = Cache(clock)
        cache.set("atr:BTC", 42, 100)
        clock.now = 100
        assertEquals(42, cache.get("atr:BTC"))
        clock.now = 101
        assertNull(cache.get("atr:BTC"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `pattern is substring and expired entries are removed`() {
        val clock = MutableClock()
        val cache = Cache(clock)
        cache.set("config:active", false)
        cache.set("atr:BTC", 42, 1)
        clock.now = 2
        assertEquals(mapOf("config:active" to false), cache.getByPattern("config:"))
        assertTrue(cache.has("config:active"))
        assertTrue(cache.getByPattern("atr:").isEmpty())
        cache.delete("config:active")
        assertFalse(cache.has("config:active"))
    }
}

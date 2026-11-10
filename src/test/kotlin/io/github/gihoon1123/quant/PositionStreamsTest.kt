package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class PositionStreamsTest {
    @Test
    fun `reference counts retain streams until final close and trade frames throttle`() {
        val clock =
            object : Clock() {
                var millis = 1000L

                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = Instant.ofEpochMilli(millis)
            }
        val mapper = JsonMapper.builder().build()
        val streams = PositionStreams(MockEnvironment(), clock, mapper)
        try {
            streams.open("BTCUSDT")
            streams.open("BTCUSDT")
            assertEquals(2, streams.references("BTCUSDT"))
            assertEquals(100.0, streams.tick("BTCUSDT", mapper.readTree("""{"data":{"p":"100"}}""")))
            assertEquals(null, streams.tick("BTCUSDT", mapper.readTree("""{"p":"101"}""")))
            clock.millis += 300
            assertEquals(101.0, streams.tick("BTCUSDT", mapper.readTree("""{"price":"101"}""")))
            streams.close("BTCUSDT")
            assertEquals(1, streams.references("BTCUSDT"))
            streams.close("BTCUSDT")
            assertEquals(0, streams.references("BTCUSDT"))
            assertEquals(null, streams.tick("BTCUSDT", mapper.readTree("""{"p":"102"}""")))
        } finally {
            streams.shutdown()
        }
    }
}

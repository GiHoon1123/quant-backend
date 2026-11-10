package io.github.gihoon1123.quant

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress

class TelegramTest {
    @Test
    fun `notification payload reaches local fake server only and failures remain nonfatal`() {
        var body = ""
        var path = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            body = exchange.requestBody.readAllBytes().decodeToString()
            path = exchange.requestURI.path
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val environment =
                MockEnvironment()
                    .withProperty(
                        "TELEGRAM_BASE_URL",
                        "http://127.0.0.1:${server.address.port}",
                    ).withProperty("TELEGRAM_BOT_TOKEN", "fixture-token")
                    .withProperty("TELEGRAM_CHAT_ID", "fixture-chat")
            Telegram(environment).text("fixture message")
            val json = JsonMapper.builder().build().readTree(body)
            assertEquals("/botfixture-token/sendMessage", path)
            assertEquals("fixture-chat", json["chat_id"].asString())
            assertEquals("HTML", json["parse_mode"].asString())
            assertEquals(false, json["disable_web_page_preview"].asBoolean())
        } finally {
            server.stop(0)
        }
    }
}

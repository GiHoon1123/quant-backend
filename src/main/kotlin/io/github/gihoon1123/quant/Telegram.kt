package io.github.gihoon1123.quant

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class Telegram(
    private val environment: Environment,
) {
    private val client =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    private val mapper = JsonMapper.builder().build()

    fun text(message: String) {
        val token = environment.getProperty("TELEGRAM_BOT_TOKEN")?.takeIf { it.isNotBlank() } ?: return
        val chat = environment.getProperty("TELEGRAM_CHAT_ID")?.takeIf { it.isNotBlank() } ?: return
        val base = environment.getProperty("TELEGRAM_BASE_URL") ?: "https://api.telegram.org"
        val host = URI.create(base).host
        if (!environment.getProperty("NOTIFICATIONS_ENABLED", Boolean::class.java, false) &&
            host !in setOf("127.0.0.1", "localhost", "::1")
        ) {
            return
        }
        val payload =
            mapper.writeValueAsString(
                mapOf(
                    "chat_id" to chat,
                    "text" to message,
                    "parse_mode" to "HTML",
                    "disable_web_page_preview" to false,
                ),
            )
        val request =
            HttpRequest
                .newBuilder(
                    URI.create("$base/bot$token/sendMessage"),
                ).timeout(
                    Duration.ofSeconds(10),
                ).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
        // Like the source, delivery failures do not propagate to the trading/analysis caller.
        runCatching { client.send(request, HttpResponse.BodyHandlers.discarding()) }
    }
}

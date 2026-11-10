package io.github.gihoon1123.quant

import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.NumberFormat
import java.time.Clock
import java.time.Duration
import java.util.Locale

@Service
class ExchangeRate(
    private val environment: Environment,
) {
    private val sources =
        listOf(
            "ExchangeRate-API" to "https://api.exchangerate-api.com/v4/latest/USD",
            "Fixer.io" to "http://data.fixer.io/api/latest?access_key=free&base=USD&symbols=KRW",
            "Open Exchange Rates" to "https://open.er-api.com/v6/latest/USD",
        )
    private val client =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    private val mapper = JsonMapper.builder().build()

    fun rate(): Double {
        val urls = environment.getProperty("EXCHANGE_RATE_URLS")?.split('|') ?: sources.map { it.second }
        for (url in urls) {
            val result =
                runCatching {
                    val request =
                        HttpRequest
                            .newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() !=
                        200
                    ) {
                        null
                    } else {
                        mapper
                            .readTree(response.body())["rates"]
                            ?.get("KRW")
                            ?.takeIf { it.isNumber }
                            ?.asDouble()
                    }
                }.getOrNull()
            if (result != null && result > 0) return result
        }
        return 1330.0
    }

    fun status(): Map<String, Any> = mapOf("defaultRate" to 1330, "apiSources" to sources.map { it.first })
}

@RestController
@RequestMapping("/api/v1/exchange-rate")
class ExchangeRateController(
    private val service: ExchangeRate,
    private val clock: Clock,
) {
    private fun format(value: Double) = "₩${NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 3 }.format(value)}"

    private fun rate(message: String): CommonResponse<Map<String, Any>> {
        val value = service.rate()
        return CommonResponse(200, message, mapOf("rate" to value, "formattedRate" to format(value), "timestamp" to clock.instant()))
    }

    @GetMapping("/usd-krw")
    fun rate() = rate("환율 조회 성공")

    @GetMapping("/force-refresh")
    fun refresh() = rate("환율 실시간 재조회 성공")

    @GetMapping("/service-status")
    fun status() = CommonResponse(200, "서비스 상태 조회 성공", service.status() + ("formattedDefaultRate" to format(1330.0)))
}

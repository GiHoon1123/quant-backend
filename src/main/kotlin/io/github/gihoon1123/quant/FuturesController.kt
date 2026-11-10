package io.github.gihoon1123.quant

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.Locale

data class OpenRequest(
    @field:NotEmpty val symbol: String,
    @field:Pattern(regexp = "LONG|SHORT") val side: String,
    @field:DecimalMin("0.001") val quantity: Double,
    @field:Min(value = 1, message = "레버리지는 최소 1배입니다.") @field:Max(value = 125, message = "레버리지는 최대 125배입니다.") val leverage: Int,
    val accountId: String? = null,
    val userId: String? = null,
    val stopLoss: ExitConfig? = null,
    val takeProfit: ExitConfig? = null,
)

data class CloseRequest(
    @field:NotEmpty val symbol: String,
    val quantity: Double? = null,
)

data class LeverageRequest(
    @field:NotEmpty val symbol: String,
    @field:Min(value = 1, message = "레버리지는 최소 1배입니다.") @field:Max(value = 125, message = "레버리지는 최대 125배입니다.") val leverage: Int,
)

data class MarginRequest(
    @field:NotEmpty val symbol: String,
    @field:Pattern(regexp = "ISOLATED|CROSSED") val marginType: String,
)

data class TransferRequest(
    @field:NotEmpty val asset: String,
    @field:DecimalMin(value = "0.1", message = "최소 이체 금액은 0.1입니다.") val amount: Double,
    @field:Pattern(regexp = "SPOT|FUTURES") val fromAccountType: String,
    @field:Pattern(regexp = "SPOT|FUTURES") val toAccountType: String,
)

data class SwitchRequest(
    @field:NotEmpty val symbol: String,
    @field:Pattern(regexp = "LONG|SHORT") val newSide: String,
    @field:DecimalMin("0.001") val newQuantity: Double,
)

data class AddRequest(
    @field:NotEmpty val symbol: String,
    @field:DecimalMin("0.001") val addQuantity: Double,
)

data class ReduceRequest(
    @field:NotEmpty val symbol: String,
    @field:DecimalMin(value = "0.0001", message = "최소 청산 수량은 0.0001입니다.") val reduceQuantity: Double,
)

data class CloseAllRequest(
    @field:NotEmpty val symbol: String,
    val memo: String? = null,
)

@RestController
@RequestMapping("/api/v1/trading/positions")
class FuturesController(
    private val futures: Futures,
) {
    @PostMapping("/position/open")
    @ResponseStatus(HttpStatus.CREATED)
    fun open(
        @Valid @RequestBody dto: OpenRequest,
    ) = CommonResponse(
        200,
        "${dto.symbol} ${dto.side} 포지션 진입이 완료되었습니다.",
        futures.open(dto.symbol, dto.side, dto.quantity, dto.leverage, dto.accountId, dto.userId),
    )

    @PostMapping("/position/close")
    @ResponseStatus(HttpStatus.CREATED)
    fun close(
        @Valid @RequestBody dto: CloseRequest,
    ) = CommonResponse(
        200,
        "${dto.symbol} 포지션 ${if (dto.quantity != null && dto.quantity != 0.0) "부분 청산" else "전체 청산"}이 완료되었습니다.",
        futures.close(dto.symbol, dto.quantity),
    )

    @PostMapping("/leverage")
    @ResponseStatus(HttpStatus.CREATED)
    fun leverage(
        @Valid @RequestBody dto: LeverageRequest,
    ) = CommonResponse(200, "${dto.symbol} 레버리지가 ${dto.leverage}배로 설정되었습니다.", futures.leverage(dto.symbol, dto.leverage))

    @PostMapping("/margin-type")
    @ResponseStatus(HttpStatus.CREATED)
    fun margin(
        @Valid @RequestBody dto: MarginRequest,
    ) = CommonResponse(
        200,
        "${dto.symbol} 마진 모드가 ${if (dto.marginType == "ISOLATED") "격리마진" else "교차마진"}으로 설정되었습니다.",
        futures.margin(dto.symbol, dto.marginType),
    )

    @GetMapping("/positions")
    fun positions(
        @RequestParam(required = false) symbol: String?,
    ) = CommonResponse(
        200,
        if (symbol !=
            null
        ) {
            "$symbol 포지션 정보입니다."
        } else {
            "현재 포지션 정보입니다."
        },
        futures.positions(symbol),
    )

    @GetMapping("/balances")
    fun balances() = CommonResponse(200, "선물 계정 잔고 정보입니다.", futures.balances())

    @GetMapping("/positions/high-risk")
    fun highRisk(
        @RequestParam(required = false) riskThreshold: Double?,
    ): CommonResponse<List<Map<String, Any>>> {
        val threshold = riskThreshold?.takeIf { it != 0.0 } ?: 0.8
        return CommonResponse(
            200,
            "청산 위험이 높은 포지션들입니다. (임계값: ${String.format(Locale.US, "%.0f", threshold * 100)}%)",
            futures.positions(risk = threshold),
        )
    }

    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    fun transfer(
        @Valid @RequestBody dto: TransferRequest,
    ) = CommonResponse(
        200,
        "${dto.amount} ${dto.asset}가 ${if (dto.fromAccountType == "SPOT") "현물 계좌" else "선물 계좌"}에서 ${if (dto.toAccountType == "SPOT") "현물 계좌" else "선물 계좌"}로 이체되었습니다.",
        futures.transfer(dto.asset, dto.amount, dto.fromAccountType, dto.toAccountType),
    )

    @PostMapping("/position/switch")
    @ResponseStatus(HttpStatus.CREATED)
    fun switch(
        @Valid @RequestBody dto: SwitchRequest,
    ) = CommonResponse(200, "${dto.symbol} 포지션이 ${dto.newSide}로 성공적으로 전환되었습니다.", futures.switch(dto.symbol, dto.newSide, dto.newQuantity))

    @PostMapping("/position/add")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @Valid @RequestBody dto: AddRequest,
    ) = CommonResponse(200, "${dto.symbol} 포지션에 ${dto.addQuantity} 수량이 추가되었습니다.", futures.adjust(dto.symbol, dto.addQuantity, false))

    @PostMapping("/position/reduce")
    @ResponseStatus(HttpStatus.CREATED)
    fun reduce(
        @Valid @RequestBody dto: ReduceRequest,
    ) = CommonResponse(200, "${dto.symbol} 포지션에서 ${dto.reduceQuantity} 수량이 청산되었습니다.", futures.adjust(dto.symbol, dto.reduceQuantity, true))

    @PostMapping("/position/close-all")
    @ResponseStatus(HttpStatus.CREATED)
    fun closeAll(
        @Valid @RequestBody dto: CloseAllRequest,
    ) = CommonResponse(200, "${dto.symbol} 포지션 전체 청산이 완료되었습니다.", futures.closeAll(dto.symbol, dto.memo))
}

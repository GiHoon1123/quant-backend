package io.github.gihoon1123.quant

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

class ApiException(
    val status: Int,
    override val message: String,
) : RuntimeException(message)

data class CommonResponse<T>(
    val status: Int,
    val message: String,
    val data: T?,
)

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException): ResponseEntity<CommonResponse<Nothing>> =
        ResponseEntity.status(error.status).body(CommonResponse(error.status, error.message, null))

    @ExceptionHandler(ResponseStatusException::class)
    fun status(error: ResponseStatusException): ResponseEntity<CommonResponse<Nothing>> =
        ResponseEntity.status(error.statusCode).body(CommonResponse(error.statusCode.value(), error.reason ?: "서버 오류가 발생했습니다.", null))

    @ExceptionHandler(Exception::class)
    fun unexpected(): ResponseEntity<CommonResponse<Nothing>> = ResponseEntity.status(500).body(CommonResponse(500, "서버 오류가 발생했습니다.", null))
}

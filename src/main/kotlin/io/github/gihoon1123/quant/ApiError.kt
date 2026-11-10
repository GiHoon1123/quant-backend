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

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException::class)
    fun validation(error: org.springframework.web.bind.MethodArgumentNotValidException): ResponseEntity<CommonResponse<Nothing>> =
        ResponseEntity.badRequest().body(
            CommonResponse(
                400,
                error.bindingResult.fieldErrors
                    .firstOrNull()
                    ?.defaultMessage ?: "Bad Request",
                null,
            ),
        )

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<CommonResponse<Nothing>> = ResponseEntity.badRequest().body(CommonResponse(400, "Bad Request", null))

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException::class)
    fun missing(error: org.springframework.web.servlet.resource.NoResourceFoundException): ResponseEntity<CommonResponse<Nothing>> =
        ResponseEntity.status(404).body(CommonResponse(404, "Cannot ${error.httpMethod} /${error.resourcePath}", null))

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception? = null): ResponseEntity<CommonResponse<Nothing>> {
        org.slf4j.LoggerFactory
            .getLogger(ApiErrors::class.java)
            .error("Unexpected API exception", error)
        return ResponseEntity.status(500).body(CommonResponse(500, "서버 오류가 발생했습니다.", null))
    }
}

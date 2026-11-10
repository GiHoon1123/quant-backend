package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApiErrorsTest {
    @Test
    fun `business errors retain status message and null data`() {
        val result = ApiErrors().api(ApiException(429, "limited"))
        assertEquals(429, result.statusCode.value())
        assertEquals(CommonResponse<Nothing>(429, "limited", null), result.body)
    }

    @Test
    fun `unexpected errors use original Korean response`() {
        assertEquals(CommonResponse<Nothing>(500, "서버 오류가 발생했습니다.", null), ApiErrors().unexpected().body)
    }
}

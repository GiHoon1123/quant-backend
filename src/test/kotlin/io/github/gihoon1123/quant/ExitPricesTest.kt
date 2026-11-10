package io.github.gihoon1123.quant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ExitPricesTest {
    private val calculator = ExitPriceCalculator()

    @Test
    fun `long and short reverse percent exit direction`() {
        val stop = ExitConfig(ExitType.PERCENT, 0.02)
        val take = ExitConfig(ExitType.PERCENT, 0.04)
        assertEquals(ExitPrices(49000.0, 52000.0), calculator.futures(50000.0, PositionSide.LONG, stop, take))
        assertEquals(ExitPrices(51000.0, 48000.0), calculator.futures(50000.0, PositionSide.SHORT, stop, take))
    }

    @Test
    fun `none and zero return null and absolute prices are unchanged`() {
        assertEquals(ExitPrices(null, 40000.0), calculator.spot(50000.0, ExitConfig(ExitType.NONE), ExitConfig(ExitType.PRICE, 40000.0)))
        assertEquals(ExitPrices(null, null), calculator.spot(100.0, ExitConfig(ExitType.PRICE, 0.0), ExitConfig(ExitType.NONE)))
    }

    @Test
    fun `percent at one and nonpositive values are invalid`() {
        val result = calculator.validate(ExitConfig(ExitType.PERCENT, 1.0), ExitConfig(ExitType.PRICE, -2.0))
        assertFalse(result.isValid)
        assertEquals(listOf("손절 퍼센트는 100% 미만이어야 합니다.", "익절 값은 0보다 커야 합니다."), result.errors)
    }
}

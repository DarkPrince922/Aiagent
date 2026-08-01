package app.jarvis.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionParserTest {
    @Test fun respectsPrecedenceAndParentheses() {
        assertEquals(14.0, ExpressionParser("2 + 3 * 4").parse(), 0.0)
        assertEquals(20.0, ExpressionParser("(2 + 3) * 4").parse(), 0.0)
    }

    @Test fun supportsUnaryAndModulo() {
        assertEquals(-1.0, ExpressionParser("-11 % 5").parse(), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTrailingInput() {
        ExpressionParser("2 + nope").parse()
    }
}

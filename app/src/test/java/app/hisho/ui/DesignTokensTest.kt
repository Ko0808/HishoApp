package app.hisho.ui

import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokensTest {
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 255) / 255.0
            return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    private fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    @Test fun textMeetsAAOnContentSurfaces() {
        for (text in listOf(DesignTokens.INK, DesignTokens.MUTED, DesignTokens.ACCENT))
            for (surface in listOf(0xFFFFFFFF.toInt(), 0xFFF5F6F8.toInt(), 0xFFE3EDFF.toInt()))
                assertTrue("Text contrast below 4.5", contrast(text, surface) >= 4.5)
    }
    @Test fun primaryWhiteLabelMeetsAA() {
        for (surface in listOf(DesignTokens.PRIMARY_START, DesignTokens.PRIMARY_END))
            assertTrue("Button contrast below 4.5", contrast(0xFFFFFFFF.toInt(), surface) >= 4.5)
    }
}

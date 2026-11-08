package com.dd3boh.xenowave.ui.theme

import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import com.dd3boh.xenowave.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OrbitronFontTest {
    @Test
    fun `approved orbitron medium loads and covers the wordmark glyphs`() {
        val context = RuntimeEnvironment.getApplication()
        val typeface = ResourcesCompat.getFont(context, R.font.orbitron_medium)
        assertNotNull(typeface)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 48f
        }
        assertTrue(paint.measureText("XENOWAVE") > 0f)
        listOf("X", "E", "N", "O", "W", "A", "V", "0", "9").forEach { glyph ->
            assertTrue("missing glyph $glyph", paint.hasGlyph(glyph))
        }
    }
}

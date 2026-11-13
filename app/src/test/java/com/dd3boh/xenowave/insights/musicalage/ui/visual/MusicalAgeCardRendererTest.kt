package com.dd3boh.xenowave.insights.musicalage.ui.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.Configuration
import com.dd3boh.xenowave.insights.musicalage.MusicalGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MusicalAgeCardRendererTest {
    @Test
    fun `vertical and square cards are rendered from synthetic data`() {
        val context = RuntimeEnvironment.getApplication()
        val outputDirectory = File(
            System.getProperty("user.dir"),
            "build/musical-age-previews",
        ).apply { mkdirs() }

        val cases = listOf(
            MusicalGeneration.GENERATION_Z to 20,
            MusicalGeneration.MILLENNIAL to 44,
            MusicalGeneration.CLASSICS to 194,
        )

        cases.forEach { (generation, age) ->
            val result = MusicalAgeTestFixtures.readyResult(generation, age)
            MusicalAgeCardFormat.entries.forEach { format ->
                val bitmap = MusicalAgeCardRenderer.render(context, result, format)
                try {
                    assertEquals(format.width, bitmap.width)
                    assertEquals(format.height, bitmap.height)
                    assertTrue("blank card for $generation/$format", hasVariation(bitmap))

                    val qrRegion = qrRegion(format)
                    val dark = countDarkPixels(bitmap, qrRegion)
                    assertTrue(
                        "QR area too empty for $generation/$format: $dark",
                        dark > 500,
                    )

                    markAsSyntheticExample(bitmap, format)
                    val file = File(
                        outputDirectory,
                        "preview-${generation.name.lowercase()}-${format.suffix}.png",
                    )
                    FileOutputStream(file).use { output ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                    assertTrue("PNG too small for $file", file.length() > 20_000)
                } finally {
                    bitmap.recycle()
                }
            }
        }
        assertNotNull(outputDirectory.listFiles())
    }

    @Test
    fun `portuguese cards keep long generation and three digit age inside the layout`() {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("pt-BR"))
        }
        val context = base.createConfigurationContext(configuration)
        val outputDirectory = File(
            System.getProperty("user.dir"),
            "build/musical-age-previews",
        ).apply { mkdirs() }
        val result = MusicalAgeTestFixtures.readyResult(MusicalGeneration.CLASSICS, 194)

        MusicalAgeCardFormat.entries.forEach { format ->
            val bitmap = MusicalAgeCardRenderer.render(context, result, format)
            try {
                assertEquals(format.width, bitmap.width)
                assertEquals(format.height, bitmap.height)
                val qrRegion = qrRegion(format)
                assertTrue(
                    "QR area too empty for pt-BR/${format.suffix}",
                    countDarkPixels(bitmap, qrRegion) > 500,
                )
                markAsSyntheticExample(bitmap, format)
                val file = File(
                    outputDirectory,
                    "preview-pt-classics-${format.suffix}.png",
                )
                FileOutputStream(file).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                assertTrue(file.length() > 20_000)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun qrRegion(format: MusicalAgeCardFormat): IntArray = when (format) {
        // x, y, width, height of the QR white tile in the approved layout.
        MusicalAgeCardFormat.VERTICAL -> intArrayOf(786, 1607, 220, 220)
        MusicalAgeCardFormat.SQUARE -> intArrayOf(834, 738, 180, 180)
    }

    private fun hasVariation(bitmap: Bitmap): Boolean {
        val first = bitmap.getPixel(0, 0)
        for (y in 0 until bitmap.height step 47) {
            for (x in 0 until bitmap.width step 47) {
                if (bitmap.getPixel(x, y) != first) return true
            }
        }
        return false
    }

    private fun countDarkPixels(bitmap: Bitmap, region: IntArray): Int {
        var count = 0
        val maxX = (region[0] + region[2]).coerceAtMost(bitmap.width)
        val maxY = (region[1] + region[3]).coerceAtMost(bitmap.height)
        for (y in region[1] until maxY) {
            for (x in region[0] until maxX) {
                val color = bitmap.getPixel(x, y)
                if (
                    Color.red(color) < 60 &&
                    Color.green(color) < 60 &&
                    Color.blue(color) < 60
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun markAsSyntheticExample(bitmap: Bitmap, format: MusicalAgeCardFormat) {
        val canvas = Canvas(bitmap)
        val text = "EXEMPLO · DADOS SINTÉTICOS"
        val textSize = if (format == MusicalAgeCardFormat.VERTICAL) 20f else 15f
        val padding = 12f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(5, 17, 33)
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 212, 255)
        }
        val width = textPaint.measureText(text) + padding * 2
        val height = textSize + padding * 2
        val right = bitmap.width - 24f
        val left = right - width
        val top = 22f
        canvas.drawRoundRect(
            RectF(left, top, right, top + height),
            9f,
            9f,
            boxPaint,
        )
        canvas.drawText(
            text,
            left + padding,
            top + padding + textSize * 0.82f,
            textPaint,
        )
    }
}

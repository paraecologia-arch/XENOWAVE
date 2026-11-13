/*
 * Copyright (C) 2025 XENOWAVE Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.xenowave.insights.musicalage.ui.visual

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.content.res.ResourcesCompat
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.insights.musicalage.ReadyResult
import com.dd3boh.xenowave.sharing.OfficialAppLinkProvider
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the approved vertical/square composition directly to a bitmap.
 *
 * This is intentionally not a screen capture: no app bars, controls, account
 * data, track names or listening details enter the image. All text is derived
 * from the already calculated [ReadyResult] plus the centralized download URL.
 */
object MusicalAgeCardRenderer {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun render(
        context: Context,
        result: ReadyResult,
        format: MusicalAgeCardFormat,
    ): Bitmap {
        val width = format.width
        val height = format.height
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val layout = CardLayout.forFormat(format)

        val space = ContextCompat.getColor(context, R.color.xenowave_space_background)
        val blue = ContextCompat.getColor(context, R.color.xenowave_primary_blue)
        val cyan = ContextCompat.getColor(context, R.color.xenowave_luminous_cyan)
        val light = ContextCompat.getColor(context, R.color.xenowave_primary_text)
        val muted = blend(light, space, 0.34f)
        val blueText = blend(cyan, Color.WHITE, 0.10f)
        val darkText = blend(space, Color.BLACK, 0.12f)

        val orbitron = ResourcesCompat.getFont(context, R.font.orbitron_medium)
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val sans = Typeface.create("sans-serif", Typeface.NORMAL)
        val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        drawBackground(canvas, width, height, space, blue, cyan, light, layout)

        val centerX = width / 2f
        drawCenteredSpacedText(
            canvas = canvas,
            text = context.getString(R.string.musical_age_card_eyebrow),
            centerX = centerX,
            baselineY = layout.eyebrowBaseline,
            paint = textPaint(layout.eyebrowSize, muted, sansMedium),
            letterSpacing = layout.eyebrowSize * 0.22f,
        )
        drawCenteredSpacedText(
            canvas = canvas,
            text = "XENOWAVE",
            centerX = centerX,
            baselineY = layout.wordmarkBaseline,
            paint = textPaint(layout.wordmarkSize, light, orbitron),
            letterSpacing = layout.wordmarkSize * 0.14f,
        )

        drawEt(
            context = context,
            canvas = canvas,
            variant = result.generation.etVariant(),
            left = layout.etLeft,
            top = layout.etTop,
            size = layout.etSize,
            cyan = cyan,
            blue = blue,
        )

        val titleText = context.getString(R.string.musical_age_card_title)
            .uppercase(Locale.getDefault())
        drawCenteredSpacedText(
            canvas = canvas,
            text = titleText,
            centerX = layout.textCenterX,
            baselineY = layout.titleBaseline,
            paint = textPaint(layout.titleSize, blend(light, space, 0.18f), sansMedium),
            letterSpacing = layout.titleSize * 0.22f,
            maxWidth = layout.maxTextWidth,
        )

        drawAge(
            canvas = canvas,
            result = result,
            layout = layout,
            centerX = layout.textCenterX,
            blueText = blueText,
            orbitron = orbitron,
            sans = sans,
            yearsLabel = context.getString(R.string.musical_age_card_years),
        )

        drawCenteredFittedText(
            canvas = canvas,
            text = result.generation.label,
            centerX = layout.textCenterX,
            baselineY = layout.generationBaseline,
            paint = textPaint(layout.generationSize, blueText, sansMedium),
            maxWidth = layout.maxTextWidth,
            minTextSize = layout.generationSize * 0.62f,
        )

        drawCenteredFittedText(
            canvas = canvas,
            text = context.getString(
                R.string.musical_age_card_era,
                result.dominantWindow.fromYear,
                result.dominantWindow.toYear,
            ),
            centerX = layout.textCenterX,
            baselineY = layout.eraBaseline,
            paint = textPaint(layout.eraSize, blend(light, space, 0.22f), sans),
            maxWidth = layout.maxTextWidth,
            minTextSize = layout.eraSize * 0.70f,
        )
        drawCenteredFittedText(
            canvas = canvas,
            text = context.getString(
                R.string.musical_age_card_reference,
                result.referenceYear,
            ),
            centerX = layout.textCenterX,
            baselineY = layout.referenceBaseline,
            paint = textPaint(layout.referenceSize, muted, sans),
            maxWidth = layout.maxTextWidth,
            minTextSize = layout.referenceSize * 0.70f,
        )
        drawCenteredFittedText(
            canvas = canvas,
            text = context.getString(
                R.string.musical_age_card_period,
                result.periodStart.format(dateFormat),
                result.periodEnd.format(dateFormat),
            ),
            centerX = layout.textCenterX,
            baselineY = layout.periodBaseline,
            paint = textPaint(layout.periodSize, muted, sans),
            maxWidth = layout.maxTextWidth,
            minTextSize = layout.periodSize * 0.70f,
        )

        drawDivider(canvas, layout, blue, cyan)
        drawDownloadSection(
            context = context,
            canvas = canvas,
            layout = layout,
            cyan = cyan,
            darkText = darkText,
            light = light,
            muted = muted,
            orbitron = orbitron,
            sans = sans,
            sansMedium = sansMedium,
        )

        drawCenteredFittedText(
            canvas = canvas,
            text = OfficialAppLinkProvider.DOWNLOAD_URL.removePrefix("https://"),
            centerX = centerX,
            baselineY = layout.urlBaseline,
            paint = textPaint(layout.urlSize, blend(light, space, 0.28f), sans),
            maxWidth = width - 120f,
            minTextSize = layout.urlSize * 0.72f,
        )
        drawCenteredFittedText(
            canvas = canvas,
            text = context.getString(R.string.musical_age_card_footer),
            centerX = centerX,
            baselineY = layout.footerBaseline,
            paint = textPaint(layout.footerSize, muted, sans),
            maxWidth = width - 120f,
            minTextSize = layout.footerSize * 0.72f,
        )

        return bitmap
    }

    private fun drawBackground(
        canvas: Canvas,
        width: Int,
        height: Int,
        space: Int,
        blue: Int,
        cyan: Int,
        light: Int,
        layout: CardLayout,
    ) {
        canvas.drawColor(space)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            width * 0.50f,
            height * layout.glowCenterY,
            height * 0.46f,
            intArrayOf(
                withAlpha(cyan, 46),
                withAlpha(blue, 20),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.shader = RadialGradient(
            width * 0.92f,
            height * 0.08f,
            height * 0.34f,
            intArrayOf(withAlpha(blue, 34), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val random = java.util.Random(20261107L)
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val starCount = if (layout.square) 110 else 170
        repeat(starCount) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val radius = 0.8f + random.nextFloat() * 2.4f
            val alpha = (24 + random.nextFloat() * 112).roundToInt()
            starPaint.color = withAlpha(if (random.nextBoolean()) light else cyan, alpha)
            canvas.drawCircle(x, y, radius, starPaint)
        }
    }

    private fun drawEt(
        context: Context,
        canvas: Canvas,
        variant: MusicalAgeEtVariant,
        left: Float,
        top: Float,
        size: Float,
        cyan: Int,
        blue: Int,
    ) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = RadialGradient(
            left + size / 2f,
            top + size / 2f,
            size * 0.54f,
            intArrayOf(withAlpha(cyan, 42), withAlpha(blue, 16), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(left + size / 2f, top + size / 2f, size * 0.54f, glow)

        val bitmap = BitmapFactory.decodeResource(context.resources, variant.drawable)
            ?: return
        try {
            val scale = min(size / bitmap.width, size / bitmap.height)
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(
                    left + (size - bitmap.width * scale) / 2f,
                    top + (size - bitmap.height * scale) / 2f,
                )
            }
            canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawAge(
        canvas: Canvas,
        result: ReadyResult,
        layout: CardLayout,
        centerX: Float,
        blueText: Int,
        orbitron: Typeface,
        sans: Typeface,
        yearsLabel: String,
    ) {
        val ageText = result.musicalAge.toString()
        val agePaint = textPaint(layout.ageSize, Color.WHITE, orbitron)
        val yearsPaint = textPaint(layout.yearsSize, blueText, sans)
        val gap = layout.ageSize * 0.13f
        val maxAgeWidth = layout.maxTextWidth - yearsPaint.measureText(yearsLabel) - gap
        fitTextPaint(agePaint, ageText, maxAgeWidth, layout.ageSize * 0.55f)

        val ageWidth = agePaint.measureText(ageText)
        val yearsWidth = yearsPaint.measureText(yearsLabel)
        val total = ageWidth + gap + yearsWidth
        val startX = centerX - total / 2f
        canvas.drawText(ageText, startX, layout.ageBaseline, agePaint)
        canvas.drawText(yearsLabel, startX + ageWidth + gap, layout.ageBaseline, yearsPaint)
    }

    private fun drawDivider(
        canvas: Canvas,
        layout: CardLayout,
        blue: Int,
        cyan: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            layout.dividerLeft,
            layout.dividerBaseline,
            layout.dividerRight,
            layout.dividerBaseline,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(blue, 150),
                withAlpha(cyan, 150),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.32f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.strokeWidth = 1.2f
        canvas.drawLine(
            layout.dividerLeft,
            layout.dividerBaseline,
            layout.dividerRight,
            layout.dividerBaseline,
            paint,
        )
    }

    private fun drawDownloadSection(
        context: Context,
        canvas: Canvas,
        layout: CardLayout,
        cyan: Int,
        darkText: Int,
        light: Int,
        muted: Int,
        orbitron: Typeface,
        sans: Typeface,
        sansMedium: Typeface,
    ) {
        val buttonRect = RectF(
            layout.ctaLeft,
            layout.downloadTop,
            layout.ctaRight,
            layout.downloadTop + layout.buttonHeight,
        )
        val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        buttonPaint.color = cyan
        canvas.drawRoundRect(buttonRect, layout.buttonRadius, layout.buttonRadius, buttonPaint)

        val prefix = context.getString(R.string.musical_age_card_download_prefix)
        val brand = "XENOWAVE"
        val prefixPaint = textPaint(layout.buttonTextSize, darkText, sansMedium)
        val brandPaint = textPaint(layout.buttonBrandSize, darkText, orbitron)
        val spacing = layout.buttonBrandSize * 0.02f
        val total = prefixPaint.measureText(prefix) + spacing + brandPaint.measureText(brand)
        val textX = buttonRect.centerX() - total / 2f
        val textBaseline = buttonRect.centerY() - (prefixPaint.descent() + prefixPaint.ascent()) / 2f
        canvas.drawText(prefix, textX, textBaseline, prefixPaint)
        canvas.drawText(brand, textX + prefixPaint.measureText(prefix) + spacing, textBaseline, brandPaint)

        val hint = context.getString(R.string.musical_age_card_download_hint).split('\n')
        val hintPaint = textPaint(layout.hintSize, blend(light, Color.WHITE, 0.06f), sans)
        hint.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                layout.ctaLeft,
                layout.hintBaseline + index * layout.hintSize * 1.55f,
                hintPaint,
            )
        }

        val qrLeft = layout.qrRight - layout.qrSize
        val qrTop = layout.downloadTop
        val qrBackground = Paint(Paint.ANTI_ALIAS_FLAG)
        qrBackground.color = Color.WHITE
        canvas.drawRoundRect(
            RectF(qrLeft, qrTop, layout.qrRight, qrTop + layout.qrSize),
            layout.qrRadius,
            layout.qrRadius,
            qrBackground,
        )
        val module = layout.qrSize / QrDownloadMatrix.SIZE
        val modulePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        modulePaint.color = Color.rgb(7, 16, 32)
        for (row in 0 until QrDownloadMatrix.SIZE) {
            for (column in 0 until QrDownloadMatrix.SIZE) {
                if (!QrDownloadMatrix.isDark(column, row)) continue
                val left = qrLeft + column * module
                val top = qrTop + row * module
                canvas.drawRect(left, top, left + module + 0.2f, top + module + 0.2f, modulePaint)
            }
        }

        val captionPaint = textPaint(layout.captionSize, muted, sansMedium)
        drawCenteredText(
            canvas = canvas,
            text = context.getString(R.string.musical_age_card_qr_caption),
            centerX = (qrLeft + layout.qrRight) / 2f,
            baselineY = layout.qrCaptionBaseline,
            paint = captionPaint,
        )
    }

    private fun drawCenteredFittedText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        paint: Paint,
        maxWidth: Float,
        minTextSize: Float,
    ) {
        fitTextPaint(paint, text, maxWidth, minTextSize)
        drawCenteredText(canvas, text, centerX, baselineY, paint)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        paint: Paint,
    ) {
        canvas.drawText(text, centerX - paint.measureText(text) / 2f, baselineY, paint)
    }

    private fun drawCenteredSpacedText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        paint: Paint,
        letterSpacing: Float,
        maxWidth: Float? = null,
    ) {
        if (maxWidth != null) {
            val width = spacedTextWidth(text, paint, letterSpacing)
            if (width > maxWidth) {
                val factor = (maxWidth / width).coerceAtMost(1f)
                paint.textSize *= factor
            }
        }
        val spacing = if (text.length > 1) letterSpacing else 0f
        val width = spacedTextWidth(text, paint, spacing)
        var x = centerX - width / 2f
        text.forEach { char ->
            val value = char.toString()
            canvas.drawText(value, x, baselineY, paint)
            x += paint.measureText(value) + spacing
        }
    }

    private fun spacedTextWidth(text: String, paint: Paint, spacing: Float): Float {
        if (text.isEmpty()) return 0f
        var width = 0f
        text.forEach { width += paint.measureText(it.toString()) }
        return width + spacing * (text.length - 1)
    }

    private fun fitTextPaint(
        paint: Paint,
        text: String,
        maxWidth: Float,
        minTextSize: Float,
    ) {
        if (maxWidth <= 0f) return
        var measured = paint.measureText(text)
        if (measured <= maxWidth) return
        paint.textSize = (paint.textSize * (maxWidth / measured))
            .coerceAtLeast(minTextSize)
        measured = paint.measureText(text)
        if (measured > maxWidth) {
            paint.textSize = (paint.textSize * (maxWidth / measured))
                .coerceAtLeast(1f)
        }
    }

    private fun textPaint(size: Float, color: Int, typeface: Typeface): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.textSize = size
            this.color = color
            this.typeface = typeface
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        return Color.argb(
            (Color.alpha(from) * inverse + Color.alpha(to) * ratio).roundToInt(),
            (Color.red(from) * inverse + Color.red(to) * ratio).roundToInt(),
            (Color.green(from) * inverse + Color.green(to) * ratio).roundToInt(),
            (Color.blue(from) * inverse + Color.blue(to) * ratio).roundToInt(),
        )
    }

    private data class CardLayout(
        val square: Boolean,
        val glowCenterY: Float,
        val eyebrowBaseline: Float,
        val eyebrowSize: Float,
        val wordmarkBaseline: Float,
        val wordmarkSize: Float,
        val etLeft: Float,
        val etTop: Float,
        val etSize: Float,
        val textCenterX: Float,
        val maxTextWidth: Float,
        val titleBaseline: Float,
        val titleSize: Float,
        val ageBaseline: Float,
        val ageSize: Float,
        val yearsSize: Float,
        val generationBaseline: Float,
        val generationSize: Float,
        val eraBaseline: Float,
        val eraSize: Float,
        val referenceBaseline: Float,
        val referenceSize: Float,
        val periodBaseline: Float,
        val periodSize: Float,
        val dividerBaseline: Float,
        val dividerLeft: Float,
        val dividerRight: Float,
        val downloadTop: Float,
        val buttonHeight: Float,
        val buttonRadius: Float,
        val buttonTextSize: Float,
        val buttonBrandSize: Float,
        val ctaLeft: Float,
        val ctaRight: Float,
        val hintBaseline: Float,
        val hintSize: Float,
        val qrRight: Float,
        val qrSize: Float,
        val qrRadius: Float,
        val qrCaptionBaseline: Float,
        val captionSize: Float,
        val urlBaseline: Float,
        val urlSize: Float,
        val footerBaseline: Float,
        val footerSize: Float,
    ) {
        companion object {
            fun forFormat(format: MusicalAgeCardFormat): CardLayout = when (format) {
                MusicalAgeCardFormat.VERTICAL -> CardLayout(
                    square = false,
                    glowCenterY = 0.29f,
                    eyebrowBaseline = 73f,
                    eyebrowSize = 21f,
                    wordmarkBaseline = 171f,
                    wordmarkSize = 58f,
                    etLeft = 140f,
                    etTop = 195f,
                    etSize = 760f,
                    textCenterX = 540f,
                    maxTextWidth = 920f,
                    titleBaseline = 1043f,
                    titleSize = 25f,
                    ageBaseline = 1228f,
                    ageSize = 190f,
                    yearsSize = 31f,
                    generationBaseline = 1347f,
                    generationSize = 58f,
                    eraBaseline = 1427f,
                    eraSize = 30f,
                    referenceBaseline = 1472f,
                    referenceSize = 21f,
                    periodBaseline = 1513f,
                    periodSize = 19f,
                    dividerBaseline = 1562f,
                    dividerLeft = 72f,
                    dividerRight = 1008f,
                    downloadTop = 1607f,
                    buttonHeight = 82f,
                    buttonRadius = 22f,
                    buttonTextSize = 30f,
                    buttonBrandSize = 27f,
                    ctaLeft = 74f,
                    ctaRight = 746f,
                    hintBaseline = 1739f,
                    hintSize = 26f,
                    qrRight = 1006f,
                    qrSize = 220f,
                    qrRadius = 14f,
                    qrCaptionBaseline = 1848f,
                    captionSize = 17f,
                    urlBaseline = 1878f,
                    urlSize = 19f,
                    footerBaseline = 1908f,
                    footerSize = 16f,
                )

                MusicalAgeCardFormat.SQUARE -> CardLayout(
                    square = true,
                    glowCenterY = 0.34f,
                    eyebrowBaseline = 45f,
                    eyebrowSize = 16f,
                    wordmarkBaseline = 119f,
                    wordmarkSize = 43f,
                    etLeft = 20f,
                    etTop = 150f,
                    etSize = 570f,
                    textCenterX = 832f,
                    maxTextWidth = 450f,
                    titleBaseline = 282f,
                    titleSize = 19f,
                    ageBaseline = 430f,
                    ageSize = 132f,
                    yearsSize = 21f,
                    generationBaseline = 520f,
                    generationSize = 40f,
                    eraBaseline = 574f,
                    eraSize = 24f,
                    referenceBaseline = 610f,
                    referenceSize = 18f,
                    periodBaseline = 640f,
                    periodSize = 17f,
                    dividerBaseline = 700f,
                    dividerLeft = 55f,
                    dividerRight = 1025f,
                    downloadTop = 738f,
                    buttonHeight = 68f,
                    buttonRadius = 18f,
                    buttonTextSize = 25f,
                    buttonBrandSize = 23f,
                    ctaLeft = 66f,
                    ctaRight = 786f,
                    hintBaseline = 842f,
                    hintSize = 21f,
                    qrRight = 1014f,
                    qrSize = 180f,
                    qrRadius = 10f,
                    qrCaptionBaseline = 948f,
                    captionSize = 14f,
                    urlBaseline = 1002f,
                    urlSize = 16f,
                    footerBaseline = 1042f,
                    footerSize = 13f,
                )
            }
        }
    }
}

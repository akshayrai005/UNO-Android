package com.uno.game.ui.game

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.uno.game.models.UnoCard

/**
 * Premium UNO card renderer.
 * Supports: front face, card back, playable highlight, disabled dimming, selected lift.
 */
class UnoCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var card: UnoCard? = null
    var isPlayable: Boolean = true
        set(value) { field = value; invalidate() }
    var isSelected: Boolean = false
        set(value) { field = value; invalidate() }
    var showBack: Boolean = false
        set(value) { field = value; invalidate() }

    // ── Paints ────────────────────────────────────────────────────────────────
    private val paintBg      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.WHITE
    }
    private val paintShadow  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 0, 0)
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    }
    private val paintGlow    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(200, 255, 215, 0)
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    }
    private val paintText    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    private val paintDim     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 20)
    }
    private val paintOval    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 35
        style = Paint.Style.FILL
    }
    private val paintOvalStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 80
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintBack    = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setCard(card: UnoCard) {
        this.card = card
        invalidate()
    }

    // ── Draw ─────────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val r = w * 0.12f   // corner radius proportional to card width

        if (showBack || card == null) {
            drawCardBack(canvas, w, h, r)
            return
        }

        drawCardFront(canvas, card!!, w, h, r)
    }

    // ── Card Back ─────────────────────────────────────────────────────────────
    private fun drawCardBack(canvas: Canvas, w: Float, h: Float, r: Float) {
        // Shadow
        canvas.drawRoundRect(3f, 6f, w - 1f, h - 1f, r, r, paintShadow)

        // Gradient bg — deep navy
        paintBack.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(0xFF0E3A6E.toInt(), 0xFF071E3D.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paintBack)

        // Border
        paintBorder.color = Color.argb(180, 30, 136, 229)
        paintBorder.strokeWidth = 4f
        canvas.drawRoundRect(2f, 2f, w - 2f, h - 2f, r, r, paintBorder)

        // Decorative inner oval
        canvas.save()
        canvas.rotate(-25f, w / 2, h / 2)
        canvas.drawOval(w * 0.12f, h * 0.18f, w * 0.88f, h * 0.82f, paintOvalStroke)
        canvas.restore()

        // UNO label in center
        paintText.textSize = h * 0.18f
        paintText.color = Color.WHITE
        paintText.alpha = 200
        canvas.drawText("UNO", w / 2, h / 2 + paintText.textSize / 3, paintText)
        paintText.alpha = 255
    }

    // ── Card Front ────────────────────────────────────────────────────────────
    private fun drawCardFront(canvas: Canvas, card: UnoCard, w: Float, h: Float, r: Float) {
        val display = card.getDisplayValue()

        // Playable glow
        if (isPlayable && isSelected) {
            paintGlow.strokeWidth = 8f
            canvas.drawRoundRect(-2f, -2f, w + 2f, h + 2f, r + 2f, r + 2f, paintGlow)
        }

        // Drop shadow
        canvas.drawRoundRect(3f, 6f, w - 1f, h - 1f, r, r, paintShadow)

        // Background gradient
        paintBg.shader = buildGradient(card, w, h)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paintBg)

        // Decorative oval in center (tilted white ellipse like real UNO)
        canvas.save()
        canvas.rotate(-25f, w / 2, h / 2)
        canvas.drawOval(w * 0.08f, h * 0.14f, w * 0.92f, h * 0.86f, paintOval)
        canvas.restore()

        // White border
        paintBorder.color = Color.WHITE
        paintBorder.strokeWidth = 4f
        paintBorder.alpha = 220
        canvas.drawRoundRect(2f, 2f, w - 2f, h - 2f, r, r, paintBorder)
        paintBorder.alpha = 255

        // ── Corner value (top-left) ──
        paintText.textSize = h * 0.13f
        paintText.color = Color.WHITE
        val cornerX = w * 0.15f
        val cornerY = h * 0.19f
        canvas.drawText(display, cornerX, cornerY, paintText)

        // ── Corner value (bottom-right, rotated) ──
        canvas.save()
        canvas.rotate(180f, w / 2, h / 2)
        canvas.drawText(display, cornerX, cornerY, paintText)
        canvas.restore()

        // ── Center big symbol ──
        paintText.textSize = when {
            display.length > 2 -> h * 0.26f
            display == "0"     -> h * 0.38f
            else               -> h * 0.38f
        }
        if (card.isWild()) {
            drawWildSymbol(canvas, card, w, h, display)
        } else {
            canvas.drawText(display, w / 2, h / 2 + paintText.textSize / 3, paintText)
        }

        // ── Dim overlay if not playable ──
        if (!isPlayable) {
            paintDim.shader = null
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paintDim)
        }

        // ── Playable indicator dot ──
        if (isPlayable) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 215, 0)
                maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(w / 2, h - h * 0.06f, h * 0.025f, dotPaint)
        }
    }

    // ── Wild card center: 4-color quadrant circle ─────────────────────────────
    private fun drawWildSymbol(canvas: Canvas, card: UnoCard, w: Float, h: Float, display: String) {
        if (display == "★" || display == "+4") {
            val cx = w / 2
            val cy = h / 2
            val radius = h * 0.22f
            val colors = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(),
                                    0xFF43A047.toInt(), 0xFFFDD835.toInt())
            val quadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val sweeps = floatArrayOf(0f, 90f, 180f, 270f)
            val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            sweeps.forEachIndexed { i, start ->
                quadPaint.color = colors[i]
                canvas.drawArc(oval, start, 90f, true, quadPaint)
            }
            // white ring
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
            }
            canvas.drawCircle(cx, cy, radius, ringPaint)

            // center label for +4
            if (display == "+4") {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
                canvas.drawCircle(cx, cy, radius * 0.45f, bgPaint)
                paintText.textSize = h * 0.18f
                paintText.color = Color.WHITE
                canvas.drawText("+4", cx, cy + paintText.textSize / 3, paintText)
            }
        } else {
            paintText.textSize = h * 0.18f
            canvas.drawText(display, w / 2, h / 2 + paintText.textSize / 3, paintText)
        }
    }

    // ── Gradient builder ─────────────────────────────────────────────────────
    private fun buildGradient(card: UnoCard, w: Float, h: Float): LinearGradient {
        val angle = if (isPlayable) 0f else 0f
        return when (card.color) {
            "red"    -> LinearGradient(0f, 0f, w, h, 0xFFFF6B6B.toInt(), 0xFFC62828.toInt(), Shader.TileMode.CLAMP)
            "green"  -> LinearGradient(0f, 0f, w, h, 0xFF66BB6A.toInt(), 0xFF1B5E20.toInt(), Shader.TileMode.CLAMP)
            "blue"   -> LinearGradient(0f, 0f, w, h, 0xFF42A5F5.toInt(), 0xFF0D47A1.toInt(), Shader.TileMode.CLAMP)
            "yellow" -> LinearGradient(0f, 0f, w, h, 0xFFFFF176.toInt(), 0xFFF57F17.toInt(), Shader.TileMode.CLAMP)
            "wild"   -> LinearGradient(0f, 0f, w, h,
                            intArrayOf(0xFF1A1A2E.toInt(), 0xFF16213E.toInt(), 0xFF0F3460.toInt()),
                            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            else     -> LinearGradient(0f, 0f, w, h, 0xFF424242.toInt(), 0xFF212121.toInt(), Shader.TileMode.CLAMP)
        }
    }
}

package com.example.bubbletranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

data class TranslatedBlock(
    val text: String,
    val bounds: Rect
)

// 메인 스레드에서 사용합니다.
class TranslationOverlay(
    private val context: Context,
    private val onToggle: () -> Unit,
    private val onStop: () -> Unit
) {
    private val manager = context.getSystemService(
        Context.WINDOW_SERVICE
    ) as WindowManager

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private val translationView = TranslationView(context)

    private val bubble = TextView(context).apply {
        text = "번역"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(35, 85, 155))
        contentDescription = "누르면 일시정지 또는 재개, 길게 누르면 종료"
        setOnClickListener { onToggle() }
        setOnLongClickListener {
            onStop()
            true
        }
    }

    private var attached = false

    fun show() {
        if (attached) return

        val textParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.75f

            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val bubbleParams = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(120)
        }

        try {
            manager.addView(translationView, textParams)
            manager.addView(bubble, bubbleParams)
            attached = true
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun setPaused(paused: Boolean) {
        bubble.text = if (paused) "재개" else "번역"
        bubble.setBackgroundColor(
            if (paused) Color.rgb(100, 100, 100)
            else Color.rgb(35, 85, 155)
        )
        if (paused) clear()
    }

    fun setStatus(message: String) {
        bubble.text = message
    }

    fun showTranslations(
        blocks: List<TranslatedBlock>,
        captureWidth: Int,
        captureHeight: Int
    ) {
        translationView.update(blocks, captureWidth, captureHeight)
    }

    fun clear() {
        translationView.update(emptyList(), 1, 1)
    }

    fun setCaptureHidden(hidden: Boolean) {
        val state = if (hidden) View.INVISIBLE else View.VISIBLE
        translationView.visibility = state
        bubble.visibility = state
    }

    // 이후 화면 변화 감지에서 오버레이 영역을 제외할 때 사용합니다.
    // 반환 좌표는 실제 화면의 픽셀 좌표입니다.
    fun getCoveredScreenRects(): List<Rect> {
        val result = mutableListOf<Rect>()

        if (translationView.isShown) {
            val location = IntArray(2)
            translationView.getLocationOnScreen(location)

            for (rect in translationView.labelRects()) {
                rect.offset(location[0], location[1])
                result.add(rect)
            }
        }

        if (bubble.isShown) {
            val location = IntArray(2)
            bubble.getLocationOnScreen(location)
            result.add(
                Rect(
                    location[0],
                    location[1],
                    location[0] + bubble.width,
                    location[1] + bubble.height
                )
            )
        }

        return result
    }

    fun close() {
        if (bubble.isAttachedToWindow) manager.removeView(bubble)
        if (translationView.isAttachedToWindow) {
            manager.removeView(translationView)
        }
        attached = false
    }

    private class TranslationView(context: Context) : View(context) {

        private val density = resources.displayMetrics.density

        // 원문 상자 크기에 맞춰 글씨를 줄이지 않습니다.
        private val fontPixels = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            18f,
            resources.displayMetrics
        )

        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }

        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 80, 80)
            style = Paint.Style.STROKE
            strokeWidth = density
        }

        private data class Label(
            val rect: Rect,
            val layout: StaticLayout
        )

        private var blocks = emptyList<TranslatedBlock>()
        private var labels = emptyList<Label>()
        private var sourceWidth = 1
        private var sourceHeight = 1

        fun labelRects(): List<Rect> {
            return labels.map { Rect(it.rect) }
        }

        fun update(
            newBlocks: List<TranslatedBlock>,
            captureWidth: Int,
            captureHeight: Int
        ) {
            blocks = newBlocks.map {
                TranslatedBlock(it.text, Rect(it.bounds))
            }
            sourceWidth = captureWidth.coerceAtLeast(1)
            sourceHeight = captureHeight.coerceAtLeast(1)
            rebuild()
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuild()
        }

        private fun rebuild() {
            if (width <= 0 || height <= 0) {
                labels = emptyList()
                return
            }

            val padding = (8 * density).toInt().coerceAtLeast(1)
            val margin = (6 * density).toInt()
            val maxWidth = width - margin * 2

            if (maxWidth <= padding * 2) {
                labels = emptyList()
                return
            }

            val scaleX = width.toFloat() / sourceWidth
            val scaleY = height.toFloat() / sourceHeight

            labels = blocks.mapNotNull { block ->
                if (block.text.isBlank()) return@mapNotNull null

                val originalWidth = (block.bounds.width() * scaleX).toInt()
                val minimumWidth = (
                    if (block.text.length > 45) 260 * density
                    else 180 * density
                ).toInt()

                val boxWidth = maxOf(originalWidth, minimumWidth)
                    .coerceAtMost(maxWidth)

                fun makeLayout(): StaticLayout {
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = fontPixels
                        typeface = Typeface.create(
                            Typeface.DEFAULT,
                            Typeface.BOLD
                        )
                    }

                    return StaticLayout.Builder.obtain(
                        block.text,
                        0,
                        block.text.length,
                        paint,
                        boxWidth - padding * 2
                    )
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .setLineSpacing(2 * density, 1.05f)
                        .build()
                }

                val layout = makeLayout()
                val boxHeight = layout.height + padding * 2

                val centerX = block.bounds.exactCenterX() * scaleX
                val left = (centerX - boxWidth / 2f).toInt()
                    .coerceIn(margin, width - margin - boxWidth)

                val desiredTop = (block.bounds.top * scaleY).toInt()
                val maxTop = (height - margin - boxHeight)
                    .coerceAtLeast(margin)

                val top = desiredTop.coerceIn(margin, maxTop)

                Label(
                    Rect(left, top, left + boxWidth, top + boxHeight),
                    layout
                )
            }

            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val padding = (8 * density).toInt().coerceAtLeast(1)

            for (label in labels) {
                canvas.drawRect(label.rect, background)
                canvas.drawRect(label.rect, border)

                val checkpoint = canvas.save()
                canvas.clipRect(label.rect)
                canvas.translate(
                    (label.rect.left + padding).toFloat(),
                    (label.rect.top + padding).toFloat()
                )
                label.layout.draw(canvas)
                canvas.restoreToCount(checkpoint)
            }
        }
    }
}

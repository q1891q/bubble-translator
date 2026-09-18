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
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView

data class TranslatedBlock(
    val text: String,
    val bounds: Rect
)

// 모든 메서드는 메인 스레드에서 호출합니다.
class TranslationOverlay(
    private val context: Context,
    private val onToggle: () -> Unit,
    private val onStop: () -> Unit
) {
    private val manager = context.getSystemService(
        Context.WINDOW_SERVICE
    ) as WindowManager

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Int): Int {
        return (value * density).toInt()
    }

    private val translationView = TranslationView(context)

    private val bubble = TextView(context).apply {
        text = "번역"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(35, 85, 155))
        contentDescription = "누르면 일시정지 또는 재개, 길게 누르면 종료"

        setOnClickListener {
            onToggle()
        }

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

            // Android의 오버레이 터치 보안 제한을 고려합니다.
            alpha = 0.75f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
            if (bubble.isAttachedToWindow) {
                manager.removeView(bubble)
            }
            if (translationView.isAttachedToWindow) {
                manager.removeView(translationView)
            }
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
        translationView.update(
            blocks,
            captureWidth,
            captureHeight
        )
    }

    fun clear() {
        translationView.update(emptyList(), 1, 1)
    }

    // 캡처에 번역문과 버블이 다시 찍히지 않도록 숨깁니다.
    // 실제 캡처는 숨김이 반영된 새 프레임에서 해야 합니다.
    fun setCaptureHidden(hidden: Boolean) {
        val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        translationView.visibility = visibility
        bubble.visibility = visibility
    }

    fun close() {
        if (bubble.isAttachedToWindow) {
            manager.removeView(bubble)
        }
        if (translationView.isAttachedToWindow) {
            manager.removeView(translationView)
        }
        attached = false
    }

    private class TranslationView(
        context: Context
    ) : View(context) {

        private val density =
            context.resources.displayMetrics.density

        private val backgroundPaint = Paint().apply {
            color = Color.WHITE
        }

        private val textPaint = TextPaint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.BLACK
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
        }

        private data class Label(
            val bounds: Rect,
            val layout: StaticLayout
        )

        private var blocks = emptyList<TranslatedBlock>()
        private var labels = emptyList<Label>()
        private var sourceWidth = 1
        private var sourceHeight = 1

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
                invalidate()
                return
            }

            val scaleX = width.toFloat() / sourceWidth
            val scaleY = height.toFloat() / sourceHeight
            val padding = (4 * density).toInt().coerceAtLeast(1)

            labels = blocks.mapNotNull { block ->
                if (block.text.isBlank()) return@mapNotNull null

                val left = (block.bounds.left * scaleX)
                    .toInt().coerceIn(0, width - 1)
                val top = (block.bounds.top * scaleY)
                    .toInt().coerceIn(0, height - 1)
                val right = (block.bounds.right * scaleX)
                    .toInt().coerceIn(left + 1, width)
                val bottom = (block.bounds.bottom * scaleY)
                    .toInt().coerceIn(top + 1, height)

                val availableWidth = right - left - padding * 2
                val availableHeight = bottom - top - padding * 2

                if (availableWidth <= 0 || availableHeight <= 0) {
                    return@mapNotNull null
                }

                var size = 16f
                var layout: StaticLayout

                do {
                    val paint = TextPaint(textPaint).apply {
                        textSize = size * density
                    }

                    layout = StaticLayout.Builder.obtain(
                        block.text,
                        0,
                        block.text.length,
                        paint,
                        availableWidth
                    )
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setIncludePad(false)
                        .build()

                    if (layout.height <= availableHeight || size <= 9f) {
                        break
                    }
                    size -= 1f
                } while (true)

                Label(Rect(left, top, right, bottom), layout)
            }

            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val padding = (4 * density).toInt().coerceAtLeast(1)

            for (label in labels) {
                val rect = label.bounds
                canvas.drawRect(rect, backgroundPaint)

                val checkpoint = canvas.save()
                canvas.clipRect(rect)
                canvas.translate(
                    (rect.left + padding).toFloat(),
                    (rect.top + padding).toFloat()
                )
                label.layout.draw(canvas)
                canvas.restoreToCount(checkpoint)
            }
        }
    }
}

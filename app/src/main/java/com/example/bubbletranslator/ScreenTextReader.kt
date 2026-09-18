package com.example.bubbletranslator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

data class ScreenTextBlock(
    val text: String,
    val bounds: Rect
)

class ScreenTextReader {

    private val readers = mutableMapOf<String, TextRecognizer>()
    private var closed = false

    private fun getReader(language: String): TextRecognizer {
        return readers.getOrPut(language) {
            when (language) {
                "en" -> TextRecognition.getClient(
                    TextRecognizerOptions.DEFAULT_OPTIONS
                )

                "zh" -> TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build()
                )

                "ja" -> TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build()
                )

                else -> throw IllegalArgumentException(
                    "지원하지 않는 인식 언어입니다: $language"
                )
            }
        }
    }

    // 메인 스레드에서 호출합니다.
    // bitmap은 성공/실패 콜백이 올 때까지 해제하거나 수정하지 않습니다.
    fun read(
        bitmap: Bitmap,
        language: String,
        onSuccess: (List<ScreenTextBlock>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (closed) return

        if (bitmap.isRecycled) {
            onError(
                IllegalArgumentException("이미 해제된 화면 이미지입니다.")
            )
            return
        }

        if (language !in setOf("en", "zh", "ja")) {
            onError(
                IllegalArgumentException("지원하지 않는 인식 언어입니다.")
            )
            return
        }

        try {
            val image = InputImage.fromBitmap(bitmap, 0)

            getReader(language).process(image)
                .addOnSuccessListener { result ->
                    if (!closed) {
                        val blocks = result.textBlocks.mapNotNull { block ->
                            val bounds = block.boundingBox
                            val text = block.text.trim()

                            if (
                                bounds == null ||
                                bounds.isEmpty ||
                                text.isBlank()
                            ) {
                                null
                            } else {
                                ScreenTextBlock(
                                    text = text,
                                    bounds = Rect(bounds)
                                )
                            }
                        }

                        onSuccess(blocks)
                    }
                }
                .addOnFailureListener { error ->
                    if (!closed) {
                        onError(error)
                    }
                }
        } catch (error: Exception) {
            if (!closed) {
                onError(error)
            }
        }
    }

    fun close() {
        closed = true
        readers.values.forEach { it.close() }
        readers.clear()
    }
}

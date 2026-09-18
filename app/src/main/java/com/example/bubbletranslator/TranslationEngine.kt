package com.example.bubbletranslator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslationEngine {

    private val translators = mutableMapOf<String, Translator>()
    private val readyLanguages = mutableSetOf<String>()

    private val cache = object :
        LinkedHashMap<Pair<String, String>, String>(128, 0.75f, true) {

        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, String>, String>?
        ): Boolean {
            return size > 300
        }
    }

    private var closed = false

    // sourceLanguage: "en" = 영어, "zh" = 중국어, "ja" = 일본어
    // 이 클래스의 메서드는 메인 스레드에서 호출합니다.
    fun translate(
        text: String,
        sourceLanguage: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (closed) return

        val sourceText = text.trim()

        if (sourceText.isEmpty()) {
            onSuccess("")
            return
        }

        val language = when (sourceLanguage) {
            "en" -> TranslateLanguage.ENGLISH
            "zh" -> TranslateLanguage.CHINESE
            "ja" -> TranslateLanguage.JAPANESE
            else -> {
                onError(
                    IllegalArgumentException("지원하지 않는 언어입니다.")
                )
                return
            }
        }

        val key = language to sourceText
        val cached = cache[key]

        if (cached != null) {
            onSuccess(cached)
            return
        }

        val translator = translators.getOrPut(language) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(language)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()

            Translation.getClient(options)
        }

        fun runTranslation() {
            if (closed) return

            translator.translate(sourceText)
                .addOnSuccessListener { translated ->
                    if (!closed) {
                        cache[key] = translated
                        onSuccess(translated)
                    }
                }
                .addOnFailureListener { error ->
                    if (!closed) {
                        onError(error)
                    }
                }
        }

        if (language in readyLanguages) {
            runTranslation()
        } else {
            val conditions = DownloadConditions.Builder()
                .build()

            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    if (!closed) {
                        readyLanguages.add(language)
                        runTranslation()
                    }
                }
                .addOnFailureListener { error ->
                    if (!closed) {
                        onError(error)
                    }
                }
        }
    }

    fun close() {
        closed = true
        translators.values.forEach { it.close() }
        translators.clear()
        readyLanguages.clear()
        cache.clear()
    }
}

package com.example.bubbletranslator

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import android.widget.Toast
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlin.math.abs

class TranslationService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: TranslationOverlay? = null

    private lateinit var textReader: ScreenTextReader
    private lateinit var translator: TranslationEngine
    private lateinit var languageIdentifier: LanguageIdentifier

    private var screenWidth = 0
    private var screenHeight = 0

    private var running = false
    private var paused = false
    private var busy = false
    private var destroyed = false

    private var previousSample: IntArray? = null
    private var revision = 0
    private var completedRevision = -1
    private var retryAfter = 0L

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                stopSelf()
            }

            override fun onCapturedContentResize(
                width: Int,
                height: Int
            ) {
                // 回転や共有範囲の変更後は安全のため再起動が必要。
                if (
                    screenWidth > 0 &&
                    (width != screenWidth || height != screenHeight)
                ) {
                    Toast.makeText(
                        this@TranslationService,
                        "화면 크기가 변경되었습니다. 번역을 다시 시작해 주세요.",
                        Toast.LENGTH_LONG
                    ).show()
                    stopSelf()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        textReader = ScreenTextReader()
        translator = TranslationEngine()
        languageIdentifier = LanguageIdentification.getClient()

        val channel = NotificationChannel(
            "translation",
            "화면 번역",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        // 실행 중에는 새 캡처 토큰을 재사용하지 않습니다.
        if (running) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(
            "resultCode",
            Activity.RESULT_CANCELED
        ) ?: Activity.RESULT_CANCELED

        val consent = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                "data",
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("data")
        }

        if (resultCode != Activity.RESULT_OK || consent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startCaptureForeground()

            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            val capture = manager.getMediaProjection(
                resultCode,
                consent
            )

            projection = capture
            capture.registerCallback(projectionCallback, handler)

            val windowManager = getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
            }

            val reader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                3
            )
            imageReader = reader

            display = capture.createVirtualDisplay(
                "BubbleTranslation",
                screenWidth,
                screenHeight,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            overlay = TranslationOverlay(
                this,
                onToggle = { togglePause() },
                onStop = { stopSelf() }
            ).also {
                it.show()
            }

            running = true
            handler.post(tick)
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "시작 실패: ${error.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startCaptureForeground() {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TranslationService::class.java).apply {
                action = "STOP"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, "translation")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("버블 번역 실행 중")
            .setContentText("버블: 일시정지 · 길게 누르기: 종료")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "종료",
                    stopIntent
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun togglePause() {
        paused = !paused
        revision++
        completedRevision = -1
        previousSample = null

        overlay?.setCaptureHidden(false)
        overlay?.setPaused(paused)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running || destroyed) return

            if (paused) {
                handler.postDelayed(this, 700)
                return
            }

            // 오버레이가 캡처 영상에 다시 들어가는 것을 줄입니다.
            overlay?.setCaptureHidden(true)

            try {
                imageReader?.acquireLatestImage()?.close()
            } catch (_: Exception) {
            }

            handler.postDelayed({
                if (!running || destroyed) return@postDelayed

                try {
                    if (!paused) {
                        val bitmap = captureBitmap()
                        if (bitmap != null) inspectFrame(bitmap)
                    }
                } catch (_: Exception) {
                    if (!paused) overlay?.setStatus("재시도")
                } finally {
                    overlay?.setCaptureHidden(false)
                    if (running && !destroyed) {
                        handler.postDelayed(this, 850)
                    }
                }
            }, 180)
        }
    }

    private fun captureBitmap(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null

        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedWidth = rowStride / pixelStride

            val padded = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            try {
                plane.buffer.rewind()
                padded.copyPixelsFromBuffer(plane.buffer)

                val cropped = Bitmap.createBitmap(
                    padded,
                    0,
                    0,
                    image.width,
                    image.height
                )

                // 같은 객체가 반환된 경우에는 여기서 해제하지 않습니다.
                if (cropped !== padded) padded.recycle()
                return cropped
            } catch (error: Exception) {
                if (!padded.isRecycled) padded.recycle()
                throw error
            }
        } finally {
            image.close()
        }
    }

    private fun makeSample(bitmap: Bitmap): IntArray {
        val tiny = Bitmap.createScaledBitmap(bitmap, 48, 80, true)
        val pixels = IntArray(48 * 80)
        tiny.getPixels(pixels, 0, 48, 0, 0, 48, 80)

        if (tiny !== bitmap) tiny.recycle()
        return pixels
    }

    private fun hasChanged(
        previous: IntArray?,
        current: IntArray
    ): Boolean {
        if (previous == null) return true

        var difference = 0L

        for (index in current.indices) {
            val a = previous[index]
            val b = current[index]

            difference += abs(((a shr 16) and 255) - ((b shr 16) and 255))
            difference += abs(((a shr 8) and 255) - ((b shr 8) and 255))
            difference += abs((a and 255) - (b and 255))
        }

        return difference.toDouble() / (current.size * 3) > 3.0
    }

    private fun inspectFrame(bitmap: Bitmap) {
        val sample = makeSample(bitmap)
        val changed = hasChanged(previousSample, sample)

        if (changed) {
            previousSample = sample
            revision++
            completedRevision = -1
            overlay?.clear()
            overlay?.setStatus("대기")
            bitmap.recycle()
            return
        }

        if (
            busy ||
            completedRevision == revision ||
            SystemClock.elapsedRealtime() < retryAfter
        ) {
            bitmap.recycle()
            return
        }

        busy = true
        val jobRevision = revision
        overlay?.setStatus("인식")

        // 일본어 인식 결과로 언어를 추정한 후,
        // 중국어·영어라면 해당 인식기로 다시 읽습니다.
        textReader.read(
            bitmap,
            "ja",
            onSuccess = { blocks ->
                if (!isCurrent(jobRevision)) {
                    releaseJob(bitmap)
                } else {
                    identifyAndRead(bitmap, blocks, jobRevision)
                }
            },
            onError = {
                failJob(bitmap, jobRevision)
            }
        )
    }

    private fun identifyAndRead(
        bitmap: Bitmap,
        firstBlocks: List<ScreenTextBlock>,
        jobRevision: Int
    ) {
        val text = firstBlocks.joinToString("\n") { it.text }.take(4000)

        if (text.isBlank()) {
            completedRevision = jobRevision
            overlay?.setStatus("번역")
            releaseJob(bitmap)
            return
        }

        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { detected ->
                if (!isCurrent(jobRevision)) {
                    releaseJob(bitmap)
                    return@addOnSuccessListener
                }

                val language = when {
                    detected in setOf("en", "zh", "ja") -> detected
                    text.any {
                        it in '\u3040'..'\u30ff'
                    } -> "ja"
                    text.any {
                        it in '\u4e00'..'\u9fff'
                    } -> "zh"
                    else -> "en"
                }

                if (language == "ja") {
                    translateBlocks(
                        bitmap,
                        firstBlocks,
                        language,
                        jobRevision
                    )
                } else {
                    textReader.read(
                        bitmap,
                        language,
                        onSuccess = { blocks ->
                            translateBlocks(
                                bitmap,
                                blocks,
                                language,
                                jobRevision
                            )
                        },
                        onError = {
                            failJob(bitmap, jobRevision)
                        }
                    )
                }
            }
            .addOnFailureListener {
                failJob(bitmap, jobRevision)
            }
    }

    private fun translateBlocks(
        bitmap: Bitmap,
        blocks: List<ScreenTextBlock>,
        language: String,
        jobRevision: Int
    ) {
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()

        if (!isCurrent(jobRevision)) {
            busy = false
            return
        }

        // 한 화면에서 너무 많은 작업이 쌓이지 않도록 제한합니다.
        val targets = blocks.filter { block ->
            block.text.any { it.isLetter() }
        }.take(35)

        val translated = mutableListOf<TranslatedBlock>()
        overlay?.setStatus("번역중")

        fun next(index: Int) {
            if (!isCurrent(jobRevision)) {
                busy = false
                return
            }

            if (index >= targets.size) {
                overlay?.showTranslations(translated, width, height)
                overlay?.setStatus("번역")
                completedRevision = jobRevision
                busy = false
                return
            }

            val block = targets[index]

            translator.translate(
                block.text,
                language,
                onSuccess = { result ->
                    translated.add(
                        TranslatedBlock(result, block.bounds)
                    )
                    next(index + 1)
                },
                onError = {
                    busy = false
                    if (isCurrent(jobRevision)) {
                        overlay?.setStatus("재시도")
                        retryAfter = SystemClock.elapsedRealtime() + 10000
                    }
                }
            )
        }

        next(0)
    }

    private fun isCurrent(jobRevision: Int): Boolean {
        return running &&
            !destroyed &&
            !paused &&
            jobRevision == revision
    }

    private fun releaseJob(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
        busy = false
    }

    private fun failJob(bitmap: Bitmap, jobRevision: Int) {
        releaseJob(bitmap)

        if (isCurrent(jobRevision)) {
            overlay?.setStatus("재시도")
            retryAfter = SystemClock.elapsedRealtime() + 10000
        }
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        revision++

        handler.removeCallbacksAndMessages(null)

        overlay?.close()
        overlay = null

        display?.release()
        display = null

        imageReader?.close()
        imageReader = null

        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null

        textReader.close()
        translator.close()
        languageIdentifier.close()

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

package com.example.bubbletranslator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val SCREEN_CAPTURE_REQUEST = 1001
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        fun addText(message: String, size: Float) {
            layout.addView(TextView(this).apply {
                text = message
                textSize = size
                setPadding(0, padding / 2, 0, padding / 2)
            })
        }

        fun addButton(label: String, action: () -> Unit) {
            layout.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addText("버블 번역", 28f)

        addText(
            "영어·중국어·일본어 이미지 속 글자를 한국어로 번역합니다.\n\n" +
                "처음에는 번역 모델 다운로드를 위해 인터넷이 필요합니다.\n" +
                "화면 캡처 중에는 민감한 정보가 있는 화면을 열지 마세요.",
            16f
        )

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, padding / 2, 0, padding / 2)
        }
        layout.addView(statusText)

        addButton("1. 다른 앱 위에 표시 허용") {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        addButton("2. 자동 번역 시작") {
            requestScreenCapture()
        }

        addButton("번역 종료") {
            stopService(Intent(this, TranslationService::class.java))
            Toast.makeText(this, "종료를 요청했습니다.", Toast.LENGTH_SHORT).show()
        }

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) {
            statusText.text = if (Settings.canDrawOverlays(this)) {
                "표시 권한: 허용됨\n자동 번역을 시작할 수 있습니다."
            } else {
                "먼저 '다른 앱 위에 표시' 권한을 허용해 주세요."
            }
        }
    }

    private fun requestScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "먼저 다른 앱 위에 표시 권한을 허용해 주세요.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val manager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            SCREEN_CAPTURE_REQUEST
        )
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != SCREEN_CAPTURE_REQUEST) return

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(
                this,
                "화면 캡처가 취소되었습니다.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val serviceIntent = Intent(
            this,
            TranslationService::class.java
        ).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }

        startForegroundService(serviceIntent)
        statusText.text = "시작을 요청했습니다. 화면의 번역 버블을 확인해 주세요."
    }
}

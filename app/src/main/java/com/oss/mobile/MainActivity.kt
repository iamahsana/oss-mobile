package com.oss.mobile

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val scenes = mutableListOf("Scene 1")
    private var selectedScene = 0
    private lateinit var sceneList: LinearLayout
    private lateinit var previewLabel: TextView
    private lateinit var streamUrl: EditText
    private lateinit var streamStatus: TextView

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 4101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderStudio()
    }

    private fun renderStudio() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 20)
            setBackgroundColor(0xFF101820.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val title = TextView(this).apply {
            text = "OSS"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "Open Stream Software  •  Android Studio"
            textSize = 14f
            setTextColor(0xFFB8C7D1.toInt())
        }

        previewLabel = TextView(this).apply {
            text = "PREVIEW\n\nScreen capture belum dimulai"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFFEAF2F8.toInt())
            setBackgroundColor(0xFF263746.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = 18; bottomMargin = 12 }
        }

        val sceneHeading = TextView(this).apply {
            text = "SCENES"
            textSize = 13f
            setTextColor(0xFF8FD3FF.toInt())
        }
        sceneList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val addScene = Button(this).apply {
            text = "＋  Add scene"
            setOnClickListener {
                scenes.add("Scene ${scenes.size + 1}")
                selectedScene = scenes.lastIndex
                refreshScenes()
            }
        }

        streamUrl = EditText(this).apply {
            hint = "RTMP URL (rtmp://server/app/stream-key)"
            textSize = 14f
            singleLine = true
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9EADB8.toInt())
            setPadding(12, 8, 12, 8)
        }

        streamStatus = TextView(this).apply {
            text = "Uji awal: screen capture + RTMP. Audio internal dan scene compositor menyusul."
            textSize = 12f
            setTextColor(0xFFFFD180.toInt())
            setPadding(0, 8, 0, 4)
        }

        val startStream = Button(this).apply {
            text = "Start screen stream"
            setOnClickListener { requestScreenCapture() }
        }
        val stopStream = Button(this).apply {
            text = "Stop streaming"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenStreamService::class.java))
                streamStatus.text = "Perintah stop dikirim."
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(previewLabel)
        root.addView(sceneHeading)
        root.addView(sceneList)
        root.addView(addScene)
        root.addView(streamUrl)
        root.addView(streamStatus)
        root.addView(startStream)
        root.addView(stopStream)
        setContentView(root)
        refreshScenes()
    }

    private fun requestScreenCapture() {
        val endpoint = streamUrl.text.toString().trim()
        if (!endpoint.startsWith("rtmp://") && !endpoint.startsWith("rtmps://")) {
            Toast.makeText(this, "Masukkan URL RTMP/RTMPS lengkap terlebih dahulu.", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    @Deprecated("Uses the platform screen-capture consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            streamStatus.text = "Izin screen capture dibatalkan."
            return
        }

        val serviceIntent = Intent(this, ScreenStreamService::class.java).apply {
            putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenStreamService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenStreamService.EXTRA_ENDPOINT, streamUrl.text.toString().trim())
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        previewLabel.text = "SCREEN CAPTURE\n\nMenyiapkan koneksi RTMP…"
        streamStatus.text = "Permintaan streaming dikirim ke service."
    }

    private fun refreshScenes() {
        sceneList.removeAllViews()
        scenes.forEachIndexed { index, scene ->
            val button = Button(this).apply {
                text = if (index == selectedScene) "✓  $scene" else scene
                setOnClickListener {
                    selectedScene = index
                    previewLabel.text = "PREVIEW\n\n$scene\nCompositor akan ditambahkan pada tahap berikutnya."
                    refreshScenes()
                }
            }
            sceneList.addView(button)
        }
        if (scenes.isNotEmpty()) {
            previewLabel.text = "PREVIEW\n\n${scenes[selectedScene]}\nBelum ada overlay/source."
        }
    }
}

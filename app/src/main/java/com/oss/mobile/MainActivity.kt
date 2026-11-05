package com.oss.mobile

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.TextureView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val scenes = mutableListOf("Scene 1")
    private var selectedScene = 0
    private var selectedAudioMode = ScreenStreamService.AUDIO_MODE_MICROPHONE
    private lateinit var sceneList: LinearLayout
    private lateinit var previewLabel: TextView
    private lateinit var previewView: TextureView
    private var streamService: ScreenStreamService? = null
    private var serviceBound = false
    private val streamConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            streamService = (binder as? ScreenStreamService.LocalBinder)?.getService()
            streamService?.attachPreview(previewView)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
        }
    }
    private lateinit var streamUrl: EditText
    private lateinit var streamKey: EditText
    private lateinit var streamStatus: TextView
    private var statusReceiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenStreamService.ACTION_STATUS) {
                streamStatus.text = intent.getStringExtra(ScreenStreamService.EXTRA_STATUS)
                    ?: "Status streaming berubah."
            }
        }
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 4101
        private const val REQUEST_MICROPHONE = 4102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            renderStudio()
        } catch (error: Throwable) {
            showStartupError(error)
        }
    }

    private fun showStartupError(error: Throwable) {
        val message = buildString {
            append("OSS gagal membuka Studio.\n\n")
            append(error.javaClass.simpleName)
            append(": ")
            append(error.message ?: "Tidak ada detail error")
            append("\n\n")
            append(error.stackTraceToString().take(5000))
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "OSS — Startup error"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val details = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(0xFFFFD180.toInt())
        }
        screen.addView(title)
        screen.addView(details)
        setContentView(screen)
    }

    override fun onStart() {
        super.onStart()
        if (!statusReceiverRegistered) {
            val filter = IntentFilter(ScreenStreamService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            statusReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (serviceBound) {
            unbindService(streamConnection)
            serviceBound = false
            streamService = null
        }
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver)
            statusReceiverRegistered = false
        }
        super.onStop()
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

        previewView = TextureView(this).apply {
            setBackgroundColor(0xFF263746.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = 18; bottomMargin = 12 }
        }
        previewLabel = TextView(this).apply {
            text = "Preview akan muncul saat screen capture dimulai"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFEAF2F8.toInt())
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

        val audioHeading = TextView(this).apply {
            text = "AUDIO SOURCE"
            textSize = 13f
            setTextColor(0xFF8FD3FF.toInt())
            setPadding(0, 10, 0, 2)
        }
        val audioModes = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val microphone = RadioButton(this@MainActivity).apply {
                id = 1
                text = "Microphone"
                setTextColor(0xFFFFFFFF.toInt())
            }
            val internal = RadioButton(this@MainActivity).apply {
                id = 2
                text = "Internal game audio"
                setTextColor(0xFFFFFFFF.toInt())
            }
            val mixed = RadioButton(this@MainActivity).apply {
                id = 3
                text = "Internal audio + microphone"
                setTextColor(0xFFFFFFFF.toInt())
            }
            addView(microphone)
            addView(internal)
            addView(mixed)
            check(1)
            setOnCheckedChangeListener { _, checkedId ->
                selectedAudioMode = when (checkedId) {
                    2 -> ScreenStreamService.AUDIO_MODE_INTERNAL
                    3 -> ScreenStreamService.AUDIO_MODE_MIX
                    else -> ScreenStreamService.AUDIO_MODE_MICROPHONE
                }
            }
        }

        streamUrl = EditText(this).apply {
            hint = "RTMP Server URL (contoh: rtmp://live.restream.io/live)"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9EADB8.toInt())
            setPadding(12, 8, 12, 8)
        }

        streamKey = EditText(this).apply {
            hint = "Stream Key"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9EADB8.toInt())
            setPadding(12, 8, 12, 8)
        }

        streamStatus = TextView(this).apply {
            text = "Pilih sumber audio. Audio internal memerlukan Android 10+ dan dapat dibatasi oleh aplikasi/game."
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
                streamStatus.text = "Streaming dihentikan."
                previewLabel.text = "Streaming berhenti — preview tidak aktif"
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(previewView)
        root.addView(previewLabel)
        root.addView(sceneHeading)
        root.addView(sceneList)
        root.addView(addScene)
        root.addView(audioHeading)
        root.addView(audioModes)
        root.addView(streamUrl)
        root.addView(streamKey)
        root.addView(streamStatus)
        root.addView(startStream)
        root.addView(stopStream)
        setContentView(root)
        refreshScenes()
    }

    private fun buildRtmpEndpoint(): String? {
        val serverUrl = streamUrl.text.toString().trim().trimEnd('/')
        val key = streamKey.text.toString().trim()
        if (!serverUrl.startsWith("rtmp://") && !serverUrl.startsWith("rtmps://")) {
            Toast.makeText(this, "Masukkan RTMP Server URL yang valid.", Toast.LENGTH_LONG).show()
            return null
        }
        if (key.isEmpty()) {
            Toast.makeText(this, "Masukkan Stream Key dari Restream atau penyedia RTMP.", Toast.LENGTH_LONG).show()
            return null
        }
        return "$serverUrl/$key"
    }

    private fun requestScreenCapture() {
        if (buildRtmpEndpoint() == null) return
        if (selectedAudioMode != ScreenStreamService.AUDIO_MODE_MICROPHONE && Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Audio internal memerlukan Android 10 atau lebih baru.", Toast.LENGTH_LONG).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            return
        }
        launchScreenCaptureConsent()
    }

    private fun launchScreenCaptureConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    @Deprecated("Uses the platform runtime permission callback")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchScreenCaptureConsent()
            } else {
                Toast.makeText(this, "Izin mikrofon diperlukan untuk audio pada build uji ini.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Uses the platform screen-capture consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            streamStatus.text = "Izin screen capture dibatalkan."
            return
        }

        val endpoint = buildRtmpEndpoint() ?: return
        val serviceIntent = Intent(this, ScreenStreamService::class.java).apply {
            putExtra(ScreenStreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenStreamService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenStreamService.EXTRA_ENDPOINT, endpoint)
            putExtra(ScreenStreamService.EXTRA_AUDIO_MODE, selectedAudioMode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        if (!serviceBound) {
            serviceBound = bindService(Intent(this, ScreenStreamService::class.java), streamConnection, Context.BIND_AUTO_CREATE)
        }
        previewLabel.text = "Menyiapkan live preview…"
        streamStatus.text = "Permintaan streaming dikirim ke service."
    }

    private fun refreshScenes() {
        sceneList.removeAllViews()
        scenes.forEachIndexed { index, scene ->
            val button = Button(this).apply {
                text = if (index == selectedScene) "✓  $scene" else scene
                setOnClickListener {
                    selectedScene = index
                    previewLabel.text = "Scene: $scene — compositor menyusul"
                    refreshScenes()
                }
            }
            sceneList.addView(button)
        }
        if (scenes.isNotEmpty()) {
            previewLabel.text = "Scene: ${scenes[selectedScene]} — belum ada overlay/source."
        }
    }
}

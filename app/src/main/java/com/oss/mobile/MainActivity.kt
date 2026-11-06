package com.oss.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.graphics.drawable.GradientDrawable
import android.widget.ScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.CheckBox
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import com.oss.mobile.audio.AudioDiagnostics

class MainActivity : Activity() {
    private val scenes = mutableListOf<String>()
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
            applyCapturedContentVisibility(streamService?.isCapturedContentVisible() == true)
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
            when (intent?.action) {
                ScreenStreamService.ACTION_STATUS -> {
                    streamStatus.text = intent.getStringExtra(ScreenStreamService.EXTRA_STATUS)
                        ?: "Status streaming berubah."
                }
                ScreenStreamService.ACTION_CAPTURE_VISIBILITY -> {
                    val visible = intent.getBooleanExtra(ScreenStreamService.EXTRA_CAPTURE_VISIBLE, false)
                    applyCapturedContentVisibility(visible)
                }
            }
        }
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 4101
        private const val REQUEST_MICROPHONE = 4102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        try {
            loadScenes()
            renderStudio()
        } catch (error: Throwable) {
            showStartupError(error)
        }
    }

    private fun applyImmersiveMode() {
        window.statusBarColor = 0xFF0B1219.toInt()
        window.navigationBarColor = 0xFF0B1219.toInt()
        window.decorView.setBackgroundColor(0xFF0B1219.toInt())
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
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
            val filter = IntentFilter().apply {
                addAction(ScreenStreamService.ACTION_STATUS)
                addAction(ScreenStreamService.ACTION_CAPTURE_VISIBILITY)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
            statusReceiverRegistered = true
        }
        bindStreamService()
    }

    private fun bindStreamService() {
        if (!serviceBound) {
            serviceBound = bindService(
                Intent(this, ScreenStreamService::class.java),
                streamConnection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    private fun applyCapturedContentVisibility(visible: Boolean) {
        if (!::previewView.isInitialized || !::previewLabel.isInitialized) return
        if (visible) {
            streamService?.detachPreview(previewView)
            previewView.visibility = View.INVISIBLE
            previewLabel.visibility = View.VISIBLE
            previewLabel.text = "Preview disembunyikan sementara agar tidak menangkap dirinya sendiri."
        } else {
            previewView.visibility = View.VISIBLE
            previewLabel.visibility = View.VISIBLE
            previewLabel.text = "Menyiapkan live preview…"
            streamService?.attachPreview(previewView)
        }
    }

    override fun onStop() {
        if (serviceBound) {
            streamService?.detachPreview(previewView)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedBackground(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun makeText(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun makeCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = roundedBackground(0xFF17232E.toInt(), 18, 0xFF263746.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    private fun styleInput(input: EditText) {
        input.background = roundedBackground(0xFF0F1922.toInt(), 12, 0xFF314454.toInt())
        input.setPadding(dp(14), dp(12), dp(14), dp(12))
        input.textSize = 14f
    }

    private fun renderStudio() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1219.toInt())
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(18), dp(22), dp(18), dp(24))
            addView(page)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(makeText("OSS", 30f, 0xFFFFFFFF.toInt(), true))
        brand.addView(makeText("OPEN STREAM SOFTWARE", 10f, 0xFF8FA7B8.toInt(), true))
        val version = TextView(this).apply {
            text = "BETA"
            textSize = 10f
            setTextColor(0xFF7DE3FF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(0xFF123244.toInt(), 20)
        }
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(version)
        page.addView(header)
        page.addView(makeText("Your mobile streaming studio", 14f, 0xFF9FB2C1.toInt()))

        val previewCard = makeCard()
        val previewTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previewTop.addView(makeText("LIVE PREVIEW", 11f, 0xFF9FB2C1.toInt(), true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewTop.addView(makeText("9:16  •  PORTRAIT", 10f, 0xFF62D9F5.toInt(), true))
        previewCard.addView(previewTop)

        val previewContainer = FrameLayout(this).apply {
            background = roundedBackground(0xFF111D27.toInt(), 14, 0xFF314454.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
            ).apply { topMargin = dp(12) }
        }
        previewView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        previewContainer.addView(previewView)
        previewLabel = TextView(this).apply {
            text = "Preview akan muncul saat screen capture dimulai"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFDCE8F0.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        previewContainer.addView(previewLabel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                if (streamService?.isCapturedContentVisible() != true) {
                    streamService?.attachPreview(previewView)
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                streamService?.updatePreviewSize(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                streamService?.detachPreview(previewView)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                if (previewView.visibility == View.VISIBLE &&
                    streamService?.isCapturedContentVisible() != true &&
                    previewLabel.visibility != View.GONE
                ) {
                    previewLabel.visibility = View.GONE
                }
            }
        }
        previewCard.addView(previewContainer)
        page.addView(previewCard)

        val sceneCard = makeCard()
        val sceneHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sceneTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sceneTitle.addView(makeText("Scenes", 17f, 0xFFFFFFFF.toInt(), true))
        sceneTitle.addView(makeText("Atur layout siaran kamu", 12f, 0xFF8FA7B8.toInt()))
        sceneHeader.addView(sceneTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val addScene = Button(this).apply {
            text = "+  Add"
            isAllCaps = false
            textSize = 12f
            setTextColor(0xFF07131B.toInt())
            background = roundedBackground(0xFF61D9F4.toInt(), 12)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                scenes.add("Scene " + (scenes.size + 1))
                selectedScene = scenes.lastIndex
                saveScenes()
                refreshScenes()
            }
        }
        sceneHeader.addView(addScene)
        sceneCard.addView(sceneHeader)
        sceneList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        sceneCard.addView(sceneList)
        page.addView(sceneCard)

        val audioCard = makeCard()
        audioCard.addView(makeText("Audio source", 17f, 0xFFFFFFFF.toInt(), true))
        audioCard.addView(makeText("Pilih suara yang dikirim ke live", 12f, 0xFF8FA7B8.toInt()))
        val audioModes = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val microphone = RadioButton(this@MainActivity).apply {
                id = 1
                text = "Microphone"
                setTextColor(0xFFEAF2F8.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF61D9F4.toInt())
            }
            val internal = RadioButton(this@MainActivity).apply {
                id = 2
                text = "Internal game audio"
                setTextColor(0xFFEAF2F8.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF61D9F4.toInt())
            }
            val mixed = RadioButton(this@MainActivity).apply {
                id = 3
                text = "Internal audio + microphone"
                setTextColor(0xFFEAF2F8.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF61D9F4.toInt())
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
        audioCard.addView(audioModes)
        val diagnosticsButton = Button(this).apply {
            text = "Audio diagnostics"
            isAllCaps = false
            textSize = 13f
            setTextColor(0xFFEAF2F8.toInt())
            background = roundedBackground(0xFF263746.toInt(), 12)
            setOnClickListener {
                val report = AudioDiagnostics.snapshot()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("OSS Audio Diagnostics")
                    .setMessage(report)
                    .setPositiveButton("Salin laporan") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OSS audio diagnostics", report))
                        Toast.makeText(this@MainActivity, "Laporan diagnostik disalin.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Tutup", null)
                    .show()
            }
        }
        audioCard.addView(diagnosticsButton)

        val mixerButton = Button(this).apply {
            text = "Digital Audio Mixer"
            isAllCaps = false
            textSize = 13f
            setTextColor(0xFF07131B.toInt())
            background = roundedBackground(0xFF61D9F4.toInt(), 12)
            setOnClickListener { showDigitalMixer() }
        }
        audioCard.addView(mixerButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(8) })
        page.addView(audioCard)

        val destinationCard = makeCard()
        destinationCard.addView(makeText("Stream destination", 17f, 0xFFFFFFFF.toInt(), true))
        destinationCard.addView(makeText("Hubungkan ke server RTMP", 12f, 0xFF8FA7B8.toInt()))
        streamUrl = EditText(this).apply {
            hint = "RTMP Server URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8295A4.toInt())
            styleInput(this)
        }
        streamKey = EditText(this).apply {
            hint = "Stream Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8295A4.toInt())
            styleInput(this)
        }
        destinationCard.addView(streamUrl, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        destinationCard.addView(streamKey, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        page.addView(destinationCard)

        streamStatus = makeText(
            "Audio internal memerlukan Android 10+ dan dapat dibatasi oleh aplikasi/game.",
            12f, 0xFFFFD180.toInt()
        )
        streamStatus.setPadding(dp(4), dp(12), dp(4), dp(8))
        page.addView(streamStatus)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val startStream = Button(this).apply {
            text = "●  Go Live"
            isAllCaps = false
            textSize = 15f
            setTextColor(0xFF06151C.toInt())
            background = roundedBackground(0xFF61D9F4.toInt(), 14)
            setOnClickListener { requestScreenCapture() }
        }
        val stopStream = Button(this).apply {
            text = "Stop"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFFFD9DE.toInt())
            background = roundedBackground(0xFF40232B.toInt(), 14)
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenStreamService::class.java).setAction(ScreenStreamService.ACTION_STOP))
                streamStatus.text = "Streaming dihentikan."
                previewLabel.text = "Streaming berhenti — preview tidak aktif"
            }
        }
        actions.addView(startStream, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(8)
        })
        actions.addView(stopStream, LinearLayout.LayoutParams(dp(104), dp(52)))
        page.addView(actions)

        setContentView(scroll)
        refreshScenes()
    }

    private fun showDigitalMixer() {
        val service = streamService
        if (service == null || !service.isMixerAvailable()) {
            AlertDialog.Builder(this)
                .setTitle("Digital Audio Mixer")
                .setMessage("Mixer tersedia saat streaming aktif dengan mode Internal audio + microphone.")
                .setPositiveButton("Mengerti", null)
                .show()
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val scroll = ScrollView(this).apply {
            addView(content)
            isFillViewport = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("OSS Digital Mixer")
            .setView(scroll)
            .setPositiveButton("Tutup", null)
            .create()
        val meterRefreshers = mutableListOf<Pair<TextView, Runnable>>()

        fun addLabel(text: String, size: Float = 13f, color: Int = 0xFFEAF2F8.toInt(), bold: Boolean = false) {
            content.addView(makeText(text, size, color, bold).apply {
                setPadding(0, dp(8), 0, dp(3))
            })
        }

        fun addChannel(channelId: String, title: String) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(0xFF17232E.toInt(), 14, 0xFF314454.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val heading = makeText(title, 15f, 0xFFFFFFFF.toInt(), true)
            val meter = makeText("Peak 0%  •  RMS 0%", 11f, 0xFF7DE3FF.toInt())
            card.addView(heading)
            card.addView(meter)

            val gainLabel = makeText("Volume: 100%", 12f, 0xFFDCE8F0.toInt())
            card.addView(gainLabel)
            val gain = SeekBar(this).apply {
                max = 200
                progress = 100
            }
            card.addView(gain)
            gain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    gainLabel.text = "Volume: $progress%"
                    if (fromUser) service.setMixerChannelGain(channelId, progress / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })

            val balanceLabel = makeText("Balance: Center", 12f, 0xFFDCE8F0.toInt())
            card.addView(balanceLabel)
            val balance = SeekBar(this).apply {
                max = 200
                progress = 100
            }
            card.addView(balance)
            balance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress - 100
                    balanceLabel.text = when {
                        value < 0 -> "Balance: L ${-value}%"
                        value > 0 -> "Balance: R $value%"
                        else -> "Balance: Center"
                    }
                    if (fromUser) service.setMixerChannelBalance(channelId, value / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })

            val toggles = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val mute = CheckBox(this).apply {
                text = "Mute"
                setTextColor(0xFFEAF2F8.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF61D9F4.toInt())
            }
            mute.setOnCheckedChangeListener { _, checked ->
                service.setMixerChannelMuted(channelId, checked)
            }
            val live = CheckBox(this).apply {
                text = "LIVE"
                isChecked = true
                setTextColor(0xFFEAF2F8.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF61D9F4.toInt())
            }
            val monitor = CheckBox(this).apply {
                text = "MONITOR"
                isChecked = false
                setTextColor(0xFF8FA7B8.toInt())
                isEnabled = false
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF61D9F4.toInt())
            }
            live.setOnCheckedChangeListener { _, checked ->
                service.setMixerChannelRouting(channelId, checked, false)
            }
            toggles.addView(mute)
            toggles.addView(live)
            toggles.addView(monitor)
            card.addView(toggles)
            content.addView(card)

            val meterRefresh = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing) return
                    val level = service.mixerMeters()[channelId]
                    if (level != null) {
                        meter.text = "Peak ${(level.peak * 100).toInt()}%  •  RMS ${(level.rms * 100).toInt()}%"
                    } else {
                        meter.text = "Peak 0%  •  RMS 0%"
                    }
                    meter.postDelayed(this, 250L)
                }
            }
            meterRefreshers.add(meter to meterRefresh)
            meter.post(meterRefresh)
        }

        addLabel("INPUT CHANNELS", 11f, 0xFF8FA7B8.toInt(), true)
        addChannel("microphone", "Microphone")
        addChannel("internal", "Internal game audio")
        addLabel("MASTER — LIVE", 13f, 0xFFFFFFFF.toInt(), true)
        val masterLabel = makeText("Master volume: 100%", 12f, 0xFFDCE8F0.toInt())
        content.addView(masterLabel)
        val master = SeekBar(this).apply {
            max = 200
            progress = 100
        }
        content.addView(master)
        master.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                masterLabel.text = "Master volume: $progress%"
                if (fromUser) service.setMixerMasterGain(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        addLabel("MONITOR bus belum memiliki output playback. Kontrolnya belum diaktifkan.", 11f, 0xFF8FA7B8.toInt())
        dialog.setOnDismissListener {
            meterRefreshers.forEach { (view, runnable) -> view.removeCallbacks(runnable) }
        }
        dialog.show()
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
        bindStreamService()
        previewLabel.visibility = View.VISIBLE
        previewLabel.text = "Menyiapkan live preview…"
        streamStatus.text = "Permintaan streaming dikirim ke service."
    }

    private fun loadScenes() {
        val prefs = getSharedPreferences("oss_studio", MODE_PRIVATE)
        val saved = prefs.getString("scene_names", null)
        scenes.clear()
        if (!saved.isNullOrBlank()) {
            scenes.addAll(saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (scenes.isEmpty()) scenes.add("Scene 1")
        selectedScene = prefs.getInt("selected_scene", 0).coerceIn(0, scenes.lastIndex)
    }

    private fun saveScenes() {
        getSharedPreferences("oss_studio", MODE_PRIVATE).edit()
            .putString("scene_names", scenes.joinToString("\n"))
            .putInt("selected_scene", selectedScene)
            .apply()
    }

    private fun showSceneOptions(index: Int) {
        if (index !in scenes.indices) return
        val options = arrayOf("Rename scene", "Delete scene")
        AlertDialog.Builder(this)
            .setTitle(scenes[index])
            .setItems(options) { _, choice ->
                when (choice) {
                    0 -> {
                        val nameInput = EditText(this).apply {
                            setText(scenes[index])
                            setSelection(text.length)
                            hint = "Nama scene"
                            setSingleLine(true)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Rename scene")
                            .setView(nameInput)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Save") { _, _ ->
                                val newName = nameInput.text.toString().trim().replace("\n", " ")
                                if (newName.isNotEmpty()) {
                                    scenes[index] = newName
                                    saveScenes()
                                    refreshScenes()
                                } else {
                                    Toast.makeText(this, "Nama scene tidak boleh kosong.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .show()
                    }
                    1 -> {
                        if (scenes.size <= 1) {
                            Toast.makeText(this, "Minimal harus ada satu scene.", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Delete scene?")
                            .setMessage("Scene \"${scenes[index]}\" akan dihapus.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete") { _, _ ->
                                scenes.removeAt(index)
                                selectedScene = when {
                                    selectedScene > index -> selectedScene - 1
                                    selectedScene == index -> (index - 1).coerceAtLeast(0)
                                    else -> selectedScene
                                }
                                saveScenes()
                                refreshScenes()
                            }
                            .show()
                    }
                }
            }
            .show()
    }

    private fun refreshScenes() {
        sceneList.removeAllViews()
        scenes.forEachIndexed { index, scene ->
            val button = Button(this).apply {
                text = if (index == selectedScene) "✓  $scene" else scene
                isAllCaps = false
                setOnClickListener {
                    selectedScene = index
                    saveScenes()
                    refreshScenes()
                }
                setOnLongClickListener {
                    showSceneOptions(index)
                    true
                }
            }
            sceneList.addView(button)
        }
        if (scenes.isNotEmpty()) {
            previewLabel.text = "Scene dipilih: ${scenes[selectedScene]} — pengaturan scene tersimpan; compositor visual menyusul."
        }
    }
}

package com.oss.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.TextureView
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.oss.mobile.audio.OssBufferedAudioMixerSource
import com.oss.mobile.audio.AudioDiagnostics
import com.oss.mobile.audio.DiagnosticAudioSource
import com.oss.mobile.diagnostics.DiagnosticCategory
import com.oss.mobile.diagnostics.DiagnosticSeverity
import com.oss.mobile.diagnostics.OssDiagnostics
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

/**
 * First RTMP proof-of-concept: captures the device display after explicit
 * MediaProjection consent and sends it through RootEncoder.
 *
 * This initial version uses the microphone as audio input. Internal playback
 * audio, persistent notification controls and reconnect logic are later tasks.
 */
class ScreenStreamService : Service(), ConnectChecker {
    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_ENDPOINT = "stream_endpoint"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val AUDIO_MODE_MICROPHONE = "microphone"
        const val AUDIO_MODE_INTERNAL = "internal"
        const val AUDIO_MODE_MIX = "mix"
        const val ACTION_STATUS = "com.oss.mobile.ACTION_STREAM_STATUS"
        const val ACTION_STOP = "com.oss.mobile.ACTION_STOP"
        const val ACTION_CAPTURE_VISIBILITY = "com.oss.mobile.ACTION_CAPTURE_VISIBILITY"
        const val EXTRA_CAPTURE_VISIBLE = "capture_visible"
        const val EXTRA_STATUS = "stream_status"
        private const val CHANNEL_ID = "oss_screen_stream"
        private const val NOTIFICATION_ID = 41
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private var stream: GenericStream? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var capturedContentVisible = false
    private var pendingPreview: TextureView? = null
    private var overlayCompositor: OverlayCompositor? = null
    private var userRequestedStop = false
    private var lastReconnectAttemptAt = 0L
    private var lastDiagnosticBitrateAt = 0L
    private val diagnosticsHandler = Handler(Looper.getMainLooper())
    private val systemDiagnosticTask = object : Runnable {
        override fun run() {
            if (stream == null) return
            recordSystemHealth()
            diagnosticsHandler.postDelayed(this, 30_000L)
        }
    }
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null
    private var diagnosticAudioManager: AudioManager? = null
    @Volatile private var mixerSource: OssBufferedAudioMixerSource? = null

    fun isMixerAvailable(): Boolean = mixerSource != null
    fun setMixerChannelGain(channelId: String, gain: Float): Boolean =
        mixerSource?.setChannelGain(channelId, gain) ?: false
    fun setMixerChannelMuted(channelId: String, muted: Boolean): Boolean =
        mixerSource?.setChannelMuted(channelId, muted) ?: false
    fun setMixerChannelBalance(channelId: String, balance: Float): Boolean =
        mixerSource?.setChannelBalance(channelId, balance) ?: false
    fun setMixerChannelRouting(channelId: String, toLive: Boolean, toMonitor: Boolean): Boolean =
        mixerSource?.setChannelRouting(channelId, toLive, toMonitor) ?: false
    fun setMixerMasterGain(gain: Float) {
        mixerSource?.setMasterGain(com.oss.mobile.audio.AudioOutputBus.LIVE, gain)
    }
    fun mixerMasterGain(): Float =
        mixerSource?.masterGain(com.oss.mobile.audio.AudioOutputBus.LIVE) ?: 1f
    fun mixerChannelState(channelId: String): com.oss.mobile.audio.AudioChannelState? =
        mixerSource?.channelState(channelId)
    fun mixerMeters() = mixerSource?.channelMeters() ?: emptyMap<String, com.oss.mobile.audio.AudioLevelMeter>()

    fun isCapturedContentVisible(): Boolean = capturedContentVisible

    /**
     * Apply visual layers provided by installed source modules to the encoded stream.
     * The compositor is initialized only after the video encoder is prepared.
     */
    fun setCompositorLayers(layers: List<CompositorLayer>) {
        overlayCompositor?.setLayers(layers)
    }

    fun clearCompositorLayers() {
        overlayCompositor?.clear()
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): ScreenStreamService = this@ScreenStreamService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    fun attachPreview(textureView: TextureView) {
        pendingPreview = textureView
        startPreviewIfReady(textureView)
    }

    private fun startPreviewIfReady(textureView: TextureView) {
        val current = stream ?: return
        if (!textureView.isAvailable || current.isOnPreview) return
        val surfaceTexture = textureView.surfaceTexture ?: return
        val width = textureView.width.coerceAtLeast(1)
        val height = textureView.height.coerceAtLeast(1)
        try {
            current.startPreview(surfaceTexture, width, height)
            sendStatus("Live preview aktif.")
        } catch (error: Exception) {
            sendStatus("Preview belum tersedia: ${error.message ?: "coba buka ulang studio"}")
        }
    }

    fun updatePreviewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        try {
            stream?.getGlInterface()?.setPreviewResolution(width, height)
        } catch (_: Exception) {
        }
    }

    fun detachPreview(textureView: TextureView) {
        if (pendingPreview === textureView) {
            try {
                if (stream?.isOnPreview == true) stream?.stopPreview()
            } catch (_: Exception) {
            }
            pendingPreview = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            userRequestedStop = true
            sendStatus("Menghentikan streaming…")
            stopStreaming()
            sendStatus("Streaming dihentikan.")
            return START_NOT_STICKY
        }

        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = getProjectionData(intent)

        if (endpoint.isBlank() || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            userRequestedStop = false
            lastReconnectAttemptAt = 0L
            startAsForeground()
            diagnosticsHandler.removeCallbacks(systemDiagnosticTask)
            sendStatus("Menyiapkan encoder dan screen capture…")
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection tidak tersedia")
            capturedContentVisible = false
            if (Build.VERSION.SDK_INT >= 34) {
                val callback = object : MediaProjection.Callback() {
                    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                        capturedContentVisible = isVisible
                        val visibilityIntent = Intent(ACTION_CAPTURE_VISIBILITY).apply {
                            setPackage(packageName)
                            putExtra(EXTRA_CAPTURE_VISIBLE, isVisible)
                        }
                        sendBroadcast(visibilityIntent)
                    }
                }
                projectionCallback = callback
                projection?.registerCallback(callback, Handler(Looper.getMainLooper()))
            }

            val audioMode = intent.getStringExtra(EXTRA_AUDIO_MODE) ?: AUDIO_MODE_MICROPHONE
            AudioDiagnostics.beginCaptureSession(audioMode)
            OssDiagnostics.center.record(DiagnosticCategory.APP, DiagnosticSeverity.INFO, "STREAM_INIT", "Stream initialization started", mapOf("audioMode" to audioMode, "sdk" to Build.VERSION.SDK_INT.toString()))
            AudioDiagnostics.add("Android SDK=${Build.VERSION.SDK_INT}; selectedMode=$audioMode")
            startAudioRecordingDiagnostics()
            val rawAudioInput = when (audioMode) {
                AUDIO_MODE_INTERNAL -> {
                    if (Build.VERSION.SDK_INT < 29) {
                        throw IllegalStateException("Audio internal memerlukan Android 10+")
                    }
                    InternalAudioSource(projection!!)
                }
                AUDIO_MODE_MIX -> {
                    if (Build.VERSION.SDK_INT < 29) {
                        throw IllegalStateException("Audio internal memerlukan Android 10+")
                    }
                    OssBufferedAudioMixerSource(projection!!)
                }
                else -> MicrophoneSource()
            }
            mixerSource = rawAudioInput as? OssBufferedAudioMixerSource
            val audioInput = if (rawAudioInput is OssBufferedAudioMixerSource) {
                rawAudioInput
            } else {
                DiagnosticAudioSource(rawAudioInput, audioMode)
            }
            AudioDiagnostics.add("Selected source adapter=${audioInput.javaClass.simpleName}")

            val output = GenericStream(this, this, NoVideoSource(), audioInput)
            // RootEncoder handles bounded reconnect attempts without rebuilding
            // MediaProjection, screen capture, or the audio pipeline.
            output.getStreamClient().setReTries(MAX_RECONNECT_ATTEMPTS)
            stream = output
            diagnosticsHandler.post(systemDiagnosticTask)
            val isMixedAudio = audioMode == AUDIO_MODE_MIX
            val prepared = output.prepareVideo(720, 1280, 1_800_000, rotation = 0) &&
                output.prepareAudio(
                    32000,
                    true,
                    96000,
                    // Keep the physical mic path unprocessed while independent playback
                    // capture is active. Device AEC/NS can aggressively attenuate speech
                    // when game playback is also present; echo control can be added later
                    // as an explicit mixer processing stage.
                    echoCanceler = false,
                    noiseSuppressor = false
                )

            OssDiagnostics.center.record(DiagnosticCategory.VIDEO, if (prepared) DiagnosticSeverity.INFO else DiagnosticSeverity.ERROR, "ENCODER_PREPARE", "Encoder preparation completed", mapOf("prepared" to prepared.toString(), "width" to "720", "height" to "1280", "fps" to "15", "audioSampleRate" to "32000"))
            if (!prepared) throw IllegalStateException("Encoder tidak berhasil disiapkan")

            // Prefer the wired headset microphone when Android exposes one.
            // InternalAudioSource remains playback-capture based and is not rerouted.
            applyWiredHeadsetInput(rawAudioInput)

            overlayCompositor = OverlayCompositor(output.getGlInterface())
            output.getGlInterface().setForceRender(true, 15)
            // ScreenSource manages display rotation itself; do not apply camera rotation.
            output.getGlInterface().setCameraOrientation(0)
            output.changeVideoSource(ScreenSource(applicationContext, projection!!))
            pendingPreview?.let { view ->
                startPreviewIfReady(view)
            }
            val audioLabel = when (audioMode) {
                AUDIO_MODE_INTERNAL -> "audio internal"
                AUDIO_MODE_MIX -> "audio internal + mikrofon"
                else -> "mikrofon"
            }
            sendStatus("Mengirim stream ke server RTMP dengan sumber $audioLabel…")
            output.startStream(endpoint)
        } catch (error: Exception) {
            OssDiagnostics.center.record(DiagnosticCategory.APP, DiagnosticSeverity.ERROR, "STREAM_START_EXCEPTION", error.javaClass.simpleName + ": " + (error.message ?: "unknown"))
            Toast.makeText(this, "OSS: ${error.message ?: "Gagal memulai streaming"}", Toast.LENGTH_LONG).show()
            stopStreaming()
        }

        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun getProjectionData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OSS screen streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startAsForeground() {
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("OSS sedang streaming")
                .setContentText("Screen capture aktif")
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("OSS sedang streaming")
                .setContentText("Screen capture aktif")
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= 30) {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun recordSystemHealth() {
        try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val memory = android.app.ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val pssKb = android.os.Debug.getPss()
            val thermal = if (Build.VERSION.SDK_INT >= 29) {
                (getSystemService(POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
            } else -1
            OssDiagnostics.center.record(
                DiagnosticCategory.SYSTEM,
                when {
                    memory.lowMemory -> DiagnosticSeverity.WARNING
                    Build.VERSION.SDK_INT >= 29 && thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> DiagnosticSeverity.WARNING
                    else -> DiagnosticSeverity.INFO
                },
                "SYSTEM_HEALTH_SAMPLE",
                "Periodic system health sample",
                mapOf(
                    "availableMemoryBytes" to memory.availMem.toString(),
                    "lowMemory" to memory.lowMemory.toString(),
                    "appPssKb" to pssKb.toString(),
                    "thermalStatus" to thermal.toString(),
                    "thermalStatusName" to thermalStatusName(thermal)
                )
            )
        } catch (error: Exception) {
            OssDiagnostics.center.record(DiagnosticCategory.SYSTEM, DiagnosticSeverity.WARNING, "SYSTEM_SAMPLE_FAILED", error.javaClass.simpleName)
        }
    }

    private fun thermalStatusName(status: Int): String = when (status) {
        android.os.PowerManager.THERMAL_STATUS_NONE -> "NONE"
        android.os.PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        android.os.PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        android.os.PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNAVAILABLE"
    }

    private fun startAudioRecordingDiagnostics() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        diagnosticAudioManager = manager
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .joinToString { "${it.type}:${it.productName}" }
        AudioDiagnostics.add("AudioManager mode=${manager.mode}; micMuted=${manager.isMicrophoneMute}; inputDevices=$inputs")

        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                AudioDiagnostics.recordingConfigurations(configs)
            }
        }
        audioRecordingCallback = callback
        try {
            manager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            AudioDiagnostics.recordingConfigurations(manager.activeRecordingConfigurations)
        } catch (error: Exception) {
            AudioDiagnostics.add("Unable to register recording callback: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun stopAudioRecordingDiagnostics() {
        val manager = diagnosticAudioManager
        val callback = audioRecordingCallback
        if (manager != null && callback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                manager.unregisterAudioRecordingCallback(callback)
            } catch (_: Exception) {
            }
        }
        audioRecordingCallback = null
        diagnosticAudioManager = null
    }

    private fun applyWiredHeadsetInput(audioInput: com.pedro.encoder.input.sources.audio.AudioSource) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val wiredMic = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: return

        val applied = when (audioInput) {
            is MicrophoneSource -> audioInput.setPreferredDevice(wiredMic)
            is OssBufferedAudioMixerSource -> audioInput.setPreferredMicrophoneDevice(wiredMic)
            else -> false
        }
        AudioDiagnostics.add("Preferred wired microphone=${wiredMic.productName}; type=${wiredMic.type}; applied=$applied")
    }

    private fun stopStreaming() {
        userRequestedStop = true
        diagnosticsHandler.removeCallbacks(systemDiagnosticTask)
        OssDiagnostics.center.record(DiagnosticCategory.APP, DiagnosticSeverity.INFO, "STREAM_STOP_REQUESTED", "Streaming stop requested")
        OssDiagnostics.center.endSession("stopped")
        stopAudioRecordingDiagnostics()
        try {
            overlayCompositor?.clear()
            overlayCompositor = null
            stream?.stopStream()
            stream?.release()
        } catch (_: Exception) {
            // Best-effort cleanup after partial initialization.
        }
        stream = null
        mixerSource = null
        pendingPreview = null
        releaseProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopStreamingResources()
        super.onDestroy()
    }

    private fun stopStreamingResources() {
        diagnosticsHandler.removeCallbacks(systemDiagnosticTask)
        OssDiagnostics.center.endSession("service_destroyed")
        stopAudioRecordingDiagnostics()
        try {
            overlayCompositor?.clear()
            overlayCompositor = null
            stream?.stopStream()
            stream?.release()
        } catch (_: Exception) {
        }
        stream = null
        mixerSource = null
        releaseProjection()
    }

    private fun releaseProjection() {
        val currentProjection = projection
        val callback = projectionCallback
        if (currentProjection != null && callback != null) {
            try {
                currentProjection.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        projectionCallback = null
        capturedContentVisible = false
        try {
            currentProjection?.stop()
        } catch (_: Exception) {
        }
        projection = null
    }

    override fun onConnectionStarted(url: String) {
        OssDiagnostics.center.record(DiagnosticCategory.STREAM, DiagnosticSeverity.INFO, "RTMP_CONNECTING", "Connecting to RTMP endpoint; credentials omitted")
        updateNotification("Menghubungkan ke server RTMP…")
        sendStatus("Menghubungkan ke server RTMP…")
    }

    override fun onConnectionSuccess() {
        OssDiagnostics.center.record(DiagnosticCategory.STREAM, DiagnosticSeverity.INFO, "RTMP_CONNECTED", "RTMP connection established")
        lastReconnectAttemptAt = 0L
        updateNotification("Terhubung — streaming aktif")
        sendStatus("TERHUBUNG — streaming aktif ke RTMP.")
    }

    override fun onConnectionFailed(reason: String) {
        OssDiagnostics.center.record(DiagnosticCategory.STREAM, DiagnosticSeverity.ERROR, "RTMP_CONNECTION_FAILED", reason)
        attemptReconnect(reason)
    }

    override fun onNewBitrate(bitrate: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDiagnosticBitrateAt >= 5000L) {
            lastDiagnosticBitrateAt = now
            OssDiagnostics.center.record(DiagnosticCategory.STREAM, DiagnosticSeverity.INFO, "RTMP_BITRATE", "Encoder bitrate sample", mapOf("bitrateBps" to bitrate.toString()))
        }
    }

    override fun onDisconnect() {
        OssDiagnostics.center.record(DiagnosticCategory.STREAM, if (userRequestedStop) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING, "RTMP_DISCONNECTED", "RTMP connection disconnected", mapOf("requested" to userRequestedStop.toString()))
        if (!userRequestedStop) {
            attemptReconnect("Koneksi RTMP terputus")
        } else {
            updateNotification("Streaming dihentikan")
            sendStatus("Streaming dihentikan.")
        }
    }

    private fun attemptReconnect(reason: String) {
        if (userRequestedStop) return
        val now = android.os.SystemClock.elapsedRealtime()
        // Ignore duplicate failure/disconnect callbacks from the same socket event,
        // but allow later failures to consume the configured retry budget.
        if (now - lastReconnectAttemptAt < 1000L) return
        lastReconnectAttemptAt = now
        val current = stream
        val retryAccepted = try {
            current?.getStreamClient()?.reTry(RECONNECT_DELAY_MS, reason) == true
        } catch (_: Exception) {
            false
        }
        OssDiagnostics.center.record(DiagnosticCategory.NETWORK, if (retryAccepted) DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR, "RTMP_RECONNECT", "Reconnect attempt evaluated", mapOf("accepted" to retryAccepted.toString()))
        if (retryAccepted) {
            updateNotification("Koneksi terputus — mencoba menyambungkan ulang…")
            sendStatus("Koneksi terputus: $reason. Mencoba menyambungkan ulang (maksimal $MAX_RECONNECT_ATTEMPTS kali)…")
        } else {
            updateNotification("Streaming terputus: $reason")
            sendStatus("Streaming terputus: $reason. Percobaan sambung ulang tidak tersedia atau batas percobaan tercapai.")
            stopStreaming()
        }
    }

    override fun onAuthError() {
        OssDiagnostics.center.record(DiagnosticCategory.STREAM, DiagnosticSeverity.ERROR, "RTMP_AUTH_ERROR", "RTMP authentication failed; credentials omitted")
        updateNotification("Autentikasi server gagal")
        sendStatus("Autentikasi gagal. Periksa Stream Key dan RTMP URL.")
        stopStreaming()
    }

    override fun onAuthSuccess() {
        OssDiagnostics.center.record(DiagnosticCategory.STREAM, DiagnosticSeverity.INFO, "RTMP_AUTH_SUCCESS", "RTMP authentication succeeded")
        updateNotification("Autentikasi berhasil")
        sendStatus("Autentikasi server berhasil.")
    }

    private fun sendStatus(message: String) {
        val statusIntent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, message)
        }
        sendBroadcast(statusIntent)
    }

    private fun updateNotification(message: String) {
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("OSS screen streaming")
            .setContentText(message)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}

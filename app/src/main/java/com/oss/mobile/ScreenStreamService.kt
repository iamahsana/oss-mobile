package com.oss.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import com.oss.mobile.audio.OssAudioMixerSource
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
            val audioInput = when (audioMode) {
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
                    OssAudioMixerSource(projection!!)
                }
                else -> MicrophoneSource()
            }

            val output = GenericStream(this, this, NoVideoSource(), audioInput)
            // RootEncoder handles bounded reconnect attempts without rebuilding
            // MediaProjection, screen capture, or the audio pipeline.
            output.getStreamClient().setReTries(MAX_RECONNECT_ATTEMPTS)
            stream = output
            val isMixedAudio = audioMode == AUDIO_MODE_MIX
            val prepared = output.prepareVideo(720, 1280, 1_800_000, rotation = 0) &&
                output.prepareAudio(
                    32000,
                    true,
                    96000,
                    echoCanceler = isMixedAudio,
                    noiseSuppressor = isMixedAudio
                )

            if (!prepared) throw IllegalStateException("Encoder tidak berhasil disiapkan")

            // Prefer the wired headset microphone when Android exposes one.
            // InternalAudioSource remains playback-capture based and is not rerouted.
            applyWiredHeadsetInput(audioInput)

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

    private fun applyWiredHeadsetInput(audioInput: com.pedro.encoder.input.sources.audio.AudioSource) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val wiredMic = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: return

        when (audioInput) {
            is MicrophoneSource -> audioInput.setPreferredDevice(wiredMic)
            is OssAudioMixerSource -> audioInput.setPreferredMicrophoneDevice(wiredMic)
        }
    }

    private fun stopStreaming() {
        userRequestedStop = true
        try {
            overlayCompositor?.clear()
            overlayCompositor = null
            stream?.stopStream()
            stream?.release()
        } catch (_: Exception) {
            // Best-effort cleanup after partial initialization.
        }
        stream = null
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
        try {
            overlayCompositor?.clear()
            overlayCompositor = null
            stream?.stopStream()
            stream?.release()
        } catch (_: Exception) {
        }
        stream = null
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
        updateNotification("Menghubungkan ke server RTMP…")
        sendStatus("Menghubungkan ke server RTMP…")
    }

    override fun onConnectionSuccess() {
        lastReconnectAttemptAt = 0L
        updateNotification("Terhubung — streaming aktif")
        sendStatus("TERHUBUNG — streaming aktif ke RTMP.")
    }

    override fun onConnectionFailed(reason: String) {
        attemptReconnect(reason)
    }

    override fun onNewBitrate(bitrate: Long) = Unit

    override fun onDisconnect() {
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
        updateNotification("Autentikasi server gagal")
        sendStatus("Autentikasi gagal. Periksa Stream Key dan RTMP URL.")
        stopStreaming()
    }

    override fun onAuthSuccess() {
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

package com.oss.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
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
        private const val CHANNEL_ID = "oss_screen_stream"
        private const val NOTIFICATION_ID = 41
    }

    private var stream: GenericStream? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
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
            startAsForeground()
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection tidak tersedia")

            val output = GenericStream(this, this, NoVideoSource(), MicrophoneSource())
            stream = output
            val prepared = output.prepareVideo(720, 1280, 1_800_000, rotation = 0) &&
                output.prepareAudio(32000, true, 96000)

            if (!prepared) throw IllegalStateException("Encoder tidak berhasil disiapkan")

            output.getGlInterface().setForceRender(true, 15)
            output.changeVideoSource(ScreenSource(applicationContext, projection!!))
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

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopStreaming() {
        try {
            stream?.stopStream()
            stream?.release()
        } catch (_: Exception) {
            // Best-effort cleanup after partial initialization.
        }
        stream = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopStreamingResources()
        super.onDestroy()
    }

    private fun stopStreamingResources() {
        try {
            stream?.stopStream()
            stream?.release()
        } catch (_: Exception) {
        }
        stream = null
        projection?.stop()
        projection = null
    }

    override fun onConnectionStarted(url: String) {
        updateNotification("Menghubungkan ke server RTMP…")
    }

    override fun onConnectionSuccess() {
        updateNotification("Terhubung — streaming aktif")
    }

    override fun onConnectionFailed(reason: String) {
        updateNotification("Koneksi gagal: $reason")
        stopStreaming()
    }

    override fun onNewBitrate(bitrate: Long) = Unit

    override fun onDisconnect() {
        updateNotification("Streaming terputus")
    }

    override fun onAuthError() {
        updateNotification("Autentikasi server gagal")
        stopStreaming()
    }

    override fun onAuthSuccess() {
        updateNotification("Autentikasi berhasil")
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

package com.mushafqiyam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AudioCaptureService : Service() {

    private lateinit var engine: MushafEngine
    private lateinit var audioCapture: AudioCapture
    
    companion object {
        const val CHANNEL_ID = "MushafQiyamAudioChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Note: engine paths should ideally be resolved and passed appropriately.
        // For the harness, we initialize a dummy or wait for paths.
        engine = MushafEngine()
        // Temporary dummy initialization
        // engine.initEngine("", "", "") 
        audioCapture = AudioCapture(engine)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                audioCapture.start()
            }
            ACTION_STOP -> {
                audioCapture.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        audioCapture.stop()
        engine.destroyEngine()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't need bound service for now
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Mushaf Qiyam Recording"
            val descriptionText = "Keeps the microphone active during prayer"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("مصحف القيام")
            .setContentText("يستمع لتلاوتك الآن...")
            // Replace with actual icon later
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) 
            .setOngoing(true)
            .build()
    }
}

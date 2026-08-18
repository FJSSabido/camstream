package com.miconstelacion.camstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Servicio en primer plano: mantiene viva la emisión (cámara/pantalla + micrófono)
 * aunque el usuario apague la pantalla o cambie de app, con una notificación
 * persistente y un botón para detenerla desde ahí mismo.
 */
class BroadcastService : Service() {

    companion object {
        const val ACTION_START = "com.miconstelacion.camstream.action.START"
        const val ACTION_STOP = "com.miconstelacion.camstream.action.STOP"
        private const val CHANNEL_ID = "camstream_live"
        private const val NOTIF_ID = 42

        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_ROOM = "room"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_USE_SCREEN = "useScreen"
        const val EXTRA_USE_FRONT_CAMERA = "useFrontCamera"
        const val EXTRA_MIC_ENABLED = "micEnabled"
        const val EXTRA_INTERNAL_AUDIO = "internalAudio"
        const val EXTRA_PROJECTION_RESULT_CODE = "projectionResultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BroadcastEngine.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val useScreen = intent?.getBooleanExtra(EXTRA_USE_SCREEN, false) ?: false
        startForegroundCompat(useScreen)

        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: ""
        val room = intent?.getStringExtra(EXTRA_ROOM) ?: ""
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""
        val useFrontCamera = intent?.getBooleanExtra(EXTRA_USE_FRONT_CAMERA, false) ?: false
        val micEnabled = intent?.getBooleanExtra(EXTRA_MIC_ENABLED, true) ?: true
        val internalAudio = intent?.getBooleanExtra(EXTRA_INTERNAL_AUDIO, false) ?: false
        val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)

        BroadcastEngine.start(
            context = applicationContext,
            serverUrl = serverUrl,
            room = room,
            password = password,
            useScreen = useScreen,
            useFrontCamera = useFrontCamera,
            micEnabled = micEnabled,
            captureInternalAudio = internalAudio,
            projectionResultCode = resultCode,
            projectionData = data
        )

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        BroadcastEngine.stop()
    }

    private fun startForegroundCompat(useScreen: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (useScreen) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "Transmisión en directo", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, BroadcastService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CamStream está en directo")
            .setContentText("Toca para volver a la app")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPending)
            .addAction(0, "Detener", stopPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}

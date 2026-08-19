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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano: mantiene viva la emisión (cámara/pantalla + micrófono)
 * aunque el usuario apague la pantalla o cambie de app, con una notificación
 * persistente y un botón para detenerla desde ahí mismo.
 *
 * IMPORTANTE: el servicio se mantiene en primer plano (con cámara/micrófono
 * reservados) mientras [BroadcastEngine.stateFlow] diga que hay una emisión
 * "en marcha" (isRunning). Si el motor se detiene solo — por ejemplo, porque
 * falla la conexión con el servidor de señalización — este servicio se entera
 * y se para también, para no dejar la cámara ni el micrófono encendidos de
 * fondo con la notificación mostrando información obsoleta.
 *
 * También es quien media los cambios de fuente EN CALIENTE hacia/desde pantalla
 * (ACTION_SWITCH_SOURCE): en Android 14+ hay que volver a declarar el tipo de
 * "foreground service" (cámara ↔ mediaProjection) ANTES de pedir la captura de
 * pantalla, o el sistema puede matar el servicio. Cambiar entre cámara delantera y
 * trasera no pasa por aquí — no cambia el tipo de servicio, así que la Activity
 * llama directamente a BroadcastEngine.switchSource().
 *
 * IMPORTANTE — cerrar la app SÍ tiene que cortar la emisión: un foreground service
 * "normal" sobrevive aunque el usuario elimine la app de la lista de recientes (es
 * justo para eso que sirve un foreground service). Aquí NO lo queremos: si el
 * usuario cierra la app deslizándola fuera de recientes, la cámara/pantalla y el
 * micrófono tienen que apagarse en el acto, no seguir emitiendo en segundo plano de
 * forma invisible. Por eso [onTaskRemoved] para la emisión explícitamente. Apagar
 * solo la PANTALLA del móvil (sin cerrar/deslizar la app) sigue manteniendo la
 * emisión viva, como es de esperar.
 */
class BroadcastService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wasRunning = false
    private var lastSource: VideoSource? = null
    private var initializedState = false

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            BroadcastEngine.stateFlow.collect { state ->
                if (!initializedState) {
                    // Primera lectura al suscribirnos: BroadcastEngine es un singleton
                    // que vive todo el proceso, así que este primer valor puede venir
                    // de un intento ANTERIOR (por ejemplo, uno que terminó en error) y
                    // no de esta instancia del servicio. Si lo tratáramos igual que un
                    // cambio real, "isRunning=true" heredado de antes podía leerse como
                    // "wasRunning" y, en cuanto BroadcastEngine.start() hace su stop()
                    // inicial para limpiar cualquier emisión previa, este bloque lo
                    // interpretaba como "el motor se ha parado solo" y se autodestruía
                    // el servicio a los pocos milisegundos de arrancar la cámara — antes
                    // incluso de que la sesión de cámara terminara de abrirse. Por eso
                    // aquí solo memorizamos el punto de partida, sin actuar sobre él.
                    initializedState = true
                    lastSource = state.activeSource
                    wasRunning = state.isRunning
                    return@collect
                }
                if (wasRunning && !state.isRunning) {
                    // El motor ya se ha soltado a sí mismo (cámara/micrófono liberados);
                    // quitamos también la notificación y terminamos el servicio.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (state.isRunning && state.activeSource != lastSource) {
                    // Red de seguridad: si la fuente cambió sin pasar por
                    // ACTION_SWITCH_SOURCE (no debería, pero por si acaso), nos
                    // aseguramos igualmente de que el tipo declarado está al día.
                    startForegroundCompat(state.activeSource)
                }
                lastSource = state.activeSource
                wasRunning = state.isRunning
            }
        }
    }

    companion object {
        const val ACTION_START = "com.miconstelacion.camstream.action.START"
        const val ACTION_STOP = "com.miconstelacion.camstream.action.STOP"
        const val ACTION_SWITCH_SOURCE = "com.miconstelacion.camstream.action.SWITCH_SOURCE"
        private const val CHANNEL_ID = "camstream_live"
        private const val NOTIF_ID = 42

        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_ROOM = "room"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_MIC_ENABLED = "micEnabled"
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

        if (intent?.action == ACTION_SWITCH_SOURCE) {
            val source = parseSource(intent.getStringExtra(EXTRA_SOURCE))
            // Declaramos el tipo de servicio correcto ANTES de tocar MediaProjection
            // (obligatorio en Android 14+ cuando se entra o se sale de pantalla).
            startForegroundCompat(source)
            val data: Intent? = intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            BroadcastEngine.switchSource(source, data)
            return START_NOT_STICKY
        }

        val source = parseSource(intent?.getStringExtra(EXTRA_SOURCE))
        startForegroundCompat(source)

        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: ""
        val room = intent?.getStringExtra(EXTRA_ROOM) ?: ""
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""
        val micEnabled = intent?.getBooleanExtra(EXTRA_MIC_ENABLED, true) ?: true
        val data: Intent? = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)

        BroadcastEngine.start(
            context = applicationContext,
            serverUrl = serverUrl,
            room = room,
            password = password,
            source = source,
            micEnabled = micEnabled,
            projectionData = data
        )

        return START_NOT_STICKY
    }

    private fun parseSource(name: String?): VideoSource =
        try {
            if (name != null) VideoSource.valueOf(name) else VideoSource.BACK_CAMERA
        } catch (e: Exception) {
            VideoSource.BACK_CAMERA
        }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        BroadcastEngine.stop()
    }

    /**
     * Se llama cuando el usuario elimina la app de la lista de apps recientes
     * (deslizándola fuera). Un foreground service sin este método seguiría vivo —
     * cámara/pantalla y micrófono encendidos — de forma invisible, que es
     * precisamente lo que no queremos: cerrar la app tiene que cortar la emisión.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        BroadcastEngine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * El vídeo es opcional (igual que el micrófono, ver [VideoSource.NONE]), así
     * que el tipo de "foreground service" declarado en Android 14+ tiene que
     * reflejar SOLO el recurso que realmente se está usando: cámara únicamente
     * con cámara delantera/trasera activa, mediaProjection únicamente con
     * pantalla, y NINGUNO de los dos (solo micrófono) cuando no hay vídeo — pedir
     * un tipo que luego no se usa de verdad es justo lo que Android 14+ vigila.
     */
    private fun startForegroundCompat(source: VideoSource) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            val type = when (source) {
                VideoSource.SCREEN ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                VideoSource.FRONT_CAMERA, VideoSource.BACK_CAMERA ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                VideoSource.NONE ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
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

package com.miconstelacion.camstream

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection

private const val TAG = "BroadcastEngine"

/** Las tres fuentes de vídeo posibles. Nunca hay dos activas a la vez. */
enum class VideoSource { NONE, FRONT_CAMERA, BACK_CAMERA, SCREEN }

/** Un mensaje del chat compartido — de un espectador o del propio anfitrión. */
data class ChatMessage(
    val fromHost: Boolean,
    val name: String,
    val text: String,
    val ts: Long,
    val viewerId: String? = null
)

data class BroadcastState(
    // true desde el instante en que se intenta arrancar (cámara/pantalla + micrófono ya
    // en marcha) hasta que se detiene o falla del todo. Es lo que debe usar la UI para
    // decidir si el botón dice "Detener" y si hay que dejar los campos bloqueados —
    // NO depende de si ya hay conexión confirmada con el servidor.
    val isRunning: Boolean = false,
    // true solo cuando el servidor de señalización ha confirmado la conexión
    // (host-ready). Se usa para mostrar la tarjeta con el enlace/QR para compartir.
    val isLive: Boolean = false,
    val statusText: String = "Desconectado",
    val viewerUrl: String? = null,
    val viewerCount: Int = 0,
    val error: String? = null,
    val activeSource: VideoSource = VideoSource.NONE,
    val micEnabled: Boolean = true
)

/**
 * Singleton que coordina señalización + WebRTC. Tanto la Activity (para pintar la
 * UI) como el Service en primer plano (para mantenerlo vivo mientras la pantalla
 * está apagada) hablan con este objeto, así que la lógica de la emisión vive en un
 * solo sitio.
 */
object BroadcastEngine {

    val stateFlow = MutableStateFlow(BroadcastState())

    // Historial del chat compartido de la emisión actual — se vacía en cada
    // start() (sala/emisión nueva = chat nuevo). Se limita a los últimos 200
    // mensajes para no crecer sin límite en una emisión muy larga.
    val chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private const val MAX_CHAT_MESSAGES = 200

    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    // Se incrementa en cada start()/stop(): si una consulta TURN todavía en vuelo
    // (ver fetchTurnIceServers) responde tarde, comparando este número se sabe si
    // sigue hablando de LA MISMA emisión o si ya se paró/arrancó otra mientras
    // tanto — evita que una respuesta tardía pise un estado más nuevo.
    private var startGeneration = 0

    fun start(
        context: Context,
        serverUrl: String,
        room: String,
        password: String,
        source: VideoSource,
        micEnabled: Boolean,
        projectionData: Intent?
    ) {
        stop()

        chatMessages.value = emptyList() // sala/emisión nueva = chat nuevo, sin arrastrar el anterior

        val appContext = context.applicationContext
        val viewerUrl = buildViewerUrl(serverUrl, room)
        stateFlow.value = BroadcastState(
            isRunning = true, isLive = false, statusText = "Conectando…", viewerUrl = viewerUrl,
            activeSource = source, micEnabled = micEnabled
        )

        val generation = ++startGeneration
        // La consulta al servidor por si hay TURN configurado (ver
        // /turn-credentials en server.js) es una llamada de red: va en un hilo de
        // fondo para no bloquear el hilo principal. Cámara/WebRTC sí necesitan
        // seguir tocándose desde el hilo principal como siempre (por eso
        // startAfterTurnLookup vuelve a Dispatchers.Main antes de continuar).
        CoroutineScope(Dispatchers.IO).launch {
            val extraIceServers = fetchTurnIceServers(serverUrl)
            withContext(Dispatchers.Main) {
                if (generation != startGeneration) return@withContext // ya no es la emisión actual
                startAfterTurnLookup(
                    appContext, serverUrl, room, password, source, micEnabled, projectionData, extraIceServers
                )
            }
        }
    }

    private fun startAfterTurnLookup(
        appContext: Context,
        serverUrl: String,
        room: String,
        password: String,
        source: VideoSource,
        micEnabled: Boolean,
        projectionData: Intent?,
        extraIceServers: List<PeerConnection.IceServer>
    ) {
        val client = WebRtcClient(appContext, object : WebRtcClient.Listener {
            override fun onLocalIceCandidate(viewerId: String, candidate: org.webrtc.IceCandidate) {
                signalingClient?.sendCandidate(viewerId, candidate)
            }
            override fun onLocalOffer(viewerId: String, sdp: String) {
                signalingClient?.sendOffer(viewerId, sdp)
            }
        }, extraIceServers)
        webRtcClient = client

        try {
            client.initialize()
            when (source) {
                VideoSource.SCREEN -> {
                    if (projectionData == null) throw IllegalStateException("Falta el permiso de captura de pantalla")
                    client.startScreenCapture(projectionData)
                }
                VideoSource.FRONT_CAMERA -> client.startCameraCapture(true)
                VideoSource.BACK_CAMERA -> client.startCameraCapture(false)
                // El vídeo es opcional, igual que el micrófono: se puede emitir solo
                // audio desde el principio, sin arrancar ninguna cámara/pantalla.
                VideoSource.NONE -> { /* sin vídeo */ }
            }
            client.setMicEnabled(micEnabled)
        } catch (e: Exception) {
            // La captura no ha llegado a arrancar bien: no dejamos nada a medias
            // encendido (cámara/micrófono) sin ningún motivo. Se registra también en
            // Logcat (antes solo quedaba el mensaje en pantalla) para poder diagnosticar
            // fallos futuros sin depender de leer el texto de estado de la app.
            Log.e(TAG, "Fallo al iniciar la captura (fuente=$source)", e)
            stopInternal("No se pudo iniciar la captura: ${e.message}")
            return
        }

        // "thisClient" nos permite, dentro de los callbacks (que llegan más tarde, de forma
        // asíncrona), comprobar que seguimos hablando de ESTA sesión de señalización y no de
        // una anterior ya cerrada — evita que un mensaje tardío de una conexión vieja toque
        // por error el estado de una emisión nueva que ya se haya iniciado mientras tanto.
        lateinit var thisClient: SignalingClient
        thisClient = SignalingClient(serverUrl, room, password, object : SignalingListener {
            override fun onHostReady(viewerCount: Int) {
                if (signalingClient !== thisClient) return
                stateFlow.value = stateFlow.value.copy(
                    isRunning = true, isLive = true, statusText = "En directo", viewerCount = viewerCount, error = null
                )
            }
            override fun onViewerJoined(viewerId: String) {
                if (signalingClient !== thisClient) return
                webRtcClient?.createPeerConnectionForViewer(viewerId)
                // Al espectador que se acaba de conectar (o que ya estaba esperando)
                // hay que decirle el estado ACTUAL de vídeo/audio directamente a él —
                // si más adelante cambia, ya lo cubre broadcastState() avisando a todos.
                val s = stateFlow.value
                signalingClient?.sendState(
                    hasVideo = s.activeSource != VideoSource.NONE, hasAudio = s.micEnabled, viewerId = viewerId
                )
                stateFlow.value = stateFlow.value.copy(viewerCount = stateFlow.value.viewerCount + 1)
            }
            override fun onViewerLeft(viewerId: String) {
                if (signalingClient !== thisClient) return
                webRtcClient?.closeViewer(viewerId)
                stateFlow.value = stateFlow.value.copy(viewerCount = maxOf(0, stateFlow.value.viewerCount - 1))
            }
            override fun onAnswer(viewerId: String, sdp: String) {
                if (signalingClient !== thisClient) return
                webRtcClient?.handleAnswer(viewerId, sdp)
            }
            override fun onRemoteCandidate(viewerId: String, candidate: IceCandidateData) {
                if (signalingClient !== thisClient) return
                webRtcClient?.handleRemoteCandidate(viewerId, candidate)
            }
            override fun onSignalingError(message: String) {
                if (signalingClient !== thisClient) return
                // Sin conexión de señalización no hay forma de que esto llegue a ningún
                // espectador: cortamos también la captura de cámara/micrófono en vez de
                // dejarla encendida indefinidamente sin ningún sitio al que emitir.
                stopInternal(message)
            }
            override fun onSignalingClosed() {
                if (signalingClient !== thisClient) return
                stopInternal("Desconectado")
            }
            override fun onChat(viewerId: String, name: String, text: String, ts: Long) {
                if (signalingClient !== thisClient) return
                appendChatMessage(ChatMessage(fromHost = false, name = name, text = text, ts = ts, viewerId = viewerId))
            }
        })
        signalingClient = thisClient
        thisClient.connect()
    }

    /**
     * Cambia la fuente de vídeo (cámara delantera/trasera/pantalla/ninguna) EN
     * CALIENTE, SIN cortar la emisión ni pasar por el Service: la pista nueva se
     * empuja directamente a todos los espectadores ya conectados (ver
     * [WebRtcClient.pushVideoTrackToExistingSenders]).
     *
     * [VideoSource.NONE] apaga la cámara/pantalla y deja la emisión solo con
     * audio — es tan válido como apagar el micrófono ([setMicEnabled]): los
     * espectadores ya conectados se quedan sin imagen (pero siguen oyendo) sin
     * que la conexión se corte ni haga falta volver a conectar nada.
     *
     * Si se cambia A pantalla, [projectionData] tiene que venir de un permiso de
     * captura recién concedido — Android exige pedirlo de nuevo cada vez, el "token"
     * de una captura anterior no se puede reutilizar para otra nueva.
     *
     * IMPORTANTE: quien llame a esto para cambiar A o DESDE pantalla (o DESDE/A
     * ninguna fuente) debe asegurarse antes de que el tipo de servicio en primer
     * plano ya está actualizado (ver BroadcastService.ACTION_SWITCH_SOURCE) — si
     * no, Android 14+ puede matar el servicio al detectar un tipo de "foreground
     * service" no declarado a tiempo.
     */
    fun switchSource(source: VideoSource, projectionData: Intent? = null) {
        val client = webRtcClient ?: return
        if (!stateFlow.value.isRunning) return

        try {
            when (source) {
                VideoSource.FRONT_CAMERA -> client.startCameraCapture(true)
                VideoSource.BACK_CAMERA -> client.startCameraCapture(false)
                VideoSource.SCREEN -> {
                    if (projectionData == null) return
                    client.startScreenCapture(projectionData)
                }
                VideoSource.NONE -> client.stopVideo()
            }
            stateFlow.value = stateFlow.value.copy(activeSource = source, error = null)
            broadcastState()
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al cambiar de fuente (fuente=$source)", e)
            stateFlow.value = stateFlow.value.copy(error = "No se pudo cambiar de fuente: ${e.message}")
        }
    }

    /**
     * Avisa a TODOS los espectadores ya conectados de si hay vídeo y/o audio
     * saliendo ahora mismo — se llama cada vez que cambia la fuente o el
     * micrófono con la emisión ya en marcha, para que sus iconos de "sin señal"
     * se actualicen sin que tengan que recargar ni reconectar nada. Al espectador
     * que se conecta por primera vez se le informa aparte, dirigido solo a él
     * (ver el "state" que se manda dentro de onViewerJoined más arriba).
     */
    private fun broadcastState() {
        val s = stateFlow.value
        signalingClient?.sendState(hasVideo = s.activeSource != VideoSource.NONE, hasAudio = s.micEnabled)
    }

    fun setMicEnabled(enabled: Boolean) {
        webRtcClient?.setMicEnabled(enabled)
        stateFlow.value = stateFlow.value.copy(micEnabled = enabled)
        broadcastState()
    }

    /**
     * Manda un mensaje del anfitrión a todo el chat compartido (lo ven todos los
     * espectadores conectados, igual que en watch.html). Se añade también al
     * historial local de inmediato — no hace falta esperar a que "vuelva" del
     * servidor, a diferencia de los mensajes de los espectadores.
     */
    fun sendChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !stateFlow.value.isRunning) return
        signalingClient?.sendChat(trimmed)
        appendChatMessage(ChatMessage(fromHost = true, name = "Anfitrión", text = trimmed, ts = System.currentTimeMillis()))
    }

    private fun appendChatMessage(msg: ChatMessage) {
        val updated = (chatMessages.value + msg)
        chatMessages.value = if (updated.size > MAX_CHAT_MESSAGES) updated.takeLast(MAX_CHAT_MESSAGES) else updated
    }

    /** Parada "normal": vacía el estado por completo, sin mensaje de error. */
    fun stop() = stopInternal(null)

    /**
     * Libera cámara/micrófono/señalización, igual que [stop], pero dejando un mensaje de
     * error visible en el estado final en vez de dejarlo en blanco — para los casos en los
     * que la emisión se corta sola (fallo de red, sala ocupada, etc.) y el usuario necesita
     * saber por qué ha dejado de estar "en directo".
     */
    private fun stopInternal(errorMessage: String?) {
        // Invalida cualquier consulta TURN todavía en vuelo de un start() anterior
        // (ver comentario en startGeneration) — si responde después de esto, su
        // comprobación de generación fallará y no hará nada.
        startGeneration++
        if (errorMessage != null) {
            // Traza con pila de llamada para saber, en Logcat, DESDE DÓNDE se ha
            // disparado una parada con error (señalización, captura, etc.) sin tener
            // que adivinarlo solo por el texto del mensaje.
            Log.w(TAG, "stopInternal con error: $errorMessage", Exception("stack trace informativo"))
        }
        signalingClient?.disconnect()
        signalingClient = null
        webRtcClient?.release()
        webRtcClient = null
        stateFlow.value = if (errorMessage != null) BroadcastState(error = errorMessage) else BroadcastState()
    }

    private fun buildViewerUrl(serverUrl: String, room: String): String {
        val base = serverUrl.trim().removeSuffix("/")
        return "$base/watch/${java.net.URLEncoder.encode(room, "UTF-8")}"
    }

    /**
     * Pregunta al propio servidor de señalización (endpoint /turn-credentials,
     * ver server.js) si tiene un servidor TURN configurado (Cloudflare Realtime).
     * Solo STUN (lo que ya llevaba WebRtcClient de fábrica) sirve únicamente si
     * se consigue conexión DIRECTA entre el móvil y quien mira — en redes con NAT
     * restrictivo (datos móviles, wifis corporativas) eso puede no llegar a pasar
     * nunca, y entonces no se ve ni se oye nada aunque todo lo demás vaya bien.
     * Llamada de red BLOQUEANTE a propósito: se invoca desde Dispatchers.IO en
     * [start], nunca desde el hilo principal. Ante cualquier fallo (sin
     * conexión, servidor sin TURN configurado, timeout...) devuelve la lista
     * vacía — nunca bloquea ni corta el arranque de la emisión por esto, sigue
     * funcionando solo con STUN igual que antes.
     */
    private fun fetchTurnIceServers(serverUrl: String): List<PeerConnection.IceServer> {
        return try {
            val base = serverUrl.trim().removeSuffix("/")
            val url = java.net.URL("$base/turn-credentials")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            try {
                val code = conn.responseCode
                if (code !in 200..299) return emptyList()
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseIceServers(body)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron obtener credenciales TURN (se sigue solo con STUN): ${e.message}")
            emptyList()
        }
    }

    private fun parseIceServers(body: String): List<PeerConnection.IceServer> {
        val arr = JSONObject(body).optJSONArray("iceServers") ?: return emptyList()
        val result = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val urls = mutableListOf<String>()
            when (val u = entry.opt("urls")) {
                is JSONArray -> for (j in 0 until u.length()) urls.add(u.getString(j))
                is String -> urls.add(u)
            }
            if (urls.isEmpty()) continue
            val builder = PeerConnection.IceServer.builder(urls)
            entry.optString("username", "").takeIf { it.isNotEmpty() }?.let { builder.setUsername(it) }
            entry.optString("credential", "").takeIf { it.isNotEmpty() }?.let { builder.setPassword(it) }
            result.add(builder.createIceServer())
        }
        return result
    }
}

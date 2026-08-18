package com.miconstelacion.camstream

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class BroadcastState(
    val isLive: Boolean = false,
    val statusText: String = "Desconectado",
    val viewerUrl: String? = null,
    val viewerCount: Int = 0,
    val error: String? = null,
    val internalAudioFile: String? = null
)

/**
 * Singleton que coordina señalización + WebRTC + (opcionalmente) captura de audio
 * interno. Tanto la Activity (para pintar la UI) como el Service en primer plano
 * (para mantenerlo vivo mientras la pantalla está apagada) hablan con este objeto,
 * así que la lógica de la emisión vive en un solo sitio.
 */
object BroadcastEngine {

    val stateFlow = MutableStateFlow(BroadcastState())

    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null
    private var internalAudioCapturer: InternalAudioCapturer? = null

    fun start(
        context: Context,
        serverUrl: String,
        room: String,
        password: String,
        useScreen: Boolean,
        useFrontCamera: Boolean,
        micEnabled: Boolean,
        captureInternalAudio: Boolean,
        projectionResultCode: Int,
        projectionData: Intent?
    ) {
        stop()

        val appContext = context.applicationContext
        val viewerUrl = buildViewerUrl(serverUrl, room)
        stateFlow.value = BroadcastState(isLive = false, statusText = "Conectando…", viewerUrl = viewerUrl)

        val client = WebRtcClient(appContext, object : WebRtcClient.Listener {
            override fun onLocalIceCandidate(viewerId: String, candidate: org.webrtc.IceCandidate) {
                signalingClient?.sendCandidate(viewerId, candidate)
            }
            override fun onLocalOffer(viewerId: String, sdp: String) {
                signalingClient?.sendOffer(viewerId, sdp)
            }
        })
        webRtcClient = client

        try {
            client.initialize()
            if (useScreen && projectionData != null) {
                client.startScreenCapture(projectionData)

                if (captureInternalAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startInternalAudioCapture(appContext, projectionResultCode, projectionData)
                }
            } else {
                client.startCameraCapture(useFrontCamera)
            }
            client.setMicEnabled(micEnabled)
        } catch (e: Exception) {
            stateFlow.value = stateFlow.value.copy(error = "No se pudo iniciar la captura: ${e.message}")
            return
        }

        signalingClient = SignalingClient(serverUrl, room, password, object : SignalingListener {
            override fun onHostReady(viewerCount: Int) {
                stateFlow.value = stateFlow.value.copy(
                    isLive = true, statusText = "En directo", viewerCount = viewerCount, error = null
                )
            }
            override fun onViewerJoined(viewerId: String) {
                webRtcClient?.createPeerConnectionForViewer(viewerId)
                stateFlow.value = stateFlow.value.copy(viewerCount = stateFlow.value.viewerCount + 1)
            }
            override fun onViewerLeft(viewerId: String) {
                webRtcClient?.closeViewer(viewerId)
                stateFlow.value = stateFlow.value.copy(viewerCount = maxOf(0, stateFlow.value.viewerCount - 1))
            }
            override fun onAnswer(viewerId: String, sdp: String) {
                webRtcClient?.handleAnswer(viewerId, sdp)
            }
            override fun onRemoteCandidate(viewerId: String, candidate: IceCandidateData) {
                webRtcClient?.handleRemoteCandidate(viewerId, candidate)
            }
            override fun onSignalingError(message: String) {
                stateFlow.value = stateFlow.value.copy(error = message)
            }
            override fun onSignalingClosed() {
                stateFlow.value = stateFlow.value.copy(isLive = false, statusText = "Desconectado")
            }
        })
        signalingClient?.connect()
    }

    fun setMicEnabled(enabled: Boolean) {
        webRtcClient?.setMicEnabled(enabled)
    }

    fun switchCamera() {
        webRtcClient?.switchCamera()
    }

    private fun startInternalAudioCapture(context: Context, resultCode: Int, data: Intent) {
        try {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Se pide un segundo "MediaProjection" independiente del que usa la captura
            // de vídeo, reutilizando el mismo permiso concedido por el usuario.
            val projection = manager.getMediaProjection(resultCode, data)
            val outDir = File(context.getExternalFilesDir(null), "audio_interno")
            val capturer = InternalAudioCapturer(
                mediaProjection = projection,
                outputDir = outDir,
                onError = { msg -> stateFlow.value = stateFlow.value.copy(error = msg) },
                onFileReady = { file -> stateFlow.value = stateFlow.value.copy(internalAudioFile = file.absolutePath) }
            )
            internalAudioCapturer = capturer
            capturer.start()
        } catch (e: Exception) {
            stateFlow.value = stateFlow.value.copy(error = "Audio interno no disponible: ${e.message}")
        }
    }

    fun stop() {
        signalingClient?.disconnect()
        signalingClient = null
        internalAudioCapturer?.stop()
        internalAudioCapturer = null
        webRtcClient?.release()
        webRtcClient = null
        stateFlow.value = BroadcastState()
    }

    private fun buildViewerUrl(serverUrl: String, room: String): String {
        val base = serverUrl.trim().removeSuffix("/")
        return "$base/watch/${java.net.URLEncoder.encode(room, "UTF-8")}"
    }
}

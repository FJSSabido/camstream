package com.miconstelacion.camstream

import android.os.Handler
import android.os.Looper
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.webrtc.IceCandidate
import java.net.URI
import java.net.URLEncoder

/** Candidato ICE recibido desde el otro lado (formato neutro, sin depender de WebRTC). */
data class IceCandidateData(val sdpMid: String?, val sdpMLineIndex: Int, val sdp: String)

interface SignalingListener {
    fun onHostReady(viewerCount: Int)
    fun onViewerJoined(viewerId: String)
    fun onViewerLeft(viewerId: String)
    fun onAnswer(viewerId: String, sdp: String)
    fun onRemoteCandidate(viewerId: String, candidate: IceCandidateData)
    fun onSignalingError(message: String)
    fun onSignalingClosed()
}

/**
 * Cliente WebSocket que habla con el servidor de señalización (server/server.js).
 * Solo transporta mensajes de "apretón de manos" de WebRTC (offer/answer/ICE);
 * el vídeo/audio en sí nunca pasa por aquí.
 */
class SignalingClient(
    serverUrl: String,
    room: String,
    password: String,
    private val listener: SignalingListener
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client: WebSocketClient

    init {
        val wsBase = serverUrl.trim()
            .removeSuffix("/")
            .replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
        val uri = URI("$wsBase/ws?room=${enc(room)}&role=host&key=${enc(password)}")

        client = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                // Nada que hacer: el servidor manda "host-ready" en cuanto acepta la conexión.
            }

            override fun onMessage(message: String?) {
                if (message != null) handleMessage(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                mainHandler.post { listener.onSignalingClosed() }
            }

            override fun onError(ex: Exception?) {
                mainHandler.post { listener.onSignalingError(ex?.message ?: "Error de conexión con el servidor") }
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun connect() {
        try {
            client.connect()
        } catch (e: Exception) {
            listener.onSignalingError(e.message ?: "No se pudo conectar")
        }
    }

    fun disconnect() {
        try { client.close() } catch (e: Exception) { /* ignorar */ }
    }

    private fun handleMessage(raw: String) {
        val json = try { JSONObject(raw) } catch (e: Exception) { return }
        val type = json.optString("type")
        mainHandler.post {
            when (type) {
                "host-ready" -> listener.onHostReady(json.optInt("viewerCount", 0))
                "viewer-joined" -> listener.onViewerJoined(json.optString("viewerId"))
                "viewer-left" -> listener.onViewerLeft(json.optString("viewerId"))
                "answer" -> listener.onAnswer(json.optString("viewerId"), json.optString("sdp"))
                "candidate" -> {
                    val c = json.optJSONObject("candidate") ?: return@post
                    listener.onRemoteCandidate(
                        json.optString("viewerId"),
                        IceCandidateData(
                            sdpMid = if (c.isNull("sdpMid")) null else c.optString("sdpMid"),
                            sdpMLineIndex = c.optInt("sdpMLineIndex", 0),
                            sdp = c.optString("candidate")
                        )
                    )
                }
                "error" -> listener.onSignalingError(json.optString("message", "Error del servidor"))
            }
        }
    }

    fun sendOffer(viewerId: String, sdp: String) {
        send(JSONObject().put("type", "offer").put("viewerId", viewerId).put("sdp", sdp))
    }

    fun sendCandidate(viewerId: String, candidate: IceCandidate) {
        val c = JSONObject()
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .put("candidate", candidate.sdp)
        send(JSONObject().put("type", "candidate").put("viewerId", viewerId).put("candidate", c))
    }

    private fun send(json: JSONObject) {
        if (client.isOpen) client.send(json.toString())
    }
}

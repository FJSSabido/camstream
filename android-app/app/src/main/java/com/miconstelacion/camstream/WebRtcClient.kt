package com.miconstelacion.camstream

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Encapsula todo el motor WebRTC: cámara/pantalla como fuente de vídeo, micrófono
 * como fuente de audio, y una PeerConnection independiente por cada espectador
 * conectado (para poder emitir a varias personas a la vez desde un único móvil).
 */
class WebRtcClient(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onLocalIceCandidate(viewerId: String, candidate: IceCandidate)
        fun onLocalOffer(viewerId: String, sdp: String)
    }

    private lateinit var eglBase: EglBase
    private lateinit var factory: PeerConnectionFactory

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val peerConnections = mutableMapOf<String, PeerConnection>()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    fun initialize() {
        eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("mic0", audioSource)
    }

    fun startCameraCapture(useFrontCamera: Boolean) {
        stopVideoCapture()

        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull {
            if (useFrontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("Este dispositivo no tiene ninguna cámara disponible")

        val capturer = enumerator.createCapturer(deviceName, null)
        videoCapturer = capturer

        val source = factory.createVideoSource(false)
        videoSource = source

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        localVideoTrack = factory.createVideoTrack("video0", source)
    }

    fun startScreenCapture(data: Intent) {
        stopVideoCapture()

        val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                // El usuario ha detenido la captura desde el panel de sistema de Android.
            }
        })
        videoCapturer = capturer

        val source = factory.createVideoSource(true)
        videoSource = source

        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        capturer.initialize(helper, context, source.capturerObserver)
        val metrics = context.resources.displayMetrics
        capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 15)

        localVideoTrack = factory.createVideoTrack("video0", source)
    }

    private fun stopVideoCapture() {
        try { videoCapturer?.stopCapture() } catch (e: Exception) { /* ignorar */ }
        videoCapturer?.dispose()
        videoCapturer = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun createPeerConnectionForViewer(viewerId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onLocalIceCandidate(viewerId, candidate)
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }) ?: return

        localVideoTrack?.let { pc.addTrack(it, listOf("cs0")) }
        localAudioTrack?.let { pc.addTrack(it, listOf("cs0")) }

        peerConnections[viewerId] = pc

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(), desc)
                listener.onLocalOffer(viewerId, desc.description)
            }
        }, MediaConstraints())
    }

    fun handleAnswer(viewerId: String, sdp: String) {
        peerConnections[viewerId]?.setRemoteDescription(
            SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    fun handleRemoteCandidate(viewerId: String, c: IceCandidateData) {
        peerConnections[viewerId]?.addIceCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex, c.sdp))
    }

    fun closeViewer(viewerId: String) {
        peerConnections.remove(viewerId)?.close()
    }

    fun release() {
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        stopVideoCapture()
        audioSource?.dispose()
        audioSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        if (::factory.isInitialized) factory.dispose()
        if (::eglBase.isInitialized) eglBase.release()
    }
}

/** Implementación con los 4 métodos vacíos por defecto, para no repetirlos en cada callback. */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

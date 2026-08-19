package com.miconstelacion.camstream

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.miconstelacion.camstream.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    // Configuración "pendiente": la que se usará para ARRANCAR la emisión. Una vez en
    // marcha, el estado real vive en BroadcastEngine.stateFlow y estos campos dejan de
    // consultarse (ver currentSource/currentMic).
    private var pendingSource: VideoSource = VideoSource.BACK_CAMERA
    private var pendingMicEnabled = true
    private var pendingStart = false

    /** Para qué se está pidiendo permiso de captura de pantalla ahora mismo. */
    private enum class ScreenPermissionPurpose { START, SWITCH_SOURCE }
    private var awaitingScreenPermissionFor: ScreenPermissionPurpose? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            proceedAfterPermissions()
        } else {
            Toast.makeText(this, "Hacen falta los permisos para poder emitir", Toast.LENGTH_LONG).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val purpose = awaitingScreenPermissionFor
        awaitingScreenPermissionFor = null

        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Toast.makeText(this, "No se concedió permiso para capturar la pantalla", Toast.LENGTH_LONG).show()
            updateSourceButtonStates()
            updateAudioButtonStates()
            return@registerForActivityResult
        }

        when (purpose) {
            ScreenPermissionPurpose.START -> {
                pendingSource = VideoSource.SCREEN
                launchBroadcastService(result.data)
            }
            ScreenPermissionPurpose.SWITCH_SOURCE -> {
                pendingSource = VideoSource.SCREEN
                switchSourceLive(VideoSource.SCREEN, result.data)
            }
            null -> { /* nada que hacer */ }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("camstream_prefs", Context.MODE_PRIVATE)
        restorePrefs()

        binding.btnRegenRoom.setOnClickListener {
            binding.inputRoomId.setText(randomRoomId())
        }

        binding.btnSourceFront.setOnClickListener { onSourceButtonClicked(VideoSource.FRONT_CAMERA) }
        binding.btnSourceBack.setOnClickListener { onSourceButtonClicked(VideoSource.BACK_CAMERA) }
        binding.btnSourceScreen.setOnClickListener { onSourceButtonClicked(VideoSource.SCREEN) }

        binding.btnToggleMic.setOnClickListener { onMicButtonClicked() }

        binding.btnStartStop.setOnClickListener {
            if (BroadcastEngine.stateFlow.value.isRunning || pendingStart) {
                stopBroadcast()
            } else {
                startBroadcastFlow()
            }
        }

        binding.btnCopyUrl.setOnClickListener {
            val url = BroadcastEngine.stateFlow.value.viewerUrl ?: return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("URL", url))
            Toast.makeText(this, "Enlace copiado", Toast.LENGTH_SHORT).show()
        }

        binding.btnShareUrl.setOnClickListener {
            val url = BroadcastEngine.stateFlow.value.viewerUrl ?: return@setOnClickListener
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(intent, "Compartir enlace"))
        }

        updateSourceButtonStates()
        updateAudioButtonStates()
        observeState()
    }

    private fun restorePrefs() {
        binding.inputServerUrl.setText(prefs.getString("serverUrl", ""))
        binding.inputRoomId.setText(prefs.getString("room", randomRoomId()))
        binding.inputPassword.setText(prefs.getString("password", ""))
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("serverUrl", binding.inputServerUrl.text?.toString()?.trim())
            .putString("room", binding.inputRoomId.text?.toString()?.trim())
            .putString("password", binding.inputPassword.text?.toString())
            .apply()
    }

    private fun randomRoomId(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    // ---- Fuente de vídeo (cámara frontal/trasera/pantalla) ----

    /**
     * El vídeo es opcional, igual que el micrófono: tocar la fuente que YA está
     * activa la apaga (deja la emisión solo con audio) en vez de volver a pedir
     * permiso o quedarse sin hacer nada — así los tres botones de fuente pueden
     * estar los tres apagados a la vez, cosa que antes no era posible (siempre
     * había una cámara/pantalla obligatoriamente encendida).
     */
    private fun onSourceButtonClicked(source: VideoSource) {
        val running = BroadcastEngine.stateFlow.value.isRunning
        val turningOff = currentSource() == source

        if (turningOff) {
            pendingSource = VideoSource.NONE
            if (running) {
                switchSourceLive(VideoSource.NONE)
            } else {
                updateSourceButtonStates()
                updateAudioButtonStates()
            }
            return
        }

        if (source == VideoSource.SCREEN) {
            requestScreenPermission(
                if (running) ScreenPermissionPurpose.SWITCH_SOURCE else ScreenPermissionPurpose.START
            )
            return
        }
        pendingSource = source
        if (running) {
            switchSourceLive(source)
        } else {
            updateSourceButtonStates()
            updateAudioButtonStates()
        }
    }

    /** Cambia de fuente con la emisión YA en marcha, sin cortarla. */
    private fun switchSourceLive(source: VideoSource, data: Intent? = null) {
        val intent = Intent(this, BroadcastService::class.java).apply {
            action = BroadcastService.ACTION_SWITCH_SOURCE
            putExtra(BroadcastService.EXTRA_SOURCE, source.name)
            putExtra(BroadcastService.EXTRA_PROJECTION_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestScreenPermission(purpose: ScreenPermissionPurpose) {
        awaitingScreenPermissionFor = purpose
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    // ---- Audio: micrófono ----

    private fun onMicButtonClicked() {
        if (BroadcastEngine.stateFlow.value.isRunning) {
            BroadcastEngine.setMicEnabled(!BroadcastEngine.stateFlow.value.micEnabled)
        } else {
            pendingMicEnabled = !pendingMicEnabled
            updateAudioButtonStates()
        }
    }

    // ---- Arranque de la emisión ----

    private fun startBroadcastFlow() {
        val serverUrl = binding.inputServerUrl.text?.toString()?.trim().orEmpty()
        val room = binding.inputRoomId.text?.toString()?.trim().orEmpty()

        if (serverUrl.isEmpty()) {
            binding.inputServerUrl.error = "Indica la URL de tu servidor"
            return
        }
        if (room.isEmpty()) {
            binding.inputRoomId.error = "Indica un nombre de sala"
            return
        }

        savePrefs()

        // Se piden cámara y micrófono siempre, sea cual sea la fuente inicial elegida:
        // así, cambiar a cámara EN CALIENTE más adelante (p. ej. mientras se comparte
        // pantalla) nunca choca con un permiso que falte a mitad de emisión.
        val needed = mutableListOf<String>()
        needed.add(Manifest.permission.RECORD_AUDIO)
        needed.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            proceedAfterPermissions()
        }
    }

    private fun proceedAfterPermissions() {
        if (pendingSource == VideoSource.SCREEN) {
            requestScreenPermission(ScreenPermissionPurpose.START)
        } else {
            launchBroadcastService(null)
        }
    }

    private fun launchBroadcastService(projectionData: Intent?) {
        pendingStart = true
        binding.btnStartStop.text = "Detener"
        binding.txtStatus.text = "Conectando…"

        val intent = Intent(this, BroadcastService::class.java).apply {
            action = BroadcastService.ACTION_START
            putExtra(BroadcastService.EXTRA_SERVER_URL, binding.inputServerUrl.text?.toString()?.trim())
            putExtra(BroadcastService.EXTRA_ROOM, binding.inputRoomId.text?.toString()?.trim())
            putExtra(BroadcastService.EXTRA_PASSWORD, binding.inputPassword.text?.toString().orEmpty())
            putExtra(BroadcastService.EXTRA_SOURCE, pendingSource.name)
            putExtra(BroadcastService.EXTRA_MIC_ENABLED, pendingMicEnabled)
            putExtra(BroadcastService.EXTRA_PROJECTION_DATA, projectionData)
        }
        ContextCompat.startForegroundService(this, intent)

        setInputsEnabled(false)
    }

    private fun stopBroadcast() {
        pendingStart = false
        val intent = Intent(this, BroadcastService::class.java).apply {
            action = BroadcastService.ACTION_STOP
        }
        startService(intent)
        setInputsEnabled(true)
        binding.btnStartStop.text = "Iniciar transmisión"
        binding.cardShare.visibility = android.view.View.GONE
        binding.txtViewerCount.text = ""
    }

    private fun setInputsEnabled(enabled: Boolean) {
        binding.inputServerUrl.isEnabled = enabled
        binding.inputRoomId.isEnabled = enabled
        binding.btnRegenRoom.isEnabled = enabled
        binding.inputPassword.isEnabled = enabled
        // Los botones de fuente/audio se quedan SIEMPRE activos: antes de arrancar
        // sirven para elegir con qué fuente empezar, y con la emisión ya en marcha
        // sirven para cambiar en caliente sin cortar nada.
    }

    // ---- Pintado de los botones grandes de fuente/audio ----

    private fun currentSource(): VideoSource {
        val state = BroadcastEngine.stateFlow.value
        return if (state.isRunning) state.activeSource else pendingSource
    }

    private fun currentMic(): Boolean {
        val state = BroadcastEngine.stateFlow.value
        return if (state.isRunning) state.micEnabled else pendingMicEnabled
    }

    // Encendido = amarillo, apagado = morado — así se ve de un vistazo qué fuente/
    // audio está activo ahora mismo sin tener que leer texto.
    private fun tintButton(button: MaterialButton, on: Boolean, enabled: Boolean = true) {
        val bgColorRes = if (on) R.color.state_on else R.color.state_off
        val textColorRes = if (on) R.color.state_on_text else R.color.state_off_text
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgColorRes))
        button.setTextColor(ContextCompat.getColor(this, textColorRes))
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
    }

    private fun updateSourceButtonStates() {
        val src = currentSource()
        tintButton(binding.btnSourceFront, src == VideoSource.FRONT_CAMERA)
        tintButton(binding.btnSourceBack, src == VideoSource.BACK_CAMERA)
        tintButton(binding.btnSourceScreen, src == VideoSource.SCREEN)
    }

    private fun updateAudioButtonStates() {
        tintButton(binding.btnToggleMic, currentMic())
    }

    // ---- Observar el estado de la emisión ----

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BroadcastEngine.stateFlow.collect { state ->
                    pendingStart = false
                    // "isRunning" es true desde que la cámara/micrófono ya están encendidos,
                    // aunque todavía no se haya confirmado la conexión con el servidor
                    // (fase "Conectando…"). Así el botón dice "Detener" durante todo el
                    // tiempo real que algo está grabando, no solo cuando ya hay espectadores.
                    binding.btnStartStop.text = if (state.isRunning) "Detener" else "Iniciar transmisión"
                    binding.txtStatus.text = state.error ?: state.statusText

                    if (state.isLive && state.viewerUrl != null) {
                        binding.cardShare.visibility = android.view.View.VISIBLE
                        binding.txtViewerUrl.text = state.viewerUrl
                        binding.txtViewerCount.text = when (state.viewerCount) {
                            0 -> "Nadie está viendo la transmisión todavía"
                            1 -> "1 persona viendo"
                            else -> "${state.viewerCount} personas viendo"
                        }
                        loadQrIfNeeded(state.viewerUrl)
                    } else if (!state.isRunning) {
                        binding.cardShare.visibility = android.view.View.GONE
                        setInputsEnabled(true)
                    }

                    updateSourceButtonStates()
                    updateAudioButtonStates()
                }
            }
        }
    }

    private var lastQrUrl: String? = null
    private fun loadQrIfNeeded(url: String) {
        if (url == lastQrUrl) return
        lastQrUrl = url
        lifecycleScope.launch {
            val bitmap = QrUtils.fetchQrBitmap(url)
            if (bitmap != null) {
                binding.imgQr.setImageBitmap(bitmap)
                binding.imgQr.visibility = android.view.View.VISIBLE
            } else {
                binding.imgQr.visibility = android.view.View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // La emisión sigue viva en el Service aunque se cierre la Activity;
        // solo se detiene si el usuario pulsa "Detener".
    }
}

package com.miconstelacion.camstream

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.miconstelacion.camstream.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var useScreenSource = false
    private var useFrontCamera = false
    private var pendingStart = false

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
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchBroadcastService(result.resultCode, result.data)
        } else {
            Toast.makeText(this, "No se concedió permiso para capturar la pantalla", Toast.LENGTH_LONG).show()
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

        binding.toggleSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            useScreenSource = checkedId == binding.btnSourceScreen.id
            updateSourceDependentViews()
        }

        binding.btnSwitchCamera.setOnClickListener {
            useFrontCamera = !useFrontCamera
            BroadcastEngine.switchCamera()
        }

        binding.switchMic.setOnCheckedChangeListener { _, checked ->
            BroadcastEngine.setMicEnabled(checked)
        }

        binding.rowInternalAudio.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.view.View.VISIBLE else android.view.View.GONE

        binding.btnStartStop.setOnClickListener {
            if (BroadcastEngine.stateFlow.value.isLive || pendingStart) {
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

        updateSourceDependentViews()
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

    private fun updateSourceDependentViews() {
        binding.btnSwitchCamera.visibility = if (useScreenSource) android.view.View.GONE else android.view.View.VISIBLE
        binding.txtInternalAudioHint.visibility =
            if (useScreenSource) android.view.View.VISIBLE else android.view.View.GONE
        binding.switchInternalAudio.isEnabled = useScreenSource
        if (!useScreenSource) binding.switchInternalAudio.isChecked = false
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

        val needed = mutableListOf<String>()
        needed.add(Manifest.permission.RECORD_AUDIO)
        if (!useScreenSource) needed.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            proceedAfterPermissions()
        }
    }

    private fun proceedAfterPermissions() {
        if (useScreenSource) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            launchBroadcastService(0, null)
        }
    }

    private fun launchBroadcastService(resultCode: Int, projectionData: Intent?) {
        pendingStart = true
        binding.btnStartStop.text = "Detener"
        binding.txtStatus.text = "Conectando…"

        val intent = Intent(this, BroadcastService::class.java).apply {
            action = BroadcastService.ACTION_START
            putExtra(BroadcastService.EXTRA_SERVER_URL, binding.inputServerUrl.text?.toString()?.trim())
            putExtra(BroadcastService.EXTRA_ROOM, binding.inputRoomId.text?.toString()?.trim())
            putExtra(BroadcastService.EXTRA_PASSWORD, binding.inputPassword.text?.toString().orEmpty())
            putExtra(BroadcastService.EXTRA_USE_SCREEN, useScreenSource)
            putExtra(BroadcastService.EXTRA_USE_FRONT_CAMERA, useFrontCamera)
            putExtra(BroadcastService.EXTRA_MIC_ENABLED, binding.switchMic.isChecked)
            putExtra(BroadcastService.EXTRA_INTERNAL_AUDIO, binding.switchInternalAudio.isChecked)
            putExtra(BroadcastService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(BroadcastService.EXTRA_PROJECTION_DATA, projectionData)
        }
        androidx.core.content.ContextCompat.startForegroundService(this, intent)

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
        binding.toggleSource.isEnabled = enabled
        binding.btnSourceCamera.isEnabled = enabled
        binding.btnSourceScreen.isEnabled = enabled
        binding.switchInternalAudio.isEnabled = enabled && useScreenSource
    }

    // ---- Observar el estado de la emisión ----

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BroadcastEngine.stateFlow.collect { state ->
                    pendingStart = false
                    binding.btnStartStop.text = if (state.isLive) "Detener" else "Iniciar transmisión"
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
                    } else if (!state.isLive) {
                        binding.cardShare.visibility = android.view.View.GONE
                        setInputsEnabled(true)
                    }

                    if (state.internalAudioFile != null) {
                        // Aviso discreto una sola vez cuando termina de guardarse el archivo.
                    }
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

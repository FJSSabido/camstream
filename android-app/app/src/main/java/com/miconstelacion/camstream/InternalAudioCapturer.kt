package com.miconstelacion.camstream

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.RandomAccessFile

/**
 * Captura EXPERIMENTAL del audio interno del teléfono (lo que suena en el propio
 * dispositivo: música, vídeo, juegos...) usando AudioPlaybackCaptureConfiguration,
 * disponible desde Android 10 (API 29).
 *
 * LIMITACIÓN CONOCIDA E IMPORTANTE: la librería WebRTC para Android no ofrece, sin
 * modificar su código nativo, una forma de sustituir su fuente de audio interna por
 * una grabación externa como esta. Por eso, en esta primera versión, el audio interno
 * capturado aquí NO se mezcla en directo dentro de la emisión WebRTC: se graba en
 * paralelo a un archivo .wav en el almacenamiento privado de la app mientras estás
 * emitiendo. Es una base real y funcional (no un mock) sobre la que se podría construir
 * en el futuro la mezcla en directo, con más tiempo de desarrollo o código nativo.
 *
 * Solo funciona capturando audio de "reproducción" de otras apps (música, vídeo, juegos
 * con USAGE_MEDIA/USAGE_GAME); Android no permite a apps normales capturar sonidos de
 * llamadas, notificaciones protegidas, ni audio de apps que se marcan como no capturables.
 */
class InternalAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val outputDir: File,
    private val onError: (String) -> Unit,
    private val onFileReady: (File) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    companion object {
        private const val SAMPLE_RATE = 48000
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (!outputDir.exists()) outputDir.mkdirs()
            val file = File(outputDir, "audio_interno_${System.currentTimeMillis()}.wav")

            audioRecord = record
            record.startRecording()
            running = true

            thread = Thread {
                var pcmBytes = 0L
                val raf = RandomAccessFile(file, "rw")
                raf.write(ByteArray(44)) // hueco para la cabecera WAV, se rellena al final
                val buffer = ByteArray(minBuf)
                try {
                    while (running) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            raf.write(buffer, 0, read)
                            pcmBytes += read
                        }
                    }
                } finally {
                    finalizeWavHeader(raf, pcmBytes)
                    raf.close()
                    onFileReady(file)
                }
            }.apply { start() }
        } catch (e: Exception) {
            onError("No se pudo iniciar la captura de audio interno: ${e.message}")
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        try { audioRecord?.stop() } catch (e: Exception) { /* ignorar */ }
        audioRecord?.release()
        audioRecord = null
    }

    private fun finalizeWavHeader(raf: RandomAccessFile, pcmBytes: Long) {
        val byteRate = SAMPLE_RATE * 2
        val header = ByteArray(44)
        writeString(header, 0, "RIFF")
        writeIntLE(header, 4, (pcmBytes + 36).toInt())
        writeString(header, 8, "WAVE")
        writeString(header, 12, "fmt ")
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, 1)
        writeIntLE(header, 24, SAMPLE_RATE)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, 2)
        writeShortLE(header, 34, 16)
        writeString(header, 36, "data")
        writeIntLE(header, 40, pcmBytes.toInt())
        raf.seek(0)
        raf.write(header)
    }

    private fun writeString(b: ByteArray, offset: Int, s: String) {
        for (i in s.indices) b[offset + i] = s[i].code.toByte()
    }
    private fun writeIntLE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xff).toByte()
        b[offset + 1] = ((value shr 8) and 0xff).toByte()
        b[offset + 2] = ((value shr 16) and 0xff).toByte()
        b[offset + 3] = ((value shr 24) and 0xff).toByte()
    }
    private fun writeShortLE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xff).toByte()
        b[offset + 1] = ((value shr 8) and 0xff).toByte()
    }
}

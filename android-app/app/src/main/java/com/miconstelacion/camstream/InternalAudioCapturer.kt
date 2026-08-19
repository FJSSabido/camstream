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
 * CÓMO LLEGA A QUIEN VE LA TRANSMISIÓN: se investigó a fondo la vía de "mezclarlo"
 * dentro de la propia pista de audio de WebRTC (la que usa el micrófono) y no es
 * viable sin forkear/recompilar el código Java interno de la librería WebRTC — su
 * único punto de enganche público (`setSamplesReadyCallback`) entrega una COPIA de
 * las muestras, no la referencia real que se codifica y se envía, así que modificarla
 * ahí no tiene ningún efecto en lo que le llega al espectador.
 *
 * En su lugar, cada trozo de audio capturado aquí se manda por un DataChannel de
 * WebRTC — un canal de datos dentro de la MISMA conexión que ya existe con cada
 * espectador, sin abrir nada nuevo — y el navegador lo reproduce con la Web Audio API
 * en paralelo al audio del micrófono (ver `watch.html`): quien mira los oye a la vez,
 * mezclados de forma natural por el propio altavoz/auriculares. Es una solución real
 * con Kotlin + JavaScript estándar, sin código nativo ni forks de WebRTC. Al ir por un
 * canal de datos aparte (no por el pipeline de audio "serio" de WebRTC) puede notarse
 * algún pequeño desajuste de sincronía o algún corte puntual en redes muy inestables;
 * en redes normales no debería notarse.
 *
 * Además, en paralelo se sigue guardando una copia íntegra a un archivo .wav en el
 * almacenamiento privado de la app — útil como respaldo o para comprobar la captura.
 *
 * Solo funciona capturando audio de "reproducción" de otras apps (música, vídeo, juegos
 * con USAGE_MEDIA/USAGE_GAME); Android no permite a apps normales capturar sonidos de
 * llamadas, notificaciones protegidas, ni audio de apps que se marcan como no capturables.
 */
class InternalAudioCapturer(
    private val mediaProjection: MediaProjection,
    private val outputDir: File,
    private val onError: (String) -> Unit,
    private val onFileReady: (File) -> Unit,
    private val onPcmChunk: (ByteArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    companion object {
        // OJO: este valor tiene que coincidir con INTERNAL_AUDIO_SAMPLE_RATE en
        // server/public/watch.html — es el navegador quien reconstruye el audio a
        // partir de las muestras PCM16 crudas que le llegan por el DataChannel, y
        // necesita saber a qué frecuencia se grabaron.
        const val SAMPLE_RATE = 48000
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
                // Trozos pequeños (~20ms a 48kHz) para que la latencia hasta el
                // espectador se note lo menos posible.
                val chunkFrames = (SAMPLE_RATE * 0.02).toInt().coerceAtLeast(1)
                val buffer = ByteArray(minOf(minBuf, chunkFrames * 2).coerceAtLeast(320))
                try {
                    while (running) {
                        // record.read() es una llamada BLOQUEANTE: si se libera el
                        // AudioRecord desde stop() mientras este hilo sigue leyendo, puede
                        // lanzar una excepción. La capturamos aquí dentro para que este
                        // hilo nunca termine con una excepción sin capturar — eso, en
                        // Android, mata la app entera (aunque sea un hilo de fondo), no
                        // solo esta grabación.
                        val read = try {
                            record.read(buffer, 0, buffer.size)
                        } catch (e: Exception) {
                            break
                        }
                        when {
                            read > 0 -> {
                                raf.write(buffer, 0, read)
                                pcmBytes += read
                                try {
                                    onPcmChunk(buffer.copyOf(read))
                                } catch (e: Exception) {
                                    // Un fallo puntual al reenviar el trozo no debe tirar
                                    // abajo la captura entera.
                                }
                            }
                            read < 0 -> break // código de error de AudioRecord: no hay más que leer
                        }
                    }
                } catch (e: Exception) {
                    // Cualquier otro fallo inesperado (p. ej. al escribir en disco): lo
                    // ignoramos aquí para no tirar abajo el proceso entero por un hilo
                    // secundario de una función experimental.
                } finally {
                    try { finalizeWavHeader(raf, pcmBytes) } catch (e: Exception) { /* ignorar */ }
                    try { raf.close() } catch (e: Exception) { /* ignorar */ }
                    onFileReady(file)
                }
            }.apply { start() }
        } catch (e: Exception) {
            onError("No se pudo iniciar la captura de audio interno: ${e.message}")
        }
    }

    fun stop() {
        running = false
        // Paramos el AudioRecord ANTES de esperar al hilo: así, si estaba bloqueado dentro
        // de record.read(), se desbloquea enseguida en vez de quedarse esperando datos que
        // ya no van a llegar (antes se esperaba solo 500ms y, si no le daba tiempo, el hilo
        // se quedaba huérfano en segundo plano hasta que read() decidiera devolver algo).
        try { audioRecord?.stop() } catch (e: Exception) { /* ignorar */ }
        thread?.join(1000)
        thread = null
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

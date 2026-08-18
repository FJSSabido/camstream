package com.miconstelacion.camstream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Genera el código QR del enlace de visualización pidiéndolo a un servicio público
 * (api.qrserver.com) para no tener que empaquetar una librería de generación de QR.
 * Si no hay conexión o el servicio falla, simplemente no se muestra el QR (la URL en
 * texto siempre está disponible para copiar/compartir).
 */
object QrUtils {
    suspend fun fetchQrBitmap(text: String, sizePx: Int = 400): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = URL("https://api.qrserver.com/v1/create-qr-code/?size=${sizePx}x${sizePx}&data=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }
}

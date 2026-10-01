package com.gabriel.cadapeso.ia

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Maneja el archivo del modelo Gemma 2 en el teléfono: importarlo desde un archivo
 * descargado, descargarlo directo de Hugging Face, listarlo y borrarlo.
 *
 * Los modelos se guardan en la carpeta privada de la app (Android/data/.../files/modelos).
 * Quedan fuera del backup automático de Google (ver data_extraction_rules.xml).
 */
class GestorModelos(private val context: Context) {

    companion object {
        const val REPO_HF = "litert-community/Gemma2-2B-IT"
        const val ARCHIVO_HF = "Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task"
        const val URL_HF = "https://huggingface.co/$REPO_HF/resolve/main/$ARCHIVO_HF"
        const val PAGINA_HF = "https://huggingface.co/$REPO_HF"
        private const val TAMANIO_APROX = 2_600_000_000L
    }

    data class EstadoDescarga(val bytes: Long, val total: Long, val terminada: Boolean, val error: String?) {
        val progreso: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    val carpeta: File
        get() = (context.getExternalFilesDir("modelos") ?: File(context.filesDir, "modelos")).apply { mkdirs() }

    fun modelos(): List<File> = carpeta.listFiles()
        ?.filter { it.isFile && (it.name.endsWith(".task") || it.name.endsWith(".bin")) && !it.name.endsWith(".parcial") }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    fun espacioLibre(): Long = runCatching { StatFs(carpeta.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /**
     * Copia un archivo elegido por el usuario (por ejemplo, el .task bajado con el navegador)
     * a la carpeta de la app. MediaPipe necesita una ruta real de archivo, no un "content://".
     */
    suspend fun importar(uri: Uri, progreso: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var nombre = "gemma2.task"
        var total = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { nombre = it }
                if (!c.isNull(1)) total = c.getLong(1)
            }
        }
        if (!nombre.endsWith(".task") && !nombre.endsWith(".bin")) nombre += ".task"
        if (total > 0 && total + 50_000_000 > espacioLibre()) {
            throw IOException("No hay espacio suficiente: el modelo ocupa ${total / 1_000_000} MB.")
        }
        val destino = File(carpeta, nombre)
        val temporal = File(carpeta, "$nombre.parcial")
        try {
            val entrada = resolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo elegido")
            entrada.use { input ->
                temporal.outputStream().buffered(1 shl 20).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copiados = 0L
                    var ultimoAviso = 0L
                    while (true) {
                        coroutineContext.ensureActive() // permite cancelar la copia
                        val leidos = input.read(buffer)
                        if (leidos < 0) break
                        output.write(buffer, 0, leidos)
                        copiados += leidos
                        if (copiados - ultimoAviso > 20_000_000) {
                            ultimoAviso = copiados
                            progreso(copiados, total)
                        }
                    }
                    progreso(copiados, total)
                }
            }
            if (temporal.length() < 50_000_000) {
                throw IOException("El archivo es demasiado chico para ser un modelo Gemma 2 (¿se descargó completo?).")
            }
            destino.delete()
            if (!temporal.renameTo(destino)) throw IOException("No se pudo guardar el modelo")
            destino
        } catch (e: Throwable) {
            temporal.delete()
            throw e
        }
    }

    /**
     * Descarga el modelo desde Hugging Face con el DownloadManager de Android (sigue aunque
     * cierres la app y muestra el progreso en la barra de notificaciones).
     *
     * El modelo es "gated": hay que aceptar la licencia de Gemma en la página del modelo y usar
     * un token de lectura. Primero pedimos con el token la URL firmada del CDN y le pasamos esa
     * URL (sin el token) al DownloadManager, porque los CDN rechazan pedidos que llevan dos
     * credenciales a la vez.
     */
    suspend fun iniciarDescarga(token: String): Long = withContext(Dispatchers.IO) {
        if (TAMANIO_APROX + 100_000_000 > espacioLibre()) {
            throw IOException("Necesitás unos 2,7 GB libres para descargar el modelo.")
        }
        val conexion = (URL(URL_HF).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }
        val urlFinal = try {
            when (val codigo = conexion.responseCode) {
                in 300..399 -> conexion.getHeaderField("Location")?.let { URL(URL(URL_HF), it).toString() }
                    ?: throw IOException("Hugging Face no devolvió la dirección de descarga")
                200 -> URL_HF
                401, 403 -> throw IOException(
                    "Hugging Face rechazó el acceso ($codigo). Entrá a $PAGINA_HF, aceptá la licencia de Gemma " +
                        "y usá un token de lectura válido (huggingface.co/settings/tokens).",
                )
                else -> throw IOException("Hugging Face respondió $codigo")
            }
        } finally {
            conexion.disconnect()
        }
        File(carpeta, ARCHIVO_HF).delete()
        val pedido = DownloadManager.Request(urlFinal.toUri())
            .setTitle("Gemma 2 para CadaPeso")
            .setDescription("Modelo de IA (~2,6 GB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "modelos", ARCHIVO_HF)
            .setAllowedOverMetered(true)
        administrador().enqueue(pedido)
    }

    fun estadoDescarga(id: Long): EstadoDescarga? {
        administrador().query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val estado = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val razon = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (estado) {
                DownloadManager.STATUS_SUCCESSFUL -> EstadoDescarga(bytes, total, true, null)
                DownloadManager.STATUS_FAILED -> EstadoDescarga(bytes, total, true, "La descarga falló (código $razon). Probá de nuevo.")
                else -> EstadoDescarga(bytes, total, false, null)
            }
        }
        return null
    }

    fun cancelarDescarga(id: Long) {
        runCatching { administrador().remove(id) }
    }

    fun archivoDescargado(): File = File(carpeta, ARCHIVO_HF)

    fun borrar(archivo: File): Boolean = archivo.delete()

    private fun administrador() = context.getSystemService(DownloadManager::class.java)
}

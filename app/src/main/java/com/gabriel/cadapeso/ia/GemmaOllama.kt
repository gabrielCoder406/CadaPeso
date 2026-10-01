package com.gabriel.cadapeso.ia

import com.gabriel.cadapeso.datos.ConfigIA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemma 2 corriendo en Ollama, en una PC de tu casa (la misma red Wi-Fi que el teléfono).
 * Sirve para teléfonos con poca memoria o para usar un Gemma más grande (gemma2:9b).
 *
 * En la PC:  ollama pull gemma2:2b   y que Ollama escuche en la red (variable de entorno
 * OLLAMA_HOST=0.0.0.0). En la app se pone la IP de la PC, por ejemplo http://192.168.0.10:11434
 */
class GemmaOllama(var config: ConfigIA) : MotorIA {

    private val base: String get() = normalizarUrl(config.ollamaUrl)

    // Ollama permite un contexto más grande que el modelo del teléfono.
    override val maxTokens: Int = 4096

    override suspend fun preparar() {
        val modelos = listarModelos(base)
        val buscado = config.ollamaModelo.trim()
        require(modelos.any { it == buscado || it == "$buscado:latest" || it.substringBefore(':') == buscado }) {
            "Ollama responde, pero no tiene el modelo \"$buscado\". En la PC ejecutá: ollama pull $buscado"
        }
    }

    override fun generar(prompt: PromptIA): Flow<String> = flow {
        val ajustado = ajustarAlContexto(prompt, this@GemmaOllama)
        val mensajes = JSONArray()
        for (t in ajustado.historial) {
            mensajes.put(JSONObject().put("role", "user").put("content", t.usuario))
            mensajes.put(JSONObject().put("role", "assistant").put("content", t.asistente))
        }
        mensajes.put(JSONObject().put("role", "user").put("content", FormatoGemma.plano(ajustado)))
        val cuerpo = JSONObject()
            .put("model", config.ollamaModelo.trim())
            .put("messages", mensajes)
            .put("stream", true)
            .put("keep_alive", "15m")
            .put(
                "options",
                JSONObject()
                    .put("temperature", ajustado.temperatura ?: config.temperatura)
                    .put("num_ctx", maxTokens)
                    .put("num_predict", ajustado.maxRespuesta),
            )

        val conexion = (URL("$base/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 180_000 // la primera respuesta incluye cargar el modelo en la PC
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conexion.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            if (conexion.responseCode != HttpURLConnection.HTTP_OK) {
                val detalle = conexion.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Ollama respondió ${conexion.responseCode}: ${detalle.take(200)}")
            }
            // Streaming: Ollama manda una línea JSON por cada pedacito de texto.
            conexion.inputStream.bufferedReader(Charsets.UTF_8).useLines { lineas ->
                for (linea in lineas) {
                    if (linea.isBlank()) continue
                    val json = JSONObject(linea)
                    if (json.has("error")) throw IOException(json.getString("error"))
                    val pedazo = json.optJSONObject("message")?.optString("content").orEmpty()
                    if (pedazo.isNotEmpty()) emit(pedazo)
                    if (json.optBoolean("done")) break
                }
            }
        } finally {
            conexion.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Ollama no expone el tokenizador: estimamos ~3,5 caracteres por token en español. */
    override fun contarTokens(texto: String): Int = (texto.length / 3.5).toInt() + 1

    override fun liberar() = Unit

    companion object {
        /** "192.168.0.10" -> "http://192.168.0.10:11434" */
        fun normalizarUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            val sinEsquema = u.substringAfter("://")
            if (!sinEsquema.contains(':')) u = "$u:11434"
            return u
        }

        /** Lista los modelos instalados en Ollama (GET /api/tags). También sirve para probar la conexión. */
        suspend fun listarModelos(url: String): List<String> = withContext(Dispatchers.IO) {
            val base = normalizarUrl(url)
            require(base.isNotEmpty()) { "Falta la dirección de Ollama (por ejemplo http://192.168.0.10:11434)" }
            val conexion = (URL("$base/api/tags").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 10_000
            }
            try {
                val codigo = runCatching { conexion.responseCode }.getOrElse {
                    throw IOException(
                        "No pude conectar con $base. Revisá que la PC y el teléfono estén en la misma red, " +
                            "que Ollama esté abierto con OLLAMA_HOST=0.0.0.0 y que el firewall permita el puerto 11434.",
                    )
                }
                if (codigo != 200) throw IOException("Ollama respondió $codigo")
                val json = JSONObject(conexion.inputStream.bufferedReader().use { it.readText() })
                val arreglo = json.optJSONArray("models") ?: JSONArray()
                (0 until arreglo.length()).map { arreglo.getJSONObject(it).optString("name") }
            } finally {
                conexion.disconnect()
            }
        }
    }
}

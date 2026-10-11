package com.gabriel.cadapeso.integraciones

import com.gabriel.cadapeso.dominio.OrigenMovimiento
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** Error que devolvió el servidor de CadaPeso (con su código HTTP y el mensaje para mostrar). */
class ErrorServidor(mensaje: String, val codigoHttp: Int) : IOException(mensaje) {
    /** El servidor ya no reconoce este teléfono (se borró o cambió la base): hay que registrarse de nuevo. */
    val noAutorizado: Boolean get() = codigoHttp == 401
}

/** Usuario, clave y campos extra del home banking de Galicia. Se guardan solo en el teléfono, cifrados. */
data class CredencialesGalicia(val usuario: String, val clave: String, val campos: Map<String, String> = emptyMap())

/** Respuesta del usuario cuando el banco pide un dato más (token por SMS, pregunta de seguridad). */
data class RespuestaBanco(val sesion: String, val campo: String, val respuesta: String)

/**
 * Habla con el servidor de CadaPeso (carpeta servidor/ del proyecto) por HTTPS.
 * El servidor guarda los tokens de Mercado Pago, recibe sus webhooks, consulta Galicia vía
 * Prometeo y le entrega al teléfono los movimientos ya convertidos al esquema unificado.
 *
 * @param token el que entregó el servidor al registrar este teléfono (null solo para registrarse)
 */
class ClienteServidor(baseUrl: String, private val token: String?) {

    private val base = normalizarUrl(baseUrl)

    /** Registra el teléfono con la clave de registro del servidor. Devuelve el token. */
    suspend fun registrar(claveRegistro: String, dispositivo: String): String {
        val r = pedir("POST", "/v1/dispositivos", JSONObject().put("clave_registro", claveRegistro).put("dispositivo", dispositivo))
        return JSONObject(r).getString("token")
    }

    suspend fun estado(): EstadoServidor = JsonServidor.estado(pedir("GET", "/v1/estado"))

    /** URL de Mercado Pago donde autorizás el acceso (se abre en el navegador). */
    suspend fun urlVincularMercadoPago(): String = JSONObject(pedir("POST", "/v1/mercadopago/vincular")).getString("url")

    /** Le pide al servidor que traiga ya los pagos de Mercado Pago. Devuelve el error, si hubo. */
    suspend fun sincronizar(): String? {
        val mp = JSONObject(pedir("POST", "/v1/sincronizar", tiempoLecturaMs = 120_000)).optJSONObject("mercado_pago") ?: return null
        return if (mp.optBoolean("ok")) null else mp.optString("error").ifBlank { "Error al sincronizar Mercado Pago" }
    }

    suspend fun camposGalicia(): List<CampoBanco> = JsonServidor.campos(pedir("GET", "/v1/galicia/campos", tiempoLecturaMs = 60_000))

    /**
     * Descarga los movimientos de Galicia de los últimos [dias] (quedan en el servidor para
     * bajarlos con [transacciones]). Las credenciales viajan cifradas por HTTPS y el servidor no
     * las guarda.
     */
    suspend fun sincronizarGalicia(c: CredencialesGalicia, respuesta: RespuestaBanco?, dias: Int): ResultadoGalicia {
        val campos = JSONObject().apply { c.campos.forEach { (k, v) -> put(k, v) } }
        val cuerpo = JSONObject().put("usuario", c.usuario).put("clave", c.clave).put("dias", dias).put("campos", campos)
        respuesta?.let { cuerpo.put("interaccion", JSONObject().put("sesion", it.sesion).put("campo", it.campo).put("respuesta", it.respuesta)) }
        // Los bancos pueden tardar: Prometeo inicia sesión y recorre cuentas y tarjetas.
        return JsonServidor.resultadoGalicia(pedir("POST", "/v1/galicia/sincronizar", cuerpo, tiempoLecturaMs = 300_000))
    }

    /** Una página de transacciones después de [desde]. Pedirla confirma que ya guardaste las anteriores. */
    suspend fun transacciones(desde: Long, instancia: String): LoteTransacciones {
        val query = "desde=$desde&limite=200" + if (instancia.isNotBlank()) "&instancia=" + URLEncoder.encode(instancia, "UTF-8") else ""
        return JsonServidor.lote(pedir("GET", "/v1/transacciones?$query"))
    }

    suspend fun desvincular(origen: OrigenMovimiento) {
        pedir("DELETE", "/v1/conexiones/${origen.name.lowercase().replace('_', '-')}")
    }

    /** Borra este teléfono y TODOS sus datos del servidor. */
    suspend fun borrarDispositivo() {
        pedir("DELETE", "/v1/dispositivos/yo")
    }

    private suspend fun pedir(metodo: String, ruta: String, cuerpo: JSONObject? = null, tiempoLecturaMs: Int = 30_000): String =
        withContext(Dispatchers.IO) {
            require(base.isNotEmpty()) { "Falta la dirección del servidor" }
            val conexion = (URL(base + ruta).openConnection() as HttpURLConnection).apply {
                requestMethod = metodo
                connectTimeout = 10_000
                readTimeout = tiempoLecturaMs
                setRequestProperty("Accept", "application/json")
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (cuerpo != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                cuerpo?.let { c -> conexion.outputStream.use { it.write(c.toString().toByteArray(Charsets.UTF_8)) } }
                val codigo = runCatching { conexion.responseCode }.getOrElse {
                    throw IOException("No pude conectar con el servidor ($base). Revisá la dirección y tu conexión a internet.")
                }
                val flujo = if (codigo in 200..299) conexion.inputStream else conexion.errorStream
                val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (codigo !in 200..299) {
                    val mensaje = JsonServidor.error(texto) ?: "El servidor respondió $codigo"
                    throw ErrorServidor(mensaje, codigo)
                }
                texto
            } finally {
                conexion.disconnect()
            }
        }

    companion object {
        /**
         * "cadapeso.midominio.com" → "https://cadapeso.midominio.com". Se exige HTTPS, salvo para
         * direcciones de la red de tu casa (para probar el servidor en la PC).
         */
        fun normalizarUrl(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return u
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            val host = runCatching { URI(u).host }.getOrNull()
            require(!host.isNullOrBlank()) { "La dirección del servidor no es válida" }
            require(u.startsWith("https://") || esRedLocal(host)) {
                "Usá una dirección https:// (sin cifrar solo se permite dentro de tu red, por ejemplo http://192.168.0.10:8080)"
            }
            return u
        }

        private fun esRedLocal(host: String): Boolean =
            host == "localhost" || host.endsWith(".local") || host.startsWith("10.") || host.startsWith("192.168.") ||
                host.startsWith("127.") || Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
    }
}

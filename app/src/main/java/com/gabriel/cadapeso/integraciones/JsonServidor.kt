package com.gabriel.cadapeso.integraciones

import com.gabriel.cadapeso.dominio.EstadoTransaccion
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.SaldoCuenta
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.TransaccionExterna
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Estado de una cuenta vinculada en el servidor. */
data class ConexionServidor(
    val origen: OrigenMovimiento,
    val alias: String,
    /** ACTIVA | ERROR | REVOCADA */
    val estado: String,
    val error: String,
    val ultimaSincronizacion: String,
) {
    val necesitaRevincular: Boolean get() = estado == "REVOCADA"
}

data class EstadoServidor(
    val instancia: String,
    val mercadoPagoDisponible: Boolean,
    val galiciaDisponible: Boolean,
    val conexiones: List<ConexionServidor>,
    val saldos: List<SaldoCuenta>,
    val pendientes: Int,
) {
    fun conexion(origen: OrigenMovimiento): ConexionServidor? = conexiones.firstOrNull { it.origen == origen }
}

/** Una página de transacciones: el cursor se guarda recién cuando la página quedó en la base. */
data class LoteTransacciones(
    val instancia: String,
    val cursor: Long,
    val hayMas: Boolean,
    val transacciones: List<TransaccionExterna>,
    /** Las que venían con algo inválido (se saltean para no trabar la sincronización). */
    val invalidas: Int,
)

/** Campo extra que pide el banco para iniciar sesión (por ejemplo, el DNI). */
data class CampoBanco(
    val nombre: String,
    val etiqueta: String,
    /** texto | clave | opciones */
    val tipo: String,
    val opcional: Boolean,
    val opciones: List<Pair<String, String>>,
)

/** Resultado de sincronizar Galicia: listo, o el banco pide un dato más (token por SMS, pregunta). */
sealed interface ResultadoGalicia {
    data class Listo(val nuevas: Int, val cuentas: Int) : ResultadoGalicia
    data class PideDato(val pregunta: String, val campo: String, val sesion: String) : ResultadoGalicia
}

/**
 * Convierte las respuestas JSON del servidor de CadaPeso a objetos de la app. Separado del
 * cliente HTTP para poder probarlo sin red.
 */
object JsonServidor {

    fun origen(texto: String): OrigenMovimiento? = runCatching { OrigenMovimiento.valueOf(texto) }.getOrNull()

    /** Una transacción del esquema unificado (snake_case, como la manda el servidor). */
    fun transaccion(o: JSONObject): TransaccionExterna? = runCatching {
        TransaccionExterna(
            idTransaccion = o.getString("id_transaccion"),
            origen = origen(o.getString("origen")) ?: return null,
            tipo = TipoMovimiento.valueOf(o.getString("tipo_movimiento")),
            monto = o.getDouble("monto").also { require(it >= 0 && !it.isNaN()) },
            moneda = o.optString("moneda", "ARS").ifBlank { "ARS" },
            fecha = LocalDate.parse(o.getString("fecha")),
            descripcion = o.optString("descripcion").ifBlank { "Movimiento" },
            categoria = o.textoONull("categoria"),
            medioPago = runCatching { MedioPago.valueOf(o.getString("medio_pago")) }.getOrDefault(MedioPago.TRANSFERENCIA),
            estado = if (o.optString("estado") == "ANULADA") EstadoTransaccion.ANULADA else EstadoTransaccion.CONFIRMADA,
            transferenciaPropia = o.optBoolean("transferencia_propia"),
            cuenta = o.textoONull("cuenta").orEmpty(),
            contraparte = o.textoONull("contraparte").orEmpty(),
        )
    }.getOrNull()

    fun lote(texto: String): LoteTransacciones {
        val o = JSONObject(texto)
        val arreglo = o.optJSONArray("transacciones") ?: JSONArray()
        val lista = (0 until arreglo.length()).mapNotNull { i -> arreglo.optJSONObject(i)?.let(::transaccion) }
        return LoteTransacciones(
            instancia = o.optString("instancia"),
            cursor = o.optLong("cursor"),
            hayMas = o.optBoolean("hay_mas"),
            transacciones = lista,
            invalidas = arreglo.length() - lista.size,
        )
    }

    fun estado(texto: String): EstadoServidor {
        val o = JSONObject(texto)
        val servicios = o.optJSONObject("servicios") ?: JSONObject()
        val conexiones = o.optJSONArray("conexiones") ?: JSONArray()
        val saldos = o.optJSONArray("saldos") ?: JSONArray()
        return EstadoServidor(
            instancia = o.optString("instancia"),
            mercadoPagoDisponible = servicios.optBoolean("mercado_pago"),
            galiciaDisponible = servicios.optBoolean("galicia"),
            conexiones = (0 until conexiones.length()).mapNotNull { i ->
                val c = conexiones.optJSONObject(i) ?: return@mapNotNull null
                ConexionServidor(
                    origen = origen(c.optString("proveedor")) ?: return@mapNotNull null,
                    alias = c.textoONull("alias").orEmpty(),
                    estado = c.optString("estado"),
                    error = c.textoONull("ultimo_error").orEmpty(),
                    ultimaSincronizacion = c.textoONull("ultima_sincronizacion").orEmpty(),
                )
            },
            saldos = (0 until saldos.length()).mapNotNull { i ->
                val s = saldos.optJSONObject(i) ?: return@mapNotNull null
                SaldoCuenta(
                    origen = origen(s.optString("origen")) ?: return@mapNotNull null,
                    cuenta = s.optString("cuenta"),
                    moneda = s.optString("moneda", "ARS"),
                    monto = s.optDouble("monto", 0.0),
                )
            },
            pendientes = o.optInt("pendientes"),
        )
    }

    fun campos(texto: String): List<CampoBanco> {
        val arreglo = JSONObject(texto).optJSONArray("campos") ?: JSONArray()
        return (0 until arreglo.length()).mapNotNull { i ->
            val c = arreglo.optJSONObject(i) ?: return@mapNotNull null
            val opciones = c.optJSONArray("opciones") ?: JSONArray()
            CampoBanco(
                nombre = c.optString("nombre").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                etiqueta = c.optString("etiqueta").ifBlank { c.optString("nombre") },
                tipo = c.optString("tipo", "texto"),
                opcional = c.optBoolean("opcional"),
                opciones = (0 until opciones.length()).mapNotNull { j ->
                    opciones.optJSONObject(j)?.let { it.optString("valor") to it.optString("etiqueta").ifBlank { it.optString("valor") } }
                },
            )
        }
    }

    fun resultadoGalicia(texto: String): ResultadoGalicia {
        val o = JSONObject(texto)
        return if (o.optString("estado") == "interaccion") {
            ResultadoGalicia.PideDato(
                pregunta = o.optString("pregunta").ifBlank { "El banco pide un dato más" },
                campo = o.getString("campo"),
                sesion = o.getString("sesion"),
            )
        } else {
            ResultadoGalicia.Listo(nuevas = o.optInt("nuevas"), cuentas = o.optInt("cuentas"))
        }
    }

    /** Credenciales de Galicia ↔ JSON (para guardarlas cifradas en el cofre). */
    fun credencialesAJson(c: CredencialesGalicia): String = JSONObject()
        .put("usuario", c.usuario).put("clave", c.clave)
        .put("campos", JSONObject().apply { c.campos.forEach { (k, v) -> put(k, v) } })
        .toString()

    fun credencialesDeJson(texto: String): CredencialesGalicia? = runCatching {
        val o = JSONObject(texto)
        val campos = o.optJSONObject("campos") ?: JSONObject()
        CredencialesGalicia(
            usuario = o.getString("usuario"),
            clave = o.getString("clave"),
            campos = campos.keys().asSequence().associateWith { campos.optString(it) },
        )
    }.getOrNull()

    /** El mensaje de error que manda el servidor ({"error": "..."}), o null. */
    fun error(texto: String): String? = runCatching { JSONObject(texto).textoONull("error") }.getOrNull()

    private fun JSONObject.textoONull(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
}

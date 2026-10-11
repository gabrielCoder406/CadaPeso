package com.gabriel.cadapeso.integraciones

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.gabriel.cadapeso.datos.Repositorio
import com.gabriel.cadapeso.datos.ResumenImportacion
import com.gabriel.cadapeso.dominio.CanalDeteccion
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.TransaccionExterna
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.LocalDate

/** Configuración de las cuentas conectadas (lo que se ve en la pantalla "Cuentas conectadas"). */
data class ConfigIntegraciones(
    val servidorUrl: String = "",
    /** Hay un token del servidor guardado en el cofre. */
    val registrado: Boolean = false,
    val leerNotificacionesGalicia: Boolean = false,
    val leerNotificacionesMercadoPago: Boolean = false,
    /** Agregar sin revisar lo que llega de la cuenta conectada (nunca lo de notificaciones). */
    val aceptarSolas: Boolean = false,
    /** Mantener los saldos de las cuentas conectadas como activos en Patrimonio. */
    val reflejarSaldos: Boolean = true,
    /** Usuario y clave de Galicia guardados en el cofre para sincronizar solo. */
    val galiciaGuardada: Boolean = false,
    val galiciaUsuario: String = "",
    val ultimaSincronizacion: Long = 0,
    val ultimoError: String = "",
    /** Última respuesta de /v1/estado (conexiones y saldos). */
    val estado: EstadoServidor? = null,
) {
    fun leeNotificacionesDe(origen: OrigenMovimiento): Boolean = when (origen) {
        OrigenMovimiento.GALICIA -> leerNotificacionesGalicia
        OrigenMovimiento.MERCADO_PAGO -> leerNotificacionesMercadoPago
        OrigenMovimiento.MANUAL -> false
    }
}

data class ResumenSincronizacion(val importacion: ResumenImportacion, val errores: List<String>)

/**
 * Todo lo de las cuentas conectadas en un lugar: configuración, registro en el servidor,
 * vinculación de Mercado Pago, Galicia vía Prometeo, sincronización y notificaciones.
 * Lo usan la pantalla (a través de ConexionesViewModel), el worker y el oyente de notificaciones.
 */
class GestorIntegraciones(private val app: Application, private val repo: Repositorio) {

    private val prefs = app.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
    private val cofre by lazy { Cofre(app) }
    private val candado = Mutex()

    private val _config = MutableStateFlow(leerConfig())
    val config: StateFlow<ConfigIntegraciones> = _config.asStateFlow()

    private fun leerConfig() = ConfigIntegraciones(
        servidorUrl = prefs.getString("servidor_url", "").orEmpty(),
        registrado = prefs.getBoolean("registrado", false),
        leerNotificacionesGalicia = prefs.getBoolean("notif_galicia", false),
        leerNotificacionesMercadoPago = prefs.getBoolean("notif_mercadopago", false),
        aceptarSolas = prefs.getBoolean("aceptar_solas", false),
        reflejarSaldos = prefs.getBoolean("reflejar_saldos", true),
        galiciaGuardada = prefs.getBoolean("galicia_guardada", false),
        galiciaUsuario = prefs.getString("galicia_usuario", "").orEmpty(),
        ultimaSincronizacion = prefs.getLong("ultima_sincronizacion", 0),
        ultimoError = prefs.getString("ultimo_error", "").orEmpty(),
        estado = prefs.getString("estado_json", null)?.let { runCatching { JsonServidor.estado(it) }.getOrNull() },
    )

    private fun cambiar(bloque: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit(commit = true, action = bloque)
        _config.value = leerConfig()
    }

    // ------------------------------------------------------------------ Opciones

    fun ponerLeerNotificaciones(origen: OrigenMovimiento, activo: Boolean) = cambiar {
        when (origen) {
            OrigenMovimiento.GALICIA -> putBoolean("notif_galicia", activo)
            OrigenMovimiento.MERCADO_PAGO -> putBoolean("notif_mercadopago", activo)
            OrigenMovimiento.MANUAL -> Unit
        }
    }

    fun ponerAceptarSolas(activo: Boolean) = cambiar { putBoolean("aceptar_solas", activo) }
    fun ponerReflejarSaldos(activo: Boolean) = cambiar { putBoolean("reflejar_saldos", activo) }

    // ------------------------------------------------------------------ Servidor

    private fun cliente(): ClienteServidor {
        val c = _config.value
        val token = cofre.leer(Cofre.TOKEN_SERVIDOR)
        check(c.registrado && token != null) { "Este teléfono no está conectado al servidor" }
        return ClienteServidor(c.servidorUrl, token)
    }

    /** Registra el teléfono en el servidor y programa la sincronización automática. */
    suspend fun registrar(url: String, claveRegistro: String) {
        val base = ClienteServidor.normalizarUrl(url)
        val token = ClienteServidor(base, null).registrar(claveRegistro.trim(), "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        cofre.guardar(Cofre.TOKEN_SERVIDOR, token)
        cambiar {
            putString("servidor_url", base)
            putBoolean("registrado", true)
            putLong("cursor", 0)
            putString("instancia", "")
            putString("ultimo_error", "")
        }
        SincronizacionWorker.programar(app)
        runCatching { actualizarEstado(ClienteServidor(base, token)) }
    }

    /**
     * Desconecta el teléfono: pide al servidor que borre todo lo suyo (tokens de Mercado Pago,
     * movimientos pendientes) y olvida el token. Los movimientos que ya están en la app quedan.
     */
    suspend fun desconectarServidor() {
        runCatching { cliente().borrarDispositivo() }
        cofre.guardar(Cofre.TOKEN_SERVIDOR, null)
        cofre.guardar(Cofre.CREDENCIALES_GALICIA, null)
        SincronizacionWorker.cancelar(app)
        cambiar {
            putBoolean("registrado", false)
            putBoolean("galicia_guardada", false)
            putString("galicia_usuario", "")
            remove("estado_json")
            putLong("cursor", 0)
            putString("instancia", "")
            putString("ultimo_error", "")
        }
    }

    private suspend fun actualizarEstado(cliente: ClienteServidor = cliente()): EstadoServidor {
        val estado = cliente.estado()
        cambiar { putString("estado_json", estadoAJson(estado)) }
        if (_config.value.reflejarSaldos) reflejarSaldos(estado)
        return estado
    }

    // ------------------------------------------------------------------ Mercado Pago

    suspend fun urlVincularMercadoPago(): String = cliente().urlVincularMercadoPago()

    suspend fun desvincular(origen: OrigenMovimiento) {
        cliente().desvincular(origen)
        if (origen == OrigenMovimiento.GALICIA) olvidarGalicia()
        actualizarEstado()
    }

    // ------------------------------------------------------------------ Galicia (Prometeo)

    suspend fun camposGalicia(): List<CampoBanco> = cliente().camposGalicia()

    /**
     * Descarga los movimientos de Galicia y los trae a la bandeja. Si [recordar], el usuario y la
     * clave quedan cifrados en el cofre para que la sincronización automática también traiga Galicia.
     */
    suspend fun conectarGalicia(c: CredencialesGalicia, recordar: Boolean, respuesta: RespuestaBanco? = null): ResultadoGalicia {
        val primeraVez = !_config.value.galiciaGuardada
        val resultado = cliente().sincronizarGalicia(c, respuesta, dias = if (primeraVez) 90 else 10)
        if (resultado is ResultadoGalicia.Listo) {
            if (recordar) {
                cofre.guardar(Cofre.CREDENCIALES_GALICIA, JsonServidor.credencialesAJson(c))
                cambiar {
                    putBoolean("galicia_guardada", true)
                    putString("galicia_usuario", c.usuario)
                }
            }
            sincronizar(pedirAlServidor = false)
        }
        return resultado
    }

    /** Usuario y clave guardados (descifrados), para sincronizar Galicia a pedido. */
    fun credencialesGalicia(): CredencialesGalicia? = cofre.leer(Cofre.CREDENCIALES_GALICIA)?.let(JsonServidor::credencialesDeJson)

    fun olvidarGalicia() {
        cofre.guardar(Cofre.CREDENCIALES_GALICIA, null)
        cambiar {
            putBoolean("galicia_guardada", false)
            putString("galicia_usuario", "")
        }
    }

    // ------------------------------------------------------------------ Sincronización

    /**
     * Sincronización completa: le pide al servidor que traiga lo nuevo de Mercado Pago (y de
     * Galicia, si guardaste la clave), baja todas las transacciones pendientes con el cursor y
     * las pasa a la bandeja. El cursor se guarda recién cuando cada página quedó en la base: si
     * se corta a la mitad, la próxima vez se vuelve a pedir esa página (y los duplicados se ignoran).
     */
    suspend fun sincronizar(pedirAlServidor: Boolean = true): ResumenSincronizacion = candado.withLock {
        val config = _config.value
        if (!config.registrado) return@withLock ResumenSincronizacion(ResumenImportacion(), emptyList())
        val errores = mutableListOf<String>()
        var resumen = ResumenImportacion()
        try {
            val cliente = cliente()
            if (pedirAlServidor) {
                if (config.estado?.conexion(OrigenMovimiento.MERCADO_PAGO) != null) {
                    cliente.sincronizar()?.let { errores += "Mercado Pago: $it" }
                }
                if (config.galiciaGuardada) {
                    val credenciales = cofre.leer(Cofre.CREDENCIALES_GALICIA)?.let(JsonServidor::credencialesDeJson)
                    if (credenciales == null) {
                        errores += "Galicia: no se pudo leer la clave guardada; volvé a conectar Galicia"
                        olvidarGalicia()
                    } else {
                        val r = runCatching { cliente.sincronizarGalicia(credenciales, null, dias = 10) }
                        r.exceptionOrNull()?.let { errores += "Galicia: ${it.message}" }
                        if (r.getOrNull() is ResultadoGalicia.PideDato) {
                            errores += "Galicia pide un código de seguridad: sincronizalo desde Cuentas conectadas"
                        }
                    }
                }
            }

            // Se piden páginas hasta que una venga vacía: ese último pedido, con el cursor final,
            // confirma la entrega y el servidor borra lo que ya está en el teléfono.
            var cursor = prefs.getLong("cursor", 0)
            var instancia = prefs.getString("instancia", "").orEmpty()
            var paginas = 0
            do {
                val lote = cliente.transacciones(cursor, instancia)
                if (lote.transacciones.isNotEmpty()) {
                    resumen += repo.importarTransacciones(lote.transacciones, CanalDeteccion.SERVIDOR, config.aceptarSolas)
                }
                cursor = lote.cursor
                instancia = lote.instancia
                prefs.edit(commit = true) {
                    putLong("cursor", cursor)
                    putString("instancia", instancia)
                }
                paginas++
            } while (lote.transacciones.isNotEmpty() && paginas < MAX_PAGINAS)
            val estado = actualizarEstado(cliente)
            estado.conexiones.filter { it.necesitaRevincular }.forEach { errores += "${it.origen.etiqueta}: ${it.error.ifBlank { "volvé a vincular la cuenta" }}" }
        } catch (e: ErrorServidor) {
            if (e.noAutorizado) {
                errores += "El servidor ya no reconoce este teléfono: volvé a conectarlo"
                cofre.guardar(Cofre.TOKEN_SERVIDOR, null)
                cambiar { putBoolean("registrado", false) }
                SincronizacionWorker.cancelar(app)
            } else {
                errores += e.message ?: "Error del servidor"
            }
        } catch (e: Exception) {
            errores += e.message ?: "No se pudo sincronizar"
        }
        cambiar {
            putLong("ultima_sincronizacion", System.currentTimeMillis())
            putString("ultimo_error", errores.joinToString("\n"))
        }
        ResumenSincronizacion(resumen, errores)
    }

    /** Una notificación del banco ya interpretada: entra a la bandeja si activaste esa fuente. */
    suspend fun importarNotificacion(t: TransaccionExterna): ResumenImportacion {
        if (!_config.value.leeNotificacionesDe(t.origen)) return ResumenImportacion()
        return repo.importarTransacciones(listOf(t), CanalDeteccion.NOTIFICACION, aceptarSolas = false)
    }

    /**
     * Cada saldo en pesos se refleja en un activo "Cuenta o billetera" de Patrimonio. Se recuerda
     * qué activo es de cada cuenta; si lo borrás, no se vuelve a crear.
     */
    private suspend fun reflejarSaldos(estado: EstadoServidor) {
        for (s in estado.saldos.filter { it.moneda == "ARS" }) {
            val clave = "activo_${s.origen.name}_${s.cuenta}"
            val guardado = prefs.getLong(clave, 0)
            if (guardado < 0) continue // lo borraste a propósito
            val nombre = if (s.origen == OrigenMovimiento.MERCADO_PAGO) "Mercado Pago" else "${s.origen.etiqueta} · ${s.cuenta}"
            val id = repo.reflejarSaldo(guardado.takeIf { it > 0 }, nombre, s.monto, LocalDate.now())
            prefs.edit { putLong(clave, id ?: -1) }
        }
    }

    companion object {
        const val ARCHIVO = "integraciones"
        private const val MAX_PAGINAS = 50

        /** Se guarda la respuesta cruda de /v1/estado para mostrar algo aunque no haya internet. */
        private fun estadoAJson(e: EstadoServidor): String = JSONObject()
            .put("instancia", e.instancia)
            .put("servicios", JSONObject().put("mercado_pago", e.mercadoPagoDisponible).put("galicia", e.galiciaDisponible))
            .put("conexiones", org.json.JSONArray(e.conexiones.map {
                JSONObject().put("proveedor", it.origen.name).put("alias", it.alias).put("estado", it.estado)
                    .put("ultimo_error", it.error).put("ultima_sincronizacion", it.ultimaSincronizacion)
            }))
            .put("saldos", org.json.JSONArray(e.saldos.map {
                JSONObject().put("origen", it.origen.name).put("cuenta", it.cuenta).put("moneda", it.moneda).put("monto", it.monto)
            }))
            .put("pendientes", e.pendientes)
            .toString()
    }
}

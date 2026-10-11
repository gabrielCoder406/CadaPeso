package com.gabriel.cadapeso.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gabriel.cadapeso.Contenedor
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.datos.MovimientoDetectado
import com.gabriel.cadapeso.datos.ResumenImportacion
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.plural
import com.gabriel.cadapeso.integraciones.CampoBanco
import com.gabriel.cadapeso.integraciones.ConfigIntegraciones
import com.gabriel.cadapeso.integraciones.CredencialesGalicia
import com.gabriel.cadapeso.integraciones.ResultadoGalicia
import com.gabriel.cadapeso.integraciones.RespuestaBanco
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Galicia pidió un dato más: se guarda lo necesario para reintentar con la respuesta. */
data class PreguntaBanco(val pregunta: ResultadoGalicia.PideDato, val credenciales: CredencialesGalicia, val recordar: Boolean)

/**
 * ViewModel de las cuentas conectadas y de la bandeja de movimientos detectados.
 * Las operaciones de red muestran "trabajando" y avisan el resultado con un Snackbar.
 */
class ConexionesViewModel(private val c: Contenedor) : ViewModel() {

    val config: StateFlow<ConfigIntegraciones> = c.integraciones.config

    val pendientes: StateFlow<List<MovimientoDetectado>> =
        c.repo.detectadosPendientes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Qué se está haciendo ("Sincronizando…"); null = nada. */
    private val _trabajando = MutableStateFlow<String?>(null)
    val trabajando: StateFlow<String?> = _trabajando.asStateFlow()

    private val _camposGalicia = MutableStateFlow<List<CampoBanco>>(emptyList())
    val camposGalicia: StateFlow<List<CampoBanco>> = _camposGalicia.asStateFlow()

    private val _pregunta = MutableStateFlow<PreguntaBanco?>(null)
    val pregunta: StateFlow<PreguntaBanco?> = _pregunta.asStateFlow()

    private val _avisos = MutableSharedFlow<Aviso>(extraBufferCapacity = 8)
    val avisos: SharedFlow<Aviso> = _avisos.asSharedFlow()

    private var ultimaAutomatica = 0L

    private fun avisar(texto: String, accion: String? = null, alAccionar: (() -> Unit)? = null) {
        _avisos.tryEmit(Aviso(texto, accion, alAccionar))
    }

    /** Corre una operación mostrando qué se está haciendo; los errores se avisan. */
    private fun trabajar(descripcion: String, bloque: suspend () -> Unit) {
        if (_trabajando.value != null) return
        viewModelScope.launch {
            _trabajando.value = descripcion
            try {
                bloque()
            } catch (e: Exception) {
                avisar(e.message ?: "Ocurrió un error")
            } finally {
                _trabajando.value = null
            }
        }
    }

    private fun textoResumen(r: ResumenImportacion): String = when {
        r.pendientes == 0 && r.aceptados == 0 -> "Sin movimientos nuevos"
        r.aceptados == 0 -> "${plural(r.pendientes, "movimiento nuevo", "movimientos nuevos")} para revisar"
        else -> plural(r.aceptados, "movimiento agregado", "movimientos agregados") +
            if (r.pendientes > 0) " y ${r.pendientes} para revisar" else ""
    }

    // ------------------------------------------------------------------ Servidor

    fun registrar(url: String, clave: String) = trabajar("Conectando con el servidor…") {
        c.integraciones.registrar(url, clave)
        avisar("¡Listo! Este teléfono quedó conectado al servidor")
    }

    fun desconectarServidor() = trabajar("Desconectando…") {
        c.integraciones.desconectarServidor()
        avisar("Teléfono desconectado. Tus movimientos siguen en la app")
    }

    fun sincronizar() = trabajar("Sincronizando…") {
        val r = c.integraciones.sincronizar()
        avisar(r.errores.firstOrNull() ?: textoResumen(r.importacion))
    }

    /** Al abrir la app: sincroniza en silencio si pasaron más de 15 minutos. */
    fun sincronizarSiHaceFalta() {
        val cfg = config.value
        val ahora = System.currentTimeMillis()
        if (!cfg.registrado || _trabajando.value != null) return
        if (ahora - cfg.ultimaSincronizacion < 15 * 60_000 || ahora - ultimaAutomatica < 15 * 60_000) return
        ultimaAutomatica = ahora
        viewModelScope.launch {
            val r = runCatching { c.integraciones.sincronizar() }.getOrNull() ?: return@launch
            if (r.importacion.pendientes > 0 || r.importacion.aceptados > 0) avisar(textoResumen(r.importacion))
        }
    }

    // ------------------------------------------------------------------ Mercado Pago

    /** Pide al servidor la URL de autorización y la abre en el navegador. */
    fun vincularMercadoPago(abrir: (String) -> Unit) = trabajar("Preparando la vinculación…") {
        abrir(c.integraciones.urlVincularMercadoPago())
    }

    /** Volvimos del navegador con la cuenta ya vinculada. */
    fun alVolverDeMercadoPago(ok: Boolean) {
        if (!ok) {
            avisar("No se completó la vinculación con Mercado Pago")
            return
        }
        avisar("¡Mercado Pago vinculado! Trayendo tus movimientos…")
        trabajar("Trayendo tus movimientos de Mercado Pago…") {
            // La primera descarga corre en el servidor: le damos unos segundos antes de pedirla.
            kotlinx.coroutines.delay(4_000)
            val r = c.integraciones.sincronizar(pedirAlServidor = false)
            avisar(r.errores.firstOrNull() ?: textoResumen(r.importacion))
        }
    }

    fun desvincular(origen: OrigenMovimiento) = trabajar("Desvinculando…") {
        c.integraciones.desvincular(origen)
        avisar("${origen.etiqueta} desvinculado")
    }

    // ------------------------------------------------------------------ Galicia

    fun cargarCamposGalicia() {
        if (_camposGalicia.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { c.integraciones.camposGalicia() }.onSuccess { _camposGalicia.value = it }
        }
    }

    fun conectarGalicia(credenciales: CredencialesGalicia, recordar: Boolean, respuesta: RespuestaBanco? = null) =
        trabajar("Conectando con Galicia (puede tardar un minuto)…") {
            _pregunta.value = null
            when (val r = c.integraciones.conectarGalicia(credenciales, recordar, respuesta)) {
                is ResultadoGalicia.PideDato -> _pregunta.value = PreguntaBanco(r, credenciales, recordar)
                is ResultadoGalicia.Listo -> avisar(
                    "Galicia: ${plural(r.cuentas, "cuenta leída", "cuentas leídas")}, ${plural(r.nuevas, "movimiento nuevo", "movimientos nuevos")}",
                )
            }
        }

    /** Sincroniza Galicia ya, con la clave guardada (si el banco pide un código, se pregunta). */
    fun sincronizarGalicia() {
        val credenciales = c.integraciones.credencialesGalicia()
        if (credenciales == null) {
            avisar("No se pudo leer la clave guardada: volvé a conectar Galicia")
            c.integraciones.olvidarGalicia()
            return
        }
        conectarGalicia(credenciales, recordar = true)
    }

    fun responderBanco(respuesta: String) {
        val p = _pregunta.value ?: return
        conectarGalicia(p.credenciales, p.recordar, RespuestaBanco(p.pregunta.sesion, p.pregunta.campo, respuesta.trim()))
    }

    fun cancelarPregunta() {
        _pregunta.value = null
    }

    fun olvidarGalicia() {
        c.integraciones.olvidarGalicia()
        avisar("Se borró la clave de Galicia del teléfono")
    }

    // ------------------------------------------------------------------ Opciones

    fun leerNotificaciones(origen: OrigenMovimiento, activo: Boolean) = c.integraciones.ponerLeerNotificaciones(origen, activo)
    fun aceptarSolas(activo: Boolean) = c.integraciones.ponerAceptarSolas(activo)
    fun reflejarSaldos(activo: Boolean) = c.integraciones.ponerReflejarSaldos(activo)

    // ------------------------------------------------------------------ Bandeja

    fun aceptar(d: MovimientoDetectado, categoriaId: Long?) = viewModelScope.launch {
        runCatching { c.repo.aceptarDetectado(d.id, categoriaId) }.onFailure { avisar(it.message ?: "No se pudo aceptar") }
    }

    fun aceptarEditado(detectadoId: Long, m: Movimiento, categoriaSugerida: Long?) = viewModelScope.launch {
        runCatching { c.repo.aceptarDetectadoEditado(detectadoId, m, categoriaSugerida) }
            .onSuccess { avisar("Movimiento agregado") }
            .onFailure { avisar(it.message ?: "No se pudo guardar") }
    }

    fun esElMismo(d: MovimientoDetectado) = viewModelScope.launch {
        c.repo.vincularDetectadoConExistente(d.id)
        avisar("Listo: quedó vinculado con el que habías cargado")
    }

    fun descartar(d: MovimientoDetectado) = viewModelScope.launch {
        c.repo.descartarDetectado(d.id)
        avisar("Descartado", "Deshacer") { viewModelScope.launch { c.repo.restaurarDetectado(d.id) } }
    }

    fun aceptarTodos() = viewModelScope.launch {
        val n = runCatching { c.repo.aceptarTodosLosDetectados() }.getOrElse { avisar(it.message ?: "No se pudo aceptar"); return@launch }
        avisar(if (n == 0) "Los que quedan necesitan que decidas uno por uno" else plural(n, "movimiento agregado", "movimientos agregados"))
    }
}

package com.gabriel.cadapeso.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gabriel.cadapeso.Contenedor
import com.gabriel.cadapeso.datos.Activo
import com.gabriel.cadapeso.datos.Categoria
import com.gabriel.cadapeso.datos.CompraEnEspera
import com.gabriel.cadapeso.datos.ConfigIA
import com.gabriel.cadapeso.datos.Deuda
import com.gabriel.cadapeso.datos.EventoPlanificado
import com.gabriel.cadapeso.datos.Meta
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.datos.Perfil
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.DatosFinancieros
import com.gabriel.cadapeso.dominio.EstadoCompra
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.MotorFinanciero
import com.gabriel.cadapeso.dominio.MotorPresupuesto
import com.gabriel.cadapeso.dominio.clave
import com.gabriel.cadapeso.notificaciones.RecordatorioCompraWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/** Mensaje corto para mostrar abajo de la pantalla (Snackbar), con acción opcional ("Deshacer"). */
data class Aviso(val texto: String, val accion: String? = null, val alAccionar: (() -> Unit)? = null)

/**
 * ViewModel principal: expone los datos y el análisis financiero (que se recalcula solo cada
 * vez que algo cambia en la base) y todas las acciones que modifican datos.
 * Vive mientras la Activity exista, así que sobrevive a rotaciones de pantalla.
 */
class AppViewModel(private val c: Contenedor) : ViewModel() {

    private data class Reloj(val hoy: LocalDate, val millis: Long)

    private val reloj = MutableStateFlow(Reloj(LocalDate.now(), System.currentTimeMillis()))

    val datos: StateFlow<DatosFinancieros?> = c.repo.datos.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** El análisis se calcula en un hilo de fondo (Dispatchers.Default) para no trabar la interfaz. */
    val analisis: StateFlow<Analisis?> = combine(c.repo.datos, reloj) { d, r -> MotorFinanciero.analizar(d, r.hoy, r.millis) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val perfil: StateFlow<Perfil?> = c.repo.perfil.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val configIA: StateFlow<ConfigIA> = c.repo.configIA.stateIn(viewModelScope, SharingStarted.Eagerly, ConfigIA())

    private val _avisos = MutableSharedFlow<Aviso>(extraBufferCapacity = 8)
    val avisos: SharedFlow<Aviso> = _avisos.asSharedFlow()

    /** Se llama al volver a la app: si cambió el día, se recalcula todo (semana, vencimientos...). */
    fun actualizarReloj() {
        reloj.value = Reloj(LocalDate.now(), System.currentTimeMillis())
    }

    fun avisar(texto: String) {
        _avisos.tryEmit(Aviso(texto))
    }

    /** Ejecuta una operación en segundo plano y avisa si salió bien o el error. */
    private fun lanzar(mensajeOk: String? = null, bloque: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                bloque()
                mensajeOk?.let { _avisos.emit(Aviso(it)) }
            } catch (e: Exception) {
                _avisos.emit(Aviso(e.message ?: "Ocurrió un error"))
            }
        }
    }

    // ------------------------------------------------------------------ Movimientos

    fun guardarMovimiento(m: Movimiento, categoriaSugerida: Long?) =
        lanzar(if (m.id == 0L) "Movimiento guardado" else "Cambios guardados") { c.repo.guardarMovimiento(m, categoriaSugerida) }

    fun borrarMovimiento(m: Movimiento) {
        viewModelScope.launch {
            c.repo.borrarMovimiento(m)
            _avisos.emit(Aviso("Movimiento borrado", "Deshacer") { viewModelScope.launch { c.repo.restaurarMovimiento(m) } })
        }
    }

    // ------------------------------------------------------------------ Categorías

    fun guardarCategoria(cat: Categoria) = lanzar("Categoría guardada") { c.repo.guardarCategoria(cat) }
    fun borrarCategoria(cat: Categoria, destino: Categoria) = lanzar("Categoría borrada; sus movimientos pasaron a ${destino.nombre}") {
        c.repo.borrarCategoria(cat, destino)
    }

    // ------------------------------------------------------------------ Presupuesto

    fun guardarAsignacion(mes: YearMonth, categoriaId: Long, monto: Double) = lanzar { c.repo.guardarAsignacion(mes.clave(), categoriaId, monto) }

    fun guardarIngresoPrevisto(mes: YearMonth, monto: Double) = lanzar("Ingreso del mes actualizado") { c.repo.guardarPlan(mes.clave(), monto) }

    /** Reparte todo el ingreso del mes según la regla elegida ("cada peso con una tarea"). */
    fun sugerirPresupuesto(mes: YearMonth) = lanzar("Presupuesto armado: cada peso tiene una tarea") {
        val d = c.repo.cargarDatos()
        val ingreso = MotorPresupuesto.ingresoReferencia(d, mes, MotorPresupuesto.resumenMes(d, mes).ingresos)
        require(ingreso > 0) { "Primero cargá tu ingreso mensual (en Ajustes o en este mismo mes)" }
        c.repo.reemplazarAsignaciones(mes.clave(), MotorPresupuesto.sugerirAsignaciones(d, mes, ingreso))
    }

    fun copiarMesAnterior(mes: YearMonth) = lanzar("Presupuesto copiado del mes anterior") {
        val d = c.repo.cargarDatos()
        val anteriores = d.asignacionesDe(mes.minusMonths(1))
        require(anteriores.isNotEmpty()) { "El mes anterior no tiene presupuesto" }
        c.repo.reemplazarAsignaciones(mes.clave(), anteriores.map { it.copy(mes = mes.clave()) })
    }

    // ------------------------------------------------------------------ Patrimonio, deudas, metas, eventos

    fun guardarActivo(a: Activo) = lanzar("Activo guardado") { c.repo.guardarActivo(a) }
    fun borrarActivo(a: Activo) = lanzar("Activo borrado") { c.repo.borrarActivo(a) }
    fun guardarDeuda(d: Deuda) = lanzar("Deuda guardada") { c.repo.guardarDeuda(d) }
    fun borrarDeuda(d: Deuda) = lanzar("Deuda borrada") { c.repo.borrarDeuda(d) }
    fun pagarDeuda(d: Deuda, monto: Double, medio: MedioPago) = lanzar("Pago registrado") { c.repo.pagarDeuda(d, monto, LocalDate.now(), medio) }
    fun guardarMeta(m: Meta) = lanzar("Meta guardada") { c.repo.guardarMeta(m) }
    fun borrarMeta(m: Meta) = lanzar("Meta borrada") { c.repo.borrarMeta(m) }
    fun aportarAMeta(m: Meta, monto: Double) = lanzar("¡Aporte registrado!") { c.repo.aportarAMeta(m, monto, LocalDate.now()) }
    fun aportarAFondo(a: Activo, monto: Double) = lanzar("¡Tu airbag creció!") { c.repo.aportarAFondo(a, monto, LocalDate.now()) }
    fun registrarAhorro(monto: Double) = lanzar("¡Te pagaste primero! 💪") { c.repo.registrarAhorro(monto, LocalDate.now()) }
    fun guardarEvento(e: EventoPlanificado) = lanzar("Evento guardado") { c.repo.guardarEvento(e) }
    fun borrarEvento(e: EventoPlanificado) = lanzar("Evento borrado") { c.repo.borrarEvento(e) }

    // ------------------------------------------------------------------ Regla de las 24/48 h

    /** Pone una compra en pausa y programa el recordatorio para cuando termine la espera. */
    fun ponerCompraEnEspera(compra: CompraEnEspera) = lanzar("Listo: te aviso en ${compra.horasEspera} horas") {
        val id = c.repo.crearCompraEnEspera(compra)
        RecordatorioCompraWorker.programar(c.app, compra.copy(id = id))
    }

    /** "No lo compro": queda registrado como ahorro por impulso evitado. */
    fun descartarCompraNueva(compra: CompraEnEspera) = lanzar("¡Bien ahí! Ese dinero sigue siendo tuyo") {
        c.repo.crearCompraEnEspera(compra.copy(estado = EstadoCompra.DESCARTADA, resueltaMillis = System.currentTimeMillis()))
    }

    fun resolverCompra(compra: CompraEnEspera, comprar: Boolean, medio: MedioPago = MedioPago.DEBITO) =
        lanzar(if (comprar) "Compra registrada" else "¡Bien ahí! Evitaste un gasto impulsivo") {
            RecordatorioCompraWorker.cancelar(c.app, compra.id)
            c.repo.resolverCompra(compra, comprar, medio)
        }

    fun borrarCompra(compra: CompraEnEspera) = lanzar {
        RecordatorioCompraWorker.cancelar(c.app, compra.id)
        c.repo.borrarCompra(compra)
    }

    // ------------------------------------------------------------------ Perfil y configuración

    fun guardarPerfil(p: Perfil) = lanzar("Perfil guardado") { c.repo.guardarPerfil(p) }

    fun guardarResultadoTest(conducta: Int?, riesgo: Int?) = lanzar("Perfil actualizado") {
        val p = c.repo.obtenerPerfil()
        c.repo.guardarPerfil(p.copy(puntajeConducta = conducta ?: p.puntajeConducta, puntajeRiesgo = riesgo ?: p.puntajeRiesgo))
    }

    /** Fin de la bienvenida: guarda el perfil, el ingreso del mes y (opcional) arma el presupuesto. */
    fun completarBienvenida(p: Perfil, armarPresupuesto: Boolean) = lanzar("¡Bienvenido/a a CadaPeso!") {
        c.repo.guardarPerfil(p.copy(onboardingCompleto = true))
        val mes = YearMonth.now()
        if (p.ingresoMensual > 0) {
            c.repo.guardarPlan(mes.clave(), p.ingresoMensual)
            if (armarPresupuesto) {
                val d = c.repo.cargarDatos()
                c.repo.reemplazarAsignaciones(mes.clave(), MotorPresupuesto.sugerirAsignaciones(d, mes, p.ingresoMensual))
            }
        }
    }

    fun empezarConEjemplo() = lanzar("Cargué 3 meses de datos de ejemplo para que explores la app") {
        val p = c.repo.obtenerPerfil()
        c.repo.guardarPerfil(p.copy(onboardingCompleto = true, edad = 29, ingresoMensual = 1_250_000.0))
        c.repo.cargarDatosEjemplo(LocalDate.now())
    }

    fun guardarConfigIA(cfg: ConfigIA) = lanzar { c.repo.guardarConfigIA(cfg) }

    // ------------------------------------------------------------------ Datos

    fun cargarDatosEjemplo() = lanzar("Datos de ejemplo cargados") { c.repo.cargarDatosEjemplo(LocalDate.now()) }

    fun borrarDatos() = lanzar("Se borraron los datos financieros") {
        c.repo.borrarDatosFinancieros()
    }

    fun exportarJson(uri: Uri) = lanzar("Respaldo guardado") { escribir(uri, c.repo.exportarJson()) }
    fun exportarCsv(uri: Uri) = lanzar("CSV exportado (se abre con Excel)") { escribir(uri, c.repo.exportarCsv()) }

    fun importarJson(uri: Uri) = lanzar("Respaldo restaurado") {
        val texto = withContext(Dispatchers.IO) {
            c.app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } ?: throw IllegalStateException("No se pudo leer el archivo")
        c.repo.importarJson(texto)
    }

    private suspend fun escribir(uri: Uri, texto: String) = withContext(Dispatchers.IO) {
        c.app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(texto.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("No se pudo escribir el archivo")
    }
}

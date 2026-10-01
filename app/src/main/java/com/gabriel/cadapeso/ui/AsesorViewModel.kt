package com.gabriel.cadapeso.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gabriel.cadapeso.Contenedor
import com.gabriel.cadapeso.datos.ConfigIA
import com.gabriel.cadapeso.datos.MensajeChat
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.MovimientoInterpretado
import com.gabriel.cadapeso.dominio.ProveedorIA
import com.gabriel.cadapeso.ia.AccionRapida
import com.gabriel.cadapeso.ia.AsesorIA
import com.gabriel.cadapeso.ia.EstadoMotor
import com.gabriel.cadapeso.ia.FormatoGemma
import com.gabriel.cadapeso.ia.GemmaOllama
import com.gabriel.cadapeso.ia.GestorModelos
import com.gabriel.cadapeso.ia.Prompts
import com.gabriel.cadapeso.ia.TurnoChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Progreso de una tarea larga (importar o descargar el modelo). */
data class ProgresoTarea(val titulo: String, val progreso: Float?, val detalle: String)

/**
 * ViewModel del asesor: conversación con Gemma (en streaming), atajos, evaluación de compras
 * impulsivas y manejo del archivo del modelo.
 */
class AsesorViewModel(private val c: Contenedor) : ViewModel() {

    val chat: StateFlow<List<MensajeChat>> = c.repo.chat.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val estadoMotor: StateFlow<EstadoMotor> = c.asesor.estado

    private val _enCurso = MutableStateFlow<String?>(null)
    /** Texto de la respuesta que Gemma está escribiendo en este momento (null si no hay ninguna). */
    val enCurso: StateFlow<String?> = _enCurso.asStateFlow()

    private val _generando = MutableStateFlow(false)
    val generando: StateFlow<Boolean> = _generando.asStateFlow()

    private var trabajo: Job? = null

    // ------------------------------------------------------------------ Chat

    fun enviar(pregunta: String, a: Analisis, config: ConfigIA) {
        val texto = pregunta.trim()
        if (texto.isEmpty() || _generando.value) return
        val historial = ultimosTurnos()
        trabajo = viewModelScope.launch {
            c.repo.agregarMensaje(MensajeChat(esUsuario = true, texto = texto, fechaMillis = System.currentTimeMillis()))
            if (!c.asesor.estaConfigurado(config)) {
                guardarRespuesta(AsesorIA.SIN_IA_CHAT)
                return@launch
            }
            ejecutar(c.asesor.generar(config, a, historial) { nivel -> Prompts.chat(a, texto, nivel) }, respaldo = null)
        }
    }

    fun accion(accion: AccionRapida, a: Analisis, config: ConfigIA) {
        if (_generando.value) return
        trabajo = viewModelScope.launch {
            c.repo.agregarMensaje(MensajeChat(esUsuario = true, texto = "${accion.emoji} ${accion.pregunta}", fechaMillis = System.currentTimeMillis()))
            val reglas = c.asesor.respuestaPorReglas(accion, a)
            if (!c.asesor.estaConfigurado(config)) {
                guardarRespuesta(reglas)
                return@launch
            }
            val flujo = c.asesor.generar(config, a) { nivel ->
                when (accion) {
                    AccionRapida.DIAGNOSTICO -> Prompts.diagnostico(a, nivel)
                    AccionRapida.SIGUIENTE_PASO -> Prompts.siguientePaso(a, nivel)
                    AccionRapida.RECORTE -> Prompts.planRecorte(a, nivel)
                    AccionRapida.DEUDAS -> Prompts.deudas(a, nivel)
                    AccionRapida.INVERTIR -> Prompts.inversion(a, nivel)
                }
            }
            ejecutar(flujo, respaldo = reglas)
        }
    }

    /** Corre la generación mostrando el texto a medida que llega; al final lo guarda en el historial. */
    private suspend fun ejecutar(flujo: Flow<String>, respaldo: String?) {
        _generando.value = true
        _enCurso.value = ""
        val texto = StringBuilder()
        try {
            flujo.collect { pedazo ->
                texto.append(pedazo)
                _enCurso.value = FormatoGemma.limpiar(texto.toString())
            }
            guardarRespuesta(FormatoGemma.limpiar(texto.toString()).trim().ifBlank { respaldo ?: "No obtuve respuesta. Probá de nuevo." })
        } catch (e: CancellationException) {
            val parcial = FormatoGemma.limpiar(texto.toString()).trim()
            if (parcial.isNotEmpty()) withContext(NonCancellable) { guardarRespuesta("$parcial …") }
            throw e
        } catch (e: Exception) {
            val detalle = e.message ?: e.javaClass.simpleName
            guardarRespuesta("⚠️ La IA no pudo responder: $detalle" + (respaldo?.let { "\n\nMientras tanto, el motor de reglas dice:\n\n$it" } ?: ""))
        } finally {
            _enCurso.value = null
            _generando.value = false
        }
    }

    private suspend fun guardarRespuesta(texto: String) {
        c.repo.agregarMensaje(MensajeChat(esUsuario = false, texto = texto, fechaMillis = System.currentTimeMillis()))
    }

    /** Últimos 2 intercambios (pregunta + respuesta) para darle contexto a la conversación. */
    private fun ultimosTurnos(): List<TurnoChat> {
        val mensajes = chat.value
        val turnos = mutableListOf<TurnoChat>()
        var i = 0
        while (i < mensajes.size - 1) {
            if (mensajes[i].esUsuario && !mensajes[i + 1].esUsuario) {
                turnos += TurnoChat(mensajes[i].texto.take(300), mensajes[i + 1].texto.take(500))
                i += 2
            } else i++
        }
        return turnos.takeLast(2)
    }

    fun detener() {
        trabajo?.cancel()
    }

    fun borrarChat() {
        viewModelScope.launch { c.repo.borrarChat() }
    }

    /** Carga el modelo en segundo plano al entrar al asesor, así la primera respuesta es más rápida. */
    fun precargar(config: ConfigIA) {
        if (!c.asesor.estaConfigurado(config)) return
        viewModelScope.launch { c.asesor.preparar(config) }
    }

    // ------------------------------------------------------------------ Compras impulsivas

    private val _opinionCompra = MutableStateFlow<String?>(null)
    val opinionCompra: StateFlow<String?> = _opinionCompra.asStateFlow()
    private var trabajoCompra: Job? = null

    fun opinarCompra(config: ConfigIA, a: Analisis, contenido: (Prompts.Nivel) -> String) {
        trabajoCompra?.cancel()
        trabajoCompra = viewModelScope.launch {
            _opinionCompra.value = ""
            val texto = StringBuilder()
            try {
                c.asesor.generar(config, a, emptyList(), contenido).collect {
                    texto.append(it)
                    _opinionCompra.value = FormatoGemma.limpiar(texto.toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _opinionCompra.value = "⚠️ ${e.message ?: "La IA no pudo responder"}"
            }
        }
    }

    fun limpiarOpinionCompra() {
        trabajoCompra?.cancel()
        _opinionCompra.value = null
    }

    // ------------------------------------------------------------------ Movimientos en lenguaje natural

    suspend fun interpretarConIA(texto: String, a: Analisis, config: ConfigIA): MovimientoInterpretado? =
        c.asesor.interpretarMovimiento(config, texto, a)

    fun iaConfigurada(config: ConfigIA): Boolean = c.asesor.estaConfigurado(config)

    // ------------------------------------------------------------------ Modelo Gemma: importar / descargar / probar

    private val _tarea = MutableStateFlow<ProgresoTarea?>(null)
    val tarea: StateFlow<ProgresoTarea?> = _tarea.asStateFlow()

    private val _resultadoPrueba = MutableStateFlow<String?>(null)
    val resultadoPrueba: StateFlow<String?> = _resultadoPrueba.asStateFlow()

    private val _modelosOllama = MutableStateFlow<List<String>>(emptyList())
    val modelosOllama: StateFlow<List<String>> = _modelosOllama.asStateFlow()

    val gestor: GestorModelos get() = c.modelos

    private val prefs = c.app.getSharedPreferences("modelo", Context.MODE_PRIVATE)

    init {
        // Si había una descarga en curso (por ejemplo, se cerró la app), retomamos el seguimiento.
        val id = prefs.getLong("descarga", -1)
        if (id >= 0) seguirDescarga(id)
    }

    fun importarModelo(uri: Uri) {
        if (_tarea.value != null) return
        viewModelScope.launch {
            _tarea.value = ProgresoTarea("Importando modelo", null, "Copiando el archivo…")
            try {
                val archivo = c.modelos.importar(uri) { copiados, total ->
                    _tarea.value = ProgresoTarea(
                        "Importando modelo",
                        if (total > 0) copiados.toFloat() / total else null,
                        "${copiados / 1_000_000} MB" + if (total > 0) " de ${total / 1_000_000} MB" else "",
                    )
                }
                usarModelo(archivo)
                _resultadoPrueba.value = "✅ Modelo importado: ${archivo.name}. Ya podés usar el asesor sin internet."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _resultadoPrueba.value = "⚠️ ${e.message}"
            } finally {
                _tarea.value = null
            }
        }
    }

    fun descargarModelo(token: String) {
        if (_tarea.value != null) return
        viewModelScope.launch {
            _tarea.value = ProgresoTarea("Descargando Gemma 2", null, "Conectando con Hugging Face…")
            try {
                val id = c.modelos.iniciarDescarga(token)
                prefs.edit { putLong("descarga", id) }
                seguirDescarga(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _tarea.value = null
                _resultadoPrueba.value = "⚠️ ${e.message}"
            }
        }
    }

    private fun seguirDescarga(id: Long) {
        viewModelScope.launch {
            while (true) {
                val estado = c.modelos.estadoDescarga(id)
                if (estado == null) {
                    _tarea.value = null
                    prefs.edit { remove("descarga") }
                    return@launch
                }
                if (estado.terminada) {
                    _tarea.value = null
                    prefs.edit { remove("descarga") }
                    if (estado.error != null) {
                        _resultadoPrueba.value = "⚠️ ${estado.error}"
                    } else {
                        usarModelo(c.modelos.archivoDescargado())
                        _resultadoPrueba.value = "✅ Gemma 2 descargado. Ya podés usar el asesor sin internet."
                    }
                    return@launch
                }
                _tarea.value = ProgresoTarea(
                    "Descargando Gemma 2",
                    if (estado.total > 0) estado.progreso else null,
                    "${estado.bytes / 1_000_000} MB" + if (estado.total > 0) " de ${estado.total / 1_000_000} MB" else "",
                )
                delay(1000)
            }
        }
    }

    fun cancelarDescarga() {
        val id = prefs.getLong("descarga", -1)
        if (id >= 0) c.modelos.cancelarDescarga(id)
        prefs.edit { remove("descarga") }
        _tarea.value = null
    }

    /** Elige un archivo de modelo y activa Gemma local. */
    fun usarModelo(archivo: File) {
        viewModelScope.launch {
            val cfg = c.repo.obtenerConfigIA()
            c.repo.guardarConfigIA(cfg.copy(proveedor = ProveedorIA.LOCAL, rutaModelo = archivo.absolutePath))
        }
    }

    fun borrarModelo(archivo: File) {
        viewModelScope.launch {
            val cfg = c.repo.obtenerConfigIA()
            if (cfg.rutaModelo == archivo.absolutePath) {
                c.asesor.liberar()
                c.repo.guardarConfigIA(cfg.copy(rutaModelo = ""))
            }
            c.modelos.borrar(archivo)
            _resultadoPrueba.value = "Modelo borrado"
        }
    }

    /** Carga el modelo y le pide un saludo corto: confirma que todo funciona y mide el tiempo. */
    fun probarMotor(config: ConfigIA, a: Analisis?) {
        if (_tarea.value != null) return
        viewModelScope.launch {
            _resultadoPrueba.value = "⏳ Cargando Gemma 2… (la primera vez puede tardar)"
            val inicio = System.currentTimeMillis()
            if (!c.asesor.preparar(config)) {
                _resultadoPrueba.value = "⚠️ " + ((c.asesor.estado.value as? EstadoMotor.Error)?.mensaje ?: "No se pudo preparar la IA")
                return@launch
            }
            val carga = (System.currentTimeMillis() - inicio) / 1000.0
            if (a == null) {
                _resultadoPrueba.value = "✅ Listo (cargó en ${"%.1f".format(carga)} s)"
                return@launch
            }
            val texto = StringBuilder()
            try {
                c.asesor.generar(config, a) { "TAREA: Saludame en una sola oración corta y decime una idea para ahorrar este mes." }
                    .collect {
                        texto.append(it)
                        _resultadoPrueba.value = "✅ Cargó en ${"%.1f".format(carga)} s.\n\n" + FormatoGemma.limpiar(texto.toString())
                    }
                val total = (System.currentTimeMillis() - inicio) / 1000.0
                _resultadoPrueba.value = "✅ Funciona (carga ${"%.1f".format(carga)} s, total ${"%.1f".format(total)} s)\n\n" +
                    FormatoGemma.limpiar(texto.toString()).trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _resultadoPrueba.value = "⚠️ ${e.message}"
            }
        }
    }

    fun probarOllama(url: String) {
        viewModelScope.launch {
            _resultadoPrueba.value = "⏳ Conectando con Ollama…"
            try {
                val modelos = GemmaOllama.listarModelos(url)
                _modelosOllama.value = modelos
                val gemmas = modelos.filter { it.startsWith("gemma2") }
                _resultadoPrueba.value = when {
                    modelos.isEmpty() -> "Ollama responde pero no tiene modelos. En la PC: ollama pull gemma2:2b"
                    gemmas.isEmpty() -> "Ollama responde. No encontré Gemma 2: en la PC ejecutá  ollama pull gemma2:2b"
                    else -> "✅ Conectado. Modelos Gemma 2: ${gemmas.joinToString()}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _resultadoPrueba.value = "⚠️ ${e.message}"
            }
        }
    }

    fun limpiarResultado() {
        _resultadoPrueba.value = null
    }
}

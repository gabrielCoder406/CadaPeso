package com.gabriel.cadapeso.ia

import android.content.Context
import com.gabriel.cadapeso.datos.ConfigIA
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.MotorAhorro
import com.gabriel.cadapeso.dominio.MovimientoInterpretado
import com.gabriel.cadapeso.dominio.ParserMovimiento
import com.gabriel.cadapeso.dominio.ProveedorIA
import com.gabriel.cadapeso.dominio.Severidad
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.normalizar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.math.max

/** Atajos del asesor: cada uno arma un prompt específico con los datos calculados. */
enum class AccionRapida(val etiqueta: String, val emoji: String, val pregunta: String) {
    DIAGNOSTICO("Diagnóstico del mes", "📊", "Hacé un diagnóstico de mi mes."),
    SIGUIENTE_PASO("¿Qué paso sigo?", "🗺️", "¿Cuál es mi próximo paso para crear riqueza?"),
    RECORTE("Plan de recorte", "✂️", "¿Qué gastos recorto primero?"),
    DEUDAS("Salir de deudas", "💳", "¿Cómo salgo de mis deudas?"),
    INVERTIR("Empezar a invertir", "📈", "¿Cómo empiezo a invertir según mi perfil?"),
}

/**
 * Orquestador del asesor financiero con IA. Elige el motor (Gemma 2 local u Ollama) según la
 * configuración, arma los prompts para que entren en el contexto del modelo y, si no hay IA
 * configurada, responde con el motor de reglas (la app nunca queda "muda").
 */
class AsesorIA(private val context: Context) {

    private val _estado = MutableStateFlow<EstadoMotor>(EstadoMotor.SinConfigurar)
    val estado: StateFlow<EstadoMotor> = _estado.asStateFlow()

    private var motor: MotorIA? = null
    private var claveMotor: String? = null
    private val mutex = Mutex()

    fun estaConfigurado(c: ConfigIA): Boolean = when (c.proveedor) {
        ProveedorIA.NINGUNO -> false
        ProveedorIA.LOCAL -> c.rutaModelo.isNotBlank()
        ProveedorIA.OLLAMA -> c.ollamaUrl.isNotBlank()
    }

    /**
     * Solo se recrea el motor si cambió algo que obliga a recargar el modelo (archivo, GPU,
     * contexto, servidor). Temperatura o plantilla se actualizan en caliente.
     */
    private fun clave(c: ConfigIA): String = when (c.proveedor) {
        ProveedorIA.LOCAL -> "local|${c.rutaModelo}|${c.usarGpu}|${c.maxTokens}"
        ProveedorIA.OLLAMA -> "ollama|${c.ollamaUrl}|${c.ollamaModelo}"
        ProveedorIA.NINGUNO -> "ninguno"
    }

    private suspend fun obtenerMotor(c: ConfigIA): MotorIA? = mutex.withLock {
        if (!estaConfigurado(c)) {
            motor?.liberar()
            motor = null
            claveMotor = null
            _estado.value = EstadoMotor.SinConfigurar
            return null
        }
        val nueva = clave(c)
        if (motor == null || claveMotor != nueva) {
            motor?.liberar()
            motor = when (c.proveedor) {
                ProveedorIA.LOCAL -> GemmaLocal(context, c)
                else -> GemmaOllama(c)
            }
            claveMotor = nueva
            _estado.value = EstadoMotor.Inactivo
        } else {
            when (val m = motor) {
                is GemmaLocal -> m.config = c
                is GemmaOllama -> m.config = c
            }
        }
        motor
    }

    /** Carga el modelo (o prueba la conexión). Devuelve true si quedó listo. */
    suspend fun preparar(c: ConfigIA): Boolean {
        val m = obtenerMotor(c) ?: return false
        if (_estado.value == EstadoMotor.Listo) return true
        _estado.value = EstadoMotor.Cargando
        return try {
            m.preparar()
            _estado.value = EstadoMotor.Listo
            true
        } catch (e: Throwable) {
            _estado.value = EstadoMotor.Error(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    fun liberar() {
        motor?.liberar()
        motor = null
        claveMotor = null
        if (_estado.value !is EstadoMotor.SinConfigurar) _estado.value = EstadoMotor.Inactivo
    }

    /**
     * Genera una respuesta en streaming. [contenido] arma el texto con los datos en dos niveles
     * de detalle: si el completo no entra en el contexto del modelo, se usa el breve.
     */
    fun generar(
        c: ConfigIA,
        a: Analisis,
        historial: List<TurnoChat> = emptyList(),
        contenido: (Prompts.Nivel) -> String,
    ): Flow<String> = flow {
        val m = obtenerMotor(c) ?: throw IllegalStateException("La IA no está configurada")
        if (!preparar(c)) throw IllegalStateException((estado.value as? EstadoMotor.Error)?.mensaje ?: "No se pudo preparar la IA")
        emitAll(m.generar(construir(m, a, historial, contenido)))
    }

    private fun construir(m: MotorIA, a: Analisis, historial: List<TurnoChat>, contenido: (Prompts.Nivel) -> String): PromptIA {
        val instrucciones = Prompts.instrucciones(a)
        val respuesta = if (m is GemmaLocal) 320 else 450
        for (nivel in Prompts.Nivel.entries) {
            val p = ajustarAlContexto(PromptIA(instrucciones, contenido(nivel), historial, respuesta), m)
            if (m.contarTokens(FormatoGemma.armar(p)) <= m.maxTokens - respuesta) return p
        }
        // Último recurso: versión breve, sin historial, y la respuesta usa lo que quede.
        val p = PromptIA(instrucciones, contenido(Prompts.Nivel.BREVE), emptyList(), respuesta)
        val usados = m.contarTokens(FormatoGemma.armar(p))
        return p.copy(maxRespuesta = max(120, m.maxTokens - usados - 8))
    }

    /**
     * Usa Gemma para entender un texto que el parser por reglas no pudo interpretar bien.
     * Devuelve null si no hay IA o si la respuesta no es un JSON válido.
     */
    suspend fun interpretarMovimiento(c: ConfigIA, texto: String, a: Analisis): MovimientoInterpretado? {
        val m = obtenerMotor(c) ?: return null
        if (!preparar(c)) return null
        val prompt = Prompts.extraerMovimiento(
            texto,
            Prompts.categoriasPara(a, TipoMovimiento.GASTO),
            Prompts.categoriasPara(a, TipoMovimiento.INGRESO),
        )
        val salida = runCatching { m.generar(prompt).toList().joinToString("") }.getOrNull() ?: return null
        return leerJsonMovimiento(salida, texto, a)
    }

    private fun leerJsonMovimiento(salida: String, texto: String, a: Analisis): MovimientoInterpretado? {
        val inicio = salida.indexOf('{')
        val fin = salida.lastIndexOf('}')
        if (inicio < 0 || fin <= inicio) return null
        val json = runCatching { JSONObject(salida.substring(inicio, fin + 1)) }.getOrNull() ?: return null
        // Lo que Gemma no entienda lo completa el parser por reglas.
        val base = ParserMovimiento.interpretar(texto, a.datos.categorias, a.hoy)
        val tipo = if (normalizar(json.optString("tipo")).startsWith("ingreso")) TipoMovimiento.INGRESO else TipoMovimiento.GASTO
        val monto = json.optDouble("monto").takeIf { !it.isNaN() && it > 0 }
            ?: ParserMovimiento.aNumero(json.optString("monto").replace("$", "").trim())
            ?: base.monto
        val nombreCategoria = normalizar(json.optString("categoria"))
        val categorias = a.datos.categorias.filter { it.tipo == tipo && !it.archivada }
        val categoria = categorias.firstOrNull { normalizar(it.nombre) == nombreCategoria }
            ?: categorias.firstOrNull { nombreCategoria.isNotBlank() && (normalizar(it.nombre).contains(nombreCategoria) || nombreCategoria.contains(normalizar(it.nombre))) }
            ?: base.categoria?.takeIf { it.tipo == tipo }
        val medio = when (normalizar(json.optString("medio"))) {
            "efectivo" -> MedioPago.EFECTIVO
            "debito" -> MedioPago.DEBITO
            "credito", "tarjeta" -> MedioPago.CREDITO
            "transferencia" -> MedioPago.TRANSFERENCIA
            "billetera", "billetera virtual" -> MedioPago.BILLETERA
            else -> base.medioPago
        }
        val diasAtras = json.optInt("dias_atras", 0).coerceIn(0, 365)
        val descripcion = json.optString("descripcion").trim().take(40).ifBlank { base.descripcion }
        return MovimientoInterpretado(
            tipo = tipo,
            monto = monto,
            descripcion = descripcion.replaceFirstChar { it.uppercase() },
            categoria = categoria,
            medioPago = medio,
            fecha = if (diasAtras > 0) a.hoy.minusDays(diasAtras.toLong()) else base.fecha,
            confianza = if (monto != null && categoria != null) 0.9 else 0.6,
        )
    }

    // ------------------------------------------------------------------ Sin IA: respuestas por reglas

    /** Respuesta del motor de reglas para cada atajo (se usa si no hay IA o si la IA falla). */
    fun respuestaPorReglas(accion: AccionRapida, a: Analisis): String {
        val m = a.moneda
        return when (accion) {
            AccionRapida.DIAGNOSTICO -> buildString {
                val buenas = a.consejos.filter { it.severidad == Severidad.POSITIVA }
                val malas = a.consejos.filter { it.severidad == Severidad.ALTA || it.severidad == Severidad.MEDIA }
                append("📊 Diagnóstico de ${Formato.mes(a.mes.mes).lowercase()}\n\n")
                append("Ingresos ${Formato.dinero(a.indicadores.ingresoReferencia, m)} · gastos ${Formato.dinero(a.mes.gastos, m)} · ")
                append("ahorro ${Formato.porcentaje(a.indicadores.tasaAhorro)}.\n\n")
                if (buenas.isNotEmpty()) {
                    append("✅ Lo que va bien:\n")
                    buenas.take(2).forEach { append("• ${it.titulo}. ${it.mensaje}\n") }
                    append('\n')
                }
                if (malas.isNotEmpty()) {
                    append("⚠️ Lo que preocupa:\n")
                    malas.take(4).forEach { append("• ${it.titulo}. ${it.mensaje}\n") }
                } else {
                    append("No hay alertas importantes este mes. 👏\n")
                }
            }
            AccionRapida.SIGUIENTE_PASO -> {
                val paso = a.hojaDeRuta.pasos.firstOrNull { !it.cumplido }
                if (paso == null) "🗺️ ¡Cumpliste los 4 pasos de la hoja de ruta! Mantené los aportes periódicos y revisá tu cartera una vez al año."
                else "🗺️ Tu próximo paso es el ${paso.numero}: ${paso.titulo}.\n\n${paso.descripcion}\n\nHoy: ${paso.detalle}."
            }
            AccionRapida.RECORTE -> a.planAjuste?.let { plan ->
                "✂️ Necesidades + cuotas se llevan el ${Formato.porcentaje(plan.porcentajeActual)} de tu ingreso (promedio de 3 meses). Plan de 3 meses: " +
                    plan.metasMensuales.joinToString(" → ") { "${Formato.mesCorto(it.first)} ${Formato.porcentaje(it.second)}" } +
                    ".\n\n" + plan.sugerencias.joinToString("\n") { "• $it" }
            } ?: buildString {
                append("✂️ Necesidades + cuotas: ${Formato.porcentaje(a.indicadores.porcentajeNecesidades)} del ingreso (no hace falta un plan de ajuste).\n\n")
                a.fugas.take(2).forEach { append("• ${it.categoria.emoji} ${it.categoria.nombre}: ${it.cantidadMes} gastos chicos = ${Formato.dinero(it.totalMes, m)}/mes.\n") }
                if (a.suscripciones.isNotEmpty()) append("• Suscripciones: ${Formato.dinero(a.suscripciones.sumOf { it.montoMensual }, m)}/mes.\n")
                val excedidas = a.presupuesto.excedidas
                excedidas.take(2).forEach { append("• ${it.categoria.nombre}: te pasaste ${Formato.dinero(-it.restante, m)}.\n") }
                if (a.fugas.isEmpty() && a.suscripciones.isEmpty() && excedidas.isEmpty()) append("No encontré fugas claras. ¡Bien!")
            }
            AccionRapida.DEUDAS -> {
                val plan = a.deudas
                if (plan.deudas.isEmpty()) "💳 No tenés deudas cargadas. Si usás tarjeta, pagá siempre el total del resumen."
                else buildString {
                    append("💳 Método avalancha: pagá el mínimo en todas y todo lo extra a la de tasa más alta.\n\n")
                    plan.avalancha.orden.forEachIndexed { i, paso ->
                        append("${i + 1}. ${paso.deuda.nombre} (${Formato.porcentaje(paso.deuda.tasaAnual, 1)}): ")
                        append(if (paso.mesLiquidacion < 600) "cancelada en el mes ${paso.mesLiquidacion}\n" else "no se cancela con la cuota actual\n")
                    }
                    append("\nIntereses totales: ${Formato.dinero(plan.avalancha.interesTotal, m)}.")
                    if (plan.ahorroAvalancha > 0) append(" Ahorrás ${Formato.dinero(plan.ahorroAvalancha, m)} frente a bola de nieve.")
                    plan.deudas.filter { it.trampaPagoMinimo }.forEach { append("\n⚠️ ${it.deuda.nombre}: la cuota no cubre los intereses. Evitá el pago mínimo.") }
                }
            }
            AccionRapida.INVERTIR -> {
                val r = a.perfil.riesgoEfectivo
                val aporte = maxOf(a.indicadores.capacidadAhorroMensual * 0.5, a.indicadores.ingresoReferencia * 0.05)
                val previo = a.hojaDeRuta.pasos.take(3).firstOrNull { !it.cumplido }
                buildString {
                    if (previo != null) append("📌 Antes de invertir, completá el paso ${previo.numero}: ${previo.titulo}.\n\n")
                    append("📈 Perfil ${r.etiqueta.lowercase()}: ${r.descripcion}\n\n")
                    append("Aportando ${Formato.dinero(aporte, m)} por mes durante 10 años (rendimiento real ~${Formato.porcentaje(r.rendimientoRealAnual)}) ")
                    append("llegarías a ${Formato.dinero(MotorAhorro.valorFuturo(aporte, r.rendimientoRealAnual, 10), m)}.\n\n")
                    append("😴 Factor sueño: en un mal año podría caer ${Formato.porcentaje(r.caidaMalAnio)}. Si eso no te deja dormir, bajá el riesgo.\n\n")
                    append("Etapa ${a.perfil.etapa.etiqueta.lowercase()}: ${a.perfil.etapa.enfoque}")
                }
            }
        }
    }

    companion object {
        const val SIN_IA_CHAT = "Para conversar libremente necesito Gemma 2. Configurala en Ajustes › Inteligencia artificial " +
            "(en el teléfono o con Ollama en tu PC). Mientras tanto, usá los atajos de abajo: responden con el motor de reglas."
    }
}

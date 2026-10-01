package com.gabriel.cadapeso.ia

import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.ClaseDeuda
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.MotorAhorro
import com.gabriel.cadapeso.dominio.TipoMovimiento

/**
 * Textos que se le mandan a Gemma. Principio de diseño: las CUENTAS las hace el motor de
 * reglas y Gemma solo EXPLICA y aconseja con esos números. Un modelo de 2B parámetros es
 * bueno redactando pero puede inventar cifras si se le pide calcular.
 */
object Prompts {

    enum class Nivel { COMPLETO, BREVE }

    private fun d(valor: Double, a: Analisis) = Formato.dinero(valor, a.moneda)
    private fun p(valor: Double) = Formato.porcentaje(valor)

    fun instrucciones(a: Analisis): String = """
        Sos CadaPeso, un asesor de finanzas personales para Argentina. Respondé en español rioplatense (usá "vos"), claro y concreto, en menos de 150 palabras. Usá viñetas cortas cuando ayuden.
        Reglas:
        - Usá SOLO los números de MIS DATOS. No inventes cifras, tasas ni porcentajes. Si falta un dato, decilo.
        - Principios: págate a vos primero apenas cobrás; armá el fondo de emergencia antes de invertir; cancelá primero la deuda de tasa más alta (avalancha); invertí aportes fijos todos los meses (DCA), diversificados y según el perfil de riesgo; ante una compra no planificada, esperá 24 a 48 horas.
        - No recomiendes bancos, acciones ni productos puntuales. Sos una guía educativa, no un asesor matriculado.
        - Tono: ${a.perfil.conductaEfectiva.tono}.
    """.trimIndent()

    /** Resumen financiero compacto. BREVE se usa cuando el contexto del modelo no alcanza. */
    fun misDatos(a: Analisis, nivel: Nivel = Nivel.COMPLETO): String {
        val completo = nivel == Nivel.COMPLETO
        val l = mutableListOf<String>()
        val ind = a.indicadores
        l += "MIS DATOS (${Formato.mes(a.mes.mes).lowercase()}, día ${a.hoy.dayOfMonth}):"
        l += "- Ingreso del mes: ${d(ind.ingresoReferencia, a)}. Gastado: ${d(a.mes.gastos, a)} (consumo ${d(a.mes.consumo, a)}, ahorro ${d(a.mes.ahorro, a)} = ${p(ind.tasaAhorro)})."
        val partidas = a.presupuesto.partidas.joinToString(", ") { "${it.partida.nombre} ${p(it.porcentajeReal)} (meta ${p(it.partida.porcentaje)})" }
        l += "- Regla ${a.presupuesto.regla.etiqueta}: $partidas."
        val top = a.mes.porCategoria.entries.sortedByDescending { it.value }.take(if (completo) 4 else 2)
            .joinToString(", ") { (id, v) -> "${a.datos.categoria(id)?.nombre ?: "?"} ${d(v, a)}" }
        if (top.isNotEmpty()) l += "- Mayores gastos: $top."
        if (a.semana.presupuestoSemanal > 0) {
            l += "- Semana: gastaste ${d(a.semana.total, a)} en gastos variables; " +
                (if (a.semana.desvio > 0) "${p(a.semana.desvio)} por encima" else "${p(-a.semana.desvio)} por debajo") + " del ritmo previsto."
        }
        a.fugas.firstOrNull()?.let { l += "- Gasto hormiga: ${it.categoria.nombre}, ${it.cantidadMes} compras chicas = ${d(it.totalMes, a)}/mes." }
        if (completo && a.suscripciones.isNotEmpty()) {
            l += "- Suscripciones: " + a.suscripciones.take(4).joinToString(", ") { "${it.nombre} ${d(it.montoMensual, a)}" } + "."
        }
        if (a.deudas.deudas.isNotEmpty()) {
            l += "- Deudas: " + a.deudas.deudas.take(if (completo) 3 else 2).joinToString("; ") { x ->
                "${x.deuda.nombre} ${d(x.deuda.saldo, a)} al ${Formato.porcentaje(x.deuda.tasaAnual, 0)} anual (${x.clase.etiqueta.lowercase()}" +
                    (if (x.trampaPagoMinimo) ", la cuota no cubre los intereses" else "") + ")"
            } + ". Cuotas = ${p(ind.ratioEndeudamiento)} del ingreso."
        } else {
            l += "- Sin deudas."
        }
        val f = a.fondoEmergencia
        l += "- Fondo de emergencia: ${d(f.acumulado, a)} = ${String.format(Formato.locale, "%.1f", f.mesesCubiertos)} meses de gastos básicos (meta ${f.mesesMin} a ${f.mesesMax})."
        if (completo) {
            l += "- Patrimonio neto: ${d(a.patrimonio.neto, a)}. Capacidad de reacción: ${p(ind.capacidadReaccion)} del gasto es recortable. Te sobra por mes: ${d(ind.capacidadAhorroMensual, a)}."
            a.metas.take(2).forEach { m ->
                l += "- Meta ${m.meta.nombre}: ${p(m.progreso * 100)} (${d(m.acumulado, a)} de ${d(m.meta.montoObjetivo, a)}), necesita ${d(m.aporteMensualNecesario, a)}/mes" +
                    (if (m.enRiesgo) ", en riesgo." else ".")
            }
        }
        val perfil = a.perfil
        l += "- Perfil: ${a.datos.perfil.edad} años (${perfil.etapa.etiqueta.lowercase()}), conducta ${perfil.conductaEfectiva.etiqueta.lowercase()}, " +
            "riesgo ${perfil.riesgoEfectivo.etiqueta.lowercase()}. Paso actual de la hoja de ruta: ${pasoRuta(a)}."
        val alertas = a.consejos.filter { it.severidad.ordinal <= 1 }.take(if (completo) 3 else 2)
        if (alertas.isNotEmpty()) l += "- Alertas: " + alertas.joinToString("; ") { it.titulo } + "."
        return l.joinToString("\n")
    }

    private fun pasoRuta(a: Analisis): String {
        val paso = a.hojaDeRuta.pasos.firstOrNull { !it.cumplido } ?: return "todos cumplidos"
        return "${paso.numero} (${paso.titulo.lowercase()})"
    }

    // ------------------------------------------------------------------ Tareas

    fun chat(a: Analisis, pregunta: String, nivel: Nivel): String =
        misDatos(a, nivel) + "\n\nMI PREGUNTA: " + pregunta.trim()

    // Ojo: trimIndent() borra la primera línea si está vacía, por eso el "\n\n" va afuera.
    fun diagnostico(a: Analisis, nivel: Nivel): String = misDatos(a, nivel) + "\n\n" + """
        TAREA: Hacé un diagnóstico de mi mes en tres partes:
        1) Lo que va bien (1 o 2 puntos).
        2) Lo que me preocupa (2 o 3 puntos, con cifras de MIS DATOS).
        3) Tres acciones concretas para esta semana.
    """.trimIndent()

    fun siguientePaso(a: Analisis, nivel: Nivel): String {
        val pasos = a.hojaDeRuta.pasos.joinToString("\n") { "${it.numero}. ${it.titulo}: ${if (it.cumplido) "cumplido" else "pendiente"} (${it.detalle})" }
        return misDatos(a, nivel) + "\nHOJA DE RUTA:\n$pasos\n\nTAREA: Decime cuál es mi próximo paso y cómo lo hago esta semana, en 3 a 5 viñetas."
    }

    fun planRecorte(a: Analisis, nivel: Nivel): String {
        val plan = a.planAjuste
        val texto = if (plan != null) {
            "PLAN DE AJUSTE (calculado): necesidades + cuotas = ${p(plan.porcentajeActual)} del ingreso (promedio de 3 meses); objetivo ${p(plan.porcentajeObjetivo)} en 3 meses: " +
                plan.metasMensuales.joinToString(" → ") { "${Formato.mesCorto(it.first)} ${p(it.second)}" } +
                ". Recorte mensual necesario al final: ${d(plan.recorteMensualFinal, a)}.\nIdeas: " + plan.sugerencias.joinToString(" ")
        } else {
            "Mis gastos básicos están en ${p(a.indicadores.porcentajeNecesidades)} del ingreso (no superan el 80%)."
        }
        return misDatos(a, nivel) + "\n$texto\n\nTAREA: Proponé qué recortar primero y cómo, con montos de MIS DATOS. Empezá por lo más fácil."
    }

    fun deudas(a: Analisis, nivel: Nivel): String {
        val plan = a.deudas
        val texto = if (plan.deudas.isEmpty()) "No tengo deudas." else {
            "PLAN AVALANCHA (calculado): orden de pago " + plan.avalancha.orden.joinToString(" → ") { it.deuda.nombre } +
                "; libre de deudas en ${plan.avalancha.meses} meses pagando las cuotas actuales; intereses totales ${d(plan.avalancha.interesTotal, a)}" +
                (if (plan.ahorroAvalancha > 0) "; ahorra ${d(plan.ahorroAvalancha, a)} frente a bola de nieve" else "") +
                (if (!plan.avalancha.esViable) ". OJO: con las cuotas actuales algunas deudas nunca se terminan de pagar" else "") + "." +
                plan.deudas.filter { it.clase == ClaseDeuda.BUENA }.joinToString("") { " ${it.deuda.nombre} es deuda buena: ${it.motivo}" }
        }
        return misDatos(a, nivel) + "\n$texto\n\nTAREA: Explicame la estrategia para salir de mis deudas y qué hacer con la tarjeta de crédito."
    }

    fun inversion(a: Analisis, nivel: Nivel): String {
        val riesgo = a.perfil.riesgoEfectivo
        val aporte = maxOf(a.indicadores.capacidadAhorroMensual * 0.5, a.indicadores.ingresoReferencia * 0.05)
        val vf = MotorAhorro.valorFuturo(aporte, riesgo.rendimientoRealAnual, 10)
        return misDatos(a, nivel) + "\nSIMULACIÓN (calculada): aportando ${d(aporte, a)} por mes durante 10 años a un rendimiento real de " +
            "${p(riesgo.rendimientoRealAnual)} anual (perfil ${riesgo.etiqueta.lowercase()}) llegarías a ${d(vf, a)}. " +
            "En un mal año la cartera podría caer un ${p(riesgo.caidaMalAnio)}.\n\n" +
            "TAREA: Explicame cómo empezar a invertir según mi perfil y mi etapa de vida (${a.perfil.etapa.enfoque}). " +
            "Incluí el \"factor sueño\": que pueda dormir tranquilo con el riesgo elegido. Si todavía no corresponde invertir, decime qué hacer antes."
    }

    /**
     * Evaluación de una compra no planificada (regla de las 24/48 h). Los números del impacto
     * los calcula la app; Gemma da la reflexión, adaptada al perfil de conducta.
     */
    fun evaluarCompra(
        a: Analisis, descripcion: String, monto: Double, categoria: String, restanteDeseos: Double,
        horasTrabajo: Double, valorInvertido10: Double, horasEspera: Int,
        respuestaLibertad: String, respuestaNecesidad: String, nivel: Nivel,
    ): String = misDatos(a, nivel) + "\n\n" + """
        QUIERO COMPRAR: $descripcion por ${d(monto, a)} ($categoria). No estaba planificado.
        IMPACTO (calculado): me quedan ${d(restanteDeseos, a)} para deseos este mes; equivale a ${String.format(Formato.locale, "%.0f", horasTrabajo)} horas de trabajo; invertido 10 años serían ${d(valorInvertido10, a)}.
        MIS RESPUESTAS: ¿me acerca o me aleja de mi libertad financiera? "$respuestaLibertad". ¿Lo necesito o es un impulso? "$respuestaNecesidad".
        La regla sugiere esperar $horasEspera horas.

        TAREA: En 4 a 6 líneas: decime si conviene esperar y por qué, una pregunta para reflexionar y una alternativa más barata.
    """.trimIndent()

    /** Para convertir texto libre en un movimiento cuando el parser por reglas no alcanza. */
    fun extraerMovimiento(texto: String, gastos: List<String>, ingresos: List<String>): PromptIA = PromptIA(
        instrucciones = """
            Convertí el texto en un movimiento de dinero. Respondé SOLO con un objeto JSON en una línea, sin explicaciones ni ```.
            Categorías de gasto: ${gastos.joinToString(", ")}.
            Categorías de ingreso: ${ingresos.joinToString(", ")}.
            Medios: efectivo, debito, credito, transferencia, billetera.
            Ejemplo: "ayer pagué 12.500 de nafta con la tarjeta" -> {"tipo":"gasto","monto":12500,"descripcion":"Nafta","categoria":"Transporte","medio":"credito","dias_atras":1}
            Ejemplo: "me depositaron 300 lucas por un trabajo" -> {"tipo":"ingreso","monto":300000,"descripcion":"Trabajo","categoria":"Trabajo independiente","medio":"transferencia","dias_atras":0}
        """.trimIndent(),
        mensaje = "Texto: \"${texto.trim()}\"",
        maxRespuesta = 120,
        temperatura = 0.1,
    )

    fun categoriasPara(a: Analisis, tipo: TipoMovimiento): List<String> =
        a.datos.categorias.filter { it.tipo == tipo && !it.archivada }.map { it.nombre }
}

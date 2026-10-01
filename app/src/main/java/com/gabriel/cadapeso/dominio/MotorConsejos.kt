package com.gabriel.cadapeso.dominio

import java.time.YearMonth
import kotlin.math.max

/**
 * Motor de recomendación por reglas: genera las alertas y consejos proactivos, la hoja de
 * ruta para crear riqueza y el plan de ajuste progresivo. Gemma después los EXPLICA en
 * lenguaje natural, pero las cifras salen siempre de acá (un modelo de 2B parámetros
 * puede inventar números; estas reglas no).
 */
object MotorConsejos {

    fun hojaDeRuta(a: Analisis): HojaDeRuta {
        val p = a.pagatePrimero
        val f = a.fondoEmergencia
        val malas = a.deudas.malas
        val m = a.moneda
        return HojaDeRuta(
            listOf(
                PasoRuta(
                    numero = 1,
                    titulo = "Págate a ti primero",
                    descripcion = "Separá el ${Formato.porcentaje(p.porcentaje)} de tu ingreso apenas cobrás, como si fuera un gasto fijo.",
                    cumplido = p.cumplido || (!p.yaCobro && p.cumplidoMesAnterior),
                    progreso = if (p.objetivoMes > 0) (p.ahorradoMes / p.objetivoMes).coerceIn(0.0, 1.0) else 0.0,
                    detalle = "Este mes: ${Formato.dinero(p.ahorradoMes, m)} de ${Formato.dinero(p.objetivoMes, m)}",
                ),
                PasoRuta(
                    numero = 2,
                    titulo = "Completar el fondo de emergencia",
                    descripcion = "Entre ${f.mesesMin} y ${f.mesesMax} meses de gastos básicos en algo líquido y de bajo riesgo.",
                    cumplido = f.gastoBasicoMensual > 0 && f.mesesCubiertos >= f.mesesMin,
                    progreso = if (f.mesesMin > 0) (f.mesesCubiertos / f.mesesMin).coerceIn(0.0, 1.0) else 0.0,
                    detalle = "Cubre ${String.format(Formato.locale, "%.1f", f.mesesCubiertos)} meses (${Formato.dinero(f.acumulado, m)})",
                ),
                PasoRuta(
                    numero = 3,
                    titulo = "Cancelar las deudas caras",
                    descripcion = "Liquidá primero las deudas malas, de la tasa más alta a la más baja (avalancha).",
                    cumplido = malas.isEmpty(),
                    progreso = if (malas.isEmpty()) 1.0 else 0.0,
                    detalle = if (malas.isEmpty()) "Sin deudas malas" else
                        "${plural(malas.size, "deuda mala", "deudas malas")} por ${Formato.dinero(malas.sumOf { it.deuda.saldo }, m)}; primero: ${malas.first().deuda.nombre}",
                ),
                PasoRuta(
                    numero = 4,
                    titulo = "Invertir todos los meses (DCA)",
                    descripcion = "Aportes fijos y periódicos en instrumentos diversificados, acordes a tu perfil de riesgo.",
                    cumplido = a.patrimonio.inversiones > 0,
                    progreso = if (a.patrimonio.inversiones > 0) 1.0 else 0.0,
                    detalle = if (a.patrimonio.inversiones > 0) "Invertido: ${Formato.dinero(a.patrimonio.inversiones, m)}"
                    else "Perfil ${a.perfil.riesgoEfectivo.etiqueta.lowercase()}: rendimiento real esperado ~${Formato.porcentaje(a.perfil.riesgoEfectivo.rendimientoRealAnual)} anual",
                ),
            ),
        )
    }

    /**
     * Si los gastos básicos (necesidades + cuotas) superan el 80% del ingreso, arma un plan
     * trimestral para bajarlos de a poco hasta el 60%, con sugerencias concretas.
     */
    fun planAjuste(a: Analisis): PlanAjuste? {
        val actual = a.indicadores.porcentajeNecesidades
        val ingreso = a.indicadores.ingresoReferencia
        if (actual <= 80.0 || ingreso <= 0) return null
        val objetivo = 60.0
        val paso = (actual - objetivo) / 3
        val mes = YearMonth.from(a.hoy)
        val metas = (1..3).map { mes.plusMonths(it.toLong()) to actual - paso * it }
        val m = a.moneda
        val sugerencias = mutableListOf<String>()
        val basicas = a.mes.porCategoria.entries
            .mapNotNull { (id, monto) -> a.datos.categoria(id)?.takeIf { it.grupo == GrupoGasto.NECESIDAD }?.let { it to monto } }
            .sortedByDescending { it.second }
        basicas.take(2).forEach { (c, monto) ->
            sugerencias += "${c.emoji} ${c.nombre} (${Formato.dinero(monto, m)} este mes): renegociá, cambiá de plan o compará proveedores. Un 10% menos son ${Formato.dinero(monto * 0.1, m)}/mes."
        }
        if (a.suscripciones.isNotEmpty()) {
            sugerencias += "📺 Revisá tus suscripciones (${Formato.dinero(a.suscripciones.sumOf { it.montoMensual }, m)}/mes): ${a.suscripciones.take(3).joinToString { it.nombre }}."
        }
        a.fugas.firstOrNull()?.let {
            sugerencias += "🐜 ${it.categoria.nombre}: ${it.cantidadMes} gastos chicos suman ${Formato.dinero(it.totalMes, m)}/mes."
        }
        val cuotas = a.deudas.totalCuotas
        if (cuotas > ingreso * 0.2) {
            sugerencias += "💳 Las cuotas de deudas son ${Formato.dinero(cuotas, m)}/mes: consultá una refinanciación a menor tasa o más plazo."
        }
        return PlanAjuste(actual, objetivo, metas, (actual - objetivo) / 100 * ingreso, sugerencias)
    }

    fun generar(a: Analisis): List<Consejo> {
        val c = mutableListOf<Consejo>()
        val m = a.moneda
        val ind = a.indicadores
        val impulsivo = a.perfil.conductaEfectiva == PerfilConducta.IMPULSIVO
        val ahorrador = a.perfil.conductaEfectiva == PerfilConducta.AHORRADOR

        // 1. Trampa del pago mínimo.
        a.deudas.deudas.filter { it.trampaPagoMinimo }.forEach { d ->
            c += Consejo(
                "trampa_${d.deuda.id}", Severidad.ALTA, "${d.deuda.nombre}: el saldo casi no baja",
                "Pagás ${Formato.dinero(d.deuda.cuotaMensual, m)} y genera ${Formato.dinero(d.interesMensual, m)} de interés por mes. " +
                    "Evitá el pago mínimo: es la forma más cara de financiarte.",
                AccionConsejo.VER_DEUDAS,
            )
        }

        // 2. Ratio de endeudamiento.
        if (ind.ratioEndeudamiento > 30) {
            val primera = a.deudas.avalancha.orden.firstOrNull()?.deuda?.nombre
            c += Consejo(
                "endeudamiento", Severidad.ALTA, "Deudas por encima del 30% de tu ingreso",
                "Tus cuotas se llevan el ${Formato.porcentaje(ind.ratioEndeudamiento)} de tu ingreso (máximo sano: 30%). " +
                    "No tomes deudas nuevas" + (primera?.let { " y aplicá avalancha: primero $it." } ?: "."),
                AccionConsejo.VER_DEUDAS,
            )
        }

        // 3. Uso de tarjeta de crédito.
        if (ind.usoTarjeta > 30) {
            c += Consejo(
                "tarjeta", if (ind.usoTarjeta > 50) Severidad.ALTA else Severidad.MEDIA, "Mucho consumo con tarjeta",
                "Este mes pagaste con crédito el ${Formato.porcentaje(ind.usoTarjeta)} de tu ingreso. " +
                    "Usá una sola tarjeta y pagá siempre el total del resumen, nunca el mínimo.",
                AccionConsejo.VER_PRESUPUESTO,
            )
        }

        // 4. Proyección en rojo.
        a.proyeccion.mesesEnRojo.firstOrNull()?.let { rojo ->
            val cerca = mesesEntre(YearMonth.from(a.hoy), rojo.mes) <= 3
            val evento = rojo.eventos.maxByOrNull { it.monto }?.let { " (${it.nombre})" } ?: ""
            c += Consejo(
                "proyeccion", if (cerca) Severidad.ALTA else Severidad.MEDIA, "${Formato.mes(rojo.mes)} viene en rojo",
                "Ese mes tus gastos superarían tus ingresos por ${Formato.dinero(-rojo.libre, m)}$evento. " +
                    "Separá ${Formato.dinero(a.proyeccion.provisionMensual, m)} por mes desde ahora para anticiparlo.",
                AccionConsejo.VER_PROYECCION,
            )
        }

        // 5. Necesidades por encima del 80%: plan de ajuste progresivo.
        a.planAjuste?.let { plan ->
            c += Consejo(
                "ajuste", Severidad.ALTA, "Necesidades y cuotas: ${Formato.porcentaje(plan.porcentajeActual)} de tu ingreso",
                "Plan de 3 meses: bajar a " + plan.metasMensuales.joinToString(" → ") { Formato.porcentaje(it.second) } +
                    ". Al final necesitás recortar unos ${Formato.dinero(plan.recorteMensualFinal, m)} por mes.",
                AccionConsejo.VER_PRESUPUESTO,
            )
        }

        // 6. Fondo de emergencia.
        val f = a.fondoEmergencia
        if (f.gastoBasicoMensual > 0 && f.mesesCubiertos < f.mesesMin) {
            c += Consejo(
                "fondo", if (f.mesesCubiertos < 1) Severidad.ALTA else Severidad.MEDIA, "Tu airbag financiero está incompleto",
                "Cubre ${String.format(Formato.locale, "%.1f", f.mesesCubiertos)} meses de gastos básicos; la meta es ${f.mesesMin} a ${f.mesesMax}. " +
                    "Te faltan ${Formato.dinero(f.faltanteMin, m)}. Guardalo en algo líquido y de bajo riesgo.",
                AccionConsejo.VER_AHORRO,
            )
        }

        // 7. Págate a ti primero.
        val p = a.pagatePrimero
        if (p.yaCobro && !p.cumplido && p.objetivoMes > 0) {
            c += Consejo(
                "pagate", if (impulsivo) Severidad.ALTA else Severidad.MEDIA, "Págate a ti primero",
                "Ya cobraste: separá ${Formato.dinero(p.faltante, m)} (${Formato.porcentaje(p.porcentaje)} de tu ingreso) antes de gastar. " +
                    "Tratalo como un gasto fijo más.",
                AccionConsejo.VER_AHORRO,
            )
        }

        // 8. Cada peso con una tarea.
        val sinAsignar = a.presupuesto.sinAsignar
        if (a.presupuesto.ingresoAAsignar > 0 && sinAsignar > a.presupuesto.ingresoAAsignar * 0.01) {
            c += Consejo(
                "sin_asignar", Severidad.MEDIA, "Hay plata sin tarea",
                "Tenés ${Formato.dinero(sinAsignar, m)} sin asignar este mes. Dale una tarea a cada peso antes de gastarlo para que no se diluya.",
                AccionConsejo.VER_PRESUPUESTO,
            )
        } else if (sinAsignar < -a.presupuesto.ingresoAAsignar * 0.01 && a.presupuesto.ingresoAAsignar > 0) {
            c += Consejo(
                "sobre_asignado", Severidad.MEDIA, "Asignaste más de lo que ganás",
                "Tu presupuesto supera tu ingreso por ${Formato.dinero(-sinAsignar, m)}. Ajustá alguna categoría.",
                AccionConsejo.VER_PRESUPUESTO,
            )
        }

        // 9. Categorías excedidas.
        val excedidas = a.presupuesto.excedidas.sortedByDescending { -it.restante }
        if (excedidas.isNotEmpty()) {
            c += Consejo(
                "excedidas", Severidad.MEDIA, "Te pasaste del presupuesto",
                excedidas.take(3).joinToString(", ") { "${it.categoria.emoji} ${it.categoria.nombre} (+${Formato.dinero(-it.restante, m)})" } +
                    if (impulsivo) ". Antes de la próxima compra no planificada, esperá 24 horas." else ".",
                AccionConsejo.VER_PRESUPUESTO,
            )
        }

        // 10. Ritmo semanal.
        val s = a.semana
        if (s.presupuestoSemanal > 0 && s.desvio > 15 && s.total > 0) {
            c += Consejo(
                "semana", Severidad.MEDIA, "Semana por encima del ritmo",
                "Vas ${Formato.porcentaje(s.desvio)} por encima de lo previsto a esta altura. " +
                    if (s.disponible > 0) "Te quedan ${Formato.dinero(s.disponible, m)} para el resto de la semana."
                    else "Ya usaste todo el margen semanal (${Formato.dinero(-s.disponible, m)} de más).",
                AccionConsejo.VER_SEMANA,
            )
        }

        // 11. Gastos hormiga.
        a.fugas.firstOrNull()?.let { h ->
            c += Consejo(
                "hormiga", if (h.totalMes > ind.ingresoReferencia * 0.05) Severidad.MEDIA else Severidad.BAJA,
                "Gasto hormiga: ${h.categoria.nombre}",
                "${h.cantidadMes} compras chicas en 30 días suman ${Formato.dinero(h.totalMes, m)}/mes (${Formato.dinero(h.totalAnual, m)} al año). " +
                    "Invertido, en 10 años serían ${Formato.dinero(h.costoOportunidad10Anios, m)}.",
                AccionConsejo.VER_FUGAS,
            )
        }

        // 12. Suscripciones.
        val totalSus = a.suscripciones.sumOf { it.montoMensual }
        if (a.suscripciones.size >= 2 && totalSus > ind.ingresoReferencia * 0.03) {
            c += Consejo(
                "suscripciones", Severidad.BAJA, "¿Usás todas tus suscripciones?",
                "Pagás ${Formato.dinero(totalSus, m)} por mes en ${a.suscripciones.size} suscripciones (${Formato.dinero(totalSus * 12, m)} al año).",
                AccionConsejo.VER_FUGAS,
            )
        }

        // 13. Metas en riesgo.
        a.metas.filter { it.enRiesgo }.take(2).forEach { e ->
            c += Consejo(
                "meta_${e.meta.id}", Severidad.MEDIA, "Meta en riesgo: ${e.meta.nombre}",
                "Necesita ${Formato.dinero(e.aporteMensualNecesario, m)} por mes y tu capacidad de ahorro es de ${Formato.dinero(ind.capacidadAhorroMensual, m)}. " +
                    "Extendé el plazo, bajá el monto o recortá un gasto variable.",
                AccionConsejo.VER_AHORRO,
            )
        }

        // 14. Deudas malas. Están ordenadas de la tasa más alta a la más baja: la primera es
        //     la prioridad de la avalancha (aunque ya se haya avisado por el pago mínimo).
        val malas = a.deudas.malas
        if (malas.isNotEmpty() && ind.ratioEndeudamiento <= 30) {
            val primera = malas.first()
            c += Consejo(
                "deuda_mala", Severidad.MEDIA, "Tenés ${plural(malas.size, "deuda mala", "deudas malas")}",
                "Suman ${Formato.dinero(malas.sumOf { it.deuda.saldo }, m)}. Con avalancha, empezá por ${primera.deuda.nombre} " +
                    "(${Formato.porcentaje(primera.deuda.tasaAnual, 1)} anual): cada peso extra ahí es el que más intereses te ahorra.",
                AccionConsejo.VER_DEUDAS,
            )
        }

        // 15. Compras en espera cuyo plazo ya venció.
        val vencidas = a.datos.compras.filter { it.estado == EstadoCompra.EN_ESPERA && it.venceMillis <= a.ahoraMillis }
        if (vencidas.isNotEmpty()) {
            c += Consejo(
                "compras", Severidad.MEDIA, "Terminó la espera de ${plural(vencidas.size, "compra", "compras")}",
                "Ya pasaron las horas de reflexión: ${vencidas.take(3).joinToString { it.descripcion }}. ¿Todavía las querés?",
                AccionConsejo.VER_COMPRAS,
            )
        }

        // 16. Exceso de liquidez: fondo completo y sin deudas caras -> invertir.
        val liquidoDeMas = a.patrimonio.activosLiquidos - f.objetivoMax
        if (f.estado == EstadoFondo.COMPLETO && a.deudas.malas.isEmpty() && f.objetivoMax > 0 && liquidoDeMas > f.objetivoMax * 0.5) {
            c += Consejo(
                "invertir", Severidad.BAJA, "Tenés plata quieta",
                "Con el fondo completo y sin deudas caras, los ${Formato.dinero(liquidoDeMas, m)} de más podrían invertirse con aportes periódicos (DCA).",
                AccionConsejo.VER_INVERSION,
            )
        }

        // 17. Perfil sin completar.
        if (a.perfil.conducta == null || a.perfil.riesgo == null) {
            c += Consejo(
                "perfil", Severidad.BAJA, "Completá tu perfil",
                "Dos tests cortos (conducta y tolerancia al riesgo) para que el asesor adapte el tono y las recomendaciones.",
                AccionConsejo.HACER_TEST,
            )
        }

        // 18. Buenas noticias (también motivan).
        if (ind.tasaAhorro >= 20) {
            c += Consejo(
                "tasa_ahorro", Severidad.POSITIVA, "¡Ahorraste el ${Formato.porcentaje(ind.tasaAhorro)} este mes!",
                if (ahorrador) "Excelente constancia. Si tu fondo de emergencia está completo, pensá en invertir parte de ese ahorro."
                else "Vas por encima del 20% recomendado. Seguí así.",
            )
        } else if (p.cumplido) {
            c += Consejo("pagate_ok", Severidad.POSITIVA, "Te pagaste primero este mes", "Separaste ${Formato.dinero(p.ahorradoMes, m)}. Ese hábito es la base de todo lo demás.")
        }
        if (a.ahorroPorImpulsosEvitados > 0) {
            c += Consejo(
                "impulsos", Severidad.POSITIVA, "Compras impulsivas evitadas",
                "Gracias a la regla de las 24/48 h, no gastaste ${Formato.dinero(a.ahorroPorImpulsosEvitados, m)}.",
            )
        }

        return c.sortedBy { it.severidad.ordinal }
    }

    /** Umbral a partir del cual un gasto no planificado activa la regla de las 24/48 h. */
    fun umbralImpulso(a: DatosFinancieros, ingresoReferencia: Double, perfil: PerfilFinanciero): Double {
        if (a.perfil.umbralImpulso > 0) return a.perfil.umbralImpulso
        if (ingresoReferencia <= 0) return 20_000.0
        val factor = when (perfil.conductaEfectiva) {
            PerfilConducta.IMPULSIVO -> 0.03
            PerfilConducta.EQUILIBRADO -> 0.05
            PerfilConducta.AHORRADOR -> 0.08
        }
        return max(ingresoReferencia * factor, 1.0)
    }
}

package com.gabriel.cadapeso.dominio

import com.gabriel.cadapeso.datos.Asignacion
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.round

/**
 * Módulo de presupuesto y flujo de efectivo: resumen mensual, "cada peso con una tarea",
 * reglas 50/30/20 y 60/10/10/20, y panel semanal.
 * Todo son funciones puras (entran datos, salen resultados), por eso se pueden testear en la PC.
 */
object MotorPresupuesto {

    fun resumenMes(d: DatosFinancieros, mes: YearMonth): ResumenMes {
        val movs = d.movimientosDe(mes)
        var ingresos = 0.0
        var gastos = 0.0
        var conTarjeta = 0.0
        var recortable = 0.0
        var variable = 0.0
        val porCategoria = HashMap<Long, Double>()
        val porGrupo = HashMap<GrupoGasto, Double>()
        for (m in movs) {
            if (m.tipo == TipoMovimiento.INGRESO) {
                ingresos += m.monto
                continue
            }
            gastos += m.monto
            porCategoria.merge(m.categoriaId, m.monto, Double::plus)
            val categoria = d.categoria(m.categoriaId)
            val grupo = categoria?.grupo ?: GrupoGasto.DESEO
            porGrupo.merge(grupo, m.monto, Double::plus)
            if (m.medioPago == MedioPago.CREDITO) conTarjeta += m.monto
            if (categoria?.recortable == true) recortable += m.monto
            if (esVariable(grupo, categoria?.fijo == true)) variable += m.monto
        }
        return ResumenMes(
            mes = mes,
            ingresos = ingresos,
            gastos = gastos,
            ahorro = porGrupo[GrupoGasto.AHORRO] ?: 0.0,
            pagoDeudas = porGrupo[GrupoGasto.DEUDA] ?: 0.0,
            porCategoria = porCategoria,
            porGrupo = porGrupo,
            conTarjeta = conTarjeta,
            recortable = recortable,
            variable = variable,
            cantidad = movs.size,
        )
    }

    /** Gasto "del día a día": no es fijo mensual, ni ahorro, ni cuota de deuda. */
    fun esVariable(grupo: GrupoGasto, fijo: Boolean): Boolean =
        !fijo && grupo != GrupoGasto.AHORRO && grupo != GrupoGasto.DEUDA && grupo != GrupoGasto.INGRESO

    /**
     * El ingreso con el que se calculan los porcentajes del mes. Es el mayor entre lo cobrado
     * de verdad y lo previsto (plan del mes o, si no hay, el ingreso del perfil): así a
     * principio de mes, antes de cobrar, ya hay una base; y si cobraste de más, se usa lo real.
     */
    fun ingresoReferencia(d: DatosFinancieros, mes: YearMonth, ingresosReales: Double): Double {
        val previsto = d.planDe(mes)?.ingresoPrevisto ?: d.perfil.ingresoMensual
        return max(ingresosReales, previsto)
    }

    fun estadoPresupuesto(d: DatosFinancieros, mes: YearMonth, resumen: ResumenMes): EstadoPresupuesto {
        val regla = d.perfil.regla
        val ingreso = ingresoReferencia(d, mes, resumen.ingresos)
        val asignadoPorCategoria = d.asignacionesDe(mes).associate { it.categoriaId to it.monto }
        val categoriasGasto = d.categorias.filter { it.tipo == TipoMovimiento.GASTO }
        val lineas = categoriasGasto
            // Las archivadas solo aparecen si tienen algo asignado o gastado ese mes.
            .filter { !it.archivada || (asignadoPorCategoria[it.id] ?: 0.0) > 0 || (resumen.porCategoria[it.id] ?: 0.0) > 0 }
            .map { c -> LineaPresupuesto(c, asignadoPorCategoria[c.id] ?: 0.0, resumen.porCategoria[c.id] ?: 0.0) }
            .sortedWith(compareBy({ regla.partidas.indexOf(regla.partidaDe(it.categoria.grupo)) }, { it.categoria.orden }))
        val partidas = regla.partidas.map { p ->
            val asignado = lineas.filter { it.categoria.grupo in p.grupos }.sumOf { it.asignado }
            val real = p.grupos.sumOf { resumen.grupo(it) }
            LineaPartida(
                partida = p,
                objetivo = ingreso * p.porcentaje / 100,
                asignado = asignado,
                real = real,
                porcentajeReal = if (ingreso > 0) real / ingreso * 100 else 0.0,
            )
        }
        return EstadoPresupuesto(
            mes = mes,
            regla = regla,
            ingresoAAsignar = ingreso,
            totalAsignado = lineas.sumOf { it.asignado },
            lineas = lineas,
            partidas = partidas,
        )
    }

    /**
     * Arma un presupuesto que reparte TODO el ingreso según la regla elegida.
     * Dentro de cada partida, el monto se divide entre sus categorías en proporción a lo que
     * gastaste en los últimos 3 meses (si no hay historia, en partes iguales).
     * En "ahorro y deudas" primero se cubren las cuotas de las deudas y el resto va a ahorro.
     */
    fun sugerirAsignaciones(d: DatosFinancieros, mes: YearMonth, ingreso: Double): List<Asignacion> {
        if (ingreso <= 0) return emptyList()
        val regla = d.perfil.regla
        val categorias = d.categorias.filter { it.tipo == TipoMovimiento.GASTO && !it.archivada }
        val historia = (1..3).map { resumenMes(d, mes.minusMonths(it.toLong())) }
        val gastoHistorico = HashMap<Long, Double>()
        historia.forEach { r -> r.porCategoria.forEach { (id, v) -> gastoHistorico.merge(id, v, Double::plus) } }
        val cuotasDeuda = d.deudas.filter { it.saldo > 0 }.sumOf { it.cuotaMensual }

        val resultado = LinkedHashMap<Long, Double>()
        for (p in regla.partidas) {
            var montoPartida = ingreso * p.porcentaje / 100
            val enPartida = categorias.filter { it.grupo in p.grupos }
            if (enPartida.isEmpty()) continue

            // Las cuotas de deudas son obligatorias: se reservan primero.
            val deCuotas = enPartida.filter { it.grupo == GrupoGasto.DEUDA }
            if (deCuotas.isNotEmpty() && cuotasDeuda > 0) {
                val reservado = minOf(cuotasDeuda, montoPartida)
                resultado[deCuotas.first().id] = reservado
                montoPartida -= reservado
            }
            val resto = enPartida.filter { it.grupo != GrupoGasto.DEUDA }.ifEmpty { if (cuotasDeuda > 0) emptyList() else deCuotas }
            if (resto.isEmpty() || montoPartida <= 0) {
                // Si sobró plata en una partida que solo tiene deudas, va a la cuota (pagar de más).
                if (montoPartida > 0 && deCuotas.isNotEmpty()) resultado.merge(deCuotas.first().id, montoPartida, Double::plus)
                continue
            }
            val pesos = resto.associate { it.id to (gastoHistorico[it.id] ?: 0.0) }
            val totalPesos = pesos.values.sum()
            for (c in resto) {
                val parte = if (totalPesos > 0) montoPartida * (pesos[c.id] ?: 0.0) / totalPesos else montoPartida / resto.size
                resultado.merge(c.id, parte, Double::plus)
            }
        }
        // Redondeo "lindo" y el centavo que sobre va a la categoría más grande, para que
        // lo asignado sume exactamente el ingreso (cada peso con una tarea).
        val paso = pasoRedondeo(ingreso)
        val redondeado = resultado.mapValues { (_, v) -> round(v / paso) * paso }.toMutableMap()
        val diferencia = ingreso - redondeado.values.sum()
        val mayor = redondeado.maxByOrNull { it.value }?.key
        if (mayor != null) redondeado[mayor] = (redondeado[mayor] ?: 0.0) + diferencia
        return redondeado.filter { it.value > 0.0 }.map { (id, monto) -> Asignacion(mes.clave(), id, round(monto * 100) / 100) }
    }

    private fun pasoRedondeo(ingreso: Double): Double = when {
        ingreso >= 1_000_000 -> 1000.0
        ingreso >= 100_000 -> 100.0
        ingreso >= 10_000 -> 10.0
        else -> 1.0
    }

    /** Panel semanal: seguimiento operativo del gasto variable contra el ritmo del presupuesto. */
    fun resumenSemana(d: DatosFinancieros, hoy: LocalDate, presupuesto: EstadoPresupuesto): ResumenSemana {
        val inicio = hoy.inicioSemana()
        val inicioAnterior = inicio.minusDays(7)
        val porDia = DoubleArray(7)
        var anterior = 0.0
        val porCategoria = HashMap<Long, Double>()
        for (m in d.movimientos) {
            if (m.tipo != TipoMovimiento.GASTO || m.fecha < inicioAnterior || m.fecha > inicio.plusDays(6)) continue
            val c = d.categoria(m.categoriaId)
            if (!esVariable(c?.grupo ?: GrupoGasto.DESEO, c?.fijo == true)) continue
            if (m.fecha < inicio) {
                anterior += m.monto
            } else {
                porDia[ChronoUnit.DAYS.between(inicio, m.fecha).toInt()] += m.monto
                porCategoria.merge(m.categoriaId, m.monto, Double::plus)
            }
        }
        val mes = YearMonth.from(hoy)
        val asignadoVariable = presupuesto.lineas
            .filter { esVariable(it.categoria.grupo, it.categoria.fijo) }
            .sumOf { it.asignado }
        // Si no hay presupuesto asignado, se estima: lo que se gastó en variables en promedio
        // los últimos 3 meses, o el 35% del ingreso si todavía no hay historia.
        val mensualVariable = when {
            asignadoVariable > 0 -> asignadoVariable
            else -> {
                val historia = (1..3).map { resumenMes(d, mes.minusMonths(it.toLong())) }.filter { it.tieneDatos }
                if (historia.isNotEmpty()) historia.map { it.variable }.average()
                else presupuesto.ingresoAAsignar * 0.35
            }
        }
        return ResumenSemana(
            inicio = inicio,
            gastoPorDia = porDia.toList(),
            total = porDia.sum(),
            totalSemanaAnterior = anterior,
            presupuestoSemanal = mensualVariable * 7.0 / mes.lengthOfMonth(),
            diasTranscurridos = (ChronoUnit.DAYS.between(inicio, hoy) + 1).toInt().coerceIn(1, 7),
            topCategorias = porCategoria.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value },
        )
    }

    /** Promedio de los últimos [n] meses COMPLETOS que tienen datos (si no hay, lista vacía). */
    fun mesesCompletosConDatos(d: DatosFinancieros, hoy: LocalDate, n: Int = 3): List<ResumenMes> {
        val actual = YearMonth.from(hoy)
        return (1..n).map { resumenMes(d, actual.minusMonths(it.toLong())) }.filter { it.tieneDatos }
    }
}

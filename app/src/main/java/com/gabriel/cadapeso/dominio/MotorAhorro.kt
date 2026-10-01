package com.gabriel.cadapeso.dominio

import com.gabriel.cadapeso.datos.Activo
import com.gabriel.cadapeso.datos.Meta
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.pow

/**
 * Módulo de ahorro programado: "págate a ti primero", fondo de emergencia, metas SMART con
 * ahorro incremental, e interés compuesto / promediado de costo (DCA).
 */
object MotorAhorro {

    /** Tasa mensual equivalente a una tasa anual compuesta: (1 + anual)^(1/12) - 1. */
    private fun mensualDeAnual(tasaAnual: Double): Double = (1 + tasaAnual / 100).pow(1.0 / 12) - 1

    /** Valor futuro de aportar [aporteMensual] todos los meses durante [anios] a [tasaAnual] %. */
    fun valorFuturo(aporteMensual: Double, tasaAnual: Double, anios: Int, inicial: Double = 0.0): Double =
        simularDCA(aporteMensual, inicial, tasaAnual, anios).lastOrNull()?.valor ?: inicial

    data class PuntoDCA(val anio: Int, val aportado: Double, val valor: Double) {
        val ganancia: Double get() = valor - aportado
    }

    /**
     * Promediado de costo (DCA): aportes fijos todos los meses. Como se compra siempre el
     * mismo monto, cuando el precio baja se compran más cuotapartes y cuando sube menos; a
     * largo plazo el interés compuesto hace el resto. Devuelve un punto por año.
     */
    fun simularDCA(aporteMensual: Double, inicial: Double, tasaAnual: Double, anios: Int): List<PuntoDCA> {
        val r = mensualDeAnual(tasaAnual)
        var valor = inicial
        var aportado = inicial
        val puntos = mutableListOf(PuntoDCA(0, aportado, valor))
        for (mes in 1..anios * 12) {
            valor = valor * (1 + r) + aporteMensual
            aportado += aporteMensual
            if (mes % 12 == 0) puntos += PuntoDCA(mes / 12, aportado, valor)
        }
        return puntos
    }

    fun fondoEmergencia(d: DatosFinancieros, gastoBasicoMensual: Double): EstadoFondoEmergencia {
        val cuentas = d.activos.filter { it.esFondoEmergencia }
        val tipo = d.perfil.tipoIngreso
        return EstadoFondoEmergencia(
            gastoBasicoMensual = gastoBasicoMensual,
            mesesMin = tipo.mesesFondoMin,
            mesesMax = tipo.mesesFondoMax,
            acumulado = cuentas.sumOf { it.valor },
            cuentas = cuentas,
        )
    }

    fun pagatePrimero(d: DatosFinancieros, hoy: LocalDate, resumen: ResumenMes, anterior: ResumenMes, ingresoReferencia: Double): EstadoPagatePrimero {
        val p = d.perfil
        val dia = p.diaCobro.coerceIn(1, 28)
        val esteMes = hoy.withDayOfMonth(minOf(dia, hoy.lengthOfMonth()))
        val proximo = if (hoy.dayOfMonth >= dia) esteMes.plusMonths(1) else esteMes
        val ingresoAnterior = MotorPresupuesto.ingresoReferencia(d, anterior.mes, anterior.ingresos)
        return EstadoPagatePrimero(
            porcentaje = p.porcentajePagatePrimero,
            objetivoMes = ingresoReferencia * p.porcentajePagatePrimero / 100,
            ahorradoMes = resumen.ahorro,
            diaCobro = dia,
            proximoCobro = proximo,
            yaCobro = hoy.dayOfMonth >= dia || resumen.ingresos > 0,
            cumplidoMesAnterior = anterior.tieneDatos && ingresoAnterior > 0 &&
                anterior.ahorro >= ingresoAnterior * p.porcentajePagatePrimero / 100 * 0.999,
        )
    }

    /** Lo acumulado en una meta: lo que ya tenía + los aportes registrados como movimientos. */
    fun acumuladoMeta(d: DatosFinancieros, meta: Meta): Double =
        meta.montoInicial + d.movimientos.filter { it.metaId == meta.id }.sumOf {
            if (it.tipo == TipoMovimiento.GASTO) it.monto else -it.monto
        }

    /**
     * Estado de cada meta. "Alcanzable" se evalúa en orden de vencimiento: cada meta consume
     * parte de tu capacidad de ahorro mensual, y si no alcanza para la siguiente, queda en riesgo.
     */
    fun metas(d: DatosFinancieros, hoy: LocalDate, capacidadAhorroMensual: Double): List<EstadoMeta> {
        var capacidadRestante = capacidadAhorroMensual
        return d.metas.filter { !it.archivada }.sortedBy { it.fechaLimite }.map { meta ->
            val acumulado = acumuladoMeta(d, meta)
            val faltante = max(0.0, meta.montoObjetivo - acumulado)
            val diasRestantes = ChronoUnit.DAYS.between(hoy, meta.fechaLimite)
            val mesesRestantes = max(diasRestantes / 30.44, 0.0)
            val necesario = when {
                faltante <= 0 -> 0.0
                mesesRestantes < 1 -> faltante
                else -> faltante / mesesRestantes
            }
            val alcanzable = faltante <= 0 || necesario <= capacidadRestante + 0.01
            if (faltante > 0) capacidadRestante = max(0.0, capacidadRestante - necesario)
            val (cuotas, fechaPlan) = planIncremental(meta, acumulado, hoy)
            EstadoMeta(
                meta = meta,
                acumulado = acumulado,
                mesesRestantes = mesesRestantes,
                aporteMensualNecesario = necesario,
                smart = ChecklistSmart(
                    especifica = meta.especifica.isNotBlank() && meta.nombre.isNotBlank(),
                    medible = meta.montoObjetivo > 0,
                    alcanzable = alcanzable,
                    relevante = meta.relevancia.isNotBlank(),
                    temporal = diasRestantes > 0 || faltante <= 0,
                ),
                proximasCuotas = cuotas,
                fechaEstimadaPlan = fechaPlan,
                enRiesgo = faltante > 0 && (!alcanzable || diasRestantes <= 0 || (fechaPlan != null && fechaPlan.isAfter(meta.fechaLimite))),
            )
        }
    }

    /**
     * Ahorro incremental: el aporte número k es (aporteInicial + k × incremento), cada semana
     * o cada mes. Empezar con poco y subir de a poco hace que el hábito sea sostenible.
     * Devuelve las próximas 6 cuotas y la fecha en que se completaría la meta.
     */
    fun planIncremental(meta: Meta, acumulado: Double, hoy: LocalDate): Pair<List<CuotaPlan>, LocalDate?> {
        if (meta.aporteInicial <= 0) return emptyList<CuotaPlan>() to null
        val faltante = meta.montoObjetivo - acumulado
        val cuotas = mutableListOf<CuotaPlan>()
        var suma = 0.0
        var fechaFin: LocalDate? = null
        var k = 0
        // Las cuotas arrancan en la creación de la meta; mostramos desde hoy en adelante.
        while (k < 520) {
            val fecha = meta.creada.plusDays(meta.frecuencia.dias * k)
            val monto = meta.aporteInicial + k * meta.incremento
            if (!fecha.isBefore(hoy)) {
                suma += monto
                if (cuotas.size < 6) cuotas += CuotaPlan(k + 1, fecha, monto, acumulado + suma)
                if (suma >= faltante) {
                    fechaFin = fecha
                    break
                }
            }
            k++
        }
        if (faltante <= 0) return emptyList<CuotaPlan>() to hoy
        return cuotas to fechaFin
    }

    /** Patrimonio neto: activos a valor actual menos el saldo de todas las deudas. */
    fun patrimonio(activos: List<Activo>, totalDeudas: Double): EstadoPatrimonio = EstadoPatrimonio(
        activos = activos.sumOf { it.valor },
        activosLiquidos = activos.filter { it.liquido }.sumOf { it.valor },
        activosQueRinden = activos.filter { it.generaIngresos }.sumOf { it.valor },
        inversiones = activos.filter { it.tipo == TipoActivo.INVERSION }.sumOf { it.valor },
        pasivos = totalDeudas,
        porTipo = activos.groupBy { it.tipo }.mapValues { (_, l) -> l.sumOf { it.valor } },
    )

    /** Meses entre hoy y una fecha (para textos tipo "faltan 7 meses"). */
    fun mesesHasta(hoy: LocalDate, fecha: LocalDate): Long = ChronoUnit.MONTHS.between(YearMonth.from(hoy), YearMonth.from(fecha))
}

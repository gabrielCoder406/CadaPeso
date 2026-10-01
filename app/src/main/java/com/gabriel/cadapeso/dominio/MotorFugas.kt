package com.gabriel.cadapeso.dominio

import com.gabriel.cadapeso.datos.EventoPlanificado
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Detección de fugas de dinero (gastos hormiga y suscripciones) y proyección a 3 y 9 meses.
 */
object MotorFugas {

    /**
     * Umbral de "gasto chico". Si el usuario no fijó uno, es el 1,5% del ingreso mensual
     * (con un ingreso de $1.200.000, todo lo que cueste hasta $18.000).
     */
    fun umbralHormiga(d: DatosFinancieros, ingresoReferencia: Double): Double = when {
        d.perfil.umbralHormiga > 0 -> d.perfil.umbralHormiga
        ingresoReferencia > 0 -> ingresoReferencia * 0.015
        else -> {
            // Sin ingreso cargado: el gasto típico más bajo (percentil 30) de los últimos 90 días.
            val montos = d.movimientos.filter { it.tipo == TipoMovimiento.GASTO }.map { it.monto }.sorted()
            if (montos.isEmpty()) 5000.0 else montos[(montos.size * 0.3).toInt().coerceAtMost(montos.size - 1)]
        }
    }

    /**
     * Gastos hormiga: compras chicas (≤ umbral) en categorías recortables que se repiten al
     * menos 4 veces en los últimos 30 días (una por semana o más). Se agrupan por categoría
     * porque "café en distintos lugares" sigue siendo la misma fuga.
     */
    fun gastosHormiga(d: DatosFinancieros, hoy: LocalDate, umbral: Double, rendimientoReal: Double): List<FugaHormiga> {
        val desde = hoy.minusDays(29)
        return d.movimientos
            .filter { it.tipo == TipoMovimiento.GASTO && it.fecha in desde..hoy && it.monto <= umbral }
            .filter { d.categoria(it.categoriaId)?.recortable == true }
            .groupBy { it.categoriaId }
            .mapNotNull { (categoriaId, movs) ->
                val categoria = d.categoria(categoriaId) ?: return@mapNotNull null
                if (movs.size < 4) return@mapNotNull null
                val total = movs.sumOf { it.monto }
                val ejemplos = movs.groupingBy { it.descripcion.trim().capitalizar() }.eachCount()
                    .entries.sortedByDescending { it.value }.take(3).map { "${it.key} (${it.value})" }
                FugaHormiga(
                    categoria = categoria,
                    cantidadMes = movs.size,
                    totalMes = total,
                    ejemplos = ejemplos,
                    costoOportunidad10Anios = MotorAhorro.valorFuturo(total, rendimientoReal, 10),
                )
            }
            .sortedByDescending { it.totalMes }
    }

    /**
     * Suscripciones: pagos que se repiten todos los meses con un monto parecido (±15%) en
     * categorías recortables (el alquiler no aparece porque no es recortable). Hace falta
     * verlos en 3 meses distintos de los últimos 4, o en 2 si ya están en una categoría de
     * suscripciones; así una pizza que se repitió dos veces no pasa por suscripción.
     */
    fun suscripciones(d: DatosFinancieros, hoy: LocalDate): List<SuscripcionDetectada> {
        val desde = hoy.minusDays(120)
        return d.movimientos
            .filter { it.tipo == TipoMovimiento.GASTO && it.fecha in desde..hoy }
            .filter { d.categoria(it.categoriaId)?.recortable == true }
            .groupBy { claveDescripcion(it.descripcion) }
            .filterKeys { it.isNotBlank() }
            .mapNotNull { (_, movs) ->
                val meses = movs.map { YearMonth.from(it.fecha) }.distinct()
                val enCategoriaSuscripcion = movs.any { normalizar(d.categoria(it.categoriaId)?.nombre ?: "").contains("suscrip") }
                if (meses.size < (if (enCategoriaSuscripcion) 2 else 3)) return@mapNotNull null
                val montos = movs.map { it.monto }.sorted()
                val mediana = montos[montos.size / 2]
                if (montos.any { abs(it - mediana) > mediana * 0.15 }) return@mapNotNull null
                // Un pago por mes (si hay varios por mes es un gasto frecuente, no una suscripción).
                if (movs.size > meses.size + 1) return@mapNotNull null
                val ultimo = movs.maxBy { it.fecha }
                SuscripcionDetectada(
                    nombre = ultimo.descripcion.trim().capitalizar(),
                    montoMensual = mediana,
                    ultimaFecha = ultimo.fecha,
                    categoriaId = ultimo.categoriaId,
                    vecesVista = movs.size,
                )
            }
            .sortedByDescending { it.montoMensual }
    }

    private val PALABRAS_VACIAS = setOf("de", "del", "la", "el", "los", "las", "en", "con", "por", "para", "pago", "compra", "cuota", "mes", "mensual", "un", "una")

    /** "Pago Spotify Premium" -> "spotify": primera palabra significativa, sin acentos ni números. */
    fun claveDescripcion(descripcion: String): String =
        normalizar(descripcion).replace(Regex("[^a-z ]"), " ").split(' ')
            .firstOrNull { it.length >= 3 && it !in PALABRAS_VACIAS } ?: ""

    /**
     * Proyección a [horizonte] meses (3 = operativa, 9 = estratégica). Usa el promedio de los
     * últimos 3 meses completos y le suma los eventos extraordinarios planificados.
     */
    fun proyeccion(d: DatosFinancieros, hoy: LocalDate, horizonte: Int = 9, gastoActualEstimado: Double, ingresoReferencia: Double): Proyeccion {
        val historia = MotorPresupuesto.mesesCompletosConDatos(d, hoy, 3)
        val ingresoBase = if (historia.isNotEmpty() && historia.any { it.ingresos > 0 }) {
            historia.map { it.ingresos }.average()
        } else ingresoReferencia
        val gastoBase = if (historia.isNotEmpty()) historia.map { it.consumo }.average() else gastoActualEstimado
        val actual = YearMonth.from(hoy)
        var acumulado = 0.0
        val meses = (1..horizonte).map { i ->
            val mes = actual.plusMonths(i.toLong())
            val eventos = eventosDelMes(d.eventos, mes)
            val proyectado = MesProyectado(mes, ingresoBase, gastoBase, eventos, 0.0)
            acumulado += proyectado.libre
            proyectado.copy(saldoAcumulado = acumulado)
        }
        // Provisión: cada gasto extraordinario se divide por los meses que faltan para él.
        val provision = meses.flatMap { m -> m.eventos.filter { it.tipo == TipoMovimiento.GASTO }.map { m.mes to it } }
            .sumOf { (mes, evento) -> evento.monto / max(1L, mesesEntre(actual, mes)) }
        return Proyeccion(ingresoBase, gastoBase, historia.size, meses, provision)
    }

    /** Eventos de un mes: los de ese mes exacto y los anuales que caen en ese mes del año. */
    fun eventosDelMes(eventos: List<EventoPlanificado>, mes: YearMonth): List<EventoPlanificado> =
        eventos.filter { e ->
            val original = mesDeClave(e.mes)
            original == mes || (e.repetirAnual && original.monthValue == mes.monthValue && original.isBefore(mes))
        }

    /** Días que faltan para un evento (para mostrar "en 45 días"). */
    fun diasHasta(hoy: LocalDate, mes: YearMonth): Long = ChronoUnit.DAYS.between(hoy, mes.atDay(1)).coerceAtLeast(0)
}

package com.gabriel.cadapeso.dominio

import com.gabriel.cadapeso.datos.Deuda
import kotlin.math.pow

/**
 * Módulo de gestión de deudas: clasifica cada deuda (buena / mala / a vigilar) y simula
 * cómo cancelarlas con el método avalancha (primero la de tasa más alta) comparado con
 * bola de nieve (primero la de saldo más chico).
 */
object MotorDeudas {

    /** Máximo de meses a simular (50 años): si no termina antes, el plan no es viable. */
    private const val MAX_MESES = 600

    /**
     * Tasa real anual: descuenta la inflación. Con inflación alta (Argentina) una TNA de 40%
     * puede ser barata, y una de 16% muy cara si la inflación es 2%. Si el crédito ajusta por
     * UVA, la tasa cargada ya es real.
     */
    fun tasaReal(deuda: Deuda, inflacionAnual: Double): Double = when {
        deuda.ajustaPorInflacion || inflacionAnual <= 0 -> deuda.tasaAnual
        else -> ((1 + deuda.tasaAnual / 100) / (1 + inflacionAnual / 100) - 1) * 100
    }

    /** Tasa mensual efectiva en pesos (incluye la inflación en los créditos UVA). */
    fun tasaMensual(deuda: Deuda, inflacionAnual: Double): Double {
        val base = deuda.tasaAnual / 100 / 12
        return if (deuda.ajustaPorInflacion && inflacionAnual > 0) {
            (1 + base) * (1 + inflacionAnual / 100).pow(1.0 / 12) - 1
        } else base
    }

    fun clasificar(deuda: Deuda, inflacionAnual: Double, umbralTasaMala: Double): DeudaAnalizada {
        val real = tasaReal(deuda, inflacionAnual)
        val interesMensual = deuda.saldo * tasaMensual(deuda, inflacionAnual)
        val rinde = deuda.rendimientoActivo
        val (clase, motivo) = when {
            rinde != null && rinde > deuda.tasaAnual ->
                ClaseDeuda.BUENA to "Lo que financiaste rinde ${Formato.porcentaje(rinde, 1)} y la deuda cuesta ${Formato.porcentaje(deuda.tasaAnual, 1)}: es apalancamiento que suma."
            rinde != null ->
                ClaseDeuda.MALA to "Lo que financiaste rinde menos (${Formato.porcentaje(rinde, 1)}) que lo que cuesta la deuda (${Formato.porcentaje(deuda.tasaAnual, 1)}): te resta."
            real > umbralTasaMala ->
                ClaseDeuda.MALA to "Tasa real de ${Formato.porcentaje(real, 1)} anual, por encima del ${Formato.porcentaje(umbralTasaMala)}: conviene liquidarla con prioridad."
            deuda.tipo.esConsumo ->
                ClaseDeuda.NEUTRA to "Deuda de consumo con tasa moderada: no es urgente, pero no financia nada que rinda."
            else ->
                ClaseDeuda.NEUTRA to "Financia un bien o tu formación con tasa moderada. Vigilá que la cuota no pase del 30% de tu ingreso."
        }
        // Trampa del pago mínimo: si la cuota apenas cubre los intereses, el saldo no baja.
        val trampa = deuda.saldo > 0 && deuda.cuotaMensual <= interesMensual * 1.2
        return DeudaAnalizada(deuda, clase, real, interesMensual, trampa, motivo)
    }

    fun plan(deudas: List<Deuda>, inflacionAnual: Double, umbralTasaMala: Double, pagoExtra: Double): PlanDeudas {
        val activas = deudas.filter { it.saldo > 0.01 }
        val analizadas = activas.map { clasificar(it, inflacionAnual, umbralTasaMala) }
            .sortedByDescending { tasaMensual(it.deuda, inflacionAnual) }
        return PlanDeudas(
            deudas = analizadas,
            pagoExtra = pagoExtra,
            avalancha = simular(activas, inflacionAnual, pagoExtra, compareByDescending { tasaMensual(it, inflacionAnual) }),
            bolaDeNieve = simular(activas, inflacionAnual, pagoExtra, compareBy { it.saldo }),
        )
    }

    /**
     * Simulación mes a mes:
     *  1. cada deuda genera su interés del mes;
     *  2. se paga la cuota mínima de cada una;
     *  3. todo lo que sobra del presupuesto (el pago extra + las cuotas de las deudas que ya
     *     se cancelaron) va a la deuda prioritaria según [prioridad]. Ese "efecto bola" es
     *     lo que acelera el plan.
     */
    fun simular(deudas: List<Deuda>, inflacionAnual: Double, pagoExtra: Double, prioridad: Comparator<Deuda>): ResultadoPlan {
        if (deudas.isEmpty()) return ResultadoPlan(0, 0.0, emptyList(), true)
        val orden = deudas.sortedWith(prioridad)
        val saldos = orden.map { it.saldo }.toDoubleArray()
        val tasas = orden.map { tasaMensual(it, inflacionAnual) }
        val interesPorDeuda = DoubleArray(orden.size)
        val mesLiquidada = IntArray(orden.size) { -1 }
        val presupuesto = orden.sumOf { it.cuotaMensual } + pagoExtra.coerceAtLeast(0.0)
        var mes = 0
        var interesTotal = 0.0
        while (saldos.any { it > 0.01 } && mes < MAX_MESES) {
            mes++
            for (i in orden.indices) if (saldos[i] > 0.01) {
                val interes = saldos[i] * tasas[i]
                saldos[i] += interes
                interesPorDeuda[i] += interes
                interesTotal += interes
            }
            var disponible = presupuesto
            for (i in orden.indices) if (saldos[i] > 0.01) {
                val pago = minOf(orden[i].cuotaMensual, saldos[i], disponible)
                saldos[i] -= pago
                disponible -= pago
            }
            for (i in orden.indices) {
                if (disponible <= 0.01) break
                if (saldos[i] > 0.01) {
                    val pago = minOf(disponible, saldos[i])
                    saldos[i] -= pago
                    disponible -= pago
                }
            }
            for (i in orden.indices) if (saldos[i] <= 0.01 && mesLiquidada[i] < 0) mesLiquidada[i] = mes
        }
        val viable = mesLiquidada.all { it > 0 }
        val pasos = orden.indices
            .map { PasoPlan(orden[it], if (mesLiquidada[it] > 0) mesLiquidada[it] else MAX_MESES, interesPorDeuda[it]) }
            .sortedBy { it.mesLiquidacion }
        return ResultadoPlan(if (viable) mes else MAX_MESES, interesTotal, pasos, viable)
    }
}

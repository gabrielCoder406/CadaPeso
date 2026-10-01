package com.gabriel.cadapeso.dominio

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

/**
 * Punto de entrada del motor: recibe TODOS los datos y devuelve un [Analisis] completo.
 * Se recalcula cada vez que cambia algo en la base de datos (con unos miles de movimientos
 * tarda milisegundos), así todas las pantallas y la IA ven siempre los mismos números.
 */
object MotorFinanciero {

    fun analizar(d: DatosFinancieros, hoy: LocalDate, ahoraMillis: Long = System.currentTimeMillis()): Analisis {
        val mesActual = YearMonth.from(hoy)
        val mes = MotorPresupuesto.resumenMes(d, mesActual)
        val anterior = MotorPresupuesto.resumenMes(d, mesActual.minusMonths(1))
        val presupuesto = MotorPresupuesto.estadoPresupuesto(d, mesActual, mes)
        val ingresoRef = presupuesto.ingresoAAsignar
        val semana = MotorPresupuesto.resumenSemana(d, hoy, presupuesto)
        val perfil = Perfilado.perfil(d.perfil.edad, d.perfil.puntajeConducta, d.perfil.puntajeRiesgo)
        val historia = MotorPresupuesto.mesesCompletosConDatos(d, hoy, 3)

        // --- Gasto mensual típico (consumo) y gasto básico (necesidades + cuotas) ---------
        // Con historia se usa el promedio de los últimos 3 meses completos. Sin historia, se
        // estima con el presupuesto asignado o, en último caso, con una fracción del ingreso
        // (estimación conservadora para no subestimar el fondo de emergencia).
        val asignadoConsumo = presupuesto.lineas.filter { it.categoria.grupo != GrupoGasto.AHORRO }.sumOf { it.asignado }
        val asignadoBasico = presupuesto.lineas
            .filter { it.categoria.grupo == GrupoGasto.NECESIDAD || it.categoria.grupo == GrupoGasto.DEUDA }
            .sumOf { it.asignado }
        val basicoMes = mes.grupo(GrupoGasto.NECESIDAD) + mes.grupo(GrupoGasto.DEUDA)
        val gastoMensual = when {
            historia.isNotEmpty() -> historia.map { it.consumo }.average()
            asignadoConsumo > 0 -> asignadoConsumo
            else -> max(mes.consumo, ingresoRef * 0.7)
        }
        val gastoBasico = when {
            historia.isNotEmpty() -> historia.map { it.grupo(GrupoGasto.NECESIDAD) + it.grupo(GrupoGasto.DEUDA) }.average()
            asignadoBasico > 0 -> asignadoBasico
            else -> max(basicoMes, ingresoRef * 0.5)
        }

        // --- Indicadores estructurales --------------------------------------------------
        val base = historia.ifEmpty { listOf(mes) }
        val consumoBase = base.sumOf { it.consumo }
        val capacidadReaccion = if (consumoBase > 0) base.sumOf { it.recortable } / consumoBase * 100 else 0.0
        val porcentajeNecesidades = if (historia.isNotEmpty()) {
            historia.map { r ->
                val ing = MotorPresupuesto.ingresoReferencia(d, r.mes, r.ingresos)
                if (ing > 0) (r.grupo(GrupoGasto.NECESIDAD) + r.grupo(GrupoGasto.DEUDA)) / ing * 100 else 0.0
            }.average()
        } else if (ingresoRef > 0) basicoMes / ingresoRef * 100 else 0.0
        val ingresoHistorico = historia.filter { it.ingresos > 0 }.map { it.ingresos }.ifEmpty { listOf(ingresoRef) }.average()

        val cuotasDeuda = d.deudas.filter { it.saldo > 0 }.sumOf { it.cuotaMensual }.let { if (it > 0) it else mes.pagoDeudas }
        val patrimonio = MotorAhorro.patrimonio(d.activos, d.deudas.sumOf { it.saldo })
        val indicadores = Indicadores(
            ingresoReferencia = ingresoRef,
            gastoMensualPromedio = gastoMensual,
            gastoBasicoMensual = gastoBasico,
            capacidadReaccion = capacidadReaccion,
            ratioEndeudamiento = if (ingresoRef > 0) cuotasDeuda / ingresoRef * 100 else 0.0,
            usoTarjeta = if (ingresoRef > 0) mes.conTarjeta / ingresoRef * 100 else 0.0,
            tasaAhorro = if (ingresoRef > 0) mes.ahorro / ingresoRef * 100 else 0.0,
            porcentajeNecesidades = porcentajeNecesidades,
            mesesSupervivencia = if (gastoBasico > 0) patrimonio.activosLiquidos / gastoBasico else 0.0,
            capacidadAhorroMensual = max(0.0, ingresoHistorico - gastoMensual),
        )

        val umbralHormiga = MotorFugas.umbralHormiga(d, ingresoRef)
        val parcial = Analisis(
            hoy = hoy,
            ahoraMillis = ahoraMillis,
            datos = d,
            mes = mes,
            mesAnterior = anterior,
            presupuesto = presupuesto,
            semana = semana,
            fugas = MotorFugas.gastosHormiga(d, hoy, umbralHormiga, perfil.riesgoEfectivo.rendimientoRealAnual),
            suscripciones = MotorFugas.suscripciones(d, hoy),
            proyeccion = MotorFugas.proyeccion(d, hoy, 9, gastoMensual, ingresoRef),
            patrimonio = patrimonio,
            indicadores = indicadores,
            fondoEmergencia = MotorAhorro.fondoEmergencia(d, gastoBasico),
            pagatePrimero = MotorAhorro.pagatePrimero(d, hoy, mes, anterior, ingresoRef),
            metas = MotorAhorro.metas(d, hoy, indicadores.capacidadAhorroMensual),
            deudas = MotorDeudas.plan(d.deudas, d.perfil.inflacionAnual, d.perfil.umbralTasaMala, 0.0),
            hojaDeRuta = HojaDeRuta(emptyList()),
            planAjuste = null,
            perfil = perfil,
            consejos = emptyList(),
            umbralHormiga = umbralHormiga,
            umbralImpulso = MotorConsejos.umbralImpulso(d, ingresoRef, perfil),
            ahorroPorImpulsosEvitados = d.compras.filter { it.estado == EstadoCompra.DESCARTADA }.sumOf { it.monto },
        )
        // La hoja de ruta, el plan de ajuste y los consejos se calculan sobre el análisis parcial.
        val conRuta = parcial.copy(hojaDeRuta = MotorConsejos.hojaDeRuta(parcial), planAjuste = MotorConsejos.planAjuste(parcial))
        return conRuta.copy(consejos = MotorConsejos.generar(conRuta))
    }
}

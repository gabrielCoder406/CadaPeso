package com.gabriel.cadapeso.dominio

import com.gabriel.cadapeso.datos.Activo
import com.gabriel.cadapeso.datos.CategoriasBase
import com.gabriel.cadapeso.datos.DatosEjemplo
import com.gabriel.cadapeso.datos.Deuda
import com.gabriel.cadapeso.datos.Meta
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.datos.Perfil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MotoresTest {

    private val categorias = CategoriasBase.lista().mapIndexed { i, c -> c.copy(id = i + 1L) }
    private val hoy = LocalDate.of(2026, 10, 20)
    private fun cat(nombre: String) = categorias.first { it.nombre == nombre }.id

    private fun gasto(fecha: LocalDate, monto: Double, categoria: String, desc: String = categoria, medio: MedioPago = MedioPago.DEBITO) =
        Movimiento(fecha = fecha, monto = monto, tipo = TipoMovimiento.GASTO, categoriaId = cat(categoria), descripcion = desc, medioPago = medio)

    private fun ingreso(fecha: LocalDate, monto: Double) =
        Movimiento(fecha = fecha, monto = monto, tipo = TipoMovimiento.INGRESO, categoriaId = cat("Sueldo"), descripcion = "Sueldo")

    // ------------------------------------------------------------------ Presupuesto

    @Test
    fun resumenMesSeparaAhorroDelConsumo() {
        val d = DatosFinancieros(
            movimientos = listOf(
                ingreso(hoy.withDayOfMonth(5), 1_000_000.0),
                gasto(hoy.withDayOfMonth(6), 300_000.0, "Vivienda"),
                gasto(hoy.withDayOfMonth(6), 100_000.0, "Ahorro e inversión"),
                gasto(hoy.withDayOfMonth(7), 50_000.0, "Comida afuera y delivery", medio = MedioPago.CREDITO),
            ),
            categorias = categorias,
        )
        val r = MotorPresupuesto.resumenMes(d, YearMonth.from(hoy))
        assertEquals(450_000.0, r.gastos, 0.01)
        assertEquals(100_000.0, r.ahorro, 0.01)
        assertEquals(350_000.0, r.consumo, 0.01)
        assertEquals(50_000.0, r.conTarjeta, 0.01)
        assertEquals(50_000.0, r.recortable, 0.01)
        assertEquals(550_000.0, r.balance, 0.01)
    }

    @Test
    fun sugerenciaAsignaCadaPeso() {
        for (regla in ReglaPresupuesto.entries) {
            val d = DatosFinancieros(
                categorias = categorias,
                perfil = Perfil(regla = regla),
                deudas = listOf(Deuda(nombre = "Préstamo", tipo = TipoDeuda.PRESTAMO_PERSONAL, saldo = 500_000.0, tasaAnual = 60.0, cuotaMensual = 70_000.0, actualizado = hoy)),
            )
            val asignaciones = MotorPresupuesto.sugerirAsignaciones(d, YearMonth.from(hoy), 1_234_567.0)
            assertEquals("Regla $regla", 1_234_567.0, asignaciones.sumOf { it.monto }, 0.01)
            // La cuota de la deuda queda cubierta.
            assertTrue(asignaciones.first { it.categoriaId == cat("Pago de deudas") }.monto >= 70_000.0)
            // El ahorro recibe su partida.
            assertTrue(asignaciones.first { it.categoriaId == cat("Ahorro e inversión") }.monto > 100_000.0)
        }
    }

    @Test
    fun panelSemanalMideSoloGastoVariable() {
        val lunes = hoy.inicioSemana()
        val d = DatosFinancieros(
            movimientos = listOf(
                gasto(lunes, 380_000.0, "Vivienda"), // fijo: no cuenta
                gasto(lunes, 20_000.0, "Supermercado"),
                gasto(lunes.plusDays(1), 10_000.0, "Café y kiosco"),
                gasto(lunes.minusDays(3), 7_000.0, "Café y kiosco"), // semana anterior
            ),
            categorias = categorias,
            perfil = Perfil(ingresoMensual = 1_000_000.0),
        )
        val mes = MotorPresupuesto.resumenMes(d, YearMonth.from(hoy))
        val semana = MotorPresupuesto.resumenSemana(d, hoy, MotorPresupuesto.estadoPresupuesto(d, YearMonth.from(hoy), mes))
        assertEquals(30_000.0, semana.total, 0.01)
        assertEquals(7_000.0, semana.totalSemanaAnterior, 0.01)
        assertEquals(20_000.0, semana.gastoPorDia[0], 0.01)
        assertTrue(semana.presupuestoSemanal > 0)
    }

    // ------------------------------------------------------------------ Fugas

    @Test
    fun detectaGastoHormigaYSuscripciones() {
        val movs = mutableListOf<Movimiento>()
        for (i in 0 until 10) movs += gasto(hoy.minusDays(i * 2L), 3_000.0, "Café y kiosco", "Café")
        for (m in 0..2) {
            movs += gasto(hoy.minusMonths(m.toLong()).withDayOfMonth(8), 11_999.0, "Suscripciones", "Netflix")
            movs += gasto(hoy.minusMonths(m.toLong()).withDayOfMonth(3), 380_000.0, "Vivienda", "Alquiler")
        }
        val d = DatosFinancieros(movimientos = movs, categorias = categorias, perfil = Perfil(ingresoMensual = 1_000_000.0))
        val hormiga = MotorFugas.gastosHormiga(d, hoy, MotorFugas.umbralHormiga(d, 1_000_000.0), 5.0)
        assertEquals(1, hormiga.size)
        assertEquals("Café y kiosco", hormiga[0].categoria.nombre)
        assertEquals(10, hormiga[0].cantidadMes)
        assertEquals(360_000.0, hormiga[0].totalAnual, 0.01)
        assertTrue(hormiga[0].costoOportunidad10Anios > 30_000.0 * 120) // el interés compuesto suma

        val subs = MotorFugas.suscripciones(d, hoy)
        assertEquals(listOf("Netflix"), subs.map { it.nombre }) // el alquiler no es recortable
    }

    @Test
    fun proyeccionSumaEventosYCalculaProvision() {
        val movs = (1..3).flatMap { m ->
            val mes = hoy.minusMonths(m.toLong())
            listOf(ingreso(mes.withDayOfMonth(5), 1_000_000.0), gasto(mes.withDayOfMonth(6), 800_000.0, "Vivienda"))
        }
        val evento = com.gabriel.cadapeso.datos.EventoPlanificado(nombre = "Vacaciones", monto = 900_000.0, mes = YearMonth.from(hoy).plusMonths(3).clave())
        val d = DatosFinancieros(movimientos = movs, categorias = categorias, eventos = listOf(evento))
        val p = MotorFugas.proyeccion(d, hoy, 9, 0.0, 0.0)
        assertEquals(1_000_000.0, p.ingresoBase, 0.01)
        assertEquals(800_000.0, p.gastoBase, 0.01)
        assertEquals(9, p.meses.size)
        assertEquals(-700_000.0, p.meses[2].libre, 0.01) // mes de las vacaciones
        assertEquals(300_000.0, p.provisionMensual, 0.01) // 900.000 en 3 meses
        assertEquals(1, p.mesesEnRojo.size)
    }

    // ------------------------------------------------------------------ Deudas

    @Test
    fun avalanchaPagaMenosInteresesQueBolaDeNieve() {
        val deudas = listOf(
            Deuda(id = 1, nombre = "Tarjeta", tipo = TipoDeuda.TARJETA, saldo = 400_000.0, tasaAnual = 100.0, cuotaMensual = 40_000.0, actualizado = hoy),
            Deuda(id = 2, nombre = "Préstamo", tipo = TipoDeuda.PRESTAMO_PERSONAL, saldo = 150_000.0, tasaAnual = 40.0, cuotaMensual = 15_000.0, actualizado = hoy),
        )
        val plan = MotorDeudas.plan(deudas, 0.0, 16.0, 50_000.0)
        assertTrue(plan.avalancha.esViable)
        assertEquals("Tarjeta", plan.avalancha.orden.first().deuda.nombre)
        assertTrue(plan.avalancha.interesTotal < plan.bolaDeNieve.interesTotal)
        assertTrue(plan.ahorroAvalancha > 0)
    }

    @Test
    fun clasificaDeudasYDetectaTrampaDelPagoMinimo() {
        val tarjeta = Deuda(nombre = "Tarjeta", tipo = TipoDeuda.TARJETA, saldo = 520_000.0, tasaAnual = 98.0, cuotaMensual = 35_000.0, actualizado = hoy)
        val r = MotorDeudas.clasificar(tarjeta, 0.0, 16.0)
        assertEquals(ClaseDeuda.MALA, r.clase)
        assertTrue(r.trampaPagoMinimo)

        val negocio = Deuda(nombre = "Horno", tipo = TipoDeuda.NEGOCIO, saldo = 1_000_000.0, tasaAnual = 30.0, cuotaMensual = 80_000.0, rendimientoActivo = 55.0, actualizado = hoy)
        assertEquals(ClaseDeuda.BUENA, MotorDeudas.clasificar(negocio, 0.0, 16.0).clase)

        // Con inflación de 30%, una TNA de 40% es una tasa real de ~7,7%: deja de ser "mala".
        val hipoteca = Deuda(nombre = "Casa", tipo = TipoDeuda.HIPOTECARIO, saldo = 10_000_000.0, tasaAnual = 40.0, cuotaMensual = 400_000.0, actualizado = hoy)
        assertEquals(7.69, MotorDeudas.tasaReal(hipoteca, 30.0), 0.01)
        assertEquals(ClaseDeuda.NEUTRA, MotorDeudas.clasificar(hipoteca, 30.0, 16.0).clase)

        // Si la cuota no cubre los intereses, el plan no es viable.
        val imposible = MotorDeudas.simular(listOf(tarjeta.copy(cuotaMensual = 10_000.0)), 0.0, 0.0, compareBy { it.saldo })
        assertFalse(imposible.esViable)
    }

    // ------------------------------------------------------------------ Ahorro

    @Test
    fun interesCompuestoYDCA() {
        // 10.000 por mes durante 1 año al 0% = 120.000.
        assertEquals(120_000.0, MotorAhorro.valorFuturo(10_000.0, 0.0, 1), 0.01)
        val puntos = MotorAhorro.simularDCA(10_000.0, 0.0, 6.0, 10)
        assertEquals(11, puntos.size)
        assertEquals(1_200_000.0, puntos.last().aportado, 0.01)
        assertTrue(puntos.last().valor > 1_600_000.0)
    }

    @Test
    fun metaSmartConPlanIncremental() {
        val meta = Meta(
            id = 1, nombre = "Viaje", especifica = "Una semana en Bariloche", relevancia = "Descansar",
            montoObjetivo = 200_000.0, fechaLimite = hoy.plusMonths(6), aporteInicial = 10_000.0, incremento = 1_000.0,
            frecuencia = FrecuenciaAhorro.SEMANAL, creada = hoy,
        )
        val d = DatosFinancieros(
            categorias = categorias, metas = listOf(meta),
            movimientos = listOf(gasto(hoy, 20_000.0, "Ahorro e inversión").copy(metaId = 1)),
        )
        val estado = MotorAhorro.metas(d, hoy, capacidadAhorroMensual = 100_000.0).single()
        assertEquals(20_000.0, estado.acumulado, 0.01)
        assertEquals(5, estado.smart.cumplidos)
        assertFalse(estado.enRiesgo)
        // 10k + 11k + ... hasta juntar 180k: 10 cuotas semanales (145k) no alcanzan, 12 sí (186k).
        assertEquals(hoy.plusWeeks(11), estado.fechaEstimadaPlan)
        assertEquals(6, estado.proximasCuotas.size)

        val sinCapacidad = MotorAhorro.metas(d, hoy, capacidadAhorroMensual = 1_000.0).single()
        assertTrue(sinCapacidad.enRiesgo)
        assertFalse(sinCapacidad.smart.alcanzable)
    }

    @Test
    fun fondoDeEmergenciaSegunTipoDeIngreso() {
        val activos = listOf(Activo(nombre = "Remunerada", tipo = TipoActivo.AHORRO_LIQUIDO, valor = 900_000.0, liquido = true, esFondoEmergencia = true, actualizado = hoy))
        val estable = MotorAhorro.fondoEmergencia(DatosFinancieros(activos = activos), 300_000.0)
        assertEquals(3.0, estable.mesesCubiertos, 0.001)
        assertEquals(EstadoFondo.SALUDABLE, estable.estado)
        val autonomo = MotorAhorro.fondoEmergencia(DatosFinancieros(activos = activos, perfil = Perfil(tipoIngreso = TipoIngreso.VARIABLE)), 300_000.0)
        assertEquals(EstadoFondo.EN_CONSTRUCCION, autonomo.estado)
        assertEquals(900_000.0, autonomo.faltanteMin, 0.01)
    }

    // ------------------------------------------------------------------ Perfilado

    @Test
    fun perfilado() {
        assertEquals(100, Perfilado.puntaje(Perfilado.TEST_CONDUCTA, listOf(4, 4, 0, 4, 0, 4)))
        assertEquals(PerfilConducta.IMPULSIVO, Perfilado.conducta(80))
        assertEquals(PerfilRiesgo.CONSERVADOR, Perfilado.riesgo(Perfilado.puntaje(Perfilado.TEST_RIESGO, listOf(0, 0, 1, 0, 1, 0))))
        assertEquals(EtapaVida.JUVENTUD, Perfilado.etapa(27))
        assertEquals(EtapaVida.CONSOLIDACION, Perfilado.etapa(45))
        assertEquals(EtapaVida.PRE_RETIRO, Perfilado.etapa(62))
    }

    // ------------------------------------------------------------------ Análisis completo

    @Test
    fun analisisCompletoConDatosDeEjemplo() {
        val paquete = DatosEjemplo.generar(categorias, hoy)
        val sinPresupuesto = DatosFinancieros(
            movimientos = paquete.movimientos, categorias = categorias,
            activos = paquete.activos.mapIndexed { i, a -> a.copy(id = i + 1L) },
            deudas = paquete.deudas.mapIndexed { i, x -> x.copy(id = i + 1L) },
            metas = paquete.metas.mapIndexed { i, m -> m.copy(id = i + 1L) },
            eventos = paquete.eventos,
            perfil = Perfil(ingresoMensual = paquete.ingresoMensual, edad = 29),
        )
        // Igual que al cargar los datos de ejemplo en la app: presupuesto sugerido para el mes.
        val mesActual = YearMonth.from(hoy)
        val ingresoMes = MotorPresupuesto.ingresoReferencia(sinPresupuesto, mesActual, MotorPresupuesto.resumenMes(sinPresupuesto, mesActual).ingresos)
        val d = sinPresupuesto.copy(asignaciones = MotorPresupuesto.sugerirAsignaciones(sinPresupuesto, mesActual, ingresoMes))
        val a = MotorFinanciero.analizar(d, hoy)
        assertTrue("Con el presupuesto sugerido no queda plata sin tarea", a.consejos.none { it.id == "sin_asignar" })
        assertTrue("La avalancha arranca por la tasa más alta", a.consejos.first { it.id == "deuda_mala" }.mensaje.contains("Tarjeta Visa"))
        assertTrue("Capacidad de ahorro positiva", a.indicadores.capacidadAhorroMensual > 0)
        assertTrue(a.mes.tieneDatos)
        assertEquals(3, a.proyeccion.mesesDeHistoria)
        assertTrue("Debería detectar el café como hormiga", a.fugas.any { it.categoria.nombre == "Café y kiosco" })
        assertTrue(a.suscripciones.any { it.nombre == "Netflix" })
        assertTrue(a.deudas.deudas.any { it.trampaPagoMinimo })
        assertEquals(EstadoFondo.CRITICO, a.fondoEmergencia.estado)
        assertNotNull("Necesidades > 80% => plan de ajuste", a.planAjuste)
        // Ahorra 50.000 de los 125.000 (10%) que debería separar: sigue en el paso 1.
        assertEquals(1, a.hojaDeRuta.pasoActual)
        assertTrue(a.consejos.isNotEmpty())
        assertEquals(Severidad.ALTA, a.consejos.first().severidad)
        assertTrue(a.indicadores.capacidadReaccion in 1.0..100.0)
        assertEquals(EtapaVida.JUVENTUD, a.perfil.etapa)
        // Las consignas de la IA salen del análisis: imprimirlas ayuda a revisarlas.
        println("Consejos generados:")
        a.consejos.forEach { println(" - [${it.severidad}] ${it.titulo}: ${it.mensaje}") }
    }
}

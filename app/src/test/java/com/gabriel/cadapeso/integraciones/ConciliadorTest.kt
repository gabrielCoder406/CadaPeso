package com.gabriel.cadapeso.integraciones

import com.gabriel.cadapeso.datos.CategoriasBase
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.datos.MovimientoDetectado
import com.gabriel.cadapeso.dominio.CanalDeteccion
import com.gabriel.cadapeso.dominio.EstadoDetectado
import com.gabriel.cadapeso.dominio.EstadoTransaccion
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.TransaccionExterna
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConciliadorTest {

    private val categorias = CategoriasBase.lista().mapIndexed { i, c -> c.copy(id = i + 1L) }
    private fun cat(nombre: String) = categorias.first { it.nombre == nombre }.id
    private val hoy = LocalDate.of(2026, 10, 11)
    private val ahora = 1_000_000L

    private fun transaccion(
        id: String = "MERCADO_PAGO:1",
        monto: Double = 3500.0,
        descripcion: String = "Café Martínez",
        tipo: TipoMovimiento = TipoMovimiento.GASTO,
        fecha: LocalDate = hoy,
        categoria: String? = null,
        estado: EstadoTransaccion = EstadoTransaccion.CONFIRMADA,
        origen: OrigenMovimiento = OrigenMovimiento.MERCADO_PAGO,
        propia: Boolean = false,
        moneda: String = "ARS",
    ) = TransaccionExterna(
        idTransaccion = id, origen = origen, tipo = tipo, monto = monto, moneda = moneda, fecha = fecha,
        descripcion = descripcion, categoria = categoria, medioPago = MedioPago.BILLETERA, estado = estado, transferenciaPropia = propia,
    )

    private fun ctx(recientes: List<MovimientoDetectado> = emptyList(), movimientos: List<Movimiento> = emptyList()) =
        ContextoImportacion(categorias, recientes, movimientos)

    private fun detectado(t: TransaccionExterna, canal: CanalDeteccion, id: Long, estado: EstadoDetectado = EstadoDetectado.PENDIENTE, movimientoId: Long? = null) =
        Conciliador.aDetectado(t, canal, ctx(), ahora).copy(id = id, estado = estado, movimientoId = movimientoId)

    @Test
    fun categoriaSugerida() {
        // La pista del proveedor gana si coincide con una categoría tuya…
        assertEquals(cat("Servicios"), Conciliador.categoriaSugerida(transaccion(descripcion = "Recarga de celular", categoria = "Servicios"), categorias)?.id)
        // …si no, el Categorizador reconoce el comercio.
        assertEquals(cat("Supermercado"), Conciliador.categoriaSugerida(transaccion(descripcion = "Supermercado Dia"), categorias)?.id)
        assertEquals(cat("Café y kiosco"), Conciliador.categoriaSugerida(transaccion(descripcion = "Café Martínez"), categorias)?.id)
        assertNull(Conciliador.categoriaSugerida(transaccion(descripcion = "Xyz"), categorias))
    }

    @Test
    fun loNuevoEntraPendiente() {
        val accion = Conciliador.decidir(transaccion(), CanalDeteccion.SERVIDOR, null, ctx(), ahora)
        val d = (accion as AccionImportacion.Insertar).detectado
        assertEquals(EstadoDetectado.PENDIENTE, d.estado)
        assertEquals("MERCADO_PAGO:1", d.idExterno)
        assertEquals(cat("Café y kiosco"), d.categoriaId)
        assertNull(d.duplicadoDeId)
    }

    @Test
    fun unPagoQueYaVieneAnuladoSeIgnora() {
        val accion = Conciliador.decidir(transaccion(estado = EstadoTransaccion.ANULADA), CanalDeteccion.SERVIDOR, null, ctx(), ahora)
        assertEquals(AccionImportacion.Ignorar, accion)
    }

    @Test
    fun avisaSiYaLoCargasteAMano() {
        val manual = Movimiento(id = 77, fecha = hoy.minusDays(1), monto = 3500.0, tipo = TipoMovimiento.GASTO, categoriaId = cat("Café y kiosco"), descripcion = "cafe")
        val d = (Conciliador.decidir(transaccion(), CanalDeteccion.SERVIDOR, null, ctx(movimientos = listOf(manual)), ahora) as AccionImportacion.Insertar).detectado
        assertEquals(77L, d.duplicadoDeId)
        // Si ese movimiento ya está vinculado a otro detectado, no se vuelve a ofrecer.
        val vinculado = detectado(transaccion(id = "MERCADO_PAGO:9"), CanalDeteccion.SERVIDOR, id = 5, estado = EstadoDetectado.ACEPTADO, movimientoId = 77)
        val d2 = (Conciliador.decidir(transaccion(), CanalDeteccion.SERVIDOR, null, ctx(listOf(vinculado), listOf(manual)), ahora) as AccionImportacion.Insertar).detectado
        assertNull(d2.duplicadoDeId)
        // Un movimiento importado (no manual) no cuenta como duplicado.
        val importado = manual.copy(origen = OrigenMovimiento.MERCADO_PAGO)
        val d3 = (Conciliador.decidir(transaccion(), CanalDeteccion.SERVIDOR, null, ctx(movimientos = listOf(importado)), ahora) as AccionImportacion.Insertar).detectado
        assertNull(d3.duplicadoDeId)
    }

    @Test
    fun elServidorReemplazaALaNotificacionPendiente() {
        val porNotificacion = detectado(transaccion(id = "MERCADO_PAGO:N:abc", descripcion = "Pago con Mercado Pago"), CanalDeteccion.NOTIFICACION, id = 10)
        val accion = Conciliador.decidir(transaccion(id = "MERCADO_PAGO:555"), CanalDeteccion.SERVIDOR, null, ctx(listOf(porNotificacion)), ahora)
        val d = (accion as AccionImportacion.Actualizar).detectado
        assertEquals(10L, d.id)
        assertEquals("MERCADO_PAGO:555", d.idExterno)
        assertEquals(CanalDeteccion.SERVIDOR, d.canal)
        assertEquals("Café Martínez", d.descripcion)
    }

    @Test
    fun laNotificacionQueLlegaDespuesDelServidorNoDuplica() {
        val porServidor = detectado(transaccion(id = "MERCADO_PAGO:555"), CanalDeteccion.SERVIDOR, id = 10)
        val accion = Conciliador.decidir(transaccion(id = "MERCADO_PAGO:N:abc"), CanalDeteccion.NOTIFICACION, null, ctx(listOf(porServidor)), ahora)
        assertEquals(EstadoDetectado.DESCARTADO, (accion as AccionImportacion.Insertar).detectado.estado)
    }

    @Test
    fun siLaNotificacionYaSeAceptoNoSeCreaOtroMovimiento() {
        val aceptado = detectado(transaccion(id = "MERCADO_PAGO:N:abc"), CanalDeteccion.NOTIFICACION, id = 10, estado = EstadoDetectado.ACEPTADO, movimientoId = 99)
        val d = (Conciliador.decidir(transaccion(id = "MERCADO_PAGO:555"), CanalDeteccion.SERVIDOR, null, ctx(listOf(aceptado)), ahora) as AccionImportacion.Insertar).detectado
        assertEquals(EstadoDetectado.ACEPTADO, d.estado)
        assertEquals(99L, d.movimientoId)
    }

    @Test
    fun gemelosSoloConMismoOrigenMontoYFechaCercana() {
        val n = detectado(transaccion(id = "GALICIA:N:x", origen = OrigenMovimiento.GALICIA), CanalDeteccion.NOTIFICACION, id = 1)
        assertNull(Conciliador.gemelo(transaccion(id = "MERCADO_PAGO:2"), CanalDeteccion.SERVIDOR, listOf(n)))
        assertNull(Conciliador.gemelo(transaccion(id = "GALICIA:2", origen = OrigenMovimiento.GALICIA, monto = 3600.0), CanalDeteccion.SERVIDOR, listOf(n)))
        assertNull(Conciliador.gemelo(transaccion(id = "GALICIA:2", origen = OrigenMovimiento.GALICIA, fecha = hoy.plusDays(5)), CanalDeteccion.SERVIDOR, listOf(n)))
        assertEquals(1L, Conciliador.gemelo(transaccion(id = "GALICIA:2", origen = OrigenMovimiento.GALICIA, fecha = hoy.plusDays(1)), CanalDeteccion.SERVIDOR, listOf(n))?.id)
    }

    @Test
    fun cambiosDeUnPendiente() {
        val pendiente = detectado(transaccion(), CanalDeteccion.SERVIDOR, id = 3)
        val anulado = Conciliador.decidir(transaccion(estado = EstadoTransaccion.ANULADA), CanalDeteccion.SERVIDOR, pendiente, ctx(), ahora)
        assertEquals(EstadoDetectado.DESCARTADO, (anulado as AccionImportacion.Actualizar).detectado.estado)
        val corregido = Conciliador.decidir(transaccion(monto = 3000.0), CanalDeteccion.SERVIDOR, pendiente, ctx(), ahora)
        assertEquals(3000.0, (corregido as AccionImportacion.Actualizar).detectado.monto, 0.001)
        val descartado = pendiente.copy(estado = EstadoDetectado.DESCARTADO)
        assertEquals(AccionImportacion.Ignorar, Conciliador.decidir(transaccion(), CanalDeteccion.SERVIDOR, descartado, ctx(), ahora))
    }

    @Test
    fun laDevolucionDeUnPagoAceptadoSeProponeComoIngreso() {
        val aceptado = detectado(transaccion(monto = 1000.0), CanalDeteccion.SERVIDOR, id = 3, estado = EstadoDetectado.ACEPTADO, movimientoId = 50)
        // Devolución parcial: el pago ahora vale 600 → se proponen 400 de ingreso.
        val parcial = (Conciliador.decidir(transaccion(monto = 600.0), CanalDeteccion.SERVIDOR, aceptado, ctx(), ahora) as AccionImportacion.Insertar).detectado
        assertEquals(TipoMovimiento.INGRESO, parcial.tipo)
        assertEquals(400.0, parcial.monto, 0.001)
        assertEquals("MERCADO_PAGO:1:devolucion:40000", parcial.idExterno)
        assertEquals(cat("Otros ingresos"), parcial.categoriaId)
        assertTrue(parcial.descripcion.startsWith("Devolución"))
        // Después se anula del todo: solo falta la diferencia (600).
        val total = (Conciliador.decidir(transaccion(estado = EstadoTransaccion.ANULADA), CanalDeteccion.SERVIDOR, aceptado, ctx(listOf(parcial)), ahora) as AccionImportacion.Insertar).detectado
        assertEquals(600.0, total.monto, 0.001)
        // Sin cambios de monto, no pasa nada.
        assertEquals(AccionImportacion.Ignorar, Conciliador.decidir(transaccion(monto = 1000.0), CanalDeteccion.SERVIDOR, aceptado, ctx(), ahora))
    }

    @Test
    fun soloSeAceptaSinRevisarLoSeguro() {
        val ok = detectado(transaccion(), CanalDeteccion.SERVIDOR, id = 1)
        assertTrue(Conciliador.aceptableSinRevision(ok))
        assertFalse(Conciliador.aceptableSinRevision(ok.copy(canal = CanalDeteccion.NOTIFICACION)))
        assertFalse(Conciliador.aceptableSinRevision(ok.copy(transferenciaPropia = true)))
        assertFalse(Conciliador.aceptableSinRevision(ok.copy(duplicadoDeId = 4)))
        assertFalse(Conciliador.aceptableSinRevision(ok.copy(moneda = "USD")))
        assertFalse(Conciliador.aceptableSinRevision(ok.copy(categoriaId = null)))
    }

    @Test
    fun alAceptarSeCreaElMovimientoConSuOrigen() {
        val d = detectado(transaccion(descripcion = "Xyz", origen = OrigenMovimiento.GALICIA, id = "GALICIA:1"), CanalDeteccion.SERVIDOR, id = 1)
        assertNull(d.categoriaId)
        val m = Conciliador.aMovimiento(d, categorias)!!
        assertEquals(cat("Otros gastos"), m.categoriaId) // sin categoría → "Otros"
        assertEquals(OrigenMovimiento.GALICIA, m.origen)
        assertEquals(3500.0, m.monto, 0.001)
        assertTrue(m.nota.contains("Banco Galicia"))
        assertEquals(cat("Supermercado"), Conciliador.aMovimiento(d, categorias, cat("Supermercado"))!!.categoriaId)
    }
}

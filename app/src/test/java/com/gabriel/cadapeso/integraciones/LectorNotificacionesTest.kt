package com.gabriel.cadapeso.integraciones

import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.TipoMovimiento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class LectorNotificacionesTest {

    private val zona = ZoneId.of("America/Argentina/Buenos_Aires")
    private val momento = LocalDateTime.of(2026, 10, 11, 14, 30).atZone(zona).toInstant().toEpochMilli()

    private fun galicia(texto: String, titulo: String = "Galicia", millis: Long = momento) =
        LectorNotificaciones.interpretar(LectorNotificaciones.PAQUETE_GALICIA, titulo, texto, millis, zona)

    private fun mp(texto: String, titulo: String = "Mercado Pago", millis: Long = momento) =
        LectorNotificaciones.interpretar(LectorNotificaciones.PAQUETE_MERCADO_PAGO, titulo, texto, millis, zona)

    // ------------------------------------------------------------------ Galicia

    @Test
    fun compraConDebito() {
        val t = galicia("Compraste $ 12.345,00 en SUPERMERCADO DIA con tu tarjeta de débito.")!!
        assertEquals(OrigenMovimiento.GALICIA, t.origen)
        assertEquals(TipoMovimiento.GASTO, t.tipo)
        assertEquals(12_345.0, t.monto, 0.001)
        assertEquals("ARS", t.moneda)
        assertEquals("Supermercado Dia", t.descripcion)
        assertEquals(MedioPago.DEBITO, t.medioPago)
        assertEquals(LocalDate.of(2026, 10, 11), t.fecha)
        assertFalse(t.transferenciaPropia)
        assertTrue(t.idTransaccion.startsWith("GALICIA:N:"))
        assertTrue(t.detalle.contains("SUPERMERCADO DIA"))
    }

    @Test
    fun transferenciaRecibida() {
        val t = galicia("Recibiste una transferencia de $ 50.000 de JUAN PEREZ", titulo = "Transferencia recibida")!!
        assertEquals(TipoMovimiento.INGRESO, t.tipo)
        assertEquals(50_000.0, t.monto, 0.001)
        assertEquals(MedioPago.TRANSFERENCIA, t.medioPago)
        assertEquals("Juan Perez", t.descripcion)
    }

    @Test
    fun pasarPlataATuMercadoPagoEsTransferenciaPropia() {
        val t = galicia("Transferiste $ 10.000,50 a tu cuenta de Mercado Pago")!!
        assertEquals(TipoMovimiento.GASTO, t.tipo)
        assertEquals(10_000.5, t.monto, 0.001)
        assertEquals(MedioPago.TRANSFERENCIA, t.medioPago)
        assertTrue(t.transferenciaPropia)
        assertEquals("Transferencia enviada", t.descripcion)
    }

    @Test
    fun extraccionDeEfectivo() {
        val t = galicia("Extrajiste $ 20.000 en un cajero Banelco")!!
        assertEquals(MedioPago.EFECTIVO, t.medioPago)
        assertTrue(t.transferenciaPropia)
        assertEquals("Extracción de efectivo", t.descripcion)
    }

    @Test
    fun compraEnDolaresConCredito() {
        val t = galicia("Compra con tu tarjeta Visa por US$ 15,99 en NETFLIX.COM")!!
        assertEquals("USD", t.moneda)
        assertEquals(15.99, t.monto, 0.001)
        assertEquals(MedioPago.CREDITO, t.medioPago)
        assertEquals("Netflix.com", t.descripcion)
    }

    @Test
    fun ignoraLoQueNoEsUnMovimiento() {
        assertNull(galicia("Aprovechá 20% de descuento pagando con tu Visa. Tope $ 5.000"))
        assertNull(galicia("Tu código de seguridad es 123456. No lo compartas."))
        assertNull(galicia("Tu compra de $ 3.500 en COTO fue rechazada"))
        assertNull(galicia("Ingresaste a Galicia desde un nuevo dispositivo"))
        assertNull(galicia("Tu resumen Visa vence el 10/11. Total: $ 150.000"))
        assertNull(galicia("Tenés disponible un préstamo preaprobado de $ 2.000.000"))
        assertNull(galicia("Transferiste a Juan Perez")) // sin monto
        assertNull(LectorNotificaciones.interpretar("com.whatsapp", "Juan", "Pagaste $ 500 en el kiosco", momento, zona))
    }

    // ------------------------------------------------------------------ Mercado Pago

    @Test
    fun pagoConMercadoPago() {
        val t = mp("Pagaste $ 3.500 a Café Martínez")!!
        assertEquals(OrigenMovimiento.MERCADO_PAGO, t.origen)
        assertEquals(TipoMovimiento.GASTO, t.tipo)
        assertEquals(MedioPago.BILLETERA, t.medioPago)
        assertEquals("Café Martínez", t.descripcion)
    }

    @Test
    fun cobroEnMercadoPago() {
        val t = mp("Juan Pérez te transfirió $ 8.000", titulo = "Recibiste dinero")!!
        assertEquals(TipoMovimiento.INGRESO, t.tipo)
        assertEquals(8_000.0, t.monto, 0.001)
        val devolucion = mp("Te devolvimos $ 200 de tu compra en Rappi")!!
        assertEquals(TipoMovimiento.INGRESO, devolucion.tipo)
        assertEquals("Rappi", devolucion.descripcion)
    }

    @Test
    fun ingresarDineroAMercadoPagoEsPropio() {
        val t = mp("Ingresaste $ 20.000 a tu cuenta")!!
        assertEquals(TipoMovimiento.INGRESO, t.tipo)
        assertTrue(t.transferenciaPropia)
    }

    @Test
    fun laMismaNotificacionNoSeDuplica() {
        val a = galicia("Compraste $ 1.500 en KIOSCO")!!
        val b = galicia("Compraste $ 1.500 en KIOSCO", millis = momento + 20_000) // mismo minuto, vuelta a publicar
        val c = galicia("Compraste $ 1.500 en KIOSCO", millis = momento + 3_600_000) // otra compra igual, una hora después
        assertEquals(a.idTransaccion, b!!.idTransaccion)
        assertNotEquals(a.idTransaccion, c!!.idTransaccion)
    }

    @Test
    fun montosEnDistintosFormatos() {
        assertEquals(1500.0, mp("Pagaste $1500 en Uber")!!.monto, 0.001)
        assertEquals(1_500.5, mp("Pagaste $ 1.500,50 en Uber")!!.monto, 0.001)
        assertEquals(100.5, mp("Pagaste $ 100,5 en Uber")!!.monto, 0.001)
        assertEquals(2_000_000.0, galicia("Recibiste $ 2.000.000 de ACME SA")!!.monto, 0.001)
    }
}

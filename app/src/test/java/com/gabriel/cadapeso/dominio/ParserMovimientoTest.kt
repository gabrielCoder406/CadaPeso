package com.gabriel.cadapeso.dominio

import com.gabriel.cadapeso.datos.CategoriasBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ParserMovimientoTest {

    private val categorias = CategoriasBase.lista().mapIndexed { i, c -> c.copy(id = i + 1L) }
    private val hoy = LocalDate.of(2026, 10, 1) // jueves

    private fun interpretar(texto: String) = ParserMovimiento.interpretar(texto, categorias, hoy)

    @Test
    fun numerosEnFormatoArgentino() {
        assertEquals(15000.0, ParserMovimiento.aNumero("15.000")!!, 0.001)
        assertEquals(15000.5, ParserMovimiento.aNumero("15.000,50")!!, 0.001)
        assertEquals(1.5, ParserMovimiento.aNumero("1,5")!!, 0.001)
        assertEquals(1500.0, ParserMovimiento.aNumero("1,500")!!, 0.001)
        assertEquals(1234567.0, ParserMovimiento.aNumero("1.234.567")!!, 0.001)
        assertEquals(1234.56, ParserMovimiento.aNumero("1,234.56")!!, 0.001)
    }

    @Test
    fun gastoConMedioYFecha() {
        val r = interpretar("Ayer pagué 12.500 de nafta con la tarjeta")
        assertEquals(TipoMovimiento.GASTO, r.tipo)
        assertEquals(12500.0, r.monto!!, 0.001)
        assertEquals("Transporte", r.categoria?.nombre)
        assertEquals(MedioPago.CREDITO, r.medioPago)
        assertEquals(hoy.minusDays(1), r.fecha)
        assertEquals("Nafta", r.descripcion)
    }

    @Test
    fun jergaArgentina() {
        val r = interpretar("15 lucas en el super con débito")
        assertEquals(15000.0, r.monto!!, 0.001)
        assertEquals("Supermercado", r.categoria?.nombre)
        assertEquals(MedioPago.DEBITO, r.medioPago)

        val palos = interpretar("me depositaron 2 palos de honorarios")
        assertEquals(TipoMovimiento.INGRESO, palos.tipo)
        assertEquals(2_000_000.0, palos.monto!!, 0.001)
        assertEquals("Trabajo independiente", palos.categoria?.nombre)
    }

    @Test
    fun ingresoDeSueldo() {
        val r = interpretar("cobré el sueldo 850000")
        assertEquals(TipoMovimiento.INGRESO, r.tipo)
        assertEquals(850000.0, r.monto!!, 0.001)
        assertEquals("Sueldo", r.categoria?.nombre)
        assertEquals("Sueldo", r.descripcion)
    }

    @Test
    fun verboDeGastoGanaAlIngreso() {
        val r = interpretar("pagué el sueldo de la empleada 200 mil")
        assertEquals(TipoMovimiento.GASTO, r.tipo)
        assertEquals(200000.0, r.monto!!, 0.001)
    }

    @Test
    fun cafeEsGastoHormigaYBilletera() {
        val r = interpretar("café 3500 mp")
        assertEquals(3500.0, r.monto!!, 0.001)
        assertEquals("Café y kiosco", r.categoria?.nombre)
        assertEquals(MedioPago.BILLETERA, r.medioPago)
        assertEquals("Café", r.descripcion)
    }

    @Test
    fun pagoDeResumenVaADeudas() {
        val r = interpretar("pagué el resumen de la tarjeta 150.000")
        assertEquals("Pago de deudas", r.categoria?.nombre)
        assertNull(r.medioPago) // la tarjeta no se paga con la tarjeta
        assertEquals(150000.0, r.monto!!, 0.001)
    }

    @Test
    fun fechasConBarraYDiaDeSemana() {
        val barra = interpretar("5/9 compré zapatillas 80 mil en cuotas")
        assertEquals(LocalDate.of(2026, 9, 5), barra.fecha)
        assertEquals(80000.0, barra.monto!!, 0.001)
        assertEquals("Ropa y compras", barra.categoria?.nombre)
        assertEquals(MedioPago.CREDITO, barra.medioPago)

        val lunes = interpretar("el lunes cena con amigos 18000")
        assertEquals(LocalDate.of(2026, 9, 28), lunes.fecha)
        assertEquals("Comida afuera y delivery", lunes.categoria?.nombre)

        val futuro = interpretar("el 25 de octubre luz 30000")
        assertEquals(LocalDate.of(2025, 10, 25), futuro.fecha) // si queda en el futuro, es del año pasado
        assertEquals("Servicios", futuro.categoria?.nombre)
    }

    @Test
    fun suscripcionNoSeConfundeConCompras() {
        assertEquals("Suscripciones", interpretar("amazon prime 5000").categoria?.nombre)
        assertEquals("Ropa y compras", interpretar("compra en amazon 40000").categoria?.nombre)
    }

    @Test
    fun aprendeCategoriaNueva() {
        val ocio = categorias.first { it.nombre == "Ocio y salidas" }
        val aprendida = Categorizador.aprender(ocio, "Karaoke con amigos", categorias)!!
        val nuevas = categorias.map { if (it.id == ocio.id) aprendida else it }
        assertEquals("Ocio y salidas", Categorizador.sugerir("karaoke 9000", nuevas, TipoMovimiento.GASTO)?.nombre)
        // Una palabra que ya existe en otra categoría no se aprende.
        assertNull(Categorizador.aprender(ocio, "Netflix", categorias))
    }
}

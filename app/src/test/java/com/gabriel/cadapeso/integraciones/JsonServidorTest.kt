package com.gabriel.cadapeso.integraciones

import com.gabriel.cadapeso.dominio.EstadoTransaccion
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.TipoMovimiento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Las respuestas son las que devuelve el servidor real (ver servidor/test/servidor.test.js). */
class JsonServidorTest {

    @Test
    fun loteDeTransaccionesDelEsquemaUnificado() {
        val lote = JsonServidor.lote(
            """
            {"instancia":"abc123","cursor":42,"hay_mas":true,"transacciones":[
              {"id_transaccion":"MERCADO_PAGO:1","monto":3500,"tipo_movimiento":"GASTO","origen":"MERCADO_PAGO","fecha":"2026-10-01",
               "categoria":null,"descripcion":"Café Martínez","moneda":"ARS","medio_pago":"BILLETERA","cuenta":"Mercado Pago",
               "estado":"CONFIRMADA","contraparte":null,"transferencia_propia":false},
              {"id_transaccion":"GALICIA:0019:g2","monto":10000,"tipo_movimiento":"GASTO","origen":"GALICIA","fecha":"2026-10-04",
               "categoria":"Pago de deudas","descripcion":"Transf. a cvu mercadopago","moneda":"ARS","medio_pago":"TRANSFERENCIA",
               "cuenta":"CA $ ·0019","estado":"ANULADA","contraparte":"Juan","transferencia_propia":true},
              {"id_transaccion":"OTRO:1","monto":1,"tipo_movimiento":"GASTO","origen":"OTRO","fecha":"2026-10-04","descripcion":"x","medio_pago":"DEBITO"},
              {"id_transaccion":"GALICIA:2","monto":-5,"tipo_movimiento":"GASTO","origen":"GALICIA","fecha":"2026-10-04","descripcion":"x","medio_pago":"DEBITO"},
              {"id_transaccion":"GALICIA:3","monto":5,"tipo_movimiento":"GASTO","origen":"GALICIA","fecha":"04/10/2026","descripcion":"x","medio_pago":"DEBITO"}
            ]}
            """.trimIndent(),
        )
        assertEquals("abc123", lote.instancia)
        assertEquals(42L, lote.cursor)
        assertTrue(lote.hayMas)
        assertEquals(2, lote.transacciones.size)
        assertEquals(3, lote.invalidas)

        val mp = lote.transacciones[0]
        assertEquals(OrigenMovimiento.MERCADO_PAGO, mp.origen)
        assertEquals(TipoMovimiento.GASTO, mp.tipo)
        assertEquals(LocalDate.of(2026, 10, 1), mp.fecha)
        assertEquals(MedioPago.BILLETERA, mp.medioPago)
        assertNull(mp.categoria)
        assertEquals("", mp.contraparte)
        assertEquals(EstadoTransaccion.CONFIRMADA, mp.estado)

        val galicia = lote.transacciones[1]
        assertEquals("Pago de deudas", galicia.categoria)
        assertEquals(EstadoTransaccion.ANULADA, galicia.estado)
        assertTrue(galicia.transferenciaPropia)
        assertEquals("Juan", galicia.contraparte)
    }

    @Test
    fun estadoDelServidor() {
        val e = JsonServidor.estado(
            """
            {"instancia":"i","servicios":{"mercado_pago":true,"galicia":false},
             "conexiones":[{"proveedor":"MERCADO_PAGO","alias":"GABRIEL.MP","estado":"REVOCADA","ultimo_error":"volvé a vincular","ultima_sincronizacion":null}],
             "saldos":[{"origen":"MERCADO_PAGO","cuenta":"Mercado Pago","moneda":"ARS","monto":15000.5,"actualizado":"2026-10-11"}],
             "pendientes":3}
            """.trimIndent(),
        )
        assertTrue(e.mercadoPagoDisponible)
        assertFalse(e.galiciaDisponible)
        val mp = e.conexion(OrigenMovimiento.MERCADO_PAGO)!!
        assertEquals("GABRIEL.MP", mp.alias)
        assertTrue(mp.necesitaRevincular)
        assertEquals("", mp.ultimaSincronizacion)
        assertNull(e.conexion(OrigenMovimiento.GALICIA))
        assertEquals(15000.5, e.saldos.single().monto, 0.001)
        assertEquals(3, e.pendientes)
    }

    @Test
    fun galiciaYSusCampos() {
        val pide = JsonServidor.resultadoGalicia("""{"estado":"interaccion","pregunta":"Ingresá el código","campo":"otp","sesion":"S"}""")
        assertEquals(ResultadoGalicia.PideDato("Ingresá el código", "otp", "S"), pide)
        assertEquals(ResultadoGalicia.Listo(nuevas = 2, cuentas = 1), JsonServidor.resultadoGalicia("""{"estado":"ok","nuevas":2,"movimientos":2,"cuentas":1}"""))

        val campos = JsonServidor.campos(
            """{"campos":[{"nombre":"document_type","etiqueta":"Tipo de documento","tipo":"opciones","opcional":false,
                "opciones":[{"valor":"DNI","etiqueta":"DNI"},{"valor":"LE","etiqueta":"Libreta"}]},
               {"nombre":"document_number","etiqueta":"Número","tipo":"texto","opcional":true,"opciones":[]}]}""",
        )
        assertEquals(listOf("document_type", "document_number"), campos.map { it.nombre })
        assertEquals(listOf("DNI" to "DNI", "LE" to "Libreta"), campos[0].opciones)
        assertTrue(campos[1].opcional)
    }

    @Test
    fun credencialesIdaYVuelta() {
        val c = CredencialesGalicia("gabi", "cl@ve\"rara", mapOf("document_number" to "30111222"))
        assertEquals(c, JsonServidor.credencialesDeJson(JsonServidor.credencialesAJson(c)))
        assertNull(JsonServidor.credencialesDeJson("esto no es json"))
    }

    @Test
    fun mensajeDeError() {
        assertEquals("La clave de registro no es correcta", JsonServidor.error("""{"error":"La clave de registro no es correcta"}"""))
        assertNull(JsonServidor.error("<html>502</html>"))
    }

    @Test
    fun direccionDelServidor() {
        assertEquals("https://cadapeso.ejemplo.com", ClienteServidor.normalizarUrl("cadapeso.ejemplo.com/"))
        assertEquals("http://192.168.0.10:8080", ClienteServidor.normalizarUrl("http://192.168.0.10:8080"))
        assertTrue(runCatching { ClienteServidor.normalizarUrl("http://cadapeso.ejemplo.com") }.isFailure)
    }
}

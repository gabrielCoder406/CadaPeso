package com.gabriel.cadapeso.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.dominio.AccionConsejo
import com.gabriel.cadapeso.notificaciones.Notificador

enum class Seccion(val etiqueta: String) {
    INICIO("Inicio"),
    MOVIMIENTOS("Movimientos"),
    PRESUPUESTO("Presupuesto"),
    PATRIMONIO("Patrimonio"),
    ASESOR("Asesor"),
}

enum class TipoTest { CONDUCTA, RIESGO }

/**
 * Avisos (Snackbar) compartidos. Cada pantalla los muestra dentro de SU Scaffold: así Material
 * acomoda el botón flotante por encima del aviso en lugar de quedar tapado.
 */
val LocalAvisos = staticCompositionLocalOf { SnackbarHostState() }

/** Pantallas que se abren encima de las secciones (con botón "atrás"). */
sealed interface Superpuesta {
    data object Ajustes : Superpuesta
    /** Cuentas conectadas: servidor, Mercado Pago, Galicia y notificaciones. */
    data object Conexiones : Superpuesta
    /** Bandeja de movimientos detectados que esperan revisión. */
    data object Detectados : Superpuesta
    data class Test(val tipo: TipoTest) : Superpuesta
}

/**
 * Qué movimiento se está editando (null = uno nuevo), el texto con el que arranca y, para uno
 * nuevo, valores precargados opcionales (por ejemplo, al "comprar igual" desde la regla de 24 h).
 * [detectadoId]: el borrador viene de la bandeja de cuentas conectadas; al guardarlo se acepta.
 */
data class EdicionMovimiento(
    val movimiento: Movimiento? = null,
    val texto: String = "",
    val borrador: Movimiento? = null,
    val detectadoId: Long? = null,
)

/** Borrador de una compra que se va a evaluar con la regla de las 24/48 horas. */
data class BorradorCompra(val descripcion: String = "", val monto: Double? = null, val categoriaId: Long? = null)

/**
 * Estado de navegación de toda la app. Es una clase simple con estados observables: cuando
 * cambia una propiedad, Compose redibuja lo que la usa. Para 5 secciones y algunas pantallas
 * encima no hace falta una librería de navegación.
 */
class Navegacion {
    var seccion by mutableStateOf(Seccion.INICIO)
    var tabPresupuesto by mutableIntStateOf(0)
    var tabPatrimonio by mutableIntStateOf(0)
    var superpuesta by mutableStateOf<Superpuesta?>(null)
    var edicion by mutableStateOf<EdicionMovimiento?>(null)
    var compra by mutableStateOf<BorradorCompra?>(null)
    var mostrarCompras by mutableStateOf(false)

    fun ir(accion: AccionConsejo, falta: TipoTest = TipoTest.CONDUCTA) {
        when (accion) {
            AccionConsejo.VER_PRESUPUESTO -> { seccion = Seccion.PRESUPUESTO; tabPresupuesto = 0 }
            AccionConsejo.VER_SEMANA -> { seccion = Seccion.PRESUPUESTO; tabPresupuesto = 1 }
            AccionConsejo.VER_PROYECCION -> { seccion = Seccion.PRESUPUESTO; tabPresupuesto = 2 }
            AccionConsejo.VER_FUGAS -> { seccion = Seccion.PRESUPUESTO; tabPresupuesto = 3 }
            AccionConsejo.VER_BALANCE -> { seccion = Seccion.PATRIMONIO; tabPatrimonio = 0 }
            AccionConsejo.VER_DEUDAS -> { seccion = Seccion.PATRIMONIO; tabPatrimonio = 1 }
            AccionConsejo.VER_AHORRO -> { seccion = Seccion.PATRIMONIO; tabPatrimonio = 2 }
            AccionConsejo.VER_INVERSION -> { seccion = Seccion.PATRIMONIO; tabPatrimonio = 3 }
            AccionConsejo.HACER_TEST -> superpuesta = Superpuesta.Test(falta)
            AccionConsejo.VER_COMPRAS -> { seccion = Seccion.ASESOR; mostrarCompras = true }
        }
    }

    /** Destino pedido por una notificación. */
    fun irADestino(destino: String) {
        superpuesta = null
        when (destino) {
            Notificador.DESTINO_COMPRAS -> ir(AccionConsejo.VER_COMPRAS)
            Notificador.DESTINO_AHORRO -> ir(AccionConsejo.VER_AHORRO)
            Notificador.DESTINO_SEMANA -> ir(AccionConsejo.VER_SEMANA)
            Notificador.DESTINO_DETECTADOS -> superpuesta = Superpuesta.Detectados
            Notificador.DESTINO_CONEXIONES, Notificador.DESTINO_VINCULADO, Notificador.DESTINO_VINCULACION_FALLIDA ->
                superpuesta = Superpuesta.Conexiones
            else -> seccion = Seccion.INICIO
        }
    }
}

package com.gabriel.cadapeso.ui.componentes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Íconos que no vienen en el paquete básico de Material (el paquete "extended" pesa varios MB).
 * Son trazos SVG de 24×24 dibujados como vectores.
 */
object Iconos {
    private fun icono(nombre: String, vararg trazos: String): ImageVector =
        ImageVector.Builder(name = nombre, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply { trazos.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) } }
            .build()

    /** Gráfico de torta (presupuesto). */
    val Presupuesto: ImageVector by lazy {
        icono(
            "Presupuesto",
            "M11,2v20c-5.07,-0.5 -9,-4.79 -9,-10s3.93,-9.5 9,-10z",
            "M13.03,2v8.99H22c-0.47,-4.74 -4.24,-8.52 -8.97,-8.99z",
            "M13.03,13.01V22c4.74,-0.47 8.5,-4.25 8.97,-8.99h-8.97z",
        )
    }

    /** Edificio con columnas (patrimonio). */
    val Patrimonio: ImageVector by lazy {
        icono(
            "Patrimonio",
            "M4,10h3v7H4z", "M10.5,10h3v7h-3z", "M17,10h3v7h-3z",
            "M2,19h20v3H2z",
            "M12,1L2,6v2h20V6z",
        )
    }

    /** Destellos (la IA). */
    val Asesor: ImageVector by lazy {
        icono(
            "Asesor",
            "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25z",
            "M11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12z",
            "M19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25z",
        )
    }

    /** Micrófono (dictar un movimiento). */
    val Microfono: ImageVector by lazy {
        icono(
            "Microfono",
            "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z",
            "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.49 6,-3.31 6,-6.72z",
        )
    }

    /** Cuadrado (detener la respuesta de la IA). */
    val Detener: ImageVector by lazy { icono("Detener", "M6,6h12v12H6z") }

    /** Billete/ingreso: flecha hacia abajo en una bandeja. */
    val Ahorro: ImageVector by lazy {
        icono(
            "Ahorro",
            "M12,2a4,4 0,1 1,0 8a4,4 0,1 1,0 -8z",
            "M3,13h18v2H3z", "M5,16h14v6H5z",
        )
    }
}

package com.gabriel.cadapeso

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.gabriel.cadapeso.notificaciones.Notificador
import com.gabriel.cadapeso.ui.AppRaiz
import com.gabriel.cadapeso.ui.tema.CadaPesoTema

/**
 * Única Activity de la app: todo lo demás son pantallas de Compose.
 * Si se abre desde una notificación, el "destino" indica a qué pantalla ir.
 */
class MainActivity : ComponentActivity() {

    private val destino = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // la app dibuja detrás de las barras del sistema (estilo Android moderno)
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) destino.value = destinoDe(intent)
        val contenedor = (application as CadaPesoApp).contenedor
        setContent {
            CadaPesoTema {
                AppRaiz(contenedor, destino.value) { destino.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destino.value = destinoDe(intent)
    }

    /**
     * A dónde ir: el destino de una notificación, o la vuelta del navegador después de autorizar
     * Mercado Pago (cadapeso://conexion?proveedor=mercadopago&resultado=ok). Del enlace solo se
     * usa "resultado": no se muestra texto que venga de afuera.
     */
    private fun destinoDe(intent: Intent?): String? {
        val datos = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && datos?.scheme == "cadapeso" && datos.host == "conexion") {
            return if (datos.getQueryParameter("resultado") == "ok") Notificador.DESTINO_VINCULADO else Notificador.DESTINO_VINCULACION_FALLIDA
        }
        return intent?.getStringExtra(Notificador.EXTRA_DESTINO)
    }
}

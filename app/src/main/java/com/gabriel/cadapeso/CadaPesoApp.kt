package com.gabriel.cadapeso

import android.app.Application
import android.content.ComponentCallbacks2
import com.gabriel.cadapeso.datos.BaseDatos
import com.gabriel.cadapeso.datos.Repositorio
import com.gabriel.cadapeso.ia.AsesorIA
import com.gabriel.cadapeso.ia.GestorModelos
import com.gabriel.cadapeso.integraciones.GestorIntegraciones
import com.gabriel.cadapeso.integraciones.SincronizacionWorker
import com.gabriel.cadapeso.notificaciones.Notificador
import com.gabriel.cadapeso.notificaciones.RevisionDiariaWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Contenedor" de dependencias hecho a mano: crea una sola vez la base de datos, el
 * repositorio y el asesor de IA, y todas las pantallas usan las mismas instancias.
 * (En apps grandes se usa Hilt; acá alcanza con esto y es más fácil de seguir.)
 */
class Contenedor(val app: Application) {
    val db: BaseDatos = BaseDatos.crear(app)
    val repo = Repositorio(db)
    val modelos = GestorModelos(app)
    val asesor = AsesorIA(app)
    /** Cuentas conectadas: Mercado Pago, Banco Galicia y notificaciones del banco. */
    val integraciones = GestorIntegraciones(app, repo)
}

class CadaPesoApp : Application() {

    lateinit var contenedor: Contenedor
        private set

    /** Corrutinas que viven mientras viva la app (no dependen de una pantalla). */
    val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        contenedor = Contenedor(this)
        Notificador.crearCanales(this)
        alcance.launch { contenedor.repo.inicializar() }
        RevisionDiariaWorker.programar(this)
        if (contenedor.integraciones.config.value.registrado) SincronizacionWorker.programar(this)
    }

    /**
     * Si Android pide memoria con la app en segundo plano, soltamos el modelo Gemma (ocupa
     * ~2,6 GB): se vuelve a cargar solo la próxima vez que lo uses.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) contenedor.asesor.liberar()
    }
}

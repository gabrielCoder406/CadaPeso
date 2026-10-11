package com.gabriel.cadapeso.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gabriel.cadapeso.Contenedor
import com.gabriel.cadapeso.datos.ConfigIA
import com.gabriel.cadapeso.datos.Perfil
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.integraciones.Conciliador
import com.gabriel.cadapeso.notificaciones.Notificador
import com.gabriel.cadapeso.ui.componentes.Iconos
import com.gabriel.cadapeso.ui.pantallas.AjustesPantalla
import com.gabriel.cadapeso.ui.pantallas.AsesorPantalla
import com.gabriel.cadapeso.ui.pantallas.BandejaPantalla
import com.gabriel.cadapeso.ui.pantallas.BienvenidaPantalla
import com.gabriel.cadapeso.ui.pantallas.ConexionesPantalla
import com.gabriel.cadapeso.ui.pantallas.EditorMovimiento
import com.gabriel.cadapeso.ui.pantallas.EvaluarCompraDialogo
import com.gabriel.cadapeso.ui.pantallas.InicioPantalla
import com.gabriel.cadapeso.ui.pantallas.MovimientosPantalla
import com.gabriel.cadapeso.ui.pantallas.PatrimonioPantalla
import com.gabriel.cadapeso.ui.pantallas.PresupuestoPantalla
import com.gabriel.cadapeso.ui.pantallas.TestPerfilPantalla
import kotlinx.coroutines.flow.merge

private fun Seccion.icono(): ImageVector = when (this) {
    Seccion.INICIO -> Icons.Filled.Home
    Seccion.MOVIMIENTOS -> Icons.AutoMirrored.Filled.List
    Seccion.PRESUPUESTO -> Iconos.Presupuesto
    Seccion.PATRIMONIO -> Iconos.Patrimonio
    Seccion.ASESOR -> Iconos.Asesor
}

/**
 * Raíz de la interfaz: decide si mostrar la bienvenida o la app, arma la barra inferior con las
 * 5 secciones, las pantallas superpuestas (ajustes, tests), el editor de movimientos y los avisos.
 */
@Composable
fun AppRaiz(contenedor: Contenedor, destino: String?, onDestinoConsumido: () -> Unit) {
    val vm: AppViewModel = viewModel { AppViewModel(contenedor) }
    val asesor: AsesorViewModel = viewModel { AsesorViewModel(contenedor) }
    val conexiones: ConexionesViewModel = viewModel { ConexionesViewModel(contenedor) }
    val perfil by vm.perfil.collectAsStateWithLifecycle()
    val analisis by vm.analisis.collectAsStateWithLifecycle()
    val config by vm.configIA.collectAsStateWithLifecycle()
    val nav = remember { Navegacion() }
    val avisos = remember { SnackbarHostState() }

    // Al volver a la app se actualiza "hoy" (por si cambió el día) y los vencimientos.
    LifecycleResumeEffect(Unit) {
        vm.actualizarReloj()
        conexiones.sincronizarSiHaceFalta() // trae lo nuevo de las cuentas conectadas, si hace rato que no
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        merge(vm.avisos, conexiones.avisos).collect { aviso ->
            val resultado = avisos.showSnackbar(aviso.texto, aviso.accion, withDismissAction = aviso.accion == null, duration = SnackbarDuration.Short)
            if (resultado == SnackbarResult.ActionPerformed) aviso.alAccionar?.invoke()
        }
    }
    LaunchedEffect(destino) {
        if (destino != null) {
            when (destino) {
                Notificador.DESTINO_VINCULADO -> conexiones.alVolverDeMercadoPago(ok = true)
                Notificador.DESTINO_VINCULACION_FALLIDA -> conexiones.alVolverDeMercadoPago(ok = false)
            }
            nav.irADestino(destino)
            onDestinoConsumido()
        }
    }

    CompositionLocalProvider(LocalAvisos provides avisos) { Contenido(perfil, analisis, config, vm, asesor, conexiones, nav) }
}

/** Lo que se ve: carga, bienvenida o la app con su barra inferior, más el editor y los formularios. */
@Composable
private fun Contenido(
    p: Perfil?, a: Analisis?, config: ConfigIA, vm: AppViewModel, asesor: AsesorViewModel, conexiones: ConexionesViewModel, nav: Navegacion,
) {
    when {
        p == null || (p.onboardingCompleto && a == null) -> Cargando()
        !p.onboardingCompleto -> BienvenidaPantalla(p, vm)
        a != null -> {
            PedirPermisoNotificaciones()
            val pendientes by conexiones.pendientes.collectAsStateWithLifecycle()
            val superpuesta = nav.superpuesta
            BackHandler(enabled = superpuesta != null) { nav.superpuesta = null }
            BackHandler(enabled = superpuesta == null && nav.seccion != Seccion.INICIO) { nav.seccion = Seccion.INICIO }
            when (superpuesta) {
                is Superpuesta.Ajustes -> AjustesPantalla(
                    a, p, config, vm, asesor,
                    onVolver = { nav.superpuesta = null },
                    onTest = { nav.superpuesta = Superpuesta.Test(it) },
                    onConexiones = { nav.superpuesta = Superpuesta.Conexiones },
                )
                Superpuesta.Conexiones -> ConexionesPantalla(
                    a, conexiones,
                    onBandeja = { nav.superpuesta = Superpuesta.Detectados },
                    onVolver = { nav.superpuesta = null },
                )
                Superpuesta.Detectados -> BandejaPantalla(
                    a, conexiones,
                    onEditar = { d, categoriaId ->
                        Conciliador.aMovimiento(d, a.datos.categorias, categoriaId)?.let {
                            nav.edicion = EdicionMovimiento(borrador = it, detectadoId = d.id)
                        }
                    },
                    onConexiones = { nav.superpuesta = Superpuesta.Conexiones },
                    onVolver = { nav.superpuesta = null },
                )
                is Superpuesta.Test -> TestPerfilPantalla(superpuesta.tipo, vm) { nav.superpuesta = null }
                null -> Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Seccion.entries.forEach { s ->
                                NavigationBarItem(
                                    selected = nav.seccion == s,
                                    onClick = { nav.seccion = s },
                                    icon = { Icon(s.icono(), contentDescription = s.etiqueta) },
                                    label = { Text(s.etiqueta, maxLines = 1) },
                                )
                            }
                        }
                    },
                ) { relleno ->
                    // consumeWindowInsets: así el teclado no deja un hueco del alto de la barra inferior.
                    Box(Modifier.padding(relleno).consumeWindowInsets(relleno)) {
                        when (nav.seccion) {
                            Seccion.INICIO -> InicioPantalla(a, nav, onCargarEjemplo = vm::cargarDatosEjemplo, pendientes = pendientes.size)
                            Seccion.MOVIMIENTOS -> MovimientosPantalla(a, nav, pendientes = pendientes.size)
                            Seccion.PRESUPUESTO -> PresupuestoPantalla(a, nav, vm)
                            Seccion.PATRIMONIO -> PatrimonioPantalla(a, nav, vm)
                            Seccion.ASESOR -> AsesorPantalla(a, nav, vm, asesor, config)
                        }
                    }
                }
            }

            nav.edicion?.let { edicion ->
                EditorMovimiento(
                    edicion = edicion,
                    a = a,
                    iaDisponible = asesor.iaConfigurada(config),
                    onInterpretarIA = { texto -> asesor.interpretarConIA(texto, a, config) },
                    onGuardar = { m, sugerida ->
                        val detectado = edicion.detectadoId
                        if (detectado != null) conexiones.aceptarEditado(detectado, m, sugerida) else vm.guardarMovimiento(m, sugerida)
                        nav.edicion = null
                    },
                    onBorrar = { m ->
                        vm.borrarMovimiento(m)
                        nav.edicion = null
                    },
                    onPensarlo = { borrador ->
                        nav.edicion = null
                        nav.compra = borrador
                    },
                    onCerrar = { nav.edicion = null },
                )
            }
            nav.compra?.let { borrador ->
                EvaluarCompraDialogo(borrador, a, config, asesor, vm, nav) { nav.compra = null }
            }
        }
    }
}

@Composable
private fun Cargando() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** En Android 13+ las notificaciones necesitan permiso: se pide una sola vez, después de la bienvenida. */
@Composable
private fun PedirPermisoNotificaciones() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val contexto = LocalContext.current
    val lanzador = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = contexto.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (!Notificador.permisoConcedido(contexto) && !prefs.getBoolean("permiso_pedido", false)) {
            prefs.edit { putBoolean("permiso_pedido", true) }
            lanzador.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

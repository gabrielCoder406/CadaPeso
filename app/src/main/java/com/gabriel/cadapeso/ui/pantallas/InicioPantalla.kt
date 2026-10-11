@file:OptIn(ExperimentalMaterial3Api::class)

package com.gabriel.cadapeso.ui.pantallas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabriel.cadapeso.dominio.AccionConsejo
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.EstadoCompra
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.plural
import com.gabriel.cadapeso.ui.LocalAvisos
import com.gabriel.cadapeso.ui.EdicionMovimiento
import com.gabriel.cadapeso.ui.Navegacion
import com.gabriel.cadapeso.ui.Superpuesta
import com.gabriel.cadapeso.ui.TipoTest
import com.gabriel.cadapeso.ui.componentes.BarraConMeta
import com.gabriel.cadapeso.ui.componentes.BarraProgreso
import com.gabriel.cadapeso.ui.componentes.FilaDato
import com.gabriel.cadapeso.ui.componentes.GraficoBarras
import com.gabriel.cadapeso.ui.componentes.GraficoDona
import com.gabriel.cadapeso.ui.componentes.Seccion
import com.gabriel.cadapeso.ui.componentes.TarjetaConsejo
import com.gabriel.cadapeso.ui.componentes.colorGrupo
import com.gabriel.cadapeso.ui.tema.ColoresApp
import com.gabriel.cadapeso.ui.tema.EstiloMonto
import com.gabriel.cadapeso.ui.tema.EstiloMontoGrande
import java.time.LocalTime

@Composable
fun InicioPantalla(a: Analisis, nav: Navegacion, onCargarEjemplo: () -> Unit, pendientes: Int = 0) {
    val nombre = a.datos.perfil.nombre.trim()
    val hora = LocalTime.now().hour
    val saludo = when (hora) {
        in 5..12 -> "Buen día"
        in 13..19 -> "Buenas tardes"
        else -> "Buenas noches"
    } + if (nombre.isNotEmpty()) ", $nombre" else ""
    val testFaltante = if (a.perfil.conducta == null) TipoTest.CONDUCTA else TipoTest.RIESGO

    Scaffold(
        snackbarHost = { SnackbarHost(LocalAvisos.current) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(saludo, style = MaterialTheme.typography.titleLarge)
                        Text(Formato.fechaLarga(a.hoy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { nav.superpuesta = Superpuesta.Ajustes }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ajustes")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.edicion = EdicionMovimiento() },
                // Material 3 no expone el texto de este botón a TalkBack: lo describimos a mano.
                modifier = Modifier.semantics { contentDescription = "Nuevo movimiento" },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Movimiento") },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (a.datos.movimientos.isEmpty()) {
                item { TarjetaBienvenida(onNuevo = { nav.edicion = EdicionMovimiento() }, onCargarEjemplo = onCargarEjemplo) }
            }
            if (pendientes > 0) {
                item {
                    Seccion(
                        "📥 ${plural(pendientes, "movimiento para revisar", "movimientos para revisar")}",
                        subtitulo = "Llegaron de Mercado Pago o Galicia: confirmalos para que cuenten",
                        onClick = { nav.superpuesta = Superpuesta.Detectados },
                    ) {}
                }
            }
            item { TarjetaMes(a, nav) }
            item { TarjetaRegla(a, nav) }
            item { TarjetaSemana(a, nav) }
            val enEspera = a.datos.compras.count { it.estado == EstadoCompra.EN_ESPERA }
            if (enEspera > 0) {
                item {
                    Seccion(
                        "🛍️ ${plural(enEspera, "compra en espera", "compras en espera")}",
                        subtitulo = "Regla de las 24/48 h: decidí con la cabeza fría",
                        onClick = { nav.ir(AccionConsejo.VER_COMPRAS) },
                    ) {}
                }
            }
            if (a.consejos.isNotEmpty()) {
                item { Text("Para tener en cuenta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
                items(a.consejos.take(5), key = { it.id }) { consejo ->
                    TarjetaConsejo(consejo) { accion -> nav.ir(accion, testFaltante) }
                }
            }
            item { TarjetaRuta(a, nav) }
            item { TarjetaPatrimonioMini(a, nav) }
        }
    }
}

@Composable
private fun TarjetaBienvenida(onNuevo: () -> Unit, onCargarEjemplo: () -> Unit) {
    Seccion("👋 Empecemos", subtitulo = "Cargá tus movimientos y el asesor empieza a trabajar") {
        Text(
            "Podés escribir o dictar \"café 3500 con débito\" y la app completa el resto. " +
                "Si querés explorar primero, cargá datos de ejemplo (después los podés borrar en Ajustes).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNuevo) { Text("Primer movimiento") }
            OutlinedButton(onClick = onCargarEjemplo) { Text("Datos de ejemplo") }
        }
    }
}

@Composable
private fun TarjetaMes(a: Analisis, nav: Navegacion) {
    val m = a.moneda
    val grupos = listOf(GrupoGasto.NECESIDAD, GrupoGasto.DESEO, GrupoGasto.EDUCACION, GrupoGasto.DEUDA, GrupoGasto.AHORRO)
        .map { it to a.mes.grupo(it) }.filter { it.second > 0 }
    Seccion(Formato.mes(a.mes.mes), subtitulo = "Flujo de efectivo del mes", onClick = { nav.ir(AccionConsejo.VER_PRESUPUESTO) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Disponible", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    Formato.dinero(a.indicadores.ingresoReferencia - a.mes.gastos, m),
                    style = EstiloMontoGrande,
                    color = if (a.indicadores.ingresoReferencia - a.mes.gastos >= 0) MaterialTheme.colorScheme.onSurface else ColoresApp.negativo,
                )
                FilaDato("Ingresos", Formato.dinero(a.mes.ingresos, m), colorValor = ColoresApp.positivo)
                FilaDato("Gastos", Formato.dinero(a.mes.gastos, m), colorValor = ColoresApp.negativo)
                FilaDato("Ahorro", "${Formato.dinero(a.mes.ahorro, m)} (${Formato.porcentaje(a.indicadores.tasaAhorro)})")
            }
            Spacer(Modifier.width(12.dp))
            GraficoDona(
                segmentos = grupos.map { (g, v) -> v.toFloat() to colorGrupo(g) },
                modifier = Modifier.size(110.dp),
                grosor = 14.dp,
            ) {
                Text("${a.mes.cantidad}\nmovs.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (grupos.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                grupos.take(4).forEach { (g, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colorGrupo(g)))
                        Spacer(Modifier.width(4.dp))
                        Text(g.etiqueta.substringBefore(' '), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        val sinAsignar = a.presupuesto.sinAsignar
        if (a.presupuesto.ingresoAAsignar > 0 && sinAsignar > a.presupuesto.ingresoAAsignar * 0.01) {
            Text(
                "💡 ${Formato.dinero(sinAsignar, m)} sin tarea asignada",
                style = MaterialTheme.typography.labelLarge,
                color = ColoresApp.advertencia,
            )
        }
    }
}

@Composable
private fun TarjetaRegla(a: Analisis, nav: Navegacion) {
    Seccion(
        "Regla ${a.presupuesto.regla.etiqueta}",
        subtitulo = "Real del mes vs. meta (línea)",
        onClick = { nav.ir(AccionConsejo.VER_PRESUPUESTO) },
    ) {
        a.presupuesto.partidas.forEach { lp ->
            val esAhorro = GrupoGasto.AHORRO in lp.partida.grupos
            // En ahorro, quedarse corto es lo malo; en el resto, pasarse.
            val mal = if (esAhorro) lp.porcentajeReal < lp.partida.porcentaje else lp.porcentajeReal > lp.partida.porcentaje
            val color = if (mal) ColoresApp.advertencia else colorGrupo(lp.partida.grupos.first())
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(lp.partida.nombre, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${Formato.porcentaje(lp.porcentajeReal)} · meta ${Formato.porcentaje(lp.partida.porcentaje)}",
                        style = MaterialTheme.typography.bodySmall.merge(EstiloMonto),
                        color = if (mal) ColoresApp.advertencia else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BarraConMeta(lp.porcentajeReal.toFloat(), lp.partida.porcentaje.toFloat(), color, escala = 100f)
            }
        }
    }
}

@Composable
private fun TarjetaSemana(a: Analisis, nav: Navegacion) {
    val s = a.semana
    val m = a.moneda
    Seccion("Esta semana", subtitulo = "Gasto variable día a día", onClick = { nav.ir(AccionConsejo.VER_SEMANA) }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Formato.dinero(s.total, m), style = MaterialTheme.typography.headlineSmall.merge(EstiloMonto))
            Spacer(Modifier.width(6.dp))
            Text("de ${Formato.dinero(s.presupuestoSemanal, m)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BarraProgreso(
            (if (s.presupuestoSemanal > 0) s.total / s.presupuestoSemanal else 0.0).toFloat(),
            if (s.total > s.esperadoHastaHoy * 1.15 && s.presupuestoSemanal > 0) ColoresApp.negativo else MaterialTheme.colorScheme.primary,
        )
        GraficoBarras(
            valores = s.gastoPorDia,
            etiquetas = listOf("L", "M", "M", "J", "V", "S", "D"),
            limite = if (s.presupuestoSemanal > 0) s.presupuestoSemanal / 7 else null,
            resaltado = s.diasTranscurridos - 1,
            alto = 70.dp,
        )
        if (s.presupuestoSemanal > 0 && s.total > 0) {
            Text(
                if (s.desvio > 0) "Vas ${Formato.porcentaje(s.desvio)} por encima del ritmo" else "Vas ${Formato.porcentaje(-s.desvio)} por debajo del ritmo 👏",
                style = MaterialTheme.typography.bodySmall,
                color = if (s.desvio > 15) ColoresApp.negativo else ColoresApp.positivo,
            )
        }
    }
}

@Composable
private fun TarjetaRuta(a: Analisis, nav: Navegacion) {
    Seccion("Hoja de ruta para crear riqueza", subtitulo = "Paso a paso, en orden", onClick = { nav.ir(AccionConsejo.VER_INVERSION) }) {
        val actual = a.hojaDeRuta.pasoActual
        a.hojaDeRuta.pasos.forEach { paso ->
            val esActual = paso.numero == actual
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(
                        when {
                            paso.cumplido -> ColoresApp.positivo
                            esActual -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (paso.cumplido) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    else Text("${paso.numero}", color = if (esActual) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(paso.titulo, style = MaterialTheme.typography.bodyMedium, fontWeight = if (esActual) FontWeight.Bold else FontWeight.Normal)
                    if (esActual) Text(paso.detalle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TarjetaPatrimonioMini(a: Analisis, nav: Navegacion) {
    val f = a.fondoEmergencia
    val m = a.moneda
    Seccion("Patrimonio neto", onClick = { nav.ir(AccionConsejo.VER_BALANCE) }) {
        Text(
            Formato.dinero(a.patrimonio.neto, m),
            style = MaterialTheme.typography.headlineSmall.merge(EstiloMonto),
            color = if (a.patrimonio.neto >= 0) MaterialTheme.colorScheme.onSurface else ColoresApp.negativo,
        )
        FilaDato("Activos", Formato.dinero(a.patrimonio.activos, m))
        FilaDato("Deudas", Formato.dinero(a.patrimonio.pasivos, m))
        Text(
            "Fondo de emergencia: ${String.format(Formato.locale, "%.1f", f.mesesCubiertos)} de ${f.mesesMin}–${f.mesesMax} meses",
            style = MaterialTheme.typography.bodySmall,
        )
        BarraProgreso((f.mesesCubiertos / f.mesesMin).toFloat(), if (f.mesesCubiertos >= f.mesesMin) ColoresApp.positivo else ColoresApp.advertencia)
    }
}

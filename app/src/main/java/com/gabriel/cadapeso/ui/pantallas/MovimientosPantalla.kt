@file:OptIn(ExperimentalMaterial3Api::class)

package com.gabriel.cadapeso.ui.pantallas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gabriel.cadapeso.datos.Categoria
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.MotorPresupuesto
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.contiene
import com.gabriel.cadapeso.dominio.normalizar
import com.gabriel.cadapeso.ui.LocalAvisos
import com.gabriel.cadapeso.ui.EdicionMovimiento
import com.gabriel.cadapeso.ui.Navegacion
import com.gabriel.cadapeso.ui.componentes.AvatarCategoria
import com.gabriel.cadapeso.ui.componentes.EstadoVacio
import com.gabriel.cadapeso.ui.componentes.SelectorCategoriaDialogo
import com.gabriel.cadapeso.ui.componentes.SelectorMes
import com.gabriel.cadapeso.ui.tema.ColoresApp
import com.gabriel.cadapeso.ui.tema.EstiloMonto
import java.time.YearMonth

@Composable
fun MovimientosPantalla(a: Analisis, nav: Navegacion) {
    var mes by rememberSaveable { mutableStateOf(YearMonth.from(a.hoy)) }
    var filtro by rememberSaveable { mutableStateOf(0) } // 0 = todos, 1 = gastos, 2 = ingresos
    var busqueda by rememberSaveable { mutableStateOf("") }
    var mostrarBuscador by rememberSaveable { mutableStateOf(false) }
    var categoriaFiltro by rememberSaveable { mutableStateOf<Long?>(null) }
    var elegirCategoria by remember { mutableStateOf(false) }
    val datos = a.datos
    val moneda = a.moneda

    val lista = remember(datos.movimientos, mes, filtro, busqueda, categoriaFiltro) {
        val texto = normalizar(busqueda.trim())
        datos.movimientos.filter { m ->
            mes.contiene(m.fecha) &&
                (filtro == 0 || (filtro == 1 && m.tipo == TipoMovimiento.GASTO) || (filtro == 2 && m.tipo == TipoMovimiento.INGRESO)) &&
                (categoriaFiltro == null || m.categoriaId == categoriaFiltro) &&
                (texto.isEmpty() || normalizar(m.descripcion + " " + (datos.categoria(m.categoriaId)?.nombre ?: "") + " " + m.nota).contains(texto))
        }
    }
    val resumen = remember(datos, mes) { MotorPresupuesto.resumenMes(datos, mes) }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalAvisos.current) },
        topBar = {
            TopAppBar(
                title = { Text("Movimientos") },
                actions = {
                    IconButton(onClick = { mostrarBuscador = !mostrarBuscador; if (!mostrarBuscador) busqueda = "" }) {
                        Icon(if (mostrarBuscador) Icons.Filled.Clear else Icons.Filled.Search, contentDescription = "Buscar")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.edicion = EdicionMovimiento() }) { Icon(Icons.Filled.Add, contentDescription = "Nuevo movimiento") }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { SelectorMes(mes, { mes = it }) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CajaResumen("Ingresos", Formato.dinero(resumen.ingresos, moneda), ColoresApp.positivo, Modifier.weight(1f))
                    CajaResumen("Gastos", Formato.dinero(resumen.gastos, moneda), ColoresApp.negativo, Modifier.weight(1f))
                    CajaResumen(
                        "Balance", Formato.dinero(resumen.balance, moneda),
                        if (resumen.balance >= 0) ColoresApp.positivo else ColoresApp.negativo, Modifier.weight(1f),
                    )
                }
            }
            if (mostrarBuscador) {
                item {
                    OutlinedTextField(
                        value = busqueda, onValueChange = { busqueda = it }, singleLine = true,
                        placeholder = { Text("Buscar por descripción, categoría o nota") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = filtro == 0, onClick = { filtro = 0 }, label = { Text("Todos") }) }
                    item { FilterChip(selected = filtro == 1, onClick = { filtro = 1 }, label = { Text("Gastos") }) }
                    item { FilterChip(selected = filtro == 2, onClick = { filtro = 2 }, label = { Text("Ingresos") }) }
                    item {
                        val cat = categoriaFiltro?.let { datos.categoria(it) }
                        FilterChip(
                            selected = cat != null,
                            onClick = { if (cat != null) categoriaFiltro = null else elegirCategoria = true },
                            label = { Text(cat?.let { "${it.emoji} ${it.nombre} ✕" } ?: "Categoría") },
                        )
                    }
                }
            }
            if (lista.isEmpty()) {
                item {
                    EstadoVacio(
                        "🧾", "Sin movimientos",
                        if (busqueda.isNotEmpty() || categoriaFiltro != null) "No hay resultados con esos filtros." else "Tocá + para cargar un gasto o un ingreso.",
                    )
                }
            }
            lista.groupBy { it.fecha }.forEach { (fecha, movs) ->
                item(key = "dia_$fecha") {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Formato.diaRelativo(fecha, a.hoy), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        val totalDia = movs.filter { it.tipo == TipoMovimiento.GASTO }.sumOf { it.monto }
                        if (totalDia > 0) Text("-${Formato.dinero(totalDia, moneda)}", style = MaterialTheme.typography.labelMedium.merge(EstiloMonto))
                    }
                }
                items(movs, key = { it.id }) { m ->
                    FilaMovimiento(m, datos.categoria(m.categoriaId), moneda) { nav.edicion = EdicionMovimiento(m) }
                }
            }
        }
    }

    if (elegirCategoria) {
        SelectorCategoriaDialogo(
            categorias = datos.categorias,
            tipo = if (filtro == 2) TipoMovimiento.INGRESO else TipoMovimiento.GASTO,
            seleccionada = categoriaFiltro,
            onElegir = { categoriaFiltro = it.id; elegirCategoria = false },
            onCerrar = { elegirCategoria = false },
        )
    }
}

@Composable
private fun CajaResumen(titulo: String, valor: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(10.dp)) {
            Text(titulo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valor, style = MaterialTheme.typography.labelLarge.merge(EstiloMonto), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun FilaMovimiento(m: Movimiento, categoria: Categoria?, moneda: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AvatarCategoria(categoria)
        Column(Modifier.weight(1f)) {
            Text(
                m.descripcion.ifBlank { categoria?.nombre ?: "Movimiento" } + (if (m.metaId != null) " 🎯" else "") + (if (m.creadoConIA) " ✨" else ""),
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${categoria?.nombre ?: "Sin categoría"} · ${m.medioPago.etiqueta}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        val ingreso = m.tipo == TipoMovimiento.INGRESO
        Text(
            (if (ingreso) "+" else "-") + Formato.dinero(m.monto, moneda),
            style = MaterialTheme.typography.bodyLarge.merge(EstiloMonto),
            color = if (ingreso) ColoresApp.positivo else MaterialTheme.colorScheme.onSurface,
        )
    }
}

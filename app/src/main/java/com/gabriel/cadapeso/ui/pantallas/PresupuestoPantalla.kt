@file:OptIn(ExperimentalMaterial3Api::class)

package com.gabriel.cadapeso.ui.pantallas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gabriel.cadapeso.datos.EventoPlanificado
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.EstadoLinea
import com.gabriel.cadapeso.dominio.EstadoPresupuesto
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.LineaPresupuesto
import com.gabriel.cadapeso.dominio.MotorPresupuesto
import com.gabriel.cadapeso.dominio.PlanAjuste
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.clave
import com.gabriel.cadapeso.dominio.leerNumero
import com.gabriel.cadapeso.dominio.mesDeClave
import com.gabriel.cadapeso.dominio.plural
import com.gabriel.cadapeso.ui.LocalAvisos
import com.gabriel.cadapeso.ui.AppViewModel
import com.gabriel.cadapeso.ui.Navegacion
import com.gabriel.cadapeso.ui.componentes.AvatarCategoria
import com.gabriel.cadapeso.ui.componentes.BarraConMeta
import com.gabriel.cadapeso.ui.componentes.BarraProgreso
import com.gabriel.cadapeso.ui.componentes.CampoDinero
import com.gabriel.cadapeso.ui.componentes.DialogoConfirmar
import com.gabriel.cadapeso.ui.componentes.FilaDato
import com.gabriel.cadapeso.ui.componentes.GraficoBarras
import com.gabriel.cadapeso.ui.componentes.GraficoLineas
import com.gabriel.cadapeso.ui.componentes.HojaFormulario
import com.gabriel.cadapeso.ui.componentes.Medidor
import com.gabriel.cadapeso.ui.componentes.Seccion
import com.gabriel.cadapeso.ui.componentes.SelectorMes
import com.gabriel.cadapeso.ui.componentes.colorGrupo
import com.gabriel.cadapeso.ui.tema.ColoresApp
import com.gabriel.cadapeso.ui.tema.EstiloMonto
import com.gabriel.cadapeso.ui.tema.EstiloMontoGrande
import java.time.YearMonth

@Composable
fun PresupuestoPantalla(a: Analisis, nav: Navegacion, vm: AppViewModel) {
    val pestanias = listOf("Plan", "Semana", "Proyección", "Fugas")
    Scaffold(
        snackbarHost = { SnackbarHost(LocalAvisos.current) },
        topBar = {
            Column {
                TopAppBar(title = { Text("Presupuesto y flujo") })
                PrimaryTabRow(selectedTabIndex = nav.tabPresupuesto) {
                    pestanias.forEachIndexed { i, t ->
                        Tab(selected = nav.tabPresupuesto == i, onClick = { nav.tabPresupuesto = i }, text = { Text(t, maxLines = 1) })
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val modificador = Modifier.padding(padding)
        when (nav.tabPresupuesto) {
            0 -> TabPlan(a, vm, modificador)
            1 -> TabSemana(a, modificador)
            2 -> TabProyeccion(a, vm, modificador)
            else -> TabFugas(a, modificador)
        }
    }
}

private val RELLENO_LISTA = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)

// =========================================================================================
// Plan mensual: "cada peso con una tarea"
// =========================================================================================

@Composable
private fun TabPlan(a: Analisis, vm: AppViewModel, modifier: Modifier) {
    var mes by rememberSaveable { mutableStateOf(YearMonth.from(a.hoy)) }
    val datos = a.datos
    val moneda = a.moneda
    val estado = remember(datos, mes) { MotorPresupuesto.estadoPresupuesto(datos, mes, MotorPresupuesto.resumenMes(datos, mes)) }
    var editando by remember { mutableStateOf<LineaPresupuesto?>(null) }
    var editarIngreso by remember { mutableStateOf(false) }
    var confirmarSugerir by remember { mutableStateOf(false) }

    LazyColumn(modifier, contentPadding = RELLENO_LISTA, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SelectorMes(mes, { mes = it }) }
        item {
            TarjetaCadaPeso(
                estado, moneda,
                onEditarIngreso = { editarIngreso = true },
                onSugerir = { if (estado.totalAsignado > 0) confirmarSugerir = true else vm.sugerirPresupuesto(mes) },
                onCopiar = { vm.copiarMesAnterior(mes) },
            )
        }
        item { TarjetaPartidas(estado, moneda) }
        if (mes == YearMonth.from(a.hoy)) {
            a.planAjuste?.let { plan -> item { TarjetaPlanAjuste(plan, moneda) } }
        }
        estado.regla.partidas.forEach { partida ->
            val lineas = estado.lineas.filter { it.categoria.grupo in partida.grupos }
            if (lineas.isEmpty()) return@forEach
            item(key = "p_${partida.nombre}") {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(partida.nombre, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colorGrupo(partida.grupos.first()))
                    Text(
                        "meta ${Formato.porcentaje(partida.porcentaje)} = ${Formato.dinero(estado.ingresoAAsignar * partida.porcentaje / 100, moneda)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(lineas, key = { "l_${it.categoria.id}" }) { linea -> FilaLinea(linea, moneda) { editando = linea } }
        }
    }

    editando?.let { linea ->
        val promedio = remember(datos, linea) {
            (1..3).map { MotorPresupuesto.resumenMes(datos, mes.minusMonths(it.toLong())).porCategoria[linea.categoria.id] ?: 0.0 }.average()
        }
        DialogoMonto(
            titulo = "${linea.categoria.emoji} ${linea.categoria.nombre}",
            inicial = linea.asignado,
            moneda = moneda,
            ayuda = if (promedio > 0) "Promedio de los últimos 3 meses: ${Formato.dinero(promedio, moneda)}" else "Poné 0 para quitar la asignación",
            onAceptar = { vm.guardarAsignacion(mes, linea.categoria.id, it) },
            onCerrar = { editando = null },
        )
    }
    if (editarIngreso) {
        DialogoMonto(
            titulo = "Ingreso previsto de ${Formato.mes(mes).lowercase()}",
            inicial = estado.ingresoAAsignar,
            moneda = moneda,
            ayuda = "Es el dinero que vas a asignar. Si cobrás más, se usa lo real.",
            onAceptar = { vm.guardarIngresoPrevisto(mes, it) },
            onCerrar = { editarIngreso = false },
        )
    }
    if (confirmarSugerir) {
        DialogoConfirmar(
            "Armar con la regla ${estado.regla.etiqueta}",
            "Reemplaza lo asignado este mes repartiendo todo el ingreso según la regla y tu historial de gastos.",
            "Armar", onConfirmar = { vm.sugerirPresupuesto(mes) }, onCerrar = { confirmarSugerir = false },
        )
    }
}

@Composable
private fun TarjetaCadaPeso(e: EstadoPresupuesto, moneda: String, onEditarIngreso: () -> Unit, onSugerir: () -> Unit, onCopiar: () -> Unit) {
    val sinAsignar = e.sinAsignar
    val color = when {
        e.ingresoAAsignar <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        sinAsignar < -1 -> ColoresApp.negativo
        sinAsignar > e.ingresoAAsignar * 0.01 -> ColoresApp.advertencia
        else -> ColoresApp.positivo
    }
    Seccion("Cada peso con una tarea", subtitulo = "Asigná todo el ingreso antes de gastarlo") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilaDato("Ingreso a asignar", Formato.dinero(e.ingresoAAsignar, moneda), Modifier.weight(1f))
            IconButton(onClick = onEditarIngreso) { Icon(Icons.Filled.Edit, contentDescription = "Editar ingreso") }
        }
        FilaDato("Asignado", Formato.dinero(e.totalAsignado, moneda))
        Text(
            when {
                e.ingresoAAsignar <= 0 -> "Cargá cuánto vas a cobrar este mes"
                sinAsignar < -1 -> "Asignaste ${Formato.dinero(-sinAsignar, moneda)} de más"
                sinAsignar > e.ingresoAAsignar * 0.01 -> "${Formato.dinero(sinAsignar, moneda)} sin tarea"
                else -> "✅ Cada peso tiene una tarea"
            },
            style = MaterialTheme.typography.titleMedium.merge(EstiloMonto),
            color = color,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onSugerir) { Text("Armar con ${e.regla.etiqueta}") }
            OutlinedButton(onClick = onCopiar) { Text("Copiar mes anterior") }
        }
    }
}

@Composable
private fun TarjetaPartidas(e: EstadoPresupuesto, moneda: String) {
    Seccion("Regla ${e.regla.etiqueta}", subtitulo = e.regla.resumen) {
        e.partidas.forEach { lp ->
            val esAhorro = GrupoGasto.AHORRO in lp.partida.grupos
            val mal = if (esAhorro) lp.porcentajeReal < lp.partida.porcentaje else lp.porcentajeReal > lp.partida.porcentaje
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(lp.partida.nombre, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "real ${Formato.porcentaje(lp.porcentajeReal)} · meta ${Formato.porcentaje(lp.partida.porcentaje)}",
                        style = MaterialTheme.typography.bodySmall.merge(EstiloMonto),
                        color = if (mal) ColoresApp.advertencia else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BarraConMeta(lp.porcentajeReal.toFloat(), lp.partida.porcentaje.toFloat(), if (mal) ColoresApp.advertencia else colorGrupo(lp.partida.grupos.first()), escala = 100f)
                Text(
                    "Gastado ${Formato.dinero(lp.real, moneda)} · asignado ${Formato.dinero(lp.asignado, moneda)} · objetivo ${Formato.dinero(lp.objetivo, moneda)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TarjetaPlanAjuste(plan: PlanAjuste, moneda: String) {
    Seccion("✂️ Plan de ajuste progresivo", subtitulo = "Necesidades + cuotas superan el 80% del ingreso (promedio de 3 meses)") {
        Text(
            "Hoy: ${Formato.porcentaje(plan.porcentajeActual)} → objetivo ${Formato.porcentaje(plan.porcentajeObjetivo)} en 3 meses, de a poco para que sea sostenible.",
            style = MaterialTheme.typography.bodyMedium,
        )
        plan.metasMensuales.forEach { (mes, objetivo) -> FilaDato(Formato.mes(mes), "≤ ${Formato.porcentaje(objetivo)}") }
        FilaDato("Recorte mensual al final", Formato.dinero(plan.recorteMensualFinal, moneda), colorValor = ColoresApp.advertencia)
        plan.sugerencias.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FilaLinea(l: LineaPresupuesto, moneda: String, onClick: () -> Unit) {
    val color = when (l.estado) {
        EstadoLinea.EXCEDIDA, EstadoLinea.SIN_PLAN -> ColoresApp.negativo
        EstadoLinea.CERCA -> ColoresApp.advertencia
        EstadoLinea.OK -> ColoresApp.positivo
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AvatarCategoria(l.categoria, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row {
                Text(l.categoria.nombre, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${Formato.dinero(l.gastado, moneda)} / ${Formato.dinero(l.asignado, moneda)}",
                    style = MaterialTheme.typography.bodySmall.merge(EstiloMonto),
                )
            }
            BarraProgreso(if (l.asignado > 0) (l.gastado / l.asignado).toFloat() else if (l.gastado > 0) 1f else 0f, color, alto = 6.dp)
            Text(
                when (l.estado) {
                    EstadoLinea.SIN_PLAN -> "Gastaste sin asignar: tocá para darle una tarea"
                    EstadoLinea.EXCEDIDA -> "Te pasaste ${Formato.dinero(-l.restante, moneda)}"
                    else -> if (l.asignado > 0) "Quedan ${Formato.dinero(l.restante, moneda)}" else "Sin asignar"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (l.estado == EstadoLinea.OK) MaterialTheme.colorScheme.onSurfaceVariant else color,
            )
        }
    }
}

/** Hoja genérica para cargar un monto. */
@Composable
fun DialogoMonto(titulo: String, inicial: Double, moneda: String, ayuda: String?, onAceptar: (Double) -> Unit, onCerrar: () -> Unit) {
    var texto by remember { mutableStateOf(if (inicial > 0) Formato.numeroEditable(inicial) else "") }
    HojaFormulario(
        titulo = titulo,
        onCerrar = onCerrar,
        botones = {
            TextButton(onClick = onCerrar) { Text("Cancelar") }
            Button(onClick = {
                onAceptar(leerNumero(texto) ?: 0.0)
                onCerrar()
            }) { Text("Guardar") }
        },
    ) {
        CampoDinero(texto, { texto = it }, "Monto", moneda = moneda, ayuda = ayuda, imeAction = ImeAction.Done)
    }
}

// =========================================================================================
// Panel semanal
// =========================================================================================

@Composable
private fun TabSemana(a: Analisis, modifier: Modifier) {
    val s = a.semana
    val m = a.moneda
    val dias = listOf("L", "M", "M", "J", "V", "S", "D").mapIndexed { i, d -> "$d ${s.inicio.plusDays(i.toLong()).dayOfMonth}" }
    LazyColumn(modifier, contentPadding = RELLENO_LISTA, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Seccion(
                "Semana del ${s.inicio.dayOfMonth} al ${Formato.fechaCorta(s.fin)}",
                subtitulo = "Gastos variables (sin alquiler, servicios, cuotas ni ahorro)",
            ) {
                Text(Formato.dinero(s.total, m), style = EstiloMontoGrande)
                BarraProgreso(if (s.presupuestoSemanal > 0) (s.total / s.presupuestoSemanal).toFloat() else 0f,
                    if (s.desvio > 15) ColoresApp.negativo else MaterialTheme.colorScheme.primary)
                GraficoBarras(s.gastoPorDia, dias, limite = if (s.presupuestoSemanal > 0) s.presupuestoSemanal / 7 else null, resaltado = s.diasTranscurridos - 1)
                Text("La línea punteada es tu límite diario.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilaDato("Presupuesto de la semana", Formato.dinero(s.presupuestoSemanal, m))
                FilaDato("Esperado a hoy (${s.diasTranscurridos} de 7 días)", Formato.dinero(s.esperadoHastaHoy, m))
                FilaDato(
                    if (s.disponible >= 0) "Te queda para la semana" else "Te pasaste",
                    Formato.dinero(kotlin.math.abs(s.disponible), m),
                    colorValor = if (s.disponible >= 0) ColoresApp.positivo else ColoresApp.negativo, destacado = true,
                )
                FilaDato("Semana anterior", Formato.dinero(s.totalSemanaAnterior, m))
                if (s.presupuestoSemanal > 0 && s.total > 0) {
                    Text(
                        if (s.desvio > 0) "Vas ${Formato.porcentaje(s.desvio)} por encima del ritmo. Ajustá ahora y no a fin de mes."
                        else "Vas ${Formato.porcentaje(-s.desvio)} por debajo del ritmo. ¡Bien!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (s.desvio > 15) ColoresApp.negativo else ColoresApp.positivo,
                    )
                }
            }
        }
        if (s.topCategorias.isNotEmpty()) {
            item {
                Seccion("¿En qué se fue?") {
                    s.topCategorias.forEach { (id, monto) ->
                        val c = a.datos.categoria(id)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AvatarCategoria(c, 32.dp)
                            Text(c?.nombre ?: "?", Modifier.weight(1f))
                            Text(Formato.dinero(monto, m), style = EstiloMonto)
                        }
                    }
                }
            }
        }
        item {
            val mes = a.mes
            val diasMes = a.hoy.lengthOfMonth()
            val proyectado = mes.variable / a.hoy.dayOfMonth * diasMes
            val asignadoVariable = a.presupuesto.lineas.filter { MotorPresupuesto.esVariable(it.categoria.grupo, it.categoria.fijo) }.sumOf { it.asignado }
            Seccion("Cierre de mes estimado", subtitulo = "Si seguís al mismo ritmo de gasto variable") {
                FilaDato("Gastado en variables", Formato.dinero(mes.variable, m))
                FilaDato("Proyección al día $diasMes", Formato.dinero(proyectado, m), destacado = true,
                    colorValor = if (asignadoVariable > 0 && proyectado > asignadoVariable) ColoresApp.negativo else Color.Unspecified)
                if (asignadoVariable > 0) FilaDato("Presupuestado", Formato.dinero(asignadoVariable, m))
            }
        }
    }
}

// =========================================================================================
// Proyección a 3 y 9 meses + eventos extraordinarios
// =========================================================================================

@Composable
private fun TabProyeccion(a: Analisis, vm: AppViewModel, modifier: Modifier) {
    var horizonte by rememberSaveable { mutableStateOf(9) }
    var editando by remember { mutableStateOf<EventoPlanificado?>(null) }
    var nuevo by remember { mutableStateOf(false) }
    val p = a.proyeccion
    val meses = p.meses.take(horizonte)
    val m = a.moneda
    LazyColumn(modifier, contentPadding = RELLENO_LISTA, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Seccion("Proyección", subtitulo = if (p.mesesDeHistoria > 0) "Promedio de tus últimos ${plural(p.mesesDeHistoria, "mes", "meses")} + eventos planificados" else "Sin historia todavía: estimado con tu ingreso y presupuesto") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(3 to "3 meses (operativa)", 9 to "9 meses (estratégica)").forEachIndexed { i, (n, t) ->
                        SegmentedButton(selected = horizonte == n, onClick = { horizonte = n }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(t, maxLines = 1) }
                    }
                }
                FilaDato("Ingreso mensual base", Formato.dinero(p.ingresoBase, m))
                FilaDato("Gasto mensual base", Formato.dinero(p.gastoBase, m))
                GraficoLineas(
                    series = listOf(meses.map { it.saldoAcumulado } to MaterialTheme.colorScheme.primary, meses.map { it.libre } to ColoresApp.advertencia),
                    etiquetas = meses.map { Formato.mesCorto(it.mes) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("— Acumulado", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    Text("— Libre de cada mes", color = ColoresApp.advertencia, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (p.provisionMensual > 0) {
            item {
                Seccion("💡 Separá ${Formato.dinero(p.provisionMensual, m)} por mes") {
                    Text(
                        "Así llegás a tus gastos extraordinarios sin endeudarte. Es la suma de cada evento dividido por los meses que faltan.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        items(meses, key = { it.mes.toString() }) { mp ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Formato.mes(mp.mes), style = MaterialTheme.typography.bodyMedium)
                    if (mp.eventos.isNotEmpty()) {
                        Text(
                            mp.eventos.joinToString { "${if (it.tipo == TipoMovimiento.INGRESO) "+" else "-"}${it.nombre}" },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Formato.dinero(mp.libre, m), style = EstiloMonto,
                        color = if (mp.libre >= 0) ColoresApp.positivo else ColoresApp.negativo,
                    )
                    Text("acum. ${Formato.dinero(mp.saldoAcumulado, m)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Seccion(
                "Gastos e ingresos extraordinarios",
                subtitulo = "Impuestos anuales, vacaciones, matrículas, aguinaldo…",
                accion = { IconButton(onClick = { nuevo = true }) { Icon(Icons.Filled.Add, contentDescription = "Agregar evento") } },
            ) {
                if (a.datos.eventos.isEmpty()) {
                    Text("Todavía no cargaste ninguno. Tocá + para anticiparte.", style = MaterialTheme.typography.bodySmall)
                }
                a.datos.eventos.sortedBy { it.mes }.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { editando = e }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.nombre, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                Formato.mes(mesDeClave(e.mes)) + if (e.repetirAnual) " · todos los años" else "",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            (if (e.tipo == TipoMovimiento.INGRESO) "+" else "-") + Formato.dinero(e.monto, m), style = EstiloMonto,
                            color = if (e.tipo == TipoMovimiento.INGRESO) ColoresApp.positivo else MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { vm.borrarEvento(e) }) { Icon(Icons.Filled.Delete, contentDescription = "Borrar") }
                    }
                }
            }
        }
    }
    if (nuevo || editando != null) {
        DialogoEvento(
            inicial = editando,
            mesInicial = YearMonth.from(a.hoy).plusMonths(1),
            moneda = m,
            onGuardar = { vm.guardarEvento(it) },
            onCerrar = { nuevo = false; editando = null },
        )
    }
}

@Composable
private fun DialogoEvento(inicial: EventoPlanificado?, mesInicial: YearMonth, moneda: String, onGuardar: (EventoPlanificado) -> Unit, onCerrar: () -> Unit) {
    var nombre by remember { mutableStateOf(inicial?.nombre ?: "") }
    var monto by remember { mutableStateOf(inicial?.monto?.let { Formato.numeroEditable(it) } ?: "") }
    var tipo by remember { mutableStateOf(inicial?.tipo ?: TipoMovimiento.GASTO) }
    var mes by remember { mutableStateOf(inicial?.let { mesDeClave(it.mes) } ?: mesInicial) }
    var anual by remember { mutableStateOf(inicial?.repetirAnual ?: false) }
    HojaFormulario(
        titulo = if (inicial == null) "Nuevo evento" else "Editar evento",
        onCerrar = onCerrar,
        botones = {
            TextButton(onClick = onCerrar) { Text("Cancelar") }
            Button(
                enabled = nombre.isNotBlank() && (leerNumero(monto) ?: 0.0) > 0,
                onClick = {
                    onGuardar(
                        EventoPlanificado(
                            id = inicial?.id ?: 0, nombre = nombre.trim(), monto = leerNumero(monto) ?: 0.0,
                            tipo = tipo, mes = mes.clave(), repetirAnual = anual,
                        ),
                    )
                    onCerrar()
                },
            ) { Text("Guardar") }
        },
    ) {
        OutlinedTextField(nombre, { nombre = it.take(40) }, label = { Text("Nombre (ej: Patente)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        CampoDinero(monto, { monto = it }, "Monto", moneda = moneda)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TipoMovimiento.entries.forEachIndexed { i, t ->
                SegmentedButton(selected = tipo == t, onClick = { tipo = t }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(t.etiqueta) }
            }
        }
        SelectorMes(mes, { mes = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = anual, onCheckedChange = { anual = it })
            Spacer(Modifier.width(4.dp))
            Text("Se repite todos los años")
        }
    }
}

// =========================================================================================
// Fugas: gastos hormiga, suscripciones y capacidad de reacción
// =========================================================================================

@Composable
private fun TabFugas(a: Analisis, modifier: Modifier) {
    val m = a.moneda
    LazyColumn(modifier, contentPadding = RELLENO_LISTA, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Seccion("🐜 Gastos hormiga", subtitulo = "Compras de hasta ${Formato.dinero(a.umbralHormiga, m)} que se repiten (últimos 30 días)") {
                if (a.fugas.isEmpty()) Text("No detecté gastos hormiga. 👏", style = MaterialTheme.typography.bodyMedium)
                a.fugas.forEach { f ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AvatarCategoria(f.categoria, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(f.categoria.nombre, style = MaterialTheme.typography.bodyMedium)
                            Text("${f.cantidadMes} compras · promedio ${Formato.dinero(f.promedio, m)}", style = MaterialTheme.typography.labelSmall)
                            Text(f.ejemplos.joinToString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "Invertido 10 años: ${Formato.dinero(f.costoOportunidad10Anios, m)}",
                                style = MaterialTheme.typography.labelSmall, color = ColoresApp.positivo,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${Formato.dinero(f.totalMes, m)}/mes", style = EstiloMonto)
                            Text("${Formato.dinero(f.totalAnual, m)}/año", style = MaterialTheme.typography.labelSmall, color = ColoresApp.negativo)
                        }
                    }
                }
            }
        }
        item {
            val total = a.suscripciones.sumOf { it.montoMensual }
            Seccion("📺 Suscripciones", subtitulo = "Pagos fijos que se repiten todos los meses") {
                if (a.suscripciones.isEmpty()) Text("No detecté suscripciones recurrentes.", style = MaterialTheme.typography.bodyMedium)
                a.suscripciones.forEach { s ->
                    FilaDato("${s.nombre} (último: ${Formato.fechaCorta(s.ultimaFecha)})", "${Formato.dinero(s.montoMensual, m)}/mes")
                }
                if (total > 0) FilaDato("Total por año", Formato.dinero(total * 12, m), colorValor = ColoresApp.negativo, destacado = true)
            }
        }
        item {
            val ind = a.indicadores
            Seccion("🛟 Capacidad de reacción", subtitulo = "Qué parte de tus gastos podrías recortar ya mismo si perdieras tus ingresos") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Medidor(ind.capacidadReaccion, 20.0, 100.0, "recortable", Formato.porcentaje(ind.capacidadReaccion), mayorEsMejor = true)
                    Text(
                        "Podrías recortar unos ${Formato.dinero(ind.gastoMensualPromedio * ind.capacidadReaccion / 100, m)} por mes. " +
                            "El resto (${Formato.dinero(ind.gastoBasicoMensual, m)} de gastos básicos) es lo que debe cubrir tu fondo de emergencia.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

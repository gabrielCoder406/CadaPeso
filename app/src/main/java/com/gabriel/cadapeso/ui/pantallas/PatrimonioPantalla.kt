@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gabriel.cadapeso.ui.pantallas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gabriel.cadapeso.datos.Activo
import com.gabriel.cadapeso.datos.Deuda
import com.gabriel.cadapeso.datos.Meta
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.ClaseDeuda
import com.gabriel.cadapeso.dominio.DeudaAnalizada
import com.gabriel.cadapeso.dominio.EstadoMeta
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.MotorAhorro
import com.gabriel.cadapeso.dominio.MotorDeudas
import com.gabriel.cadapeso.dominio.PerfilRiesgo
import com.gabriel.cadapeso.dominio.TipoActivo
import com.gabriel.cadapeso.dominio.leerNumero
import com.gabriel.cadapeso.ui.LocalAvisos
import com.gabriel.cadapeso.ui.AppViewModel
import com.gabriel.cadapeso.ui.Navegacion
import com.gabriel.cadapeso.ui.Superpuesta
import com.gabriel.cadapeso.ui.TipoTest
import com.gabriel.cadapeso.ui.componentes.BarraConMeta
import com.gabriel.cadapeso.ui.componentes.BarraProgreso
import com.gabriel.cadapeso.ui.componentes.CampoDinero
import com.gabriel.cadapeso.ui.componentes.DialogoConfirmar
import com.gabriel.cadapeso.ui.componentes.Etiqueta
import com.gabriel.cadapeso.ui.componentes.FilaDato
import com.gabriel.cadapeso.ui.componentes.GraficoLineas
import com.gabriel.cadapeso.ui.componentes.Medidor
import com.gabriel.cadapeso.ui.componentes.Seccion
import com.gabriel.cadapeso.ui.tema.ColoresApp
import com.gabriel.cadapeso.ui.tema.EstiloMonto
import com.gabriel.cadapeso.ui.tema.EstiloMontoGrande
import java.time.YearMonth
import kotlin.math.roundToInt

@Composable
fun PatrimonioPantalla(a: Analisis, nav: Navegacion, vm: AppViewModel) {
    val pestanias = listOf("Balance", "Deudas", "Ahorro y metas", "Invertir")
    Scaffold(
        snackbarHost = { SnackbarHost(LocalAvisos.current) },
        topBar = {
            Column {
                TopAppBar(title = { Text("Patrimonio y salud financiera") })
                PrimaryScrollableTabRow(selectedTabIndex = nav.tabPatrimonio, edgePadding = 8.dp) {
                    pestanias.forEachIndexed { i, t ->
                        Tab(selected = nav.tabPatrimonio == i, onClick = { nav.tabPatrimonio = i }, text = { Text(t, maxLines = 1) })
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val modificador = Modifier.padding(padding)
        when (nav.tabPatrimonio) {
            0 -> TabBalance(a, nav, vm, modificador)
            1 -> TabDeudas(a, vm, modificador)
            2 -> TabAhorro(a, vm, modificador)
            else -> TabInvertir(a, nav, modificador)
        }
    }
}

private val RELLENO = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)

// =========================================================================================
// Balance: activos, pasivos e indicadores
// =========================================================================================

@Composable
private fun TabBalance(a: Analisis, nav: Navegacion, vm: AppViewModel, modifier: Modifier) {
    val m = a.moneda
    val ind = a.indicadores
    var editando by remember { mutableStateOf<Activo?>(null) }
    var nuevo by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = RELLENO, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Seccion("Patrimonio neto", subtitulo = "Lo que tenés menos lo que debés") {
                Text(Formato.dinero(a.patrimonio.neto, m), style = EstiloMontoGrande, color = if (a.patrimonio.neto >= 0) MaterialTheme.colorScheme.onSurface else ColoresApp.negativo)
                FilaDato("Activos (valor de mercado)", Formato.dinero(a.patrimonio.activos, m), colorValor = ColoresApp.positivo)
                FilaDato("  · líquidos", Formato.dinero(a.patrimonio.activosLiquidos, m))
                FilaDato("  · que generan ingresos", Formato.dinero(a.patrimonio.activosQueRinden, m))
                FilaDato("Pasivos (saldo de deudas)", Formato.dinero(a.patrimonio.pasivos, m), colorValor = ColoresApp.negativo)
            }
        }
        item {
            Seccion("Indicadores", subtitulo = "La línea marca el límite sano") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Medidor(ind.ratioEndeudamiento, 30.0, 60.0, "Cuotas / ingreso", Formato.porcentaje(ind.ratioEndeudamiento))
                    Medidor(ind.usoTarjeta, 30.0, 60.0, "Tarjeta / ingreso", Formato.porcentaje(ind.usoTarjeta))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Medidor(ind.capacidadReaccion, 20.0, 100.0, "Capacidad de reacción", Formato.porcentaje(ind.capacidadReaccion), mayorEsMejor = true)
                    Medidor(ind.tasaAhorro, 20.0, 50.0, "Tasa de ahorro", Formato.porcentaje(ind.tasaAhorro), mayorEsMejor = true)
                }
                FilaDato("Meses de supervivencia", String.format(Formato.locale, "%.1f meses", ind.mesesSupervivencia))
                Text(
                    "Endeudamiento y tarjeta: alerta si pasan el 30% del ingreso. Capacidad de reacción: qué parte de tus gastos podrías recortar ya mismo ante una crisis.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Seccion(
                "Activos", subtitulo = "Cuentas, ahorros, inversiones y bienes",
                accion = { IconButton(onClick = { nuevo = true }) { Icon(Icons.Filled.Add, contentDescription = "Agregar activo") } },
            ) {
                if (a.datos.activos.isEmpty()) Text("Cargá tus cuentas y bienes para calcular tu patrimonio.", style = MaterialTheme.typography.bodySmall)
                a.datos.activos.sortedByDescending { it.valor }.forEach { activo ->
                    Row(Modifier.fillMaxWidth().clickable { editando = activo }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(activo.tipo.emoji, Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(activo.nombre, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(
                                    if (activo.liquido) "líquido" else null,
                                    if (activo.generaIngresos) "genera ingresos" else null,
                                    if (activo.esFondoEmergencia) "fondo de emergencia" else null,
                                ).joinToString(" · ").ifBlank { activo.tipo.etiqueta },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Formato.dinero(activo.valor, m), style = EstiloMonto)
                    }
                }
            }
        }
        item {
            Seccion("Pasivos", subtitulo = "Saldo pendiente de tus deudas", onClick = { nav.tabPatrimonio = 1 }) {
                if (a.deudas.deudas.isEmpty()) Text("Sin deudas cargadas. 🙌", style = MaterialTheme.typography.bodySmall)
                a.deudas.deudas.forEach { d -> FilaDato("${d.deuda.tipo.emoji} ${d.deuda.nombre}", Formato.dinero(d.deuda.saldo, m)) }
                Text("Ver plan de pago ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (nuevo || editando != null) {
        DialogoActivo(editando, m, a, onGuardar = { vm.guardarActivo(it) }, onBorrar = { vm.borrarActivo(it) }, onCerrar = { nuevo = false; editando = null })
    }
}

// =========================================================================================
// Deudas: clasificación y plan avalancha
// =========================================================================================

@Composable
private fun TabDeudas(a: Analisis, vm: AppViewModel, modifier: Modifier) {
    val m = a.moneda
    val perfil = a.datos.perfil
    var extraTexto by rememberSaveable { mutableStateOf("") }
    val extra = leerNumero(extraTexto) ?: 0.0
    val plan = remember(a.datos.deudas, extra, perfil.inflacionAnual, perfil.umbralTasaMala) {
        MotorDeudas.plan(a.datos.deudas, perfil.inflacionAnual, perfil.umbralTasaMala, extra)
    }
    var editando by remember { mutableStateOf<Deuda?>(null) }
    var nueva by remember { mutableStateOf(false) }
    var pagando by remember { mutableStateOf<Deuda?>(null) }
    var borrando by remember { mutableStateOf<Deuda?>(null) }

    LazyColumn(modifier, contentPadding = RELLENO, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Seccion(
                "Tus deudas",
                subtitulo = "Malas: consumo caro (más de ${Formato.porcentaje(perfil.umbralTasaMala)} real). Buenas: financian algo que rinde más que su costo.",
                accion = { IconButton(onClick = { nueva = true }) { Icon(Icons.Filled.Add, contentDescription = "Agregar deuda") } },
            ) {
                FilaDato("Saldo total", Formato.dinero(plan.totalSaldo, m), destacado = true)
                FilaDato("Cuotas por mes", Formato.dinero(plan.totalCuotas, m))
                FilaDato(
                    "Cuotas / ingreso", Formato.porcentaje(a.indicadores.ratioEndeudamiento),
                    colorValor = if (a.indicadores.ratioEndeudamiento > 30) ColoresApp.negativo else ColoresApp.positivo,
                )
                if (perfil.inflacionAnual > 0) {
                    Text("Tasas reales calculadas con ${Formato.porcentaje(perfil.inflacionAnual)} de inflación anual.", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (plan.deudas.isNotEmpty()) {
            item {
                Seccion("Plan avalancha", subtitulo = "Pagá el mínimo en todas y todo lo extra a la de tasa más alta") {
                    CampoDinero(extraTexto, { extraTexto = it }, "Pago extra por mes (opcional)", moneda = m)
                    val r = plan.avalancha
                    if (r.esViable) {
                        val fin = YearMonth.from(a.hoy).plusMonths(r.meses.toLong())
                        FilaDato("Libre de deudas en", "${r.meses} meses (${Formato.mes(fin).lowercase()})", destacado = true)
                        FilaDato("Intereses que vas a pagar", Formato.dinero(r.interesTotal, m), colorValor = ColoresApp.negativo)
                        FilaDato("Con bola de nieve serían", Formato.dinero(plan.bolaDeNieve.interesTotal, m))
                        if (plan.ahorroAvalancha > 1) Text("✅ Avalancha te ahorra ${Formato.dinero(plan.ahorroAvalancha, m)} en intereses.", color = ColoresApp.positivo, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            "⚠️ Con estos pagos alguna deuda no se termina nunca: los intereses le ganan a la cuota. Sumá un pago extra o refinanciá.",
                            color = ColoresApp.negativo, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text("Orden de cancelación:", style = MaterialTheme.typography.labelLarge)
                    r.orden.forEachIndexed { i, paso ->
                        FilaDato(
                            "${i + 1}. ${paso.deuda.nombre} (${Formato.porcentaje(paso.deuda.tasaAnual, 1)})",
                            if (paso.mesLiquidacion < 600) "mes ${paso.mesLiquidacion}" else "—",
                        )
                    }
                }
            }
        }
        items(plan.deudas, key = { it.deuda.id }) { d ->
            TarjetaDeuda(d, m, perfil.inflacionAnual > 0, onPagar = { pagando = d.deuda }, onEditar = { editando = d.deuda }, onBorrar = { borrando = d.deuda })
        }
        val pagadas = a.datos.deudas.filter { it.saldo <= 0.01 }
        if (pagadas.isNotEmpty()) {
            item {
                Seccion("🎉 Deudas canceladas") {
                    pagadas.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${d.tipo.emoji} ${d.nombre}", Modifier.weight(1f))
                            TextButton(onClick = { vm.borrarDeuda(d) }) { Text("Quitar") }
                        }
                    }
                }
            }
        }
    }
    if (nueva || editando != null) {
        DialogoDeuda(editando, m, a.hoy, onGuardar = { vm.guardarDeuda(it) }, onCerrar = { nueva = false; editando = null })
    }
    pagando?.let { d ->
        DialogoMonto(
            titulo = "Pagar ${d.nombre}",
            inicial = d.cuotaMensual.coerceAtMost(d.saldo),
            moneda = m,
            ayuda = "Se registra como gasto y baja el saldo (${Formato.dinero(d.saldo, m)})",
            onAceptar = { if (it > 0) vm.pagarDeuda(d, it, com.gabriel.cadapeso.dominio.MedioPago.TRANSFERENCIA) },
            onCerrar = { pagando = null },
        )
    }
    borrando?.let { d ->
        DialogoConfirmar("Borrar deuda", "Se borra \"${d.nombre}\". Los pagos ya registrados quedan como movimientos.", "Borrar",
            onConfirmar = { vm.borrarDeuda(d) }, onCerrar = { borrando = null }, peligroso = true)
    }
}

@Composable
private fun TarjetaDeuda(d: DeudaAnalizada, m: String, mostrarReal: Boolean, onPagar: () -> Unit, onEditar: () -> Unit, onBorrar: () -> Unit) {
    val color = when (d.clase) {
        ClaseDeuda.MALA -> ColoresApp.negativo
        ClaseDeuda.BUENA -> ColoresApp.positivo
        ClaseDeuda.NEUTRA -> ColoresApp.advertencia
    }
    Seccion("${d.deuda.tipo.emoji} ${d.deuda.nombre}", subtitulo = d.deuda.tipo.etiqueta, accion = { Etiqueta(d.clase.etiqueta, color) }) {
        FilaDato("Saldo", Formato.dinero(d.deuda.saldo, m), destacado = true)
        FilaDato("Tasa (TNA)" + if (d.deuda.ajustaPorInflacion) " + UVA" else "", Formato.porcentaje(d.deuda.tasaAnual, 1))
        if (mostrarReal && !d.deuda.ajustaPorInflacion) FilaDato("Tasa real", Formato.porcentaje(d.tasaReal, 1))
        FilaDato("Cuota / pago mínimo", Formato.dinero(d.deuda.cuotaMensual, m))
        FilaDato("Interés de este mes", Formato.dinero(d.interesMensual, m), colorValor = ColoresApp.negativo)
        if (d.trampaPagoMinimo) {
            Text("⚠️ Trampa del pago mínimo: la cuota casi no baja el saldo. Pagá más que el mínimo.", color = ColoresApp.negativo, style = MaterialTheme.typography.bodySmall)
        }
        Text(d.motivo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPagar) { Text("Registrar pago") }
            TextButton(onClick = onEditar) { Text("Editar") }
            TextButton(onClick = onBorrar) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
        }
    }
}

// =========================================================================================
// Ahorro: págate primero, fondo de emergencia y metas SMART
// =========================================================================================

@Composable
private fun TabAhorro(a: Analisis, vm: AppViewModel, modifier: Modifier) {
    val m = a.moneda
    val p = a.pagatePrimero
    val f = a.fondoEmergencia
    var editandoMeta by remember { mutableStateOf<Meta?>(null) }
    var nuevaMeta by remember { mutableStateOf(false) }
    var aportando by remember { mutableStateOf<Meta?>(null) }
    var borrandoMeta by remember { mutableStateOf<Meta?>(null) }
    var aportarFondo by remember { mutableStateOf<Activo?>(null) }
    var porcentaje by remember(a.datos.perfil.porcentajePagatePrimero) { mutableFloatStateOf(a.datos.perfil.porcentajePagatePrimero.toFloat()) }

    LazyColumn(modifier, contentPadding = RELLENO, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Seccion("💪 Págate a ti primero", subtitulo = "Apenas cobrás (día ${p.diaCobro}), separá tu ahorro como si fuera un gasto fijo") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${porcentaje.roundToInt()}% del ingreso", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("= ${Formato.dinero(a.indicadores.ingresoReferencia * porcentaje.roundToInt() / 100, m)}", style = EstiloMonto)
                }
                Slider(
                    value = porcentaje, onValueChange = { porcentaje = it }, valueRange = 5f..30f, steps = 24,
                    onValueChangeFinished = { vm.guardarPerfil(a.datos.perfil.copy(porcentajePagatePrimero = porcentaje.roundToInt().toDouble())) },
                )
                FilaDato("Ahorrado este mes", Formato.dinero(p.ahorradoMes, m))
                BarraProgreso(if (p.objetivoMes > 0) (p.ahorradoMes / p.objetivoMes).toFloat() else 0f, if (p.cumplido) ColoresApp.positivo else ColoresApp.advertencia)
                if (p.cumplido) {
                    Text("✅ ¡Cumplido este mes!", color = ColoresApp.positivo)
                } else if (p.faltante > 0) {
                    FilledTonalButton(onClick = { vm.registrarAhorro(p.faltante) }) { Text("Registrar ahorro de ${Formato.dinero(p.faltante, m)}") }
                    Text(
                        "La app no mueve tu dinero: hacé la transferencia a tu cuenta de ahorro (o programala en tu banco) y registrala acá.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Seccion("🛟 Fondo de emergencia", subtitulo = "Tu airbag: ${f.mesesMin} a ${f.mesesMax} meses de gastos básicos, en algo líquido y de bajo riesgo") {
                Text(
                    "${String.format(Formato.locale, "%.1f", f.mesesCubiertos)} meses cubiertos · ${f.estado.etiqueta}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (f.mesesCubiertos >= f.mesesMin) ColoresApp.positivo else ColoresApp.advertencia,
                )
                BarraConMeta(f.mesesCubiertos.toFloat(), f.mesesMin.toFloat(), if (f.mesesCubiertos >= f.mesesMin) ColoresApp.positivo else ColoresApp.advertencia, escala = f.mesesMax.toFloat())
                Row {
                    Text("0", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text("mín. ${f.mesesMin}", style = MaterialTheme.typography.labelSmall)
                    Text("   máx. ${f.mesesMax} meses", style = MaterialTheme.typography.labelSmall)
                }
                FilaDato("Gasto básico mensual", Formato.dinero(f.gastoBasicoMensual, m))
                FilaDato("Acumulado", Formato.dinero(f.acumulado, m), destacado = true)
                FilaDato("Objetivo mínimo", Formato.dinero(f.objetivoMin, m))
                FilaDato("Objetivo ideal", Formato.dinero(f.objetivoMax, m))
                if (f.faltanteMin > 0) FilaDato("Te falta (mínimo)", Formato.dinero(f.faltanteMin, m), colorValor = ColoresApp.advertencia)
                if (f.cuentas.isEmpty()) {
                    Text("Todavía no marcaste ninguna cuenta como fondo de emergencia.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        vm.guardarActivo(
                            Activo(nombre = "Fondo de emergencia", tipo = TipoActivo.AHORRO_LIQUIDO, valor = 0.0, liquido = true,
                                generaIngresos = true, esFondoEmergencia = true, actualizado = a.hoy),
                        )
                    }) { Text("Crear cuenta para el fondo") }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        f.cuentas.forEach { cuenta ->
                            FilledTonalButton(onClick = { aportarFondo = cuenta }) { Text("Aportar a ${cuenta.nombre}") }
                        }
                    }
                }
            }
        }
        item {
            Seccion(
                "🎯 Metas SMART",
                subtitulo = "Específicas, Medibles, Alcanzables, Relevantes y con Tiempo",
                accion = { IconButton(onClick = { nuevaMeta = true }) { Icon(Icons.Filled.Add, contentDescription = "Nueva meta") } },
            ) {
                FilaDato("Tu capacidad de ahorro mensual", Formato.dinero(a.indicadores.capacidadAhorroMensual, m))
                if (a.metas.isEmpty()) Text("Creá tu primera meta: un viaje, un fondo para el auto, la entrada de una casa…", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(a.metas, key = { it.meta.id }) { e ->
            TarjetaMeta(e, m, onAportar = { aportando = e.meta }, onEditar = { editandoMeta = e.meta }, onBorrar = { borrandoMeta = e.meta })
        }
    }

    if (nuevaMeta || editandoMeta != null) {
        DialogoMeta(editandoMeta, m, a.hoy, onGuardar = { vm.guardarMeta(it) }, onCerrar = { nuevaMeta = false; editandoMeta = null })
    }
    aportando?.let { meta ->
        val proxima = a.metas.firstOrNull { it.meta.id == meta.id }?.proximasCuotas?.firstOrNull()?.monto
        DialogoMonto(
            "Aportar a ${meta.nombre}", proxima ?: 0.0, m,
            ayuda = proxima?.let { "Según tu plan incremental, la próxima cuota es ${Formato.dinero(it, m)}" },
            onAceptar = { if (it > 0) vm.aportarAMeta(meta, it) }, onCerrar = { aportando = null },
        )
    }
    aportarFondo?.let { cuenta ->
        DialogoMonto(
            "Aportar al fondo (${cuenta.nombre})", 0.0, m, ayuda = "Se registra como ahorro y suma al saldo de la cuenta",
            onAceptar = { if (it > 0) vm.aportarAFondo(cuenta, it) }, onCerrar = { aportarFondo = null },
        )
    }
    borrandoMeta?.let { meta ->
        DialogoConfirmar("Borrar meta", "Se borra \"${meta.nombre}\". Los aportes quedan registrados como ahorro.", "Borrar",
            onConfirmar = { vm.borrarMeta(meta) }, onCerrar = { borrandoMeta = null }, peligroso = true)
    }
}

@Composable
private fun TarjetaMeta(e: EstadoMeta, m: String, onAportar: () -> Unit, onEditar: () -> Unit, onBorrar: () -> Unit) {
    val meta = e.meta
    Seccion("${meta.emoji} ${meta.nombre}", subtitulo = meta.especifica.ifBlank { null }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Formato.dinero(e.acumulado, m), style = MaterialTheme.typography.titleLarge.merge(EstiloMonto))
            Text(" de ${Formato.dinero(meta.montoObjetivo, m)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BarraProgreso(e.progreso.toFloat(), if (e.completa) ColoresApp.positivo else if (e.enRiesgo) ColoresApp.advertencia else MaterialTheme.colorScheme.primary, alto = 10.dp)
        if (e.completa) {
            Text("🎉 ¡Meta cumplida!", color = ColoresApp.positivo, style = MaterialTheme.typography.titleSmall)
        } else {
            FilaDato("Fecha límite", Formato.fechaLarga(meta.fechaLimite))
            FilaDato("Necesitás por mes", Formato.dinero(e.aporteMensualNecesario, m), colorValor = if (e.enRiesgo) ColoresApp.advertencia else Color.Unspecified)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("S" to e.smart.especifica, "M" to e.smart.medible, "A" to e.smart.alcanzable, "R" to e.smart.relevante, "T" to e.smart.temporal)
                .forEach { (letra, ok) -> Etiqueta(letra + if (ok) " ✓" else " ✗", if (ok) ColoresApp.positivo else ColoresApp.advertencia) }
        }
        if (!e.smart.relevante) Text("Agregá por qué te importa: las metas con sentido se cumplen más.", style = MaterialTheme.typography.labelSmall)
        if (e.proximasCuotas.isNotEmpty()) {
            Text("Ahorro incremental (${meta.frecuencia.etiqueta.lowercase()}):", style = MaterialTheme.typography.labelLarge)
            e.proximasCuotas.take(4).forEach { c -> FilaDato("${Formato.fechaCorta(c.fecha)} · cuota ${c.numero}", Formato.dinero(c.monto, m)) }
            e.fechaEstimadaPlan?.let { Text("Con este plan la completás el ${Formato.fechaLarga(it)}.", style = MaterialTheme.typography.bodySmall) }
        }
        if (e.enRiesgo && !e.completa) {
            Text("⚠️ En riesgo: extendé el plazo, bajá el monto o liberá un gasto variable.", color = ColoresApp.advertencia, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!e.completa) FilledTonalButton(onClick = onAportar) { Text("Aportar") }
            TextButton(onClick = onEditar) { Text("Editar") }
            TextButton(onClick = onBorrar) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
        }
    }
}

// =========================================================================================
// Invertir: hoja de ruta, perfil, simulador DCA y factor sueño
// =========================================================================================

@Composable
private fun TabInvertir(a: Analisis, nav: Navegacion, modifier: Modifier) {
    val m = a.moneda
    val riesgo = a.perfil.riesgoEfectivo
    val sugerido = maxOf(a.indicadores.capacidadAhorroMensual * 0.5, a.indicadores.ingresoReferencia * 0.05)
    var aporteTexto by rememberSaveable { mutableStateOf(Formato.numeroEditable(Math.round(sugerido / 1000.0) * 1000.0)) }
    var inicialTexto by rememberSaveable { mutableStateOf("") }
    var anios by rememberSaveable { mutableStateOf(10f) }
    var rendimiento by rememberSaveable { mutableStateOf(riesgo.rendimientoRealAnual.toFloat()) }
    val aporte = leerNumero(aporteTexto) ?: 0.0
    val inicial = leerNumero(inicialTexto) ?: 0.0
    val puntos = remember(aporte, inicial, anios, rendimiento) {
        MotorAhorro.simularDCA(aporte, inicial, rendimiento.toDouble(), anios.roundToInt())
    }
    val final = puntos.last()
    val perfilSimulado = PerfilRiesgo.entries.minBy { kotlin.math.abs(it.rendimientoRealAnual - rendimiento) }

    LazyColumn(modifier, contentPadding = RELLENO, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Seccion("🗺️ Hoja de ruta", subtitulo = "Invertir es el paso 4: los anteriores te protegen") {
                a.hojaDeRuta.pasos.forEach { paso ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text((if (paso.cumplido) "✅ " else "${paso.numero}. ") + paso.titulo, style = MaterialTheme.typography.bodyMedium)
                        Text(paso.descripcion, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BarraProgreso(paso.progreso.toFloat(), if (paso.cumplido) ColoresApp.positivo else MaterialTheme.colorScheme.primary, alto = 5.dp)
                        Text(paso.detalle, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Seccion("👤 Tu perfil inversor") {
                FilaDato("Etapa de vida", "${a.perfil.etapa.etiqueta} (${a.perfil.etapa.rango})")
                Text(a.perfil.etapa.enfoque, style = MaterialTheme.typography.bodySmall)
                FilaDato("Tolerancia al riesgo", a.perfil.riesgo?.etiqueta ?: "Sin test (se asume moderado)")
                Text(riesgo.descripcion, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { nav.superpuesta = Superpuesta.Test(TipoTest.RIESGO) }) {
                    Text(if (a.perfil.riesgo == null) "Hacer el test de riesgo" else "Repetir el test")
                }
            }
        }
        item {
            Seccion("📈 Simulador de inversión periódica (DCA)", subtitulo = "Aportes fijos todos los meses + interés compuesto") {
                CampoDinero(aporteTexto, { aporteTexto = it }, "Aporte mensual", moneda = m)
                CampoDinero(inicialTexto, { inicialTexto = it }, "Aporte inicial (opcional)", moneda = m)
                Text("Plazo: ${anios.roundToInt()} años", style = MaterialTheme.typography.labelLarge)
                Slider(value = anios, onValueChange = { anios = it }, valueRange = 1f..40f, steps = 38)
                Text("Rendimiento real anual: ${String.format(Formato.locale, "%.1f", rendimiento)}% (≈ ${perfilSimulado.etiqueta.lowercase()})", style = MaterialTheme.typography.labelLarge)
                Slider(value = rendimiento, onValueChange = { rendimiento = it }, valueRange = 0f..12f)
                FilaDato("Valor final", Formato.dinero(final.valor, m), destacado = true, colorValor = ColoresApp.positivo)
                FilaDato("Total aportado", Formato.dinero(final.aportado, m))
                FilaDato("Ganancia por interés compuesto", Formato.dinero(final.ganancia, m))
                GraficoLineas(
                    series = listOf(puntos.map { it.valor } to ColoresApp.positivo, puntos.map { it.aportado } to MaterialTheme.colorScheme.outline),
                    etiquetas = puntos.map { "${it.anio}" },
                )
                Text("Verde: valor de la inversión · Gris: lo que pusiste. Rendimiento real = por encima de la inflación.", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            val caida = final.valor * perfilSimulado.caidaMalAnio / 100
            Seccion("😴 Factor sueño", subtitulo = "Elegí un riesgo que te deje dormir tranquilo") {
                Text(
                    "Una cartera ${perfilSimulado.etiqueta.lowercase()} puede caer ~${Formato.porcentaje(perfilSimulado.caidaMalAnio)} en un mal año. " +
                        "Al final del plazo serían ${Formato.dinero(caida, m)} menos en el papel durante un tiempo. ¿Dormirías tranquilo/a?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { rendimiento = PerfilRiesgo.CONSERVADOR.rendimientoRealAnual.toFloat() }) { Text("No, bajar riesgo") }
                    TextButton(onClick = { rendimiento = PerfilRiesgo.AGRESIVO.rendimientoRealAnual.toFloat() }) { Text("Sí, puedo más") }
                }
            }
        }
        val hormiga = a.fugas.sumOf { it.totalMes }
        if (hormiga > 0) {
            item {
                Seccion("☕ Tus gastos hormiga, invertidos") {
                    Text(
                        "Si invirtieras los ${Formato.dinero(hormiga, m)} por mes de gastos hormiga, en 10 años tendrías " +
                            "${Formato.dinero(MotorAhorro.valorFuturo(hormiga, riesgo.rendimientoRealAnual, 10), m)}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            Text(
                "CadaPeso es una guía educativa: no recomienda productos ni reemplaza a un asesor financiero matriculado.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

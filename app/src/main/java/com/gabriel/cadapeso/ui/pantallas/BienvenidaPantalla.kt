@file:OptIn(ExperimentalMaterial3Api::class)

package com.gabriel.cadapeso.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabriel.cadapeso.datos.Perfil
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.Perfilado
import com.gabriel.cadapeso.dominio.ReglaPresupuesto
import com.gabriel.cadapeso.dominio.TipoIngreso
import com.gabriel.cadapeso.dominio.leerNumero
import com.gabriel.cadapeso.ui.LocalAvisos
import com.gabriel.cadapeso.ui.AppViewModel
import com.gabriel.cadapeso.ui.componentes.CampoDinero
import com.gabriel.cadapeso.ui.componentes.CampoNumero
import com.gabriel.cadapeso.ui.componentes.Seccion
import kotlin.math.roundToInt

/** Bienvenida en 5 pasos: presentación, datos personales, ingresos, regla y "págate primero". */
@Composable
fun BienvenidaPantalla(perfil: Perfil, vm: AppViewModel) {
    var paso by rememberSaveable { mutableStateOf(0) }
    var nombre by rememberSaveable { mutableStateOf(perfil.nombre) }
    var edad by rememberSaveable { mutableStateOf("") }
    var ingreso by rememberSaveable { mutableStateOf("") }
    var tipo by rememberSaveable { mutableStateOf(TipoIngreso.ESTABLE) }
    var diaCobro by rememberSaveable { mutableStateOf("1") }
    var regla by rememberSaveable { mutableStateOf(ReglaPresupuesto.R_50_30_20) }
    var armar by rememberSaveable { mutableStateOf(true) }
    var pagate by rememberSaveable { mutableFloatStateOf(10f) }
    var inflacion by rememberSaveable { mutableStateOf("") }
    val total = 5

    Scaffold(snackbarHost = { SnackbarHost(LocalAvisos.current) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (paso > 0) LinearProgressIndicator(progress = { paso / (total - 1f) }, modifier = Modifier.fillMaxWidth())
            when (paso) {
                0 -> {
                    Spacer(Modifier.height(24.dp))
                    Text("🪙", fontSize = 64.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text("CadaPeso", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text(
                        "Cada peso con una tarea", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    )
                    listOf(
                        "📊 Presupuesto con la regla 50/30/20 o 60/10/10/20",
                        "🐜 Detecta gastos hormiga, suscripciones y desvíos semanales",
                        "💳 Plan para salir de deudas (método avalancha)",
                        "🛟 Fondo de emergencia, metas SMART e inversión periódica",
                        "✨ Asesor con Gemma 2 de Google, privado en tu teléfono",
                    ).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { paso = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Empezar") }
                    OutlinedButton(onClick = { vm.empezarConEjemplo() }, modifier = Modifier.fillMaxWidth()) { Text("Explorar con datos de ejemplo") }
                    Text(
                        "Tus datos se guardan solo en este teléfono.", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                1 -> {
                    Text("Contanos de vos", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(nombre, { nombre = it.take(30) }, label = { Text("¿Cómo te llamás?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    CampoNumero(edad, { edad = it }, "Edad", sufijo = "años")
                    leerNumero(edad)?.toInt()?.takeIf { it in 14..100 }?.let { e ->
                        val etapa = Perfilado.etapa(e)
                        Seccion("Etapa: ${etapa.etiqueta}", subtitulo = etapa.rango) { Text(etapa.enfoque, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                2 -> {
                    Text("Tus ingresos", style = MaterialTheme.typography.headlineSmall)
                    CampoDinero(ingreso, { ingreso = it }, "Ingreso mensual neto (lo que cobrás)")
                    Text("¿Cómo son tus ingresos?", style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        TipoIngreso.entries.forEachIndexed { i, t ->
                            SegmentedButton(selected = tipo == t, onClick = { tipo = t }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                                Text(t.etiqueta.substringBefore(" ("))
                            }
                        }
                    }
                    Text(
                        "Fondo de emergencia recomendado: ${tipo.mesesFondoMin} a ${tipo.mesesFondoMax} meses de gastos básicos.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    CampoNumero(diaCobro, { diaCobro = it }, "Día del mes en que cobrás", ayuda = "Ese día te recordamos págate a ti primero")
                }
                3 -> {
                    Text("Tu regla de presupuesto", style = MaterialTheme.typography.headlineSmall)
                    Text("Le da una tarea a cada peso antes de que empiece el mes.", style = MaterialTheme.typography.bodyMedium)
                    ReglaPresupuesto.entries.forEach { r ->
                        Seccion(r.etiqueta, subtitulo = r.resumen) {
                            Row(Modifier.fillMaxWidth().selectable(selected = regla == r, role = Role.RadioButton) { regla = r }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = regla == r, onClick = null)
                                Column(Modifier.padding(start = 8.dp)) {
                                    r.partidas.forEach { p -> Text("${Formato.porcentaje(p.porcentaje)} ${p.nombre}: ${p.explicacion}", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = armar, onCheckedChange = { armar = it })
                        Text("Armar mi presupuesto de este mes con esta regla")
                    }
                }
                else -> {
                    Text("Págate a ti primero", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Apenas cobrás, separá una parte para vos como si fuera un gasto fijo. Empezar con 10% es una gran base.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val ingresoNum = leerNumero(ingreso) ?: 0.0
                    Text(
                        "${pagate.roundToInt()}% " + if (ingresoNum > 0) "= ${Formato.dinero(ingresoNum * pagate.roundToInt() / 100)} por mes" else "",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Slider(value = pagate, onValueChange = { pagate = it }, valueRange = 5f..30f, steps = 24)
                    CampoNumero(
                        inflacion, { inflacion = it }, "Inflación anual esperada (opcional)", sufijo = "%",
                        ayuda = "Sirve para saber si una deuda es cara de verdad (tasa real)",
                    )
                }
            }
            if (paso > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { paso-- }) { Text("Atrás") }
                    Spacer(Modifier.weight(1f))
                    if (paso < total - 1) {
                        Button(onClick = { paso++ }) { Text("Siguiente") }
                    } else {
                        Button(onClick = {
                            vm.completarBienvenida(
                                perfil.copy(
                                    nombre = nombre.trim(),
                                    edad = leerNumero(edad)?.toInt()?.coerceIn(14, 100) ?: 30,
                                    ingresoMensual = leerNumero(ingreso) ?: 0.0,
                                    tipoIngreso = tipo,
                                    diaCobro = leerNumero(diaCobro)?.toInt()?.coerceIn(1, 28) ?: 1,
                                    regla = regla,
                                    porcentajePagatePrimero = pagate.roundToInt().toDouble(),
                                    inflacionAnual = leerNumero(inflacion) ?: 0.0,
                                ),
                                armarPresupuesto = armar,
                            )
                        }) { Text("¡Listo!") }
                    }
                }
            }
        }
    }
}

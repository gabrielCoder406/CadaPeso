@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gabriel.cadapeso.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gabriel.cadapeso.datos.Activo
import com.gabriel.cadapeso.datos.Deuda
import com.gabriel.cadapeso.datos.Meta
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.FrecuenciaAhorro
import com.gabriel.cadapeso.dominio.TipoActivo
import com.gabriel.cadapeso.dominio.TipoDeuda
import com.gabriel.cadapeso.dominio.leerNumero
import com.gabriel.cadapeso.ui.componentes.CampoDinero
import com.gabriel.cadapeso.ui.componentes.CampoNumero
import com.gabriel.cadapeso.ui.componentes.HojaFormulario
import com.gabriel.cadapeso.ui.componentes.SelectorFechaDialogo
import java.time.LocalDate

// Formularios de patrimonio (activos, deudas y metas) en hojas que suben desde abajo.

/** Fila con un texto y un interruptor. */
@Composable
fun FilaInterruptor(texto: String, valor: Boolean, onCambio: (Boolean) -> Unit, detalle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(texto, style = MaterialTheme.typography.bodyMedium)
            if (detalle != null) Text(detalle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = valor, onCheckedChange = onCambio)
    }
}

@Composable
fun DialogoActivo(inicial: Activo?, moneda: String, a: Analisis, onGuardar: (Activo) -> Unit, onBorrar: (Activo) -> Unit, onCerrar: () -> Unit) {
    var nombre by remember { mutableStateOf(inicial?.nombre ?: "") }
    var tipo by remember { mutableStateOf(inicial?.tipo ?: TipoActivo.CUENTA) }
    var valor by remember { mutableStateOf(inicial?.valor?.let { Formato.numeroEditable(it) } ?: "") }
    var liquido by remember { mutableStateOf(inicial?.liquido ?: true) }
    var rinde by remember { mutableStateOf(inicial?.generaIngresos ?: false) }
    var fondo by remember { mutableStateOf(inicial?.esFondoEmergencia ?: false) }
    HojaFormulario(
        titulo = if (inicial == null) "Nuevo activo" else "Editar activo",
        onCerrar = onCerrar,
        botones = {
            if (inicial != null) TextButton(onClick = { onBorrar(inicial); onCerrar() }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onCerrar) { Text("Cancelar") }
            Button(enabled = nombre.isNotBlank(), onClick = {
                onGuardar(
                    Activo(
                        id = inicial?.id ?: 0, nombre = nombre.trim(), tipo = tipo, valor = leerNumero(valor) ?: 0.0,
                        liquido = liquido, generaIngresos = rinde, esFondoEmergencia = fondo, actualizado = a.hoy,
                    ),
                )
                onCerrar()
            }) { Text("Guardar") }
        },
    ) {
        OutlinedTextField(nombre, { nombre = it.take(40) }, label = { Text("Nombre (ej: Cuenta Brubank)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TipoActivo.entries.forEach { t ->
                FilterChip(selected = tipo == t, onClick = { tipo = t; liquido = t.liquidoPorDefecto }, label = { Text("${t.emoji} ${t.etiqueta.substringBefore(" (")}") })
            }
        }
        CampoDinero(valor, { valor = it }, "Valor actual (de mercado)", moneda = moneda)
        FilaInterruptor("Líquido", liquido, { liquido = it }, "Lo podés tener en efectivo en pocos días sin perder valor")
        FilaInterruptor("Genera ingresos", rinde, { rinde = it }, "Intereses, alquileres, dividendos")
        FilaInterruptor("Es parte del fondo de emergencia", fondo, { fondo = it; if (it) liquido = true }, "Tu airbag ante imprevistos")
    }
}

@Composable
fun DialogoDeuda(inicial: Deuda?, moneda: String, hoy: LocalDate, onGuardar: (Deuda) -> Unit, onCerrar: () -> Unit) {
    var nombre by remember { mutableStateOf(inicial?.nombre ?: "") }
    var tipo by remember { mutableStateOf(inicial?.tipo ?: TipoDeuda.TARJETA) }
    var saldo by remember { mutableStateOf(inicial?.saldo?.let { Formato.numeroEditable(it) } ?: "") }
    var tasa by remember { mutableStateOf(inicial?.tasaAnual?.let { Formato.numeroEditable(it) } ?: "") }
    var cuota by remember { mutableStateOf(inicial?.cuotaMensual?.let { Formato.numeroEditable(it) } ?: "") }
    var uva by remember { mutableStateOf(inicial?.ajustaPorInflacion ?: false) }
    var rendimiento by remember { mutableStateOf(inicial?.rendimientoActivo?.let { Formato.numeroEditable(it) } ?: "") }
    HojaFormulario(
        titulo = if (inicial == null) "Nueva deuda" else "Editar deuda",
        onCerrar = onCerrar,
        botones = {
            TextButton(onClick = onCerrar) { Text("Cancelar") }
            Button(enabled = nombre.isNotBlank() && (leerNumero(saldo) ?: -1.0) >= 0, onClick = {
                onGuardar(
                    Deuda(
                        id = inicial?.id ?: 0, nombre = nombre.trim(), tipo = tipo, saldo = leerNumero(saldo) ?: 0.0,
                        tasaAnual = leerNumero(tasa) ?: 0.0, cuotaMensual = leerNumero(cuota) ?: 0.0, ajustaPorInflacion = uva,
                        rendimientoActivo = leerNumero(rendimiento), actualizado = hoy,
                    ),
                )
                onCerrar()
            }) { Text("Guardar") }
        },
    ) {
        OutlinedTextField(nombre, { nombre = it.take(40) }, label = { Text("Nombre (ej: Tarjeta Visa)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TipoDeuda.entries.forEach { t -> FilterChip(selected = tipo == t, onClick = { tipo = t }, label = { Text("${t.emoji} ${t.etiqueta}") }) }
        }
        CampoDinero(saldo, { saldo = it }, "Saldo pendiente", moneda = moneda)
        CampoNumero(tasa, { tasa = it }, "Tasa nominal anual (TNA)", sufijo = "%", ayuda = "Está en el resumen o el contrato (en tarjetas, la TNA de financiación)")
        CampoDinero(cuota, { cuota = it }, "Cuota mensual o pago mínimo", moneda = moneda)
        FilaInterruptor("Ajusta por UVA / inflación", uva, { uva = it }, "Créditos UVA: la tasa cargada ya es real")
        CampoNumero(
            rendimiento, { rendimiento = it }, "¿Financia algo que rinde? (opcional)", sufijo = "% anual",
            ayuda = "Si el negocio o bien financiado rinde más que la tasa, es deuda buena",
        )
    }
}

@Composable
fun DialogoMeta(inicial: Meta?, moneda: String, hoy: LocalDate, onGuardar: (Meta) -> Unit, onCerrar: () -> Unit) {
    var emoji by remember { mutableStateOf(inicial?.emoji ?: "🎯") }
    var nombre by remember { mutableStateOf(inicial?.nombre ?: "") }
    var especifica by remember { mutableStateOf(inicial?.especifica ?: "") }
    var relevancia by remember { mutableStateOf(inicial?.relevancia ?: "") }
    var objetivo by remember { mutableStateOf(inicial?.montoObjetivo?.let { Formato.numeroEditable(it) } ?: "") }
    var yaTengo by remember { mutableStateOf(inicial?.montoInicial?.takeIf { it > 0 }?.let { Formato.numeroEditable(it) } ?: "") }
    var fecha by remember { mutableStateOf(inicial?.fechaLimite ?: hoy.plusMonths(6)) }
    var aporte by remember { mutableStateOf(inicial?.aporteInicial?.takeIf { it > 0 }?.let { Formato.numeroEditable(it) } ?: "") }
    var incremento by remember { mutableStateOf(inicial?.incremento?.takeIf { it > 0 }?.let { Formato.numeroEditable(it) } ?: "") }
    var frecuencia by remember { mutableStateOf(inicial?.frecuencia ?: FrecuenciaAhorro.SEMANAL) }
    var elegirFecha by remember { mutableStateOf(false) }
    val emojis = listOf("🎯", "🏖️", "🏠", "🚗", "💻", "🎓", "💍", "👶", "🛟", "✈️")

    HojaFormulario(
        titulo = if (inicial == null) "Nueva meta SMART" else "Editar meta",
        onCerrar = onCerrar,
        botones = {
            TextButton(onClick = onCerrar) { Text("Cancelar") }
            Button(enabled = nombre.isNotBlank() && (leerNumero(objetivo) ?: 0.0) > 0, onClick = {
                onGuardar(
                    Meta(
                        id = inicial?.id ?: 0, nombre = nombre.trim(), emoji = emoji, especifica = especifica.trim(),
                        relevancia = relevancia.trim(), montoObjetivo = leerNumero(objetivo) ?: 0.0,
                        montoInicial = leerNumero(yaTengo) ?: 0.0, fechaLimite = fecha,
                        aporteInicial = leerNumero(aporte) ?: 0.0, incremento = leerNumero(incremento) ?: 0.0,
                        frecuencia = frecuencia, creada = inicial?.creada ?: hoy, archivada = inicial?.archivada ?: false,
                    ),
                )
                onCerrar()
            }) { Text("Guardar") }
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            emojis.forEach { e -> FilterChip(selected = emoji == e, onClick = { emoji = e }, label = { Text(e) }) }
        }
        OutlinedTextField(nombre, { nombre = it.take(40) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(especifica, { especifica = it.take(120) }, label = { Text("S · ¿Qué querés lograr exactamente?") }, modifier = Modifier.fillMaxWidth())
        CampoDinero(objetivo, { objetivo = it }, "M · Monto objetivo", moneda = moneda)
        CampoDinero(yaTengo, { yaTengo = it }, "Lo que ya tenés ahorrado para esto", moneda = moneda)
        OutlinedTextField(relevancia, { relevancia = it.take(120) }, label = { Text("R · ¿Por qué te importa?") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("T · Fecha límite:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = { elegirFecha = true }, label = { Text(Formato.fechaLarga(fecha)) },
                leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
        Text("Ahorro incremental (opcional): empezá con poco y subí de a poco.", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FrecuenciaAhorro.entries.forEachIndexed { i, f ->
                SegmentedButton(selected = frecuencia == f, onClick = { frecuencia = f }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(f.etiqueta) }
            }
        }
        CampoDinero(aporte, { aporte = it }, "Primer aporte", moneda = moneda)
        CampoDinero(incremento, { incremento = it }, "Cuánto sube cada aporte", moneda = moneda)
    }
    if (elegirFecha) SelectorFechaDialogo(fecha, { fecha = it }, { elegirFecha = false })
}

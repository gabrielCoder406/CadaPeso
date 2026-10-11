@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gabriel.cadapeso.ui.pantallas

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.MovimientoInterpretado
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.ParserMovimiento
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.leerNumero
import com.gabriel.cadapeso.ui.BorradorCompra
import com.gabriel.cadapeso.ui.EdicionMovimiento
import com.gabriel.cadapeso.ui.componentes.AvatarCategoria
import com.gabriel.cadapeso.ui.componentes.CampoDinero
import com.gabriel.cadapeso.ui.componentes.DialogoConfirmar
import com.gabriel.cadapeso.ui.componentes.Iconos
import com.gabriel.cadapeso.ui.componentes.SelectorCategoriaDialogo
import com.gabriel.cadapeso.ui.componentes.SelectorFechaDialogo
import com.gabriel.cadapeso.ui.tema.ColoresApp
import kotlinx.coroutines.launch

/**
 * Hoja para cargar o editar un movimiento. Arriba se puede escribir (o dictar) en lenguaje
 * natural; el parser completa los campos al instante y, si no entiende, se puede pedir ayuda a
 * Gemma. Si el gasto es un "gusto" grande y no planificado, ofrece la regla de las 24 horas.
 */
@Composable
fun EditorMovimiento(
    edicion: EdicionMovimiento,
    a: Analisis,
    iaDisponible: Boolean,
    onInterpretarIA: suspend (String) -> MovimientoInterpretado?,
    onGuardar: (Movimiento, Long?) -> Unit,
    onBorrar: (Movimiento) -> Unit,
    onPensarlo: (BorradorCompra) -> Unit,
    onCerrar: () -> Unit,
) {
    val original = edicion.movimiento
    val base = original ?: edicion.borrador
    val datos = a.datos
    val hoja = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val alcance = rememberCoroutineScope()

    // Medio de pago por defecto: el que más usaste en tus últimos gastos.
    val medioHabitual = remember(datos.movimientos) {
        datos.movimientos.filter { it.tipo == TipoMovimiento.GASTO }.take(30)
            .groupingBy { it.medioPago }.eachCount().maxByOrNull { it.value }?.key ?: MedioPago.DEBITO
    }

    var texto by remember { mutableStateOf(edicion.texto) }
    var tipo by remember { mutableStateOf(base?.tipo ?: TipoMovimiento.GASTO) }
    var montoTexto by remember { mutableStateOf(base?.monto?.let { Formato.numeroEditable(it) } ?: "") }
    var descripcion by remember { mutableStateOf(base?.descripcion ?: "") }
    var categoriaId by remember { mutableStateOf(base?.categoriaId) }
    var categoriaSugerida by remember { mutableStateOf(base?.categoriaId) }
    var fecha by remember { mutableStateOf(base?.fecha ?: a.hoy) }
    var medio by remember { mutableStateOf(base?.medioPago ?: medioHabitual) }
    var nota by remember { mutableStateOf(base?.nota ?: "") }
    var metaId by remember { mutableStateOf(original?.metaId) }
    var deudaId by remember { mutableStateOf(original?.deudaId) }
    var conIA by remember { mutableStateOf(original?.creadoConIA ?: false) }
    var interpretando by remember { mutableStateOf(false) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var confianza by remember { mutableStateOf(1.0) }
    var elegirCategoria by remember { mutableStateOf(false) }
    var elegirFecha by remember { mutableStateOf(false) }
    var confirmarBorrado by remember { mutableStateOf(false) }

    fun aplicar(r: MovimientoInterpretado) {
        tipo = r.tipo
        r.monto?.let { montoTexto = Formato.numeroEditable(it) }
        if (r.descripcion.isNotBlank()) descripcion = r.descripcion
        r.categoria?.let { categoriaId = it.id; categoriaSugerida = it.id }
        r.medioPago?.let { medio = it }
        fecha = r.fecha
        confianza = r.confianza
        mensaje = if (r.monto == null) "No encontré el monto: escribilo abajo." else null
    }

    fun interpretarLocal() {
        if (texto.isBlank()) return
        aplicar(ParserMovimiento.interpretar(texto, datos.categorias, a.hoy))
    }

    // Dictado por voz con el reconocedor de Android (en español de Argentina).
    val dictado = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        if (resultado.resultCode == Activity.RESULT_OK) {
            resultado.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                texto = it
                interpretarLocal()
            }
        }
    }

    // Si se abrió con un texto (por ejemplo, desde el asesor), se interpreta enseguida.
    LaunchedEffect(edicion) { if (edicion.texto.isNotBlank() && original == null) interpretarLocal() }

    val categoria = categoriaId?.let { datos.categoria(it) }
    val monto = leerNumero(montoTexto)
    val linea = categoria?.let { c -> a.presupuesto.lineas.firstOrNull { it.categoria.id == c.id } }
    val esImpulso = original == null && edicion.borrador == null && tipo == TipoMovimiento.GASTO && categoria?.recortable == true &&
        fecha == a.hoy && monto != null && monto >= a.umbralImpulso

    ModalBottomSheet(onDismissRequest = onCerrar, sheetState = hoja) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (original == null) "Nuevo movimiento" else "Editar movimiento", style = MaterialTheme.typography.titleLarge)

            // ---- Lenguaje natural -----------------------------------------------------
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                label = { Text("Escribí o dictá") },
                placeholder = { Text("ej: ayer 12 lucas de nafta con la tarjeta") },
                trailingIcon = {
                    IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Contame el gasto o ingreso")
                        try {
                            dictado.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            mensaje = "Este teléfono no tiene reconocimiento de voz."
                        }
                    }) { Icon(Iconos.Microfono, contentDescription = "Dictar") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { interpretarLocal() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { interpretarLocal() }, enabled = texto.isNotBlank()) { Text("Completar") }
                if (iaDisponible) {
                    OutlinedButton(
                        onClick = {
                            alcance.launch {
                                interpretando = true
                                mensaje = "Gemma está leyendo el texto…"
                                val r = runCatching { onInterpretarIA(texto) }.getOrNull()
                                interpretando = false
                                if (r != null) {
                                    aplicar(r)
                                    conIA = true
                                    mensaje = "✨ Completado con Gemma: revisá los datos."
                                } else {
                                    mensaje = "Gemma no pudo interpretarlo; completalo a mano."
                                }
                            }
                        },
                        enabled = texto.isNotBlank() && !interpretando,
                    ) {
                        if (interpretando) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Iconos.Asesor, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Con IA")
                    }
                }
            }
            if (confianza < 0.8 && texto.isNotBlank() && iaDisponible && mensaje == null) {
                Text("No entendí todo: probá \"Con IA\" o completá los campos.", style = MaterialTheme.typography.bodySmall, color = ColoresApp.advertencia)
            }
            mensaje?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            // ---- Campos ------------------------------------------------------------------
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TipoMovimiento.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tipo == t,
                        onClick = {
                            if (tipo != t) {
                                tipo = t
                                categoriaId = null
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, TipoMovimiento.entries.size),
                    ) { Text(t.etiqueta) }
                }
            }
            CampoDinero(montoTexto, { montoTexto = it }, "Monto", moneda = a.moneda)
            OutlinedTextField(
                value = descripcion, onValueChange = { descripcion = it.take(60) }, label = { Text("Descripción") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { elegirCategoria = true },
                    label = { Text(categoria?.nombre ?: "Elegir categoría") },
                    leadingIcon = { AvatarCategoria(categoria, 24.dp) },
                )
                AssistChip(
                    onClick = { elegirFecha = true },
                    label = { Text(Formato.diaRelativo(fecha, a.hoy)) },
                    leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            linea?.takeIf { it.asignado > 0 && tipo == TipoMovimiento.GASTO }?.let { l ->
                // Si se está editando un gasto de esta misma categoría, su monto viejo ya está contado.
                val yaContado = if (original?.categoriaId == l.categoria.id) original?.monto ?: 0.0 else 0.0
                val restanteDespues = l.restante - (monto ?: 0.0) + yaContado
                Text(
                    if (restanteDespues >= 0) "En ${l.categoria.nombre} te quedarían ${Formato.dinero(restanteDespues, a.moneda)} este mes."
                    else "Con esto te pasás ${Formato.dinero(-restanteDespues, a.moneda)} del presupuesto de ${l.categoria.nombre}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (restanteDespues >= 0) MaterialTheme.colorScheme.onSurfaceVariant else ColoresApp.negativo,
                )
            }

            Text("Medio de pago", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MedioPago.entries.forEach { mp ->
                    FilterChip(selected = medio == mp, onClick = { medio = mp }, label = { Text("${mp.emoji} ${mp.etiqueta}") })
                }
            }

            if (categoria?.grupo == GrupoGasto.AHORRO && datos.metas.any { !it.archivada }) {
                Text("¿Es un aporte a una meta?", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = metaId == null, onClick = { metaId = null }, label = { Text("Ninguna") })
                    datos.metas.filter { !it.archivada }.forEach { meta ->
                        FilterChip(selected = metaId == meta.id, onClick = { metaId = meta.id }, label = { Text("${meta.emoji} ${meta.nombre}") })
                    }
                }
            }
            if (categoria?.grupo == GrupoGasto.DEUDA && datos.deudas.any { it.saldo > 0 || it.id == deudaId }) {
                Text("¿Qué deuda pagaste? (baja su saldo)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = deudaId == null, onClick = { deudaId = null }, label = { Text("Ninguna") })
                    datos.deudas.filter { it.saldo > 0 || it.id == deudaId }.forEach { d ->
                        FilterChip(selected = deudaId == d.id, onClick = { deudaId = d.id }, label = { Text("${d.tipo.emoji} ${d.nombre}") })
                    }
                }
            }
            OutlinedTextField(
                value = nota, onValueChange = { nota = it.take(200) }, label = { Text("Nota (opcional)") },
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )

            // ---- Intervención en compras impulsivas ------------------------------------------
            if (esImpulso) {
                Card(colors = CardDefaults.cardColors(containerColor = ColoresApp.advertencia.copy(alpha = 0.12f)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🛑 ¿Lo pensás 24 horas?", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Es un gusto de ${Formato.dinero(monto ?: 0.0, a.moneda)} que no estaba planificado. " +
                                "¿Te acerca o te aleja de tu libertad financiera? ¿Lo necesitás o es un impulso?",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            onPensarlo(BorradorCompra(descripcion.ifBlank { categoria?.nombre ?: "" }, monto, categoriaId))
                        }) { Text("Evaluar con la regla de las 24/48 h ›") }
                    }
                }
            }

            // ---- Botones ---------------------------------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (original != null) {
                    TextButton(onClick = { confirmarBorrado = true }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCerrar) { Text("Cancelar") }
                Button(
                    enabled = monto != null && monto > 0 && categoria != null,
                    onClick = {
                        val c = categoria ?: return@Button
                        val movimiento = Movimiento(
                            id = original?.id ?: 0,
                            fecha = fecha,
                            monto = Math.round((monto ?: 0.0) * 100) / 100.0,
                            tipo = tipo,
                            categoriaId = c.id,
                            descripcion = descripcion.trim().ifBlank { c.nombre },
                            medioPago = medio,
                            nota = nota.trim(),
                            metaId = if (c.grupo == GrupoGasto.AHORRO) metaId else null,
                            deudaId = if (c.grupo == GrupoGasto.DEUDA) deudaId else null,
                            creadoConIA = conIA,
                            // Un movimiento importado de Mercado Pago o Galicia sigue siéndolo aunque lo edites.
                            origen = base?.origen ?: OrigenMovimiento.MANUAL,
                        )
                        onGuardar(movimiento, categoriaSugerida)
                    },
                ) { Text("Guardar") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (elegirCategoria) {
        SelectorCategoriaDialogo(
            categorias = datos.categorias,
            tipo = tipo,
            seleccionada = categoriaId,
            onElegir = { categoriaId = it.id; elegirCategoria = false },
            onCerrar = { elegirCategoria = false },
        )
    }
    if (elegirFecha) {
        SelectorFechaDialogo(fecha, { fecha = it }, { elegirFecha = false })
    }
    if (confirmarBorrado && original != null) {
        DialogoConfirmar(
            "Borrar movimiento", "¿Seguro? Vas a poder deshacerlo desde el aviso de abajo.", "Borrar",
            onConfirmar = { onBorrar(original) }, onCerrar = { confirmarBorrado = false }, peligroso = true,
        )
    }
}

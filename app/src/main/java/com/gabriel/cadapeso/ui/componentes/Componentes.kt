@file:OptIn(ExperimentalMaterial3Api::class)

package com.gabriel.cadapeso.ui.componentes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gabriel.cadapeso.datos.Categoria
import com.gabriel.cadapeso.dominio.AccionConsejo
import com.gabriel.cadapeso.dominio.Consejo
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.Severidad
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.ui.tema.ColoresApp
import com.gabriel.cadapeso.ui.tema.EstiloMonto
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------
// Contenedores y textos
// ---------------------------------------------------------------------------------------

/** Tarjeta con título, subtítulo opcional y una acción a la derecha (por ejemplo, un botón). */
@Composable
fun Seccion(
    titulo: String,
    modifier: Modifier = Modifier,
    subtitulo: String? = null,
    onClick: (() -> Unit)? = null,
    accion: (@Composable () -> Unit)? = null,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(titulo, style = MaterialTheme.typography.titleMedium)
                    if (subtitulo != null) {
                        Text(subtitulo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                accion?.invoke()
            }
            contenido()
        }
    }
}

@Composable
fun FilaDato(etiqueta: String, valor: String, modifier: Modifier = Modifier, colorValor: Color = Color.Unspecified, destacado: Boolean = false) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(etiqueta, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            valor,
            style = if (destacado) MaterialTheme.typography.titleMedium.merge(EstiloMonto) else MaterialTheme.typography.bodyMedium.merge(EstiloMonto),
            color = colorValor,
        )
    }
}

@Composable
fun Etiqueta(texto: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(
            texto,
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
fun EstadoVacio(emoji: String, titulo: String, texto: String, modifier: Modifier = Modifier, boton: String? = null, onBoton: () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, fontSize = 44.sp)
        Text(titulo, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (boton != null) {
            TextButton(onClick = onBoton) { Text(boton) }
        }
    }
}

/** Círculo con el emoji de la categoría sobre su color. */
@Composable
fun AvatarCategoria(categoria: Categoria?, tamanio: Dp = 40.dp) {
    val color = categoria?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(tamanio).clip(CircleShape).background(color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(categoria?.emoji ?: "❔", fontSize = (tamanio.value * 0.45f).sp)
    }
}

// ---------------------------------------------------------------------------------------
// Barras y gráficos (dibujados con Canvas, sin librerías externas)
// ---------------------------------------------------------------------------------------

@Composable
fun BarraProgreso(progreso: Float, color: Color, modifier: Modifier = Modifier, alto: Dp = 8.dp) {
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(alto)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(fondo, cornerRadius = r)
        val p = progreso.coerceIn(0f, 1f)
        if (p > 0f) drawRoundRect(color, size = Size(max(size.height, size.width * p), size.height), cornerRadius = r)
    }
}

/**
 * Barra con una marca vertical en la meta. [valor] y [meta] van de 0 a [escala].
 * Sirve para "Necesidades 62% (meta 50%)".
 */
@Composable
fun BarraConMeta(valor: Float, meta: Float, color: Color, modifier: Modifier = Modifier, escala: Float = 1f, alto: Dp = 10.dp) {
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    val marca = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(alto + 6.dp)) {
        val barraAlto = alto.toPx()
        val top = (size.height - barraAlto) / 2
        val r = CornerRadius(barraAlto / 2, barraAlto / 2)
        drawRoundRect(fondo, topLeft = Offset(0f, top), size = Size(size.width, barraAlto), cornerRadius = r)
        val p = (valor / escala).coerceIn(0f, 1f)
        if (p > 0f) drawRoundRect(color, topLeft = Offset(0f, top), size = Size(max(barraAlto, size.width * p), barraAlto), cornerRadius = r)
        val x = size.width * (meta / escala).coerceIn(0f, 1f)
        drawLine(marca, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Gráfico de dona: cada segmento es (valor, color). */
@Composable
fun GraficoDona(segmentos: List<Pair<Float, Color>>, modifier: Modifier = Modifier, grosor: Dp = 18.dp, centro: @Composable () -> Unit = {}) {
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        // matchParentSize: el lienzo ocupa exactamente lo mismo que la caja.
        Canvas(Modifier.matchParentSize()) {
            val g = grosor.toPx()
            val lado = min(size.width, size.height) - g
            val esquina = Offset((size.width - lado) / 2, (size.height - lado) / 2)
            val total = segmentos.sumOf { it.first.toDouble() }.toFloat()
            drawArc(fondo, 0f, 360f, false, esquina, Size(lado, lado), style = Stroke(g))
            if (total <= 0f) return@Canvas
            var inicio = -90f
            for ((valor, color) in segmentos) {
                val barrido = valor / total * 360f
                if (barrido > 0.5f) drawArc(color, inicio, barrido - 0.5f, false, esquina, Size(lado, lado), style = Stroke(g))
                inicio += barrido
            }
        }
        centro()
    }
}

/**
 * Barras verticales (por ejemplo, gasto por día de la semana). Si se pasa [limite], se dibuja
 * una línea punteada y las barras que lo superan se pintan de rojo.
 */
@Composable
fun GraficoBarras(
    valores: List<Double>,
    etiquetas: List<String>,
    modifier: Modifier = Modifier,
    limite: Double? = null,
    resaltado: Int? = null,
    alto: Dp = 120.dp,
) {
    val colorNormal = MaterialTheme.colorScheme.primary
    val colorExceso = ColoresApp.negativo
    val colorTenue = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val colorLinea = MaterialTheme.colorScheme.onSurfaceVariant
    val maximo = max(valores.maxOrNull() ?: 0.0, limite ?: 0.0).takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(alto)) {
            val n = valores.size.coerceAtLeast(1)
            val ancho = size.width / n
            val barra = ancho * 0.6f
            valores.forEachIndexed { i, v ->
                val h = (v / maximo * size.height).toFloat()
                val color = when {
                    limite != null && v > limite -> colorExceso
                    resaltado != null && i > resaltado -> colorTenue
                    else -> colorNormal
                }
                drawRoundRect(
                    color,
                    topLeft = Offset(i * ancho + (ancho - barra) / 2, size.height - h),
                    size = Size(barra, h),
                    cornerRadius = CornerRadius(6f, 6f),
                )
            }
            if (limite != null) {
                val y = (size.height - limite / maximo * size.height).toFloat()
                var x = 0f
                while (x < size.width) {
                    drawLine(colorLinea, Offset(x, y), Offset(min(x + 10f, size.width), y), strokeWidth = 2f)
                    x += 18f
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            etiquetas.forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** Líneas (una o varias series) con línea de cero si hay valores negativos. */
@Composable
fun GraficoLineas(series: List<Pair<List<Double>, Color>>, etiquetas: List<String>, modifier: Modifier = Modifier, alto: Dp = 140.dp) {
    val colorCero = MaterialTheme.colorScheme.outline
    val todos = series.flatMap { it.first }
    val maximo = max(todos.maxOrNull() ?: 1.0, 0.0)
    val minimo = min(todos.minOrNull() ?: 0.0, 0.0)
    val rango = (maximo - minimo).takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(alto).padding(vertical = 6.dp)) {
            fun y(v: Double) = (size.height - (v - minimo) / rango * size.height).toFloat()
            if (minimo < 0) drawLine(colorCero, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 2f)
            for ((valores, color) in series) {
                if (valores.isEmpty()) continue
                val paso = if (valores.size > 1) size.width / (valores.size - 1) else 0f
                val camino = Path()
                valores.forEachIndexed { i, v -> if (i == 0) camino.moveTo(0f, y(v)) else camino.lineTo(i * paso, y(v)) }
                drawPath(camino, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                valores.forEachIndexed { i, v -> drawCircle(color, 3.5.dp.toPx(), Offset(i * paso, y(v))) }
            }
        }
        if (etiquetas.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val mostrar = if (etiquetas.size > 6) etiquetas.filterIndexed { i, _ -> i % 2 == 0 || i == etiquetas.lastIndex } else etiquetas
                mostrar.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/**
 * Medidor semicircular para indicadores con umbral (ej. endeudamiento: alerta arriba de 30%).
 * [mayorEsMejor] invierte los colores (ej. capacidad de reacción).
 */
@Composable
fun Medidor(valor: Double, umbral: Double, maximo: Double, titulo: String, texto: String, modifier: Modifier = Modifier, mayorEsMejor: Boolean = false) {
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    val malo = if (mayorEsMejor) valor < umbral else valor > umbral
    val color = if (malo) ColoresApp.negativo else ColoresApp.positivo
    val marca = MaterialTheme.colorScheme.onSurface
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.width(120.dp).height(66.dp)) {
                val g = 12.dp.toPx()
                val lado = size.width - g
                val esquina = Offset(g / 2, g / 2)
                drawArc(fondo, 180f, 180f, false, esquina, Size(lado, lado), style = Stroke(g, cap = StrokeCap.Round))
                val p = (valor / maximo).coerceIn(0.0, 1.0).toFloat()
                if (p > 0) drawArc(color, 180f, 180f * p, false, esquina, Size(lado, lado), style = Stroke(g, cap = StrokeCap.Round))
                // Marca del umbral
                val angulo = Math.toRadians(180.0 + 180.0 * (umbral / maximo).coerceIn(0.0, 1.0))
                val cx = size.width / 2
                val cy = g / 2 + lado / 2
                val r1 = lado / 2 - g
                val r2 = lado / 2 + g / 2
                drawLine(
                    marca,
                    Offset(cx + (r1 * Math.cos(angulo)).toFloat(), cy + (r1 * Math.sin(angulo)).toFloat()),
                    Offset(cx + (r2 * Math.cos(angulo)).toFloat(), cy + (r2 * Math.sin(angulo)).toFloat()),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            Text(texto, style = MaterialTheme.typography.titleMedium.merge(EstiloMonto), color = color)
        }
        Text(titulo, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

// ---------------------------------------------------------------------------------------
// Consejos
// ---------------------------------------------------------------------------------------

@Composable
fun TarjetaConsejo(consejo: Consejo, modifier: Modifier = Modifier, onAccion: ((AccionConsejo) -> Unit)? = null) {
    val color = ColoresApp.deSeveridad(consejo.severidad)
    val icono = when (consejo.severidad) {
        Severidad.ALTA -> "🚨"
        Severidad.MEDIA -> "⚠️"
        Severidad.BAJA -> "💡"
        Severidad.POSITIVA -> "🎉"
    }
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(icono, fontSize = 20.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(consejo.titulo, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(consejo.mensaje, style = MaterialTheme.typography.bodySmall)
                val accion = consejo.accion
                if (accion != null && onAccion != null) {
                    TextButton(onClick = { onAccion(accion) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(accion.etiqueta + " ›", color = color)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Campos y selectores
// ---------------------------------------------------------------------------------------

/** Campo para montos con teclado numérico. Guarda el texto tal cual; se interpreta con leerNumero(). */
@Composable
fun CampoDinero(
    valor: String,
    onCambio: (String) -> Unit,
    etiqueta: String,
    modifier: Modifier = Modifier,
    moneda: String = "$",
    ayuda: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = { nuevo -> onCambio(nuevo.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(etiqueta) },
        prefix = { Text("$moneda ") },
        supportingText = ayuda?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Campo de porcentaje o número simple. */
@Composable
fun CampoNumero(valor: String, onCambio: (String) -> Unit, etiqueta: String, modifier: Modifier = Modifier, sufijo: String? = null, ayuda: String? = null) {
    OutlinedTextField(
        value = valor,
        onValueChange = { nuevo -> onCambio(nuevo.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(etiqueta) },
        suffix = sufijo?.let { { Text(it) } },
        supportingText = ayuda?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun SelectorMes(mes: YearMonth, onCambio: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { onCambio(mes.minusMonths(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mes anterior")
        }
        Text(Formato.mes(mes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(170.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { onCambio(mes.plusMonths(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mes siguiente")
        }
    }
}

/** Grilla de categorías para elegir una (más cómodo que un menú desplegable en el celular). */
@Composable
fun SelectorCategoriaDialogo(
    categorias: List<Categoria>,
    tipo: TipoMovimiento,
    seleccionada: Long?,
    onElegir: (Categoria) -> Unit,
    onCerrar: () -> Unit,
) {
    val lista = categorias.filter { it.tipo == tipo && (!it.archivada || it.id == seleccionada) }
    AlertDialog(
        onDismissRequest = onCerrar,
        confirmButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
        title = { Text(if (tipo == TipoMovimiento.GASTO) "Categoría del gasto" else "Categoría del ingreso") },
        text = {
            LazyVerticalGrid(columns = GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lista, key = { it.id }) { c ->
                    val elegida = c.id == seleccionada
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (elegida) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onElegir(c) }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AvatarCategoria(c, 44.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(c.nombre, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
    )
}

@Composable
fun SelectorFechaDialogo(fecha: LocalDate, onElegir: (LocalDate) -> Unit, onCerrar: () -> Unit) {
    // El DatePicker trabaja en milisegundos UTC; un día = 86.400.000 ms.
    val estado = rememberDatePickerState(initialSelectedDateMillis = fecha.toEpochDay() * 86_400_000L)
    DatePickerDialog(
        onDismissRequest = onCerrar,
        confirmButton = {
            TextButton(onClick = {
                estado.selectedDateMillis?.let { onElegir(LocalDate.ofEpochDay(it / 86_400_000L)) }
                onCerrar()
            }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    ) {
        DatePicker(state = estado)
    }
}

@Composable
fun DialogoConfirmar(titulo: String, texto: String, confirmar: String, onConfirmar: () -> Unit, onCerrar: () -> Unit, peligroso: Boolean = false) {
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(titulo) },
        text = { Text(texto) },
        confirmButton = {
            TextButton(onClick = { onConfirmar(); onCerrar() }) {
                Text(confirmar, color = if (peligroso) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}

/**
 * Formulario en una hoja que sube desde abajo. En el celular es más cómodo que un diálogo
 * centrado: tiene más lugar, se desplaza bien y convive mejor con el teclado.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HojaFormulario(
    titulo: String,
    onCerrar: () -> Unit,
    botones: @Composable FlowRowScope.() -> Unit,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(titulo, style = MaterialTheme.typography.titleLarge)
            contenido()
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = botones,
            )
        }
    }
}

@Composable
fun Separador() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

/** Color de una partida/grupo para los gráficos. */
@Composable
fun colorGrupo(grupo: GrupoGasto): Color = when (grupo) {
    GrupoGasto.NECESIDAD -> Color(0xFF26A69A)
    GrupoGasto.DESEO -> Color(0xFFFFA726)
    GrupoGasto.EDUCACION -> Color(0xFF7E57C2)
    GrupoGasto.AHORRO -> Color(0xFF43A047)
    GrupoGasto.DEUDA -> Color(0xFF78909C)
    GrupoGasto.INGRESO -> MaterialTheme.colorScheme.primary
}

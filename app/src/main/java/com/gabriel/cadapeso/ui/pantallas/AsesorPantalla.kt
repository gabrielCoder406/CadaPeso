@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gabriel.cadapeso.ui.pantallas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gabriel.cadapeso.datos.CompraEnEspera
import com.gabriel.cadapeso.datos.ConfigIA
import com.gabriel.cadapeso.datos.MensajeChat
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.EstadoCompra
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.PerfilConducta
import com.gabriel.cadapeso.dominio.ProveedorIA
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.leerNumero
import com.gabriel.cadapeso.ia.AccionRapida
import com.gabriel.cadapeso.ia.EstadoMotor
import com.gabriel.cadapeso.ia.Prompts
import com.gabriel.cadapeso.ui.LocalAvisos
import com.gabriel.cadapeso.ui.AppViewModel
import com.gabriel.cadapeso.ui.AsesorViewModel
import com.gabriel.cadapeso.ui.BorradorCompra
import com.gabriel.cadapeso.ui.EdicionMovimiento
import com.gabriel.cadapeso.ui.Navegacion
import com.gabriel.cadapeso.ui.Superpuesta
import com.gabriel.cadapeso.ui.TipoTest
import com.gabriel.cadapeso.ui.componentes.AvatarCategoria
import com.gabriel.cadapeso.ui.componentes.CampoDinero
import com.gabriel.cadapeso.ui.componentes.DialogoConfirmar
import com.gabriel.cadapeso.ui.componentes.FilaDato
import com.gabriel.cadapeso.ui.componentes.HojaFormulario
import com.gabriel.cadapeso.ui.componentes.Iconos
import com.gabriel.cadapeso.ui.componentes.Seccion
import com.gabriel.cadapeso.ui.componentes.SelectorCategoriaDialogo
import com.gabriel.cadapeso.ui.tema.ColoresApp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.pow

@Composable
fun AsesorPantalla(a: Analisis, nav: Navegacion, vm: AppViewModel, asesor: AsesorViewModel, config: ConfigIA) {
    val chat by asesor.chat.collectAsStateWithLifecycle()
    val enCurso by asesor.enCurso.collectAsStateWithLifecycle()
    val generando by asesor.generando.collectAsStateWithLifecycle()
    val estado by asesor.estadoMotor.collectAsStateWithLifecycle()
    var texto by rememberSaveable { mutableStateOf("") }
    var confirmarBorrado by remember { mutableStateOf(false) }
    val lista = rememberLazyListState()
    val enEspera = a.datos.compras.filter { it.estado == EstadoCompra.EN_ESPERA }

    // Al entrar, se carga el modelo en segundo plano para que la primera respuesta sea más rápida.
    LaunchedEffect(config) { asesor.precargar(config) }
    // Bajar hasta el último mensaje cuando llega algo nuevo.
    LaunchedEffect(chat.size, enCurso?.length) {
        val total = lista.layoutInfo.totalItemsCount
        if (total > 0) lista.scrollToItem(total - 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalAvisos.current) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Asesor financiero")
                        EstadoIA(estado, config) { nav.superpuesta = Superpuesta.Ajustes }
                    }
                },
                actions = {
                    if (chat.isNotEmpty()) IconButton(onClick = { confirmarBorrado = true }) { Icon(Icons.Filled.Delete, contentDescription = "Borrar conversación") }
                },
            )
        },
        // Atajos + campo de texto van en la barra inferior del Scaffold: así los avisos aparecen
        // ARRIBA de ella (no la tapan) y la barra sube con el teclado (imePadding).
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.imePadding()) {
                Column {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            AssistChip(onClick = { nav.compra = BorradorCompra() }, label = { Text("🛍️ ¿Lo compro?") })
                        }
                        items(AccionRapida.entries) { acc ->
                            AssistChip(
                                enabled = !generando,
                                onClick = { asesor.accion(acc, a, config) },
                                label = { Text("${acc.emoji} ${acc.etiqueta}") },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = texto,
                            onValueChange = { texto = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Preguntale algo a tu asesor…") },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                        )
                        FilledIconButton(
                            onClick = {
                                if (generando) asesor.detener() else {
                                    asesor.enviar(texto, a, config)
                                    texto = ""
                                }
                            },
                            enabled = generando || texto.isNotBlank(),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            if (generando) Icon(Iconos.Detener, contentDescription = "Detener") else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            state = lista,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { TarjetaPerfil(a, nav) }
            if (enEspera.isNotEmpty() || nav.mostrarCompras) {
                item { SeccionCompras(a, enEspera, vm, nav) }
            }
            if (chat.isEmpty() && enCurso == null) {
                item { Presentacion(config) }
            }
            items(chat, key = { it.id }) { Burbuja(it.texto, it.esUsuario) }
            enCurso?.let { parcial -> item(key = "en_curso") { Burbuja(parcial.ifEmpty { "Pensando…" }, false, escribiendo = true) } }
        }
    }

    if (confirmarBorrado) {
        DialogoConfirmar("Borrar conversación", "Se borra el historial del chat (tus datos no se tocan).", "Borrar",
            onConfirmar = { asesor.borrarChat() }, onCerrar = { confirmarBorrado = false })
    }
}

@Composable
private fun EstadoIA(estado: EstadoMotor, config: ConfigIA, onClick: () -> Unit) {
    val (texto, color) = when (estado) {
        EstadoMotor.SinConfigurar -> "Motor de reglas · tocá para activar Gemma 2" to MaterialTheme.colorScheme.onSurfaceVariant
        EstadoMotor.Inactivo -> "Gemma 2 configurada" to MaterialTheme.colorScheme.onSurfaceVariant
        EstadoMotor.Cargando -> "Cargando Gemma 2…" to ColoresApp.advertencia
        EstadoMotor.Listo -> (if (config.proveedor == ProveedorIA.LOCAL) "Gemma 2 en el teléfono ✓" else "Gemma 2 vía Ollama ✓") to ColoresApp.positivo
        is EstadoMotor.Error -> "Error de IA · tocá para revisar" to ColoresApp.negativo
    }
    TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
        Text(texto, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun TarjetaPerfil(a: Analisis, nav: Navegacion) {
    val p = a.perfil
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { nav.ir(com.gabriel.cadapeso.dominio.AccionConsejo.VER_INVERSION) }, label = { Text("🧭 ${p.etapa.etiqueta}") })
        AssistChip(
            onClick = { nav.superpuesta = Superpuesta.Test(TipoTest.CONDUCTA) },
            label = { Text("🧠 " + (p.conducta?.etiqueta ?: "Test de conducta")) },
        )
        AssistChip(
            onClick = { nav.superpuesta = Superpuesta.Test(TipoTest.RIESGO) },
            label = { Text("🎲 " + (p.riesgo?.let { "Riesgo ${it.etiqueta.lowercase()}" } ?: "Test de riesgo")) },
        )
    }
}

@Composable
private fun Presentacion(config: ConfigIA) {
    Seccion("✨ Hola, soy tu asesor", subtitulo = "Uso tus datos reales, calculados por el motor de reglas") {
        Text(
            "Preguntame lo que quieras sobre tu plata: \"¿puedo irme de vacaciones en enero?\", \"¿qué deuda pago primero?\", " +
                "\"¿cómo bajo el gasto en delivery?\". Las cuentas las hace la app; Gemma 2 las explica y te aconseja.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (config.proveedor == ProveedorIA.NINGUNO) {
            Text(
                "Ahora estoy en modo reglas: los atajos de abajo funcionan igual. Para conversar libremente, activá Gemma 2 en Ajustes.",
                style = MaterialTheme.typography.bodySmall, color = ColoresApp.advertencia,
            )
        }
    }
}

@Composable
private fun Burbuja(texto: String, esUsuario: Boolean, escribiendo: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (esUsuario) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (esUsuario) 18.dp else 4.dp, bottomEnd = if (esUsuario) 4.dp else 18.dp,
            ),
            color = if (esUsuario) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!esUsuario) {
                    if (escribiendo) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Iconos.Asesor, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
                SelectionContainer { Text(markdownSimple(texto), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** Gemma suele responder con **negritas** y viñetas "* ": las mostramos prolijas. */
private fun markdownSimple(texto: String): AnnotatedString = buildAnnotatedString {
    texto.lines().forEachIndexed { i, lineaOriginal ->
        if (i > 0) append('\n')
        var linea = lineaOriginal
        val sangria = linea.takeWhile { it == ' ' }
        val sinSangria = linea.trimStart()
        if (sinSangria.startsWith("* ") || sinSangria.startsWith("- ")) linea = sangria + "• " + sinSangria.drop(2)
        if (sinSangria.startsWith("#")) linea = sinSangria.trimStart('#').trim()
        val partes = linea.split("**")
        partes.forEachIndexed { j, parte ->
            if (j % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(parte) } else append(parte)
        }
    }
}

// =========================================================================================
// Compras en espera (regla de las 24/48 h)
// =========================================================================================

@Composable
private fun SeccionCompras(a: Analisis, enEspera: List<CompraEnEspera>, vm: AppViewModel, nav: Navegacion) {
    // Reloj que se actualiza cada minuto para la cuenta regresiva.
    val ahora by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    Seccion(
        "🛍️ Compras en espera",
        subtitulo = "Esperar antes de comprar desactiva el impulso",
        accion = { TextButton(onClick = { nav.mostrarCompras = false }) { Text("Ocultar") } },
    ) {
        if (enEspera.isEmpty()) Text("No tenés compras en pausa. Usá \"¿Lo compro?\" antes de un gasto no planificado.", style = MaterialTheme.typography.bodySmall)
        enEspera.forEach { c ->
            val restante = c.venceMillis - ahora
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AvatarCategoria(a.datos.categoria(c.categoriaId), 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(c.descripcion, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (restante > 0) "Faltan ${restante / 3_600_000} h ${(restante / 60_000) % 60} min" else "⏰ Ya podés decidir con la cabeza fría",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (restante > 0) MaterialTheme.colorScheme.onSurfaceVariant else ColoresApp.advertencia,
                        )
                    }
                    Text(Formato.dinero(c.monto, a.moneda))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.resolverCompra(c, comprar = false) }) { Text("No lo compro") }
                    if (restante <= 0) OutlinedButton(onClick = { vm.resolverCompra(c, comprar = true, medio = MedioPago.DEBITO) }) { Text("Lo compro") }
                    else TextButton(onClick = { vm.borrarCompra(c) }) { Text("Quitar") }
                }
            }
        }
        if (a.ahorroPorImpulsosEvitados > 0) {
            Text("💚 Ya evitaste ${Formato.dinero(a.ahorroPorImpulsosEvitados, a.moneda)} en compras impulsivas.", color = ColoresApp.positivo, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// =========================================================================================
// "¿Lo compro?": intervención en compras emocionales e impulsivas
// =========================================================================================

/**
 * Evalúa una compra no planificada: muestra su impacto real (presupuesto de deseos, horas de
 * trabajo, costo de oportunidad), plantea las dos preguntas anti-gasto y sugiere esperar
 * 24 o 48 horas. Gemma agrega una reflexión adaptada al perfil de conducta.
 */
@Composable
fun EvaluarCompraDialogo(
    borrador: BorradorCompra,
    a: Analisis,
    config: ConfigIA,
    asesor: AsesorViewModel,
    vm: AppViewModel,
    nav: Navegacion,
    onCerrar: () -> Unit,
) {
    val datos = a.datos
    var descripcion by remember { mutableStateOf(borrador.descripcion) }
    var montoTexto by remember { mutableStateOf(borrador.monto?.let { Formato.numeroEditable(it) } ?: "") }
    var categoriaId by remember {
        mutableStateOf(borrador.categoriaId ?: datos.categorias.firstOrNull { it.grupo == GrupoGasto.DESEO && !it.archivada && it.nombre.startsWith("Ropa") }?.id
            ?: datos.categorias.firstOrNull { it.recortable }?.id)
    }
    var respuestaLibertad by remember { mutableStateOf("") }
    var respuestaNecesidad by remember { mutableStateOf("") }
    var elegirCategoria by remember { mutableStateOf(false) }
    val opinion by asesor.opinionCompra.collectAsStateWithLifecycle()
    val categoria = categoriaId?.let { datos.categoria(it) }
    val monto = leerNumero(montoTexto) ?: 0.0
    val m = a.moneda
    val ind = a.indicadores

    // ---- Impacto calculado por el motor de reglas --------------------------------------------
    val partidaDeseos = a.presupuesto.partidas.firstOrNull { GrupoGasto.DESEO in it.partida.grupos }
    val restanteDeseos = partidaDeseos?.let { max(0.0, max(it.asignado, it.objetivo) - it.real) } ?: 0.0
    val horasTrabajo = if (ind.ingresoReferencia > 0) monto / (ind.ingresoReferencia / 160) else 0.0
    val invertido10 = monto * (1 + a.perfil.riesgoEfectivo.rendimientoRealAnual / 100).pow(10)
    val fueraDePresupuesto = monto > restanteDeseos
    val horas = if (monto >= a.umbralImpulso * 2 || fueraDePresupuesto || a.perfil.conductaEfectiva == PerfilConducta.IMPULSIVO) 48 else 24
    val opcionesLibertad = listOf("Me acerca", "Es neutro", "Me aleja")
    val opcionesNecesidad = listOf("Lo necesito", "Lo quiero, puedo esperar", "Es un impulso")

    LaunchedEffect(Unit) { asesor.limpiarOpinionCompra() }

    fun compra(estado: EstadoCompra = EstadoCompra.EN_ESPERA) = CompraEnEspera(
        descripcion = descripcion.trim().ifBlank { categoria?.nombre ?: "Compra" },
        monto = monto,
        categoriaId = categoriaId ?: 0,
        creadaMillis = System.currentTimeMillis(),
        horasEspera = horas,
        estado = estado,
        respuestaLibertad = respuestaLibertad,
        respuestaNecesidad = respuestaNecesidad,
        comentarioIA = opinion.orEmpty(),
    )

    HojaFormulario(
        titulo = "🛍️ ¿Lo compro?",
        onCerrar = { asesor.limpiarOpinionCompra(); onCerrar() },
        botones = {
            TextButton(enabled = monto > 0 && categoriaId != null, onClick = {
                asesor.limpiarOpinionCompra()
                onCerrar()
                nav.edicion = EdicionMovimiento(
                    borrador = Movimiento(
                        fecha = a.hoy, monto = monto, tipo = TipoMovimiento.GASTO, categoriaId = categoriaId ?: 0,
                        descripcion = descripcion.trim(),
                    ),
                )
            }) { Text("Comprar igual") }
            OutlinedButton(enabled = monto > 0 && categoriaId != null, onClick = {
                vm.descartarCompraNueva(compra())
                asesor.limpiarOpinionCompra()
                onCerrar()
            }) { Text("No lo compro") }
            Button(enabled = monto > 0 && categoriaId != null, onClick = {
                vm.ponerCompraEnEspera(compra())
                asesor.limpiarOpinionCompra()
                onCerrar()
            }) { Text("Esperar $horas h") }
        },
    ) {
        OutlinedTextField(descripcion, { descripcion = it.take(50) }, label = { Text("¿Qué querés comprar?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        CampoDinero(montoTexto, { montoTexto = it }, "Precio", moneda = m)
        AssistChip(onClick = { elegirCategoria = true }, label = { Text(categoria?.let { "${it.emoji} ${it.nombre}" } ?: "Categoría") })
        if (monto > 0) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Impacto real", style = MaterialTheme.typography.titleSmall)
                    FilaDato("Te queda para gustos este mes", Formato.dinero(restanteDeseos, m), colorValor = if (fueraDePresupuesto) ColoresApp.negativo else Color0())
                    if (horasTrabajo > 0) FilaDato("Equivale a", String.format(Formato.locale, "%.0f horas de trabajo", horasTrabajo))
                    FilaDato("Invertido 10 años serían", Formato.dinero(invertido10, m), colorValor = ColoresApp.positivo)
                    if (fueraDePresupuesto) Text("⚠️ Está fuera de tu presupuesto de gustos.", color = ColoresApp.negativo, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("¿Comprar esto me acerca o me aleja de mi libertad financiera?", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            opcionesLibertad.forEach { o -> FilterChip(selected = respuestaLibertad == o, onClick = { respuestaLibertad = o }, label = { Text(o) }) }
        }
        Text("¿Realmente lo necesito o es un impulso emocional?", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            opcionesNecesidad.forEach { o -> FilterChip(selected = respuestaNecesidad == o, onClick = { respuestaNecesidad = o }, label = { Text(o) }) }
        }
        // Veredicto por reglas (siempre visible) + opinión de Gemma (si está configurada).
        val impulso = respuestaNecesidad == "Es un impulso" || respuestaLibertad == "Me aleja"
        val veredicto = when {
            monto <= 0 -> null
            impulso || fueraDePresupuesto -> "Recomendación: esperá $horas horas. Si cuando pase el tiempo lo seguís queriendo, decidilo con la cabeza fría."
            respuestaNecesidad == "Lo necesito" -> "Parece una compra razonable: entra en tu presupuesto y la necesitás. Compará precios antes."
            else -> "Si podés esperar, esperá $horas horas: muchas ganas desaparecen solas."
        }
        veredicto?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (impulso || fueraDePresupuesto) ColoresApp.advertencia else Color0()) }
        if (asesor.iaConfigurada(config) && monto > 0) {
            OutlinedButton(onClick = {
                asesor.opinarCompra(config, a) { nivel ->
                    Prompts.evaluarCompra(
                        a, descripcion.ifBlank { categoria?.nombre ?: "algo" }, monto, categoria?.nombre ?: "", restanteDeseos,
                        horasTrabajo, invertido10, horas, respuestaLibertad.ifBlank { "no respondí" },
                        respuestaNecesidad.ifBlank { "no respondí" }, nivel,
                    )
                }
            }) {
                Icon(Iconos.Asesor, contentDescription = null, Modifier.size(18.dp))
                Text("  Opinión de Gemma")
            }
        }
        opinion?.let { texto ->
            Box(Modifier.fillMaxWidth()) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(markdownSimple(texto.ifEmpty { "Pensando…" }), Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (elegirCategoria) {
        SelectorCategoriaDialogo(datos.categorias, TipoMovimiento.GASTO, categoriaId, { categoriaId = it.id; elegirCategoria = false }, { elegirCategoria = false })
    }
}

/** Color por defecto del texto (atajo para no importar Color en este archivo). */
@Composable
private fun Color0() = MaterialTheme.colorScheme.onSurface

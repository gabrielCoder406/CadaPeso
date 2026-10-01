@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gabriel.cadapeso.ui.pantallas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gabriel.cadapeso.datos.Categoria
import com.gabriel.cadapeso.datos.ConfigIA
import com.gabriel.cadapeso.datos.Perfil
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.Perfilado
import com.gabriel.cadapeso.dominio.ProveedorIA
import com.gabriel.cadapeso.dominio.ReglaPresupuesto
import com.gabriel.cadapeso.dominio.TipoIngreso
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.leerNumero
import com.gabriel.cadapeso.ia.GestorModelos
import com.gabriel.cadapeso.ui.LocalAvisos
import com.gabriel.cadapeso.ui.AppViewModel
import com.gabriel.cadapeso.ui.AsesorViewModel
import com.gabriel.cadapeso.ui.TipoTest
import com.gabriel.cadapeso.ui.componentes.AvatarCategoria
import com.gabriel.cadapeso.ui.componentes.CampoDinero
import com.gabriel.cadapeso.ui.componentes.CampoNumero
import com.gabriel.cadapeso.ui.componentes.DialogoConfirmar
import com.gabriel.cadapeso.ui.componentes.FilaDato
import com.gabriel.cadapeso.ui.componentes.HojaFormulario
import com.gabriel.cadapeso.ui.componentes.Seccion
import com.gabriel.cadapeso.ui.tema.ColoresApp
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun AjustesPantalla(
    a: Analisis?,
    perfil: Perfil,
    config: ConfigIA,
    vm: AppViewModel,
    asesor: AsesorViewModel,
    onVolver: () -> Unit,
    onTest: (TipoTest) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(LocalAvisos.current) },
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { IconButton(onClick = onVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SeccionIA(config, vm, asesor, a) }
            item { SeccionPerfil(perfil, vm) }
            item { SeccionTests(perfil, onTest) }
            item { SeccionCategorias(a?.datos?.categorias.orEmpty(), vm) }
            item { SeccionDatos(vm) }
            item { AcercaDe() }
        }
    }
}

// =========================================================================================
// Inteligencia artificial
// =========================================================================================

@Composable
private fun SeccionIA(config: ConfigIA, vm: AppViewModel, asesor: AsesorViewModel, a: Analisis?) {
    val tarea by asesor.tarea.collectAsStateWithLifecycle()
    val resultado by asesor.resultadoPrueba.collectAsStateWithLifecycle()
    val modelosOllama by asesor.modelosOllama.collectAsStateWithLifecycle()
    val abrir = LocalUriHandler.current
    var token by remember { mutableStateOf("") }
    var url by remember(config.ollamaUrl) { mutableStateOf(config.ollamaUrl) }
    var modeloOllama by remember(config.ollamaModelo) { mutableStateOf(config.ollamaModelo) }
    var maxTokens by remember(config.maxTokens) { mutableStateOf(config.maxTokens.toString()) }
    var temperatura by remember(config.temperatura) { mutableStateOf(config.temperatura.toFloat()) }
    // Se recalcula cuando cambia el modelo elegido o termina una tarea (importación/descarga).
    val modelos = remember(config.rutaModelo, tarea) { asesor.gestor.modelos() }
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { asesor.importarModelo(it) } }

    Seccion("✨ Inteligencia artificial (Gemma 2)", subtitulo = "Las cuentas las hace siempre el motor de reglas; Gemma las explica y conversa") {
        ProveedorIA.entries.forEach { p ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = config.proveedor == p, role = Role.RadioButton) {
                    vm.guardarConfigIA(config.copy(proveedor = p))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = config.proveedor == p, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(p.etiqueta, style = MaterialTheme.typography.bodyLarge)
                    Text(p.detalle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (config.proveedor == ProveedorIA.LOCAL) {
            Text("Modelo en el teléfono", style = MaterialTheme.typography.titleSmall)
            if (modelos.isEmpty()) {
                Text("Todavía no hay ningún modelo. Necesitás el archivo Gemma2-2B-IT (.task, ~2,6 GB).", style = MaterialTheme.typography.bodySmall)
            }
            modelos.forEach { archivo ->
                val enUso = archivo.absolutePath == config.rutaModelo
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (enUso) "✅ " else "") + archivo.name, style = MaterialTheme.typography.bodySmall)
                        Text("${archivo.length() / 1_000_000} MB", style = MaterialTheme.typography.labelSmall)
                    }
                    if (!enUso) TextButton(onClick = { asesor.usarModelo(archivo) }) { Text("Usar") }
                    TextButton(onClick = { asesor.borrarModelo(archivo) }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
                }
            }
            tarea?.let { t ->
                Text("${t.titulo}: ${t.detalle}", style = MaterialTheme.typography.bodySmall)
                val p = t.progreso
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (t.titulo.startsWith("Descargando")) TextButton(onClick = { asesor.cancelarDescarga() }) { Text("Cancelar descarga") }
            }
            if (tarea == null) {
                Text(
                    "Opción A (recomendada): en el navegador del teléfono entrá a la página del modelo, iniciá sesión en Hugging Face, " +
                        "aceptá la licencia de Gemma, descargá el archivo ${GestorModelos.ARCHIVO_HF} y después importalo acá.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { abrir.openUri(GestorModelos.PAGINA_HF) }) { Text("Abrir página") }
                    FilledTonalButton(onClick = { importar.launch(arrayOf("*/*")) }) { Text("Importar archivo") }
                }
                Text(
                    "Opción B: descarga directa con tu token de lectura de Hugging Face (huggingface.co/settings/tokens). El token se usa una sola vez y no se guarda.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it.trim() }, label = { Text("Token de Hugging Face (hf_…)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { asesor.descargarModelo(token); token = "" }, enabled = token.length > 8) { Text("Descargar Gemma 2 (~2,6 GB)") }
            }
            FilaInterruptor("Usar GPU", config.usarGpu, { vm.guardarConfigIA(config.copy(usarGpu = it)) }, "Más rápido en algunos teléfonos; si falla, volvé a CPU")
            FilaInterruptor(
                "Plantilla de chat manual", config.plantillaManual, { vm.guardarConfigIA(config.copy(plantillaManual = it)) },
                "Arma los turnos <start_of_turn> de Gemma. Desactivalo solo si las respuestas salen raras",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CampoNumero(maxTokens, { maxTokens = it }, "Contexto (tokens)", Modifier.weight(1f), ayuda = "1280 para Gemma2-2B-IT de LiteRT")
                TextButton(onClick = {
                    val n = leerNumero(maxTokens)?.toInt()?.coerceIn(512, 8192) ?: 1280
                    vm.guardarConfigIA(config.copy(maxTokens = n))
                }) { Text("Aplicar") }
            }
        }

        if (config.proveedor == ProveedorIA.OLLAMA) {
            Text(
                "En la PC: instalá Ollama, ejecutá  ollama pull gemma2:2b  y abrilo con la variable OLLAMA_HOST=0.0.0.0 para que escuche en la red. " +
                    "Poné acá la IP de la PC (la ves con ipconfig).",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(url, { url = it.trim() }, label = { Text("Dirección de Ollama") }, placeholder = { Text("http://192.168.0.10:11434") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(modeloOllama, { modeloOllama = it.trim() }, label = { Text("Modelo") }, placeholder = { Text("gemma2:2b") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (modelosOllama.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modelosOllama.forEach { nombre -> FilterChip(selected = modeloOllama == nombre, onClick = { modeloOllama = nombre }, label = { Text(nombre) }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { asesor.probarOllama(url) }) { Text("Probar conexión") }
                FilledTonalButton(onClick = { vm.guardarConfigIA(config.copy(ollamaUrl = url, ollamaModelo = modeloOllama.ifBlank { "gemma2:2b" })) }) { Text("Guardar") }
            }
        }

        if (config.proveedor != ProveedorIA.NINGUNO) {
            Text("Creatividad: ${String.format(Formato.locale, "%.1f", temperatura)} (más baja = más precisa)", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = temperatura, onValueChange = { temperatura = it }, valueRange = 0.1f..1.2f,
                onValueChangeFinished = { vm.guardarConfigIA(config.copy(temperatura = (temperatura * 10).roundToInt() / 10.0)) },
            )
            Button(onClick = { asesor.probarMotor(config, a) }, enabled = tarea == null) { Text("Probar Gemma 2") }
        }
        resultado?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { asesor.limpiarResultado() }) { Text("Ocultar") }
        }
    }
}

// =========================================================================================
// Perfil financiero
// =========================================================================================

@Composable
private fun SeccionPerfil(perfil: Perfil, vm: AppViewModel) {
    var nombre by remember(perfil) { mutableStateOf(perfil.nombre) }
    var edad by remember(perfil) { mutableStateOf(perfil.edad.toString()) }
    var ingreso by remember(perfil) { mutableStateOf(if (perfil.ingresoMensual > 0) Formato.numeroEditable(perfil.ingresoMensual) else "") }
    var tipo by remember(perfil) { mutableStateOf(perfil.tipoIngreso) }
    var diaCobro by remember(perfil) { mutableStateOf(perfil.diaCobro.toString()) }
    var moneda by remember(perfil) { mutableStateOf(perfil.moneda) }
    var regla by remember(perfil) { mutableStateOf(perfil.regla) }
    var pagate by remember(perfil) { mutableStateOf(Formato.numeroEditable(perfil.porcentajePagatePrimero)) }
    var inflacion by remember(perfil) { mutableStateOf(if (perfil.inflacionAnual > 0) Formato.numeroEditable(perfil.inflacionAnual) else "") }
    var umbralTasa by remember(perfil) { mutableStateOf(Formato.numeroEditable(perfil.umbralTasaMala)) }
    var umbralHormiga by remember(perfil) { mutableStateOf(if (perfil.umbralHormiga > 0) Formato.numeroEditable(perfil.umbralHormiga) else "") }
    var umbralImpulso by remember(perfil) { mutableStateOf(if (perfil.umbralImpulso > 0) Formato.numeroEditable(perfil.umbralImpulso) else "") }

    Seccion("👤 Perfil financiero") {
        OutlinedTextField(nombre, { nombre = it.take(30) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        CampoNumero(edad, { edad = it }, "Edad", sufijo = "años")
        CampoDinero(ingreso, { ingreso = it }, "Ingreso mensual neto", moneda = moneda)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TipoIngreso.entries.forEachIndexed { i, t ->
                SegmentedButton(selected = tipo == t, onClick = { tipo = t }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                    Text(t.etiqueta.substringBefore(" ("))
                }
            }
        }
        CampoNumero(diaCobro, { diaCobro = it }, "Día de cobro", ayuda = "Ese día te recordamos págate a ti primero")
        OutlinedTextField(moneda, { moneda = it.take(4) }, label = { Text("Símbolo de moneda") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Regla de presupuesto", style = MaterialTheme.typography.labelLarge)
        ReglaPresupuesto.entries.forEach { r ->
            Row(Modifier.fillMaxWidth().selectable(selected = regla == r, role = Role.RadioButton) { regla = r }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = regla == r, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(r.etiqueta, style = MaterialTheme.typography.bodyLarge)
                    Text(r.resumen, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        CampoNumero(pagate, { pagate = it }, "Págate a ti primero", sufijo = "% del ingreso")
        CampoNumero(inflacion, { inflacion = it }, "Inflación anual esperada (opcional)", sufijo = "%", ayuda = "Con inflación alta, las deudas se evalúan por su tasa real")
        CampoNumero(umbralTasa, { umbralTasa = it }, "Una deuda es cara desde", sufijo = "% real anual")
        CampoDinero(umbralHormiga, { umbralHormiga = it }, "Monto máximo de un gasto hormiga", moneda = moneda, ayuda = "Vacío = automático (1,5% del ingreso)")
        CampoDinero(umbralImpulso, { umbralImpulso = it }, "Compra que activa la regla de 24 h", moneda = moneda, ayuda = "Vacío = automático según tu perfil")
        Button(onClick = {
            vm.guardarPerfil(
                perfil.copy(
                    nombre = nombre.trim(),
                    edad = leerNumero(edad)?.toInt()?.coerceIn(14, 100) ?: perfil.edad,
                    ingresoMensual = leerNumero(ingreso) ?: 0.0,
                    tipoIngreso = tipo,
                    diaCobro = leerNumero(diaCobro)?.toInt()?.coerceIn(1, 28) ?: perfil.diaCobro,
                    moneda = moneda.ifBlank { "$" },
                    regla = regla,
                    porcentajePagatePrimero = leerNumero(pagate)?.coerceIn(0.0, 90.0) ?: perfil.porcentajePagatePrimero,
                    inflacionAnual = leerNumero(inflacion) ?: 0.0,
                    umbralTasaMala = leerNumero(umbralTasa) ?: 16.0,
                    umbralHormiga = leerNumero(umbralHormiga) ?: 0.0,
                    umbralImpulso = leerNumero(umbralImpulso) ?: 0.0,
                ),
            )
        }) { Text("Guardar perfil") }
    }
}

@Composable
private fun SeccionTests(perfil: Perfil, onTest: (TipoTest) -> Unit) {
    Seccion("🧠 Diagnóstico del usuario", subtitulo = "Adapta el tono del asesor y la frecuencia de las alertas") {
        FilaDato("Etapa de vida", Perfilado.etapa(perfil.edad).let { "${it.etiqueta} (${it.rango})" })
        FilaDato("Conducta financiera", Perfilado.conducta(perfil.puntajeConducta)?.etiqueta ?: "Sin hacer")
        FilaDato("Tolerancia al riesgo", Perfilado.riesgo(perfil.puntajeRiesgo)?.etiqueta ?: "Sin hacer")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onTest(TipoTest.CONDUCTA) }) { Text("Test de conducta") }
            OutlinedButton(onClick = { onTest(TipoTest.RIESGO) }) { Text("Test de riesgo") }
        }
    }
}

// =========================================================================================
// Categorías
// =========================================================================================

@Composable
private fun SeccionCategorias(categorias: List<Categoria>, vm: AppViewModel) {
    var editando by remember { mutableStateOf<Categoria?>(null) }
    var nueva by remember { mutableStateOf<TipoMovimiento?>(null) }
    var verTodas by remember { mutableStateOf(false) }
    val visibles = if (verTodas) categorias else categorias.take(6)
    Seccion(
        "🏷️ Categorías", subtitulo = "Tocá una para cambiar su grupo, si es recortable o sus palabras clave",
        accion = { IconButton(onClick = { nueva = TipoMovimiento.GASTO }) { Icon(Icons.Filled.Add, contentDescription = "Nueva categoría") } },
    ) {
        visibles.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { editando = c }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarCategoria(c, 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.nombre + if (c.archivada) " (archivada)" else "", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        c.grupo.etiqueta + (if (c.recortable) " · recortable" else "") + (if (c.fijo) " · fijo" else ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (categorias.size > 6) TextButton(onClick = { verTodas = !verTodas }) { Text(if (verTodas) "Ver menos" else "Ver las ${categorias.size}") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nueva = TipoMovimiento.GASTO }) { Text("+ Gasto") }
            OutlinedButton(onClick = { nueva = TipoMovimiento.INGRESO }) { Text("+ Ingreso") }
        }
    }
    val tipoNueva = nueva
    if (editando != null || tipoNueva != null) {
        DialogoCategoria(
            inicial = editando, tipo = editando?.tipo ?: tipoNueva ?: TipoMovimiento.GASTO, todas = categorias,
            onGuardar = { vm.guardarCategoria(it) },
            onBorrar = { c, destino -> vm.borrarCategoria(c, destino) },
            onCerrar = { editando = null; nueva = null },
        )
    }
}

@Composable
private fun DialogoCategoria(
    inicial: Categoria?, tipo: TipoMovimiento, todas: List<Categoria>,
    onGuardar: (Categoria) -> Unit, onBorrar: (Categoria, Categoria) -> Unit, onCerrar: () -> Unit,
) {
    var nombre by remember { mutableStateOf(inicial?.nombre ?: "") }
    var emoji by remember { mutableStateOf(inicial?.emoji ?: "📦") }
    var grupo by remember { mutableStateOf(inicial?.grupo ?: if (tipo == TipoMovimiento.INGRESO) GrupoGasto.INGRESO else GrupoGasto.DESEO) }
    var recortable by remember { mutableStateOf(inicial?.recortable ?: (tipo == TipoMovimiento.GASTO)) }
    var fijo by remember { mutableStateOf(inicial?.fijo ?: false) }
    var claves by remember { mutableStateOf(inicial?.palabrasClave ?: "") }
    var archivada by remember { mutableStateOf(inicial?.archivada ?: false) }
    var confirmarBorrado by remember { mutableStateOf(false) }
    // A dónde van los movimientos si se borra: "Otros gastos/ingresos" o la primera del mismo tipo.
    val destino = todas.filter { it.tipo == tipo && it.id != inicial?.id }.let { lista -> lista.firstOrNull { it.nombre.startsWith("Otros") } ?: lista.firstOrNull() }

    HojaFormulario(
        titulo = if (inicial == null) "Nueva categoría" else "Editar categoría",
        onCerrar = onCerrar,
        botones = {
            if (inicial != null && destino != null) {
                TextButton(onClick = { confirmarBorrado = true }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            }
            TextButton(onClick = onCerrar) { Text("Cancelar") }
            Button(enabled = nombre.isNotBlank(), onClick = {
                onGuardar(
                    (inicial ?: Categoria(nombre = "", emoji = "", tipo = tipo, grupo = grupo, recortable = recortable, orden = 18)).copy(
                        nombre = nombre.trim(), emoji = emoji.ifBlank { "📦" }, grupo = grupo, recortable = recortable, fijo = fijo,
                        palabrasClave = claves.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","), archivada = archivada,
                    ),
                )
                onCerrar()
            }) { Text("Guardar") }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(emoji, { emoji = it.take(4) }, label = { Text("Emoji") }, singleLine = true, modifier = Modifier.width(90.dp))
            OutlinedTextField(nombre, { nombre = it.take(30) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (tipo == TipoMovimiento.GASTO) {
            Text("Grupo del presupuesto", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GrupoGasto.entries.filter { it != GrupoGasto.INGRESO }.forEach { g ->
                    FilterChip(selected = grupo == g, onClick = { grupo = g }, label = { Text(g.etiqueta) })
                }
            }
            FilaInterruptor("Recortable", recortable, { recortable = it }, "Lo podrías dejar de gastar ya mismo en una crisis")
            FilaInterruptor("Gasto fijo mensual", fijo, { fijo = it }, "Alquiler, servicios: no cuenta en el ritmo semanal")
        }
        OutlinedTextField(
            claves, { claves = it }, label = { Text("Palabras clave (separadas por coma)") },
            supportingText = { Text("Para reconocerla al escribir: \"coto,carrefour,super\"") }, modifier = Modifier.fillMaxWidth(),
        )
        if (inicial != null) FilaInterruptor("Archivada", archivada, { archivada = it }, "Deja de aparecer al cargar movimientos")
    }
    if (confirmarBorrado && inicial != null && destino != null) {
        DialogoConfirmar(
            "Borrar ${inicial.nombre}", "Sus movimientos pasan a \"${destino.nombre}\" y se quita su presupuesto.", "Borrar",
            onConfirmar = { onBorrar(inicial, destino); onCerrar() }, onCerrar = { confirmarBorrado = false }, peligroso = true,
        )
    }
}

// =========================================================================================
// Datos: respaldo, CSV, ejemplo y borrado
// =========================================================================================

@Composable
private fun SeccionDatos(vm: AppViewModel) {
    var confirmar by remember { mutableStateOf<String?>(null) }
    var uriImportar by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportarJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportarJson) }
    val exportarCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::exportarCsv) }
    val importarJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uriImportar = uri }
    val hoy = LocalDate.now()

    Seccion("💾 Tus datos", subtitulo = "Todo queda en el teléfono. Hacé un respaldo de vez en cuando.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { exportarJson.launch("cadapeso-respaldo-$hoy.json") }) { Text("Exportar respaldo") }
            OutlinedButton(onClick = { importarJson.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restaurar respaldo") }
            OutlinedButton(onClick = { exportarCsv.launch("cadapeso-movimientos-$hoy.csv") }) { Text("Exportar a Excel (CSV)") }
            OutlinedButton(onClick = { confirmar = "ejemplo" }) { Text("Cargar datos de ejemplo") }
            TextButton(onClick = { confirmar = "borrar" }) { Text("Borrar datos", color = MaterialTheme.colorScheme.error) }
        }
    }
    when (confirmar) {
        "ejemplo" -> DialogoConfirmar(
            "Datos de ejemplo", "Se agregan 3 meses de movimientos, deudas, activos y metas inventados para que explores la app.", "Cargar",
            onConfirmar = { vm.cargarDatosEjemplo() }, onCerrar = { confirmar = null },
        )
        "borrar" -> DialogoConfirmar(
            "Borrar todos los datos", "Se borran movimientos, presupuestos, deudas, activos, metas y el chat. Tu perfil y la IA se conservan. No se puede deshacer.",
            "Borrar todo", onConfirmar = { vm.borrarDatos() }, onCerrar = { confirmar = null }, peligroso = true,
        )
    }
    uriImportar?.let { uri ->
        DialogoConfirmar(
            "Restaurar respaldo", "Se reemplazan TODOS los datos actuales por los del archivo.", "Restaurar",
            onConfirmar = { vm.importarJson(uri) }, onCerrar = { uriImportar = null }, peligroso = true,
        )
    }
}

@Composable
private fun AcercaDe() {
    Seccion("ℹ️ Acerca de CadaPeso") {
        Text("Versión 1.0 · Gestión de gastos con asesor financiero basado en Gemma 2 de Google.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Gemma es un modelo de Google, sujeto a los Términos de uso de Gemma. Corre con MediaPipe LLM Inference (en el teléfono) u Ollama (en tu PC).",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "CadaPeso es una herramienta educativa: no es asesoramiento financiero profesional ni recomienda productos de inversión.",
            style = MaterialTheme.typography.bodySmall, color = ColoresApp.advertencia,
        )
    }
}

// =========================================================================================
// Tests de perfil (conducta y riesgo)
// =========================================================================================

@Composable
fun TestPerfilPantalla(tipo: TipoTest, vm: AppViewModel, onCerrar: () -> Unit) {
    val preguntas = if (tipo == TipoTest.CONDUCTA) Perfilado.TEST_CONDUCTA else Perfilado.TEST_RIESGO
    var respuestas by remember { mutableStateOf(List(preguntas.size) { -1 }) }
    var puntaje by remember { mutableStateOf<Int?>(null) }
    Scaffold(
        snackbarHost = { SnackbarHost(LocalAvisos.current) },
        topBar = {
            TopAppBar(
                title = { Text(if (tipo == TipoTest.CONDUCTA) "Test de conducta financiera" else "Test de tolerancia al riesgo") },
                navigationIcon = { IconButton(onClick = onCerrar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    if (tipo == TipoTest.CONDUCTA) "Respondé con honestidad: no hay respuestas buenas ni malas. Sirve para que el asesor te hable de la forma que más te ayuda."
                    else "Mide cómo reaccionás ante caídas del mercado y pérdidas temporales. Incluye el \"factor sueño\": elegir un riesgo que te deje dormir tranquilo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            preguntas.forEachIndexed { i, p ->
                item(key = "p$i") {
                    Seccion("${i + 1}. ${p.texto}") {
                        p.opciones.forEachIndexed { j, o ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = respuestas[i] == j, role = Role.RadioButton) {
                                    respuestas = respuestas.toMutableList().also { it[i] = j }
                                    puntaje = null
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = respuestas[i] == j, onClick = null)
                                Text(o.texto, Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    enabled = respuestas.none { it < 0 },
                    onClick = {
                        val p = Perfilado.puntaje(preguntas, respuestas)
                        puntaje = p
                        if (tipo == TipoTest.CONDUCTA) vm.guardarResultadoTest(conducta = p, riesgo = null)
                        else vm.guardarResultadoTest(conducta = null, riesgo = p)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ver resultado") }
            }
            puntaje?.let { p ->
                item {
                    if (tipo == TipoTest.CONDUCTA) {
                        val c = Perfilado.conducta(p)!!
                        Seccion("Resultado: ${c.etiqueta}", subtitulo = "Puntaje $p de 100") {
                            Text(
                                when (c) {
                                    com.gabriel.cadapeso.dominio.PerfilConducta.AHORRADOR -> "Tenés buenos hábitos. El asesor te va a animar a invertir lo que ahorrás."
                                    com.gabriel.cadapeso.dominio.PerfilConducta.EQUILIBRADO -> "Tenés hábitos razonables con algunos puntos a mejorar."
                                    com.gabriel.cadapeso.dominio.PerfilConducta.IMPULSIVO -> "Tendés a comprar por impulso o emoción. Vas a ver más seguido la regla de las 24 h, con un umbral más bajo."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onCerrar) { Text("Listo") }
                        }
                    } else {
                        val r = Perfilado.riesgo(p)!!
                        Seccion("Resultado: ${r.etiqueta}", subtitulo = "Puntaje $p de 100") {
                            Text(r.descripcion, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Rendimiento real esperado ~${Formato.porcentaje(r.rendimientoRealAnual)} anual; en un mal año podría caer ~${Formato.porcentaje(r.caidaMalAnio)}.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onCerrar) { Text("Listo") }
                        }
                    }
                }
            }
        }
    }
}

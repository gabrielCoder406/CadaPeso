@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gabriel.cadapeso.ui.pantallas

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gabriel.cadapeso.datos.MovimientoDetectado
import com.gabriel.cadapeso.dominio.Analisis
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.SaldoCuenta
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.plural
import com.gabriel.cadapeso.integraciones.CampoBanco
import com.gabriel.cadapeso.integraciones.ConfigIntegraciones
import com.gabriel.cadapeso.integraciones.CredencialesGalicia
import com.gabriel.cadapeso.integraciones.OyenteNotificaciones
import com.gabriel.cadapeso.ui.ConexionesViewModel
import com.gabriel.cadapeso.ui.LocalAvisos
import com.gabriel.cadapeso.ui.PreguntaBanco
import com.gabriel.cadapeso.ui.componentes.AvatarCategoria
import com.gabriel.cadapeso.ui.componentes.DialogoConfirmar
import com.gabriel.cadapeso.ui.componentes.EstadoVacio
import com.gabriel.cadapeso.ui.componentes.Etiqueta
import com.gabriel.cadapeso.ui.componentes.FilaDato
import com.gabriel.cadapeso.ui.componentes.HojaFormulario
import com.gabriel.cadapeso.ui.componentes.Seccion
import com.gabriel.cadapeso.ui.componentes.SelectorCategoriaDialogo
import com.gabriel.cadapeso.ui.tema.ColoresApp
import com.gabriel.cadapeso.ui.tema.EstiloMonto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Cuentas conectadas: lectura de notificaciones del banco (sin servidor), conexión con el
 * servidor de CadaPeso, Mercado Pago (OAuth) y Banco Galicia (vía Prometeo).
 */
@Composable
fun ConexionesPantalla(a: Analisis, vm: ConexionesViewModel, onBandeja: () -> Unit, onVolver: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val trabajando by vm.trabajando.collectAsStateWithLifecycle()
    val pendientes by vm.pendientes.collectAsStateWithLifecycle()
    val pregunta by vm.pregunta.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(LocalAvisos.current) },
        topBar = {
            TopAppBar(
                title = { Text("Cuentas conectadas") },
                navigationIcon = { IconButton(onClick = onVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") } },
                actions = {
                    if (config.registrado) {
                        IconButton(onClick = vm::sincronizar, enabled = trabajando == null) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Sincronizar")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            trabajando?.let { texto ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(texto, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (pendientes.isNotEmpty()) {
                item {
                    Seccion(
                        "📥 ${plural(pendientes.size, "movimiento para revisar", "movimientos para revisar")}",
                        subtitulo = "Llegaron de tus cuentas: aceptalos, editalos o descartalos",
                        onClick = onBandeja,
                    ) {}
                }
            }
            item { SeccionNotificacionesBanco(config, vm) }
            item { SeccionServidor(config, trabajando != null, vm) }
            if (config.registrado) {
                item { SeccionMercadoPago(config, a.moneda, trabajando != null, vm) }
                item { SeccionGalicia(config, a.moneda, trabajando != null, vm) }
                item { SeccionOpcionesConexiones(config, vm) }
            }
            item { SeccionPrivacidadConexiones() }
        }
    }

    pregunta?.let { p -> HojaPreguntaBanco(p, onResponder = vm::responderBanco, onCancelar = vm::cancelarPregunta) }
}

// =========================================================================================
// Notificaciones del banco (todo en el teléfono)
// =========================================================================================

@Composable
private fun SeccionNotificacionesBanco(config: ConfigIntegraciones, vm: ConexionesViewModel) {
    val contexto = LocalContext.current
    // El acceso se da en una pantalla del sistema: se vuelve a mirar cada vez que volvés a la app.
    var permiso by remember { mutableStateOf(OyenteNotificaciones.permisoConcedido(contexto)) }
    LifecycleResumeEffect(Unit) {
        permiso = OyenteNotificaciones.permisoConcedido(contexto)
        onPauseOrDispose { }
    }
    val alguna = config.leerNotificacionesGalicia || config.leerNotificacionesMercadoPago

    Seccion(
        "📲 Avisos del banco en tu teléfono",
        subtitulo = "Lee las notificaciones de las apps de Galicia y Mercado Pago. No necesita servidor, internet ni claves: nada sale del teléfono.",
    ) {
        FilaInterruptor(
            "Banco Galicia", config.leerNotificacionesGalicia, { vm.leerNotificaciones(OrigenMovimiento.GALICIA, it) },
            "Compras con débito y crédito, transferencias y extracciones",
        )
        FilaInterruptor(
            "Mercado Pago", config.leerNotificacionesMercadoPago, { vm.leerNotificaciones(OrigenMovimiento.MERCADO_PAGO, it) },
            "Pagos, cobros y transferencias",
        )
        when {
            alguna && !permiso -> {
                Text(
                    "Falta darle a CadaPeso el \"Acceso a notificaciones\" en los ajustes de Android.",
                    style = MaterialTheme.typography.bodySmall, color = ColoresApp.advertencia,
                )
                FilledTonalButton(onClick = { runCatching { OyenteNotificaciones.abrirAjustesDelSistema(contexto) } }) { Text("Dar acceso") }
                Text(
                    "Si Android dice \"Configuración restringida\" (pasa con las apps instaladas desde un APK): abrí " +
                        "Información de la app › menú ⋮ › Permitir configuración restringida, y volvé a tocar \"Dar acceso\".",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { runCatching { OyenteNotificaciones.abrirInformacionDeLaApp(contexto) } }) { Text("Abrir información de la app") }
            }
            permiso -> Text("✅ Acceso a notificaciones activado", style = MaterialTheme.typography.labelMedium, color = ColoresApp.positivo)
        }
        Text(
            "Lo detectado va a la bandeja para que lo revises: las notificaciones a veces no traen todos los datos.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// =========================================================================================
// Servidor
// =========================================================================================

@Composable
private fun SeccionServidor(config: ConfigIntegraciones, ocupado: Boolean, vm: ConexionesViewModel) {
    var url by remember(config.servidorUrl) { mutableStateOf(config.servidorUrl) }
    var clave by remember { mutableStateOf("") }
    var confirmar by remember { mutableStateOf(false) }

    Seccion(
        "🖥️ Servidor de CadaPeso",
        subtitulo = "Hace falta para Mercado Pago (API oficial y webhooks) y para Galicia (vía Prometeo). Lo instalás vos: está en la carpeta servidor/ del proyecto.",
    ) {
        if (!config.registrado) {
            OutlinedTextField(
                url, { url = it.trim() }, label = { Text("Dirección del servidor") },
                placeholder = { Text("https://cadapeso.tudominio.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                clave, { clave = it }, label = { Text("Clave de registro") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("La CLAVE_REGISTRO que pusiste en el servidor") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.registrar(url, clave); clave = "" }, enabled = !ocupado && url.isNotBlank() && clave.length >= 12) {
                Text("Conectar este teléfono")
            }
        } else {
            FilaDato("Servidor", config.servidorUrl.removePrefix("https://"))
            FilaDato("Última sincronización", if (config.ultimaSincronizacion > 0) momento(config.ultimaSincronizacion) else "Nunca")
            if (config.ultimoError.isNotBlank()) {
                Text(config.ultimoError, style = MaterialTheme.typography.bodySmall, color = ColoresApp.negativo)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = vm::sincronizar, enabled = !ocupado) { Text("Sincronizar ahora") }
                TextButton(onClick = { confirmar = true }, enabled = !ocupado) { Text("Desconectar", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmar) {
        DialogoConfirmar(
            "Desconectar este teléfono",
            "El servidor borra los tokens de Mercado Pago y los movimientos que tenía guardados para vos. Lo que ya está en la app se queda.",
            "Desconectar", onConfirmar = vm::desconectarServidor, onCerrar = { confirmar = false }, peligroso = true,
        )
    }
}

// =========================================================================================
// Mercado Pago
// =========================================================================================

@Composable
private fun SeccionMercadoPago(config: ConfigIntegraciones, moneda: String, ocupado: Boolean, vm: ConexionesViewModel) {
    val abrir = LocalUriHandler.current
    val estado = config.estado
    val conexion = estado?.conexion(OrigenMovimiento.MERCADO_PAGO)
    var confirmar by remember { mutableStateOf(false) }

    Seccion(
        "🤝 Mercado Pago",
        subtitulo = "API oficial con OAuth 2.0: autorizás el acceso desde la web de Mercado Pago. Cada pago o cobro llega al servidor al instante (webhook).",
    ) {
        when {
            estado != null && !estado.mercadoPagoDisponible -> Text(
                "El servidor no tiene configurada una aplicación de Mercado Pago (MP_CLIENT_ID y MP_CLIENT_SECRET).",
                style = MaterialTheme.typography.bodySmall, color = ColoresApp.advertencia,
            )
            conexion == null -> {
                Text("Se abre Mercado Pago en el navegador; al terminar, volvés solo a la app.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { vm.vincularMercadoPago { abrir.openUri(it) } }, enabled = !ocupado) { Text("Vincular Mercado Pago") }
            }
            else -> {
                FilaDato("Cuenta", conexion.alias.ifBlank { "Vinculada" })
                FilaDato("Estado", textoEstadoConexion(conexion.estado))
                if (conexion.error.isNotBlank()) Text(conexion.error, style = MaterialTheme.typography.bodySmall, color = ColoresApp.negativo)
                FilasSaldo(estado?.saldos.orEmpty().filter { it.origen == OrigenMovimiento.MERCADO_PAGO }, moneda)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (conexion.necesitaRevincular) {
                        Button(onClick = { vm.vincularMercadoPago { abrir.openUri(it) } }, enabled = !ocupado) { Text("Volver a vincular") }
                    }
                    TextButton(onClick = { confirmar = true }, enabled = !ocupado) { Text("Desvincular", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmar) {
        DialogoConfirmar(
            "Desvincular Mercado Pago",
            "El servidor borra los tokens de acceso. Para revocar también el permiso, entrá a Mercado Pago › Seguridad › Aplicaciones conectadas.",
            "Desvincular", onConfirmar = { vm.desvincular(OrigenMovimiento.MERCADO_PAGO) }, onCerrar = { confirmar = false }, peligroso = true,
        )
    }
}

// =========================================================================================
// Banco Galicia (vía Prometeo)
// =========================================================================================

@Composable
private fun SeccionGalicia(config: ConfigIntegraciones, moneda: String, ocupado: Boolean, vm: ConexionesViewModel) {
    val estado = config.estado
    val disponible = estado?.galiciaDisponible != false
    val campos by vm.camposGalicia.collectAsStateWithLifecycle()
    var usuario by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    val extras = remember { mutableStateMapOf<String, String>() }
    var recordar by remember { mutableStateOf(true) }
    var confirmar by remember { mutableStateOf(false) }
    LaunchedEffect(disponible, config.galiciaGuardada) {
        if (disponible && !config.galiciaGuardada) vm.cargarCamposGalicia()
    }
    val completos = usuario.isNotBlank() && clave.isNotBlank() && campos.all { it.opcional || !extras[it.nombre].isNullOrBlank() }

    Seccion(
        "🏦 Banco Galicia (vía Prometeo)",
        subtitulo = "Galicia no tiene API pública para personas. Prometeo, un agregador de Open Banking conectado con el banco, trae cuentas, saldos y movimientos con tu usuario del home banking.",
    ) {
        when {
            !disponible -> Text(
                "El servidor no tiene configurado Prometeo (PROMETEO_API_KEY). Mientras tanto podés usar los avisos del banco de arriba.",
                style = MaterialTheme.typography.bodySmall, color = ColoresApp.advertencia,
            )
            config.galiciaGuardada -> {
                FilaDato("Usuario", config.galiciaUsuario)
                FilasSaldo(estado?.saldos.orEmpty().filter { it.origen == OrigenMovimiento.GALICIA }, moneda)
                Text("Se sincroniza solo cada unas horas. Si el banco pide un código, te lo preguntamos al sincronizar desde acá.", style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = vm::sincronizarGalicia, enabled = !ocupado) { Text("Sincronizar Galicia") }
                    TextButton(onClick = { confirmar = true }, enabled = !ocupado) { Text("Olvidar mi clave", color = MaterialTheme.colorScheme.error) }
                }
            }
            else -> {
                OutlinedTextField(usuario, { usuario = it.trim() }, label = { Text("Usuario del home banking") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    clave, { clave = it }, label = { Text("Clave") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                campos.forEach { campo -> CampoExtraBanco(campo, extras[campo.nombre].orEmpty()) { extras[campo.nombre] = it } }
                FilaInterruptor(
                    "Recordar en este teléfono", recordar, { recordar = it },
                    "Cifrada con el Keystore de Android, para sincronizar sola. Si no, la pedimos cada vez.",
                )
                Button(
                    onClick = {
                        vm.conectarGalicia(CredencialesGalicia(usuario, clave, extras.toMap()), recordar)
                        clave = ""
                    },
                    enabled = !ocupado && completos,
                ) { Text("Conectar Galicia") }
                Text(
                    "Tu clave viaja cifrada (HTTPS) a tu servidor, que la usa solo para abrir la sesión en Prometeo y no la guarda.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmar) {
        DialogoConfirmar(
            "Olvidar la clave de Galicia", "Se borra del teléfono. Los movimientos que ya importaste se quedan.", "Olvidar",
            onConfirmar = { vm.olvidarGalicia() }, onCerrar = { confirmar = false }, peligroso = true,
        )
    }
}

/** Campo extra que pide el banco (documento, tipo de documento…), según lo que informa Prometeo. */
@Composable
private fun CampoExtraBanco(campo: CampoBanco, valor: String, onCambio: (String) -> Unit) {
    if (campo.tipo == "opciones" && campo.opciones.isNotEmpty()) {
        Text(campo.etiqueta, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            campo.opciones.forEach { (v, etiqueta) -> FilterChip(selected = valor == v, onClick = { onCambio(v) }, label = { Text(etiqueta) }) }
        }
    } else {
        OutlinedTextField(
            valor, { onCambio(it.trim()) }, label = { Text(campo.etiqueta + if (campo.opcional) " (opcional)" else "") }, singleLine = true,
            visualTransformation = if (campo.tipo == "clave") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** El banco pide un dato más para entrar (token por SMS, pregunta de seguridad). */
@Composable
private fun HojaPreguntaBanco(p: PreguntaBanco, onResponder: (String) -> Unit, onCancelar: () -> Unit) {
    var respuesta by remember { mutableStateOf("") }
    HojaFormulario(
        titulo = "Galicia pide un dato más",
        onCerrar = onCancelar,
        botones = {
            TextButton(onClick = onCancelar) { Text("Cancelar") }
            Button(onClick = { onResponder(respuesta) }, enabled = respuesta.isNotBlank()) { Text("Enviar") }
        },
    ) {
        Text(p.pregunta.pregunta, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(respuesta, { respuesta = it }, label = { Text("Respuesta") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

// =========================================================================================
// Opciones y privacidad
// =========================================================================================

@Composable
private fun SeccionOpcionesConexiones(config: ConfigIntegraciones, vm: ConexionesViewModel) {
    Seccion("⚙️ Cómo importar") {
        FilaInterruptor(
            "Agregar sin revisar", config.aceptarSolas, vm::aceptarSolas,
            "Lo que llega de Mercado Pago o Galicia entra directo a tus movimientos (salvo transferencias entre tus cuentas o posibles duplicados). Lo de las notificaciones siempre se revisa.",
        )
        FilaInterruptor(
            "Saldos en Patrimonio", config.reflejarSaldos, vm::reflejarSaldos,
            "Mantiene un activo \"Cuenta o billetera\" con el saldo en pesos de cada cuenta conectada",
        )
    }
}

@Composable
private fun SeccionPrivacidadConexiones() {
    Seccion("🔒 Qué datos salen del teléfono") {
        Text(
            "• Avisos del banco: nada; se leen y procesan en el teléfono.\n" +
                "• Mercado Pago: tu servidor guarda los tokens de acceso cifrados (AES-256) y recibe los pagos de la API.\n" +
                "• Galicia: tu servidor usa tu clave solo para hablar con Prometeo; no la guarda.\n" +
                "• Los movimientos esperan cifrados en el servidor hasta que el teléfono los baja; después se borran.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FilasSaldo(saldos: List<SaldoCuenta>, moneda: String) {
    saldos.forEach { s ->
        FilaDato("Saldo ${s.cuenta}".trim(), Formato.dinero(s.monto, if (s.moneda == "ARS") moneda else "US$".takeIf { s.moneda == "USD" } ?: s.moneda))
    }
}

private fun textoEstadoConexion(estado: String): String = when (estado) {
    "ACTIVA" -> "✅ Activa"
    "ERROR" -> "⚠️ Con errores"
    "REVOCADA" -> "⛔ Hay que volver a vincular"
    else -> estado
}

/** "Hoy 14:05", "Ayer 09:30", "Lun 6 oct 18:00". */
private fun momento(millis: Long): String {
    val fechaHora = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return Formato.diaRelativo(fechaHora.toLocalDate(), LocalDate.now()) + " " + String.format(Formato.locale, "%02d:%02d", fechaHora.hour, fechaHora.minute)
}

// =========================================================================================
// Bandeja de movimientos detectados
// =========================================================================================

/**
 * Lo que llegó de las cuentas conectadas y espera revisión: se acepta (con la categoría
 * sugerida o la que elijas), se edita antes de guardarlo o se descarta.
 */
@Composable
fun BandejaPantalla(
    a: Analisis,
    vm: ConexionesViewModel,
    onEditar: (MovimientoDetectado, Long?) -> Unit,
    onConexiones: () -> Unit,
    onVolver: () -> Unit,
) {
    val pendientes by vm.pendientes.collectAsStateWithLifecycle()
    val categoriaElegida = remember { mutableStateMapOf<Long, Long>() }
    var elegirPara by remember { mutableStateOf<MovimientoDetectado?>(null) }
    val sinDecision = pendientes.count { !it.transferenciaPropia && it.duplicadoDeId == null }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalAvisos.current) },
        topBar = {
            TopAppBar(
                title = { Text("Para revisar") },
                navigationIcon = { IconButton(onClick = onVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") } },
                actions = { TextButton(onClick = onConexiones) { Text("Cuentas") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (pendientes.isEmpty()) {
                item {
                    EstadoVacio(
                        "📥", "Nada para revisar",
                        "Cuando lleguen movimientos de Mercado Pago o de Galicia, aparecen acá para que los confirmes.",
                        boton = "Cuentas conectadas", onBoton = onConexiones,
                    )
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            plural(pendientes.size, "movimiento", "movimientos"),
                            Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        )
                        if (sinDecision > 0) FilledTonalButton(onClick = { vm.aceptarTodos() }) { Text("Aceptar todos ($sinDecision)") }
                    }
                }
                items(pendientes, key = { it.id }) { d ->
                    val categoriaId = categoriaElegida[d.id] ?: d.categoriaId
                    TarjetaDetectado(
                        d, a, categoriaId,
                        onCategoria = { elegirPara = d },
                        onAceptar = { if (d.moneda == "ARS") vm.aceptar(d, categoriaId) else onEditar(d, categoriaId) },
                        onEditar = { onEditar(d, categoriaId) },
                        onDescartar = { vm.descartar(d) },
                        onEsElMismo = { vm.esElMismo(d) },
                    )
                }
            }
        }
    }

    elegirPara?.let { d ->
        SelectorCategoriaDialogo(
            categorias = a.datos.categorias,
            tipo = d.tipo,
            seleccionada = categoriaElegida[d.id] ?: d.categoriaId,
            onElegir = { categoriaElegida[d.id] = it.id; elegirPara = null },
            onCerrar = { elegirPara = null },
        )
    }
}

@Composable
private fun TarjetaDetectado(
    d: MovimientoDetectado,
    a: Analisis,
    categoriaId: Long?,
    onCategoria: () -> Unit,
    onAceptar: () -> Unit,
    onEditar: () -> Unit,
    onDescartar: () -> Unit,
    onEsElMismo: () -> Unit,
) {
    val categoria = categoriaId?.let { a.datos.categoria(it) }
    val ingreso = d.tipo == TipoMovimiento.INGRESO
    val simbolo = if (d.moneda == "ARS") a.moneda else if (d.moneda == "USD") "US$" else d.moneda
    val duplicado = d.duplicadoDeId?.let { id -> a.datos.movimientos.firstOrNull { it.id == id } }

    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarCategoria(categoria)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.descripcion, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${d.origen.emoji} ${d.origen.etiqueta} · ${Formato.diaRelativo(d.fecha, a.hoy)} · ${d.medioPago.etiqueta}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                Text(
                    (if (ingreso) "+" else "-") + Formato.dinero(d.monto, simbolo),
                    style = MaterialTheme.typography.bodyLarge.merge(EstiloMonto),
                    color = if (ingreso) ColoresApp.positivo else MaterialTheme.colorScheme.onSurface,
                )
            }
            FilterChip(
                selected = categoria != null, onClick = onCategoria,
                label = { Text(categoria?.let { "${it.emoji} ${it.nombre}" } ?: "Elegir categoría") },
            )
            if (d.transferenciaPropia) {
                Etiqueta("Parece plata entre tus cuentas", ColoresApp.advertencia)
                Text(
                    "Si es una transferencia a tu propia cuenta o una extracción, descartalo: no es ni ingreso ni gasto.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (duplicado != null) {
                Etiqueta("¿Ya lo cargaste?", ColoresApp.advertencia)
                Text(
                    "Cargaste \"${duplicado.descripcion}\" el ${Formato.fechaCorta(duplicado.fecha)} por ${Formato.dinero(duplicado.monto, a.moneda)}.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (d.moneda != "ARS") {
                Text("Está en ${d.moneda}: al aceptarlo podés pasarlo a pesos.", style = MaterialTheme.typography.labelSmall, color = ColoresApp.advertencia)
            }
            if (d.detalle.isNotBlank()) {
                Text(
                    d.detalle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDescartar) { Text("Descartar") }
                if (duplicado != null) OutlinedButton(onClick = onEsElMismo) { Text("Es el mismo") }
                TextButton(onClick = onEditar) { Text("Editar") }
                Button(onClick = onAceptar) { Text(if (duplicado != null) "Es otro" else "Aceptar") }
            }
        }
    }
}

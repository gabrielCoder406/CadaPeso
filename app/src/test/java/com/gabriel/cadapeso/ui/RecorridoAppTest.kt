package com.gabriel.cadapeso.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.gabriel.cadapeso.Contenedor
import com.gabriel.cadapeso.datos.Perfil
import com.gabriel.cadapeso.dominio.CanalDeteccion
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.TransaccionExterna
import com.gabriel.cadapeso.ui.tema.CadaPesoTema
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate
import java.time.YearMonth

/**
 * Prueba de punta a punta de la app REAL (base de datos Room, motor, pantallas) simulando
 * Android en la PC con Robolectric: se toca la interfaz como lo haría un usuario.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class RecorridoAppTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var contenedor: Contenedor

    @Before
    fun preparar() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        app.deleteDatabase("cadapeso.db")
        contenedor = Contenedor(app)
        runBlocking { contenedor.repo.inicializar() }
    }

    @After
    fun cerrar() {
        contenedor.db.close()
    }

    // ------------------------------------------------------------------ Ayudantes

    private fun iniciar() = compose.setContent { CadaPesoTema { AppRaiz(contenedor, null) {} } }

    private fun existe(texto: String, substring: Boolean = false) =
        compose.onAllNodesWithText(texto, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun esperarTexto(texto: String, substring: Boolean = false, ms: Long = 20_000) {
        try {
            compose.waitUntil(ms) { existe(texto, substring) }
        } catch (e: Throwable) {
            // Si falla, mostramos qué textos había en pantalla: ayuda mucho a entender el porqué.
            val visibles = compose.onAllNodes(SemanticsMatcher("cualquiera") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes().flatMap { nodo ->
                    nodo.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                        nodo.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.map { "[desc] $it" }
                }
            val fusionados = compose.onAllNodes(SemanticsMatcher("cualquiera") { true })
                .fetchSemanticsNodes().map { nodo ->
                    nodo.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("+") { it.text } +
                        nodo.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString("+") { "[desc]$it" }
                }.filter { it.isNotBlank() }
            throw AssertionError(
                "No apareció \"$texto\". En pantalla: ${visibles.joinToString(" | ")}\nFUSIONADOS: ${fusionados.joinToString(" | ")}", e,
            )
        }
    }

    private fun clic(texto: String, substring: Boolean = false) {
        esperarTexto(texto, substring)
        val nodo = compose.onAllNodesWithText(texto, substring = substring).onFirst()
        // Si está dentro de algo desplazable y fuera de la vista, primero se lo trae a la pantalla.
        runCatching { nodo.performScrollTo() }
        nodo.performClick()
        compose.waitForIdle()
    }

    /** Los avisos de abajo duran 4 segundos: se adelanta el reloj de Compose para que desaparezcan. */
    private fun dejarPasarAvisos() {
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
    }

    /**
     * Baja en la lista (LazyColumn) hasta que aparezca el texto. Con [substring] = false busca el
     * texto exacto: así "Supermercado" no se confunde con un consejo que lo menciona.
     */
    private fun bajarHasta(texto: String, substring: Boolean = true) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(texto, substring = substring))
        compose.waitForIdle()
    }

    /**
     * Espera a que la base cumpla una condición. En Robolectric el hilo principal solo avanza
     * cuando el test lo deja: idleMainLooper() permite que sigan las corrutinas del ViewModel
     * (que vuelven al hilo principal entre una operación de base de datos y la siguiente).
     */
    private fun esperarDatos(ms: Long = 20_000, condicion: (com.gabriel.cadapeso.dominio.DatosFinancieros) -> Boolean) =
        compose.waitUntil(ms) {
            ShadowLooper.idleMainLooper()
            runBlocking { condicion(contenedor.repo.cargarDatos()) }
        }

    // ------------------------------------------------------------------ Pruebas

    @Test
    fun bienvenidaArmaPerfilYPresupuesto() {
        iniciar()
        clic("Empezar")
        compose.onNodeWithText("¿Cómo te llamás?").performTextInput("Gabriel")
        compose.onNodeWithText("Edad").performTextInput("29")
        esperarTexto("Etapa: Juventud")
        clic("Siguiente")
        compose.onNodeWithText("Ingreso mensual neto (lo que cobrás)").performTextInput("1200000")
        clic("Siguiente")
        clic("Siguiente") // regla 50/30/20 por defecto, con "armar presupuesto" marcado
        clic("¡Listo!")
        esperarTexto("Disponible")

        val perfil = runBlocking { contenedor.repo.obtenerPerfil() }
        assertTrue(perfil.onboardingCompleto)
        assertEquals("Gabriel", perfil.nombre)
        assertEquals(1_200_000.0, perfil.ingresoMensual, 0.01)
        // "Cada peso con una tarea": el presupuesto del mes reparte exactamente el ingreso.
        esperarDatos { it.asignacionesDe(YearMonth.now()).isNotEmpty() }
        val asignado = runBlocking { contenedor.repo.cargarDatos().asignacionesDe(YearMonth.now()).sumOf { it.monto } }
        assertEquals(1_200_000.0, asignado, 0.01)
    }

    @Test
    fun formulariosModificanLaBase() {
        iniciar()
        clic("Explorar con datos de ejemplo")
        esperarDatos { it.movimientos.size > 100 }
        esperarTexto("Disponible")
        dejarPasarAvisos()

        // ---- Asignar presupuesto a una categoría ("cada peso con una tarea") ----
        clic("Presupuesto")
        esperarTexto("Cada peso con una tarea")
        // Según la fecha, arriba puede aparecer el "plan de ajuste", que nombra al supermercado en
        // un consejo: se busca la fila de la categoría por su texto exacto.
        bajarHasta("Supermercado", substring = false)
        clic("Supermercado")
        esperarTexto("🛒 Supermercado")
        compose.onNodeWithText("Monto").performTextClearance()
        compose.onNodeWithText("Monto").performTextInput("300000")
        clic("Guardar")
        esperarDatos { d ->
            val super_ = d.categorias.first { it.nombre == "Supermercado" }
            d.asignacionesDe(YearMonth.now()).any { it.categoriaId == super_.id && it.monto == 300_000.0 }
        }
        dejarPasarAvisos()

        // ---- Pagar una deuda: registra el gasto y baja el saldo ----
        clic("Patrimonio")
        clic("Deudas")
        esperarTexto("Tus deudas")
        bajarHasta("Registrar pago")
        clic("Registrar pago")
        esperarTexto("Pagar Tarjeta Visa")
        clic("Guardar")
        esperarDatos { d -> d.deudas.any { it.nombre == "Tarjeta Visa" && it.saldo == 485_000.0 } }
        esperarDatos { d -> d.movimientos.any { it.descripcion == "Pago Tarjeta Visa" && it.deudaId != null } }
        dejarPasarAvisos()

        // ---- Crear una meta SMART ----
        clic("Ahorro y metas")
        esperarTexto("💪 Págate a ti primero")
        bajarHasta("🎯 Metas SMART")
        compose.onNodeWithContentDescription("Nueva meta").performClick()
        esperarTexto("Nueva meta SMART")
        compose.onNodeWithText("Nombre").performTextInput("Moto")
        compose.onNodeWithText("M · Monto objetivo").performTextInput("2000000")
        clic("Guardar")
        esperarDatos { d -> d.metas.any { it.nombre == "Moto" && it.montoObjetivo == 2_000_000.0 } }
    }

    @Test
    fun recorridoCompletoConDatosDeEjemplo() {
        iniciar()
        clic("Explorar con datos de ejemplo")
        esperarDatos { it.movimientos.size > 100 }
        esperarTexto("Disponible")
        dejarPasarAvisos()
        assertTrue(existe("Regla 50/30/20"))
        bajarHasta("Hoja de ruta para crear riqueza")

        // ---- Movimientos (el mes anterior siempre tiene datos completos) ----
        clic("Movimientos")
        esperarTexto("Balance")
        compose.onNodeWithContentDescription("Mes anterior").performClick()
        esperarTexto(com.gabriel.cadapeso.dominio.Formato.mes(YearMonth.now().minusMonths(1)))
        bajarHasta("Alquiler")
        esperarTexto("Alquiler")

        // ---- Presupuesto: las 4 pestañas ----
        clic("Presupuesto")
        esperarTexto("Cada peso con una tarea")
        clic("Semana")
        esperarTexto("Presupuesto de la semana")
        clic("Proyección")
        esperarTexto("Ingreso mensual base")
        clic("Fugas")
        esperarTexto("🐜 Gastos hormiga")
        esperarTexto("Café y kiosco")

        // ---- Patrimonio: las 4 pestañas ----
        clic("Patrimonio")
        esperarTexto("Patrimonio neto")
        clic("Deudas")
        esperarTexto("Tus deudas")
        bajarHasta("Plan avalancha")
        bajarHasta("Tarjeta Visa")
        esperarTexto("Tarjeta Visa", substring = true)
        clic("Ahorro y metas")
        esperarTexto("💪 Págate a ti primero")
        clic("Invertir")
        esperarTexto("🗺️ Hoja de ruta")

        // ---- Asesor sin IA: los atajos responden con el motor de reglas ----
        clic("Asesor")
        esperarTexto("Asesor financiero")
        clic("📊 Diagnóstico del mes")
        esperarTexto("📊 Diagnóstico de", substring = true)
        compose.onNodeWithText("Preguntale algo a tu asesor…").performTextInput("¿Cómo voy este mes?")
        compose.onNodeWithContentDescription("Enviar").performClick()
        esperarTexto("Para conversar libremente necesito Gemma 2", substring = true)

        // ---- Regla de las 24/48 h ----
        clic("🛍️ ¿Lo compro?")
        compose.onNodeWithText("¿Qué querés comprar?").performTextInput("Zapatillas")
        compose.onNodeWithText("Precio").performTextInput("150000")
        clic("Es un impulso")
        esperarTexto("Recomendación: esperá", substring = true)
        clic("Esperar ", substring = true)
        esperarDatos { d -> d.compras.any { it.descripcion == "Zapatillas" } }
        dejarPasarAvisos()
        bajarHasta("Compras en espera")
        esperarTexto("🛍️ Compras en espera")

        // ---- Cargar un movimiento escrito en lenguaje natural ----
        clic("Inicio")
        esperarTexto("Disponible")
        compose.onNodeWithContentDescription("Nuevo movimiento").performClick()
        compose.waitForIdle()
        esperarTexto("Nuevo movimiento")
        compose.onNodeWithText("Escribí o dictá").performTextInput("café 3500 con débito")
        clic("Completar")
        clic("Guardar")
        esperarDatos { d ->
            d.movimientos.any { it.descripcion == "Café" && it.monto == 3500.0 && it.medioPago == MedioPago.DEBITO && it.tipo == TipoMovimiento.GASTO }
        }
        dejarPasarAvisos()

        // ---- Ajustes: elegir Ollama sin dirección da un error claro ----
        compose.onNodeWithContentDescription("Ajustes").performClick()
        esperarTexto("✨ Inteligencia artificial (Gemma 2)")
        clic("Gemma 2 vía Ollama")
        clic("Probar conexión")
        esperarTexto("Falta la dirección de Ollama", substring = true)
        clic("Sin IA")
        compose.onNodeWithContentDescription("Volver").performClick()
        esperarTexto("Disponible")
    }

    @Test
    fun bandejaDeCuentasConectadas() {
        // Llegan dos movimientos de las cuentas conectadas (como los que baja la sincronización).
        runBlocking {
            contenedor.repo.guardarPerfil(Perfil(nombre = "Gabriel", ingresoMensual = 1_000_000.0, onboardingCompleto = true))
            contenedor.repo.importarTransacciones(
                listOf(
                    TransaccionExterna(
                        idTransaccion = "MERCADO_PAGO:1", origen = OrigenMovimiento.MERCADO_PAGO, tipo = TipoMovimiento.GASTO,
                        monto = 3500.0, fecha = LocalDate.now(), descripcion = "Café Martínez", medioPago = MedioPago.BILLETERA,
                    ),
                    TransaccionExterna(
                        idTransaccion = "GALICIA:1234:a", origen = OrigenMovimiento.GALICIA, tipo = TipoMovimiento.GASTO,
                        monto = 10_000.0, fecha = LocalDate.now(), descripcion = "Transf. a cvu mercadopago",
                        medioPago = MedioPago.TRANSFERENCIA, transferenciaPropia = true,
                    ),
                ),
                CanalDeteccion.SERVIDOR,
                aceptarSolas = false,
            )
        }
        iniciar()
        clic("📥 2 movimientos para revisar")
        esperarTexto("Para revisar")
        esperarTexto("Parece plata entre tus cuentas")

        // "Aceptar todos" deja afuera la transferencia propia: esa la decide el usuario.
        clic("Aceptar todos (1)")
        esperarDatos { d -> d.movimientos.any { it.descripcion == "Café Martínez" && it.origen == OrigenMovimiento.MERCADO_PAGO } }
        dejarPasarAvisos()
        clic("Descartar")
        esperarTexto("Nada para revisar")
        assertTrue(runBlocking { contenedor.repo.detectadosPendientesAhora() }.isEmpty())
        assertEquals(1, runBlocking { contenedor.repo.cargarDatos() }.movimientos.size)

        // La pantalla de cuentas conectadas se abre sin servidor configurado.
        dejarPasarAvisos()
        clic("Cuentas")
        esperarTexto("📲 Avisos del banco en tu teléfono")
        esperarTexto("Clave de registro")
    }
}

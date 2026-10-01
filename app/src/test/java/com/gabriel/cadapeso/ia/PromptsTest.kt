package com.gabriel.cadapeso.ia

import com.gabriel.cadapeso.datos.CategoriasBase
import com.gabriel.cadapeso.datos.DatosEjemplo
import com.gabriel.cadapeso.datos.Perfil
import com.gabriel.cadapeso.dominio.DatosFinancieros
import com.gabriel.cadapeso.dominio.MotorFinanciero
import com.gabriel.cadapeso.dominio.MotorPresupuesto
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Los prompts tienen que entrar en el contexto de Gemma2-2B-IT de LiteRT: 1280 tokens en total,
 * de los cuales la app reserva 320 para la respuesta. Gemma usa ~1 token cada 3,5 a 4 caracteres
 * en español, así que el prompt completo debería quedar por debajo de ~3300 caracteres.
 */
class PromptsTest {

    private val hoy = LocalDate.of(2026, 10, 20)

    private fun analisis() = run {
        val categorias = CategoriasBase.lista().mapIndexed { i, c -> c.copy(id = i + 1L) }
        val p = DatosEjemplo.generar(categorias, hoy)
        val base = DatosFinancieros(
            movimientos = p.movimientos, categorias = categorias,
            activos = p.activos.mapIndexed { i, a -> a.copy(id = i + 1L) },
            deudas = p.deudas.mapIndexed { i, d -> d.copy(id = i + 1L) },
            metas = p.metas.mapIndexed { i, m -> m.copy(id = i + 1L) },
            eventos = p.eventos, perfil = Perfil(ingresoMensual = p.ingresoMensual, edad = 29, puntajeConducta = 70, puntajeRiesgo = 50),
        )
        val mes = YearMonth.from(hoy)
        val ingreso = MotorPresupuesto.ingresoReferencia(base, mes, MotorPresupuesto.resumenMes(base, mes).ingresos)
        MotorFinanciero.analizar(base.copy(asignaciones = MotorPresupuesto.sugerirAsignaciones(base, mes, ingreso)), hoy)
    }

    private fun armar(a: com.gabriel.cadapeso.dominio.Analisis, contenido: String) =
        FormatoGemma.armar(PromptIA(Prompts.instrucciones(a), contenido))

    @Test
    fun promptsEntranEnElContextoDeGemma() {
        val a = analisis()
        val casos = mapOf(
            "chat" to Prompts.chat(a, "¿Puedo irme de vacaciones en enero?", Prompts.Nivel.COMPLETO),
            "diagnostico" to Prompts.diagnostico(a, Prompts.Nivel.COMPLETO),
            "siguientePaso" to Prompts.siguientePaso(a, Prompts.Nivel.COMPLETO),
            "recorte" to Prompts.planRecorte(a, Prompts.Nivel.COMPLETO),
            "deudas" to Prompts.deudas(a, Prompts.Nivel.COMPLETO),
            "inversion" to Prompts.inversion(a, Prompts.Nivel.COMPLETO),
            "deudas (breve)" to Prompts.deudas(a, Prompts.Nivel.BREVE),
            "recorte (breve)" to Prompts.planRecorte(a, Prompts.Nivel.BREVE),
        )
        for ((nombre, contenido) in casos) {
            val prompt = armar(a, contenido)
            val tokensAprox = prompt.length / 3.5
            println("[$nombre] ${prompt.length} caracteres ≈ ${tokensAprox.toInt()} tokens")
            if (nombre.contains("breve")) {
                assertTrue("$nombre: la versión breve tiene que entrar holgada", tokensAprox < 1280 - 320)
            }
        }
        // Se imprime un prompt completo para poder leerlo y revisar su calidad.
        println("\n================ PROMPT DE EJEMPLO (diagnóstico) ================\n" + armar(a, Prompts.diagnostico(a, Prompts.Nivel.COMPLETO)))
    }

    @Test
    fun formatoDeTurnosDeGemma() {
        val p = PromptIA("INSTRUCCIONES", "PREGUNTA", listOf(TurnoChat("hola", "¡hola!")))
        val esperado = "<start_of_turn>user\nhola<end_of_turn>\n<start_of_turn>model\n¡hola!<end_of_turn>\n" +
            "<start_of_turn>user\nINSTRUCCIONES\n\nPREGUNTA<end_of_turn>\n<start_of_turn>model\n"
        assertTrue(FormatoGemma.armar(p) == esperado)
        assertTrue(FormatoGemma.limpiar("Respuesta<end_of_turn>") == "Respuesta")
    }
}

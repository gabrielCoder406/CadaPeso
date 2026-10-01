// Google marcó la API MediaPipe LLM Inference como obsoleta en favor de LiteRT-LM, pero
// LiteRT-LM usa otro formato (.litertlm) y no ofrece Gemma 2. Para usar Gemma 2 en el teléfono
// esta sigue siendo la vía oficial; si algún día pasás a Gemma 3/3n, conviene migrar a LiteRT-LM.
@file:Suppress("DEPRECATION")

package com.gabriel.cadapeso.ia

import android.content.Context
import com.gabriel.cadapeso.datos.ConfigIA
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemma 2 corriendo DENTRO del teléfono con MediaPipe LLM Inference. Tus datos financieros
 * nunca salen del dispositivo y funciona sin internet.
 *
 * El modelo (archivo .task de ~2,6 GB) se carga una sola vez y queda en memoria; cada
 * pregunta abre una "sesión" nueva, que es liviana. El modelo Gemma2-2B-IT de LiteRT tiene
 * un contexto de 1280 tokens en total, por eso los prompts de la app son compactos.
 */
class GemmaLocal(private val context: Context, var config: ConfigIA) : MotorIA {

    private var llm: LlmInference? = null
    private val mutexCarga = Mutex()
    /** El motor nativo genera de a una respuesta por vez. */
    private val mutexGeneracion = Mutex()

    override val maxTokens: Int get() = config.maxTokens

    override suspend fun preparar() {
        mutexCarga.withLock {
            if (llm != null) return
            withContext(Dispatchers.IO) {
                val archivo = File(config.rutaModelo)
                require(config.rutaModelo.isNotBlank() && archivo.exists()) {
                    "No encuentro el archivo del modelo. Importalo o descargalo en Ajustes › Inteligencia artificial."
                }
                val opciones = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(archivo.absolutePath)
                    .setMaxTokens(config.maxTokens)
                    .setMaxTopK(40)
                    .setPreferredBackend(if (config.usarGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
                    .build()
                llm = try {
                    LlmInference.createFromOptions(context, opciones)
                } catch (e: Throwable) {
                    throw IllegalStateException(
                        "No se pudo cargar Gemma 2 (${e.message ?: e.javaClass.simpleName}). " +
                            "Si elegiste GPU probá con CPU; si no, puede faltar memoria RAM (se recomiendan 6 GB o más).",
                        e,
                    )
                }
            }
        }
    }

    override fun generar(prompt: PromptIA): Flow<String> = callbackFlow {
        mutexGeneracion.lock()
        val liberado = AtomicBoolean(false)
        fun liberarMutex() {
            if (liberado.compareAndSet(false, true)) mutexGeneracion.unlock()
        }

        var sesion: LlmInferenceSession? = null
        try {
            preparar()
            val motor = llm ?: error("El modelo no está cargado")
            val opciones = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTopP(0.95f)
                .setTemperature((prompt.temperatura ?: config.temperatura).toFloat())
                .apply {
                    // Plantilla manual: le pasamos al motor plantillas vacías para que no agregue
                    // las suyas, y el texto ya va con las marcas de turno de Gemma.
                    if (config.plantillaManual) setPromptTemplates(PromptTemplates.builder().build())
                }
                .build()
            val nuevaSesion = LlmInferenceSession.createFromOptions(motor, opciones)
            sesion = nuevaSesion
            val ajustado = ajustarAlContexto(prompt, this@GemmaLocal, ::formatear)
            nuevaSesion.addQueryChunk(formatear(ajustado))
            val futuro = nuevaSesion.generateResponseAsync { parcial, terminado ->
                if (!parcial.isNullOrEmpty()) trySend(parcial)
                if (terminado) close()
            }
            // Cuando termina (bien, con error o cancelada) se cierra la sesión y se libera el turno.
            futuro.addListener({
                val error = runCatching { futuro.get() }.exceptionOrNull()
                runCatching { nuevaSesion.close() }
                liberarMutex()
                if (error != null) close(error) else close()
            }, Runnable::run)
            awaitClose {
                if (!futuro.isDone) runCatching { nuevaSesion.cancelGenerateResponseAsync() }
            }
        } catch (e: Throwable) {
            runCatching { sesion?.close() }
            liberarMutex()
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private fun formatear(p: PromptIA): String = if (config.plantillaManual) FormatoGemma.armar(p) else FormatoGemma.plano(p)

    override fun contarTokens(texto: String): Int =
        runCatching { llm?.sizeInTokens(texto) }.getOrNull() ?: (texto.length / 3.2).toInt()

    override fun liberar() {
        // Si está generando, no se puede cerrar el motor nativo en medio de la respuesta.
        if (!mutexGeneracion.tryLock()) return
        try {
            runCatching { llm?.close() }
            llm = null
        } finally {
            mutexGeneracion.unlock()
        }
    }
}

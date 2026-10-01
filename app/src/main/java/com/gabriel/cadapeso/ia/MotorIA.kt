package com.gabriel.cadapeso.ia

import kotlinx.coroutines.flow.Flow

/** Un intercambio anterior de la conversación. */
data class TurnoChat(val usuario: String, val asistente: String)

/**
 * Lo que se le manda al modelo. Gemma 2 no tiene rol "system": las instrucciones y los datos
 * financieros van pegados al último mensaje del usuario (lo más cerca posible de la pregunta,
 * que es donde un modelo chico les presta más atención).
 */
data class PromptIA(
    val instrucciones: String,
    val mensaje: String,
    val historial: List<TurnoChat> = emptyList(),
    /** Tokens reservados para la respuesta. */
    val maxRespuesta: Int = 360,
    val temperatura: Double? = null,
)

/** Estado del motor de IA para mostrar en la interfaz. */
sealed interface EstadoMotor {
    data object SinConfigurar : EstadoMotor
    data object Inactivo : EstadoMotor
    data object Cargando : EstadoMotor
    data object Listo : EstadoMotor
    data class Error(val mensaje: String) : EstadoMotor
}

/**
 * Contrato común de los dos motores (Gemma 2 en el teléfono y Gemma 2 vía Ollama). El resto
 * de la app no sabe cuál está usando: solo pide "generá texto a partir de este prompt".
 */
interface MotorIA {
    /** Contexto total en tokens (pregunta + respuesta). */
    val maxTokens: Int

    /** Carga el modelo o verifica la conexión. Lanza una excepción con un mensaje claro si falla. */
    suspend fun preparar()

    /** Genera la respuesta de a pedazos (streaming), para ir mostrándola mientras se escribe. */
    fun generar(prompt: PromptIA): Flow<String>

    /** Cantidad de tokens de un texto (exacta en el modelo local, estimada en Ollama). */
    fun contarTokens(texto: String): Int

    /** Libera la memoria del modelo. */
    fun liberar()
}

/**
 * Formato de turnos de Gemma:
 *   <start_of_turn>user
 *   ...pregunta...<end_of_turn>
 *   <start_of_turn>model
 *   ...respuesta...<end_of_turn>
 * El prompt termina abriendo el turno del modelo, para que escriba su respuesta.
 */
object FormatoGemma {
    private const val INICIO = "<start_of_turn>"
    private const val FIN = "<end_of_turn>"

    fun armar(p: PromptIA): String = buildString {
        for (t in p.historial) {
            append(INICIO).append("user\n").append(t.usuario.trim()).append(FIN).append('\n')
            append(INICIO).append("model\n").append(t.asistente.trim()).append(FIN).append('\n')
        }
        append(INICIO).append("user\n")
        append(p.instrucciones.trim()).append("\n\n").append(p.mensaje.trim())
        append(FIN).append('\n')
        append(INICIO).append("model\n")
    }

    /** Versión sin marcas (cuando el archivo del modelo trae su propia plantilla). */
    fun plano(p: PromptIA): String = p.instrucciones.trim() + "\n\n" + p.mensaje.trim()

    /** Limpia restos de marcas que a veces el modelo repite al final. */
    fun limpiar(texto: String): String = texto
        .replace(FIN, "").replace(INICIO + "model", "").replace(INICIO + "user", "").replace(INICIO, "")
}

/** Recorta el historial (de lo más viejo a lo más nuevo) hasta que el prompt entre en el contexto. */
fun ajustarAlContexto(p: PromptIA, motor: MotorIA, armar: (PromptIA) -> String = FormatoGemma::armar): PromptIA {
    var actual = p
    val limite = motor.maxTokens - p.maxRespuesta
    while (actual.historial.isNotEmpty() && motor.contarTokens(armar(actual)) > limite) {
        actual = actual.copy(historial = actual.historial.drop(1))
    }
    return actual
}

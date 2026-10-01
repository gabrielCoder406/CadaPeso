package com.gabriel.cadapeso.dominio

/**
 * Perfilado inicial del usuario: test de conducta financiera (gasto emocional vs ahorro),
 * test de tolerancia al riesgo (incluye el "factor sueño") y etapa de vida por edad.
 * El resultado adapta el tono del asesor y la frecuencia de las alertas.
 */
object Perfilado {

    data class Opcion(val texto: String, val puntos: Int)
    data class Pregunta(val texto: String, val opciones: List<Opcion>)

    private fun frecuencia(invertida: Boolean = false): List<Opcion> {
        val textos = listOf("Nunca", "Rara vez", "A veces", "Seguido", "Casi siempre")
        return textos.mapIndexed { i, t -> Opcion(t, if (invertida) 4 - i else i) }
    }

    /** Puntaje alto = tendencia al gasto impulsivo / emocional. */
    val TEST_CONDUCTA = listOf(
        Pregunta("Cuando estás aburrido/a, estresado/a o triste, ¿comprás algo para sentirte mejor?", frecuencia()),
        Pregunta("¿Te pasa llegar a fin de mes sin saber bien en qué se fue la plata?", frecuencia()),
        Pregunta("Antes de una compra grande, ¿esperás unos días y comparás precios?", frecuencia(invertida = true)),
        Pregunta("¿Usás la tarjeta de crédito o las cuotas para gastos del día a día?", frecuencia()),
        Pregunta("¿Separás una parte de tu ingreso apenas cobrás?", frecuencia(invertida = true)),
        Pregunta("¿Comprás por una oferta o promoción aunque no lo necesitaras?", frecuencia()),
    )

    /** Puntaje alto = mayor tolerancia al riesgo. */
    val TEST_RIESGO = listOf(
        Pregunta(
            "Si tu inversión cae un 20% en un mes, ¿qué hacés?",
            listOf(Opcion("Vendo todo para no perder más", 0), Opcion("Vendo una parte", 1), Opcion("Espero sin hacer nada", 2), Opcion("Compro más, está barato", 3)),
        ),
        Pregunta(
            "¿Cuándo vas a necesitar el dinero que invertís?",
            listOf(Opcion("En menos de 1 año", 0), Opcion("Entre 1 y 3 años", 1), Opcion("Entre 3 y 7 años", 2), Opcion("En más de 7 años", 3)),
        ),
        Pregunta(
            "¿Qué cartera preferís?",
            listOf(Opcion("Ganar poco pero seguro", 0), Opcion("Un poco de riesgo para ganar algo más", 1), Opcion("Mitad segura, mitad arriesgada", 2), Opcion("Máximo crecimiento aunque haya caídas fuertes", 3)),
        ),
        Pregunta(
            "¿Qué experiencia tenés invirtiendo?",
            listOf(Opcion("Ninguna", 0), Opcion("Plazo fijo o fondos comunes", 1), Opcion("Bonos, CEDEARs o acciones", 2), Opcion("Varios años en los mercados", 3)),
        ),
        Pregunta(
            "¿Qué tan estables son tus ingresos?",
            listOf(Opcion("Muy inestables", 0), Opcion("Algo variables", 1), Opcion("Estables", 2), Opcion("Muy estables y con ahorro de respaldo", 3)),
        ),
        Pregunta(
            "Factor sueño: ¿dormirías tranquilo/a si tus ahorros invertidos bajan un 15% durante unos meses?",
            listOf(Opcion("No, me desvelaría", 0), Opcion("Me preocuparía bastante", 1), Opcion("Un poco, pero lo tolero", 2), Opcion("Sí, sin problema", 3)),
        ),
    )

    /** Convierte las respuestas (índice elegido en cada pregunta) en un puntaje de 0 a 100. */
    fun puntaje(test: List<Pregunta>, respuestas: List<Int>): Int {
        val maximo = test.sumOf { p -> p.opciones.maxOf { it.puntos } }
        val obtenido = test.zip(respuestas).sumOf { (p, i) -> p.opciones.getOrNull(i)?.puntos ?: 0 }
        return if (maximo == 0) 0 else obtenido * 100 / maximo
    }

    fun conducta(puntaje: Int?): PerfilConducta? = when {
        puntaje == null -> null
        puntaje < 35 -> PerfilConducta.AHORRADOR
        puntaje <= 60 -> PerfilConducta.EQUILIBRADO
        else -> PerfilConducta.IMPULSIVO
    }

    fun riesgo(puntaje: Int?): PerfilRiesgo? = when {
        puntaje == null -> null
        puntaje < 40 -> PerfilRiesgo.CONSERVADOR
        puntaje <= 70 -> PerfilRiesgo.MODERADO
        else -> PerfilRiesgo.AGRESIVO
    }

    fun etapa(edad: Int): EtapaVida = when {
        edad <= 30 -> EtapaVida.JUVENTUD
        edad <= 50 -> EtapaVida.CONSOLIDACION
        else -> EtapaVida.PRE_RETIRO
    }

    fun perfil(edad: Int, puntajeConducta: Int?, puntajeRiesgo: Int?): PerfilFinanciero =
        PerfilFinanciero(etapa(edad), conducta(puntajeConducta), riesgo(puntajeRiesgo))
}

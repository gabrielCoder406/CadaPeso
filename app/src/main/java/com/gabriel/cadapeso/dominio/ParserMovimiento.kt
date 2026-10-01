package com.gabriel.cadapeso.dominio

import com.gabriel.cadapeso.datos.Categoria
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Resultado de interpretar un texto como "ayer gasté 12.500 de nafta con la tarjeta". */
data class MovimientoInterpretado(
    val tipo: TipoMovimiento,
    val monto: Double?,
    val descripcion: String,
    val categoria: Categoria?,
    val medioPago: MedioPago?,
    val fecha: LocalDate,
    /** 0 a 1: qué tan seguro está el parser (si es bajo, conviene probar con Gemma). */
    val confianza: Double,
)

/**
 * Convierte texto libre en un movimiento SIN usar IA: es instantáneo y funciona siempre.
 * Gemma se usa solo como segunda opción cuando este parser no entiende el texto.
 * Entiende la forma de hablar argentina: "15 lucas" = 15.000, "2 palos" = 2.000.000,
 * "15.000,50" con punto de miles y coma decimal.
 */
object ParserMovimiento {

    // Número con miles (15.000 / 1,500,000) o simple (2500 / 12,5), con multiplicador opcional.
    private val REGEX_MONTO = Regex(
        """(?<![\w/])(\$\s*)?(\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)\s*(k|mil|lucas?|palos?|millon(?:es)?|m)?(?![\w/])""",
    )
    private val REGEX_FECHA_BARRA = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
    private val REGEX_HACE_DIAS = Regex("""\bhace (\d{1,2}) dias?\b""")
    private val REGEX_EL_DIA = Regex("""\bel dia (\d{1,2})\b""")
    private val MESES_NORM = Formato.MESES.map { normalizar(it) }
    private val REGEX_DIA_DE_MES = Regex("""\b(?:el )?(\d{1,2}) de (${MESES_NORM.joinToString("|")})\b""")
    private val REGEX_ANTEAYER = Regex("""\b(anteayer|antes de ayer|antier)\b""")
    private val REGEX_AYER = Regex("""\bayer\b""")
    private val REGEX_PAGO_DEUDA = Regex("""\b(resumen|pago (de )?(la )?tarjeta|pague (el resumen|la tarjeta)|cuota (del )?prestamo|prestamo)\b""")
    private val DIAS_SEMANA = mapOf(
        "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY, "miercoles" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY, "viernes" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY,
    )

    /** Verbos que indican sin duda un gasto ("me cobraron" es un gasto, "cobré" un ingreso). */
    private val PALABRAS_GASTO = listOf("gaste", "pague", "compre", "abone", "me cobraron", "saque", "perdi", "invite", "done")
    private val PALABRAS_INGRESO = listOf(
        "cobre", "me pagaron", "me depositaron", "me transfirieron", "me entro", "me entraron",
        "ingreso", "ingresaron", "sueldo", "salario", "aguinaldo", "honorario", "vendi", "gane",
        "reintegro", "me devolvieron", "devolucion", "freelance", "rendimiento", "intereses", "cobro",
    )

    // Orden importante: "tarjeta de debito" tiene que ganarle a "tarjeta".
    private val PALABRAS_MEDIO = listOf(
        MedioPago.DEBITO to listOf("tarjeta de debito", "debito"),
        MedioPago.BILLETERA to listOf("mercado pago", "mercadopago", "mp", "uala", "naranja x", "brubank", "modo", "qr", "billetera", "lemon", "prex", "personal pay", "cuenta dni"),
        MedioPago.TRANSFERENCIA to listOf("transferencia", "transferi", "alias", "cbu", "cvu"),
        MedioPago.CREDITO to listOf("tarjeta de credito", "credito", "tarjeta", "visa", "mastercard", "master", "amex", "en cuotas", "cuotas"),
        MedioPago.EFECTIVO to listOf("efectivo", "cash", "billete", "en mano"),
    )

    /** Palabras que no aportan a la descripción ("gasté 3000 EN EL súper CON débito"). */
    private val RELLENO = setOf(
        "gaste", "gasto", "pague", "pago", "compre", "compra", "cobre", "cobro", "me", "pagaron", "depositaron",
        "transfirieron", "entro", "entraron", "en", "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas",
        "con", "por", "para", "al", "a", "y", "o", "hoy", "ayer", "anteayer", "antier", "antes", "hace", "dia", "dias",
        "tarjeta", "credito", "debito", "efectivo", "transferencia", "transferi", "mercado", "mercadopago", "mp",
        "plata", "pesos", "peso", "que", "lo", "le", "les", "mi", "mis", "su", "sus", "fue", "fui", "salio",
        "costo", "cuesta", "vale", "son", "es", "uala", "qr", "billetera", "visa", "master", "mastercard",
        "amex", "cuotas", "cash", "k", "mil", "luca", "lucas", "palo", "palos", "millon", "millones", "m",
        "brubank", "lemon", "prex", "alias", "cbu", "cvu", "ingreso", "ingresaron", "vendi", "abone",
        "gane", "este", "esta", "eso", "esto", "nos", "te", "se", "ya", "recien", "algo", "cobraron", "saque",
    ) + DIAS_SEMANA.keys + MESES_NORM

    fun interpretar(texto: String, categorias: List<Categoria>, hoy: LocalDate): MovimientoInterpretado {
        val norm = " " + normalizar(texto).replace(Regex("\\s+"), " ").trim() + " "

        // 1) Fecha (se procesa primero y se borra, para que "5/9" no se confunda con un monto).
        var resto = norm
        var fecha = hoy
        fun quitar(r: Regex) { resto = r.replace(resto, " ") }
        when {
            REGEX_ANTEAYER.containsMatchIn(resto) -> { fecha = hoy.minusDays(2); quitar(REGEX_ANTEAYER) }
            REGEX_AYER.containsMatchIn(resto) -> { fecha = hoy.minusDays(1); quitar(REGEX_AYER) }
            REGEX_HACE_DIAS.containsMatchIn(resto) -> {
                fecha = hoy.minusDays(REGEX_HACE_DIAS.find(resto)!!.groupValues[1].toLong())
                quitar(REGEX_HACE_DIAS)
            }
            REGEX_DIA_DE_MES.containsMatchIn(resto) -> {
                val m = REGEX_DIA_DE_MES.find(resto)!!
                fecha = fechaValida(hoy.year, MESES_NORM.indexOf(m.groupValues[2]) + 1, m.groupValues[1].toInt(), hoy, mesExplicito = true) ?: hoy
                quitar(REGEX_DIA_DE_MES)
            }
            REGEX_FECHA_BARRA.containsMatchIn(resto) -> {
                val m = REGEX_FECHA_BARRA.find(resto)!!
                val anio = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it } ?: hoy.year
                fecha = fechaValida(anio, m.groupValues[2].toInt(), m.groupValues[1].toInt(), hoy, mesExplicito = true) ?: hoy
                quitar(REGEX_FECHA_BARRA)
            }
            REGEX_EL_DIA.containsMatchIn(resto) -> {
                fecha = fechaValida(hoy.year, hoy.monthValue, REGEX_EL_DIA.find(resto)!!.groupValues[1].toInt(), hoy, mesExplicito = false) ?: hoy
                quitar(REGEX_EL_DIA)
            }
            else -> DIAS_SEMANA.entries.firstOrNull { Regex("""\bel ${it.key}\b""").containsMatchIn(resto) }?.let { (nombre, dia) ->
                fecha = hoy.with(TemporalAdjusters.previousOrSame(dia))
                quitar(Regex("""\bel $nombre\b"""))
            }
        }

        // 2) Monto: preferimos el que tiene "$" o multiplicador; si no, el número más grande.
        val candidatos = REGEX_MONTO.findAll(resto).mapNotNull { m ->
            val base = aNumero(m.groupValues[2]) ?: return@mapNotNull null
            val mult = when (m.groupValues[3]) {
                "k", "mil", "luca", "lucas" -> 1_000.0
                "palo", "palos", "millon", "millones", "m" -> 1_000_000.0
                else -> 1.0
            }
            Triple(base * mult, m.groupValues[1].isNotEmpty() || mult > 1, m.value)
        }.toList()
        val elegido = candidatos.firstOrNull { it.second } ?: candidatos.maxByOrNull { it.first }
        val monto = elegido?.first?.takeIf { it > 0 }
        candidatos.forEach { resto = resto.replace(it.third, " ") }

        // 3) Tipo: un verbo de gasto manda ("pagué el sueldo de la empleada" es un gasto).
        val tipo = when {
            PALABRAS_GASTO.any { contienePalabra(norm, it) } -> TipoMovimiento.GASTO
            PALABRAS_INGRESO.any { contienePalabra(norm, it) } -> TipoMovimiento.INGRESO
            else -> TipoMovimiento.GASTO
        }

        // 4) Medio de pago. Las palabras del medio se sacan antes de categorizar, para que
        //    "nafta con tarjeta" no caiga en la categoría de pago de tarjetas.
        var medio = PALABRAS_MEDIO.firstOrNull { (_, claves) -> claves.any { contienePalabra(norm, it) } }?.first
        val esPagoDeuda = tipo == TipoMovimiento.GASTO && REGEX_PAGO_DEUDA.containsMatchIn(norm)
        PALABRAS_MEDIO.flatMap { it.second }.sortedByDescending { it.length }.forEach { clave ->
            resto = Regex("""\b${Regex.escape(clave)}\b""").replace(resto, " ")
        }

        // 5) Categoría.
        val categoria = if (esPagoDeuda) {
            if (medio == MedioPago.CREDITO) medio = null // la tarjeta no se paga con la tarjeta
            categorias.firstOrNull { it.grupo == GrupoGasto.DEUDA && !it.archivada }
        } else {
            Categorizador.sugerir(resto, categorias, tipo)
        }

        // 6) Descripción: las palabras del texto original que no son relleno, montos ni fechas.
        val descripcion = descripcionDesde(texto).ifBlank { categoria?.nombre ?: "" }

        var confianza = 0.0
        if (monto != null) confianza += 0.5
        if (categoria != null) confianza += 0.3
        if (descripcion.isNotBlank()) confianza += 0.2
        return MovimientoInterpretado(tipo, monto, descripcion, categoria, medio, fecha, confianza)
    }

    /** Palabra (o frase) completa, admitiendo plural: "honorario" coincide con "honorarios". */
    private fun contienePalabra(texto: String, palabra: String): Boolean =
        Regex("""\b${Regex.escape(palabra)}(s|es)?\b""").containsMatchIn(texto)

    /** Arma una descripción corta con las palabras significativas del texto original. */
    fun descripcionDesde(texto: String): String {
        val palabras = texto.split(Regex("\\s+"))
            .filter { palabra ->
                val n = normalizar(palabra).replace(Regex("[^a-z0-9]"), "")
                n.isNotEmpty() && n !in RELLENO && n.none { it.isDigit() } && !palabra.contains('$')
            }
            .map { it.trim(',', '.', ';', ':', '!', '?', '¡', '¿', '"', '\'', '(', ')') }
            .filter { it.isNotEmpty() }
        return palabras.joinToString(" ").take(40).trim().capitalizar()
    }

    /**
     * Convierte texto numérico en Double entendiendo los dos formatos:
     * "15.000" -> 15000, "15.000,50" -> 15000.5, "1,5" -> 1.5, "1,500" -> 1500, "1.5" -> 1.5.
     * Regla: un separador seguido de exactamente 3 dígitos al final es de miles.
     */
    fun aNumero(s: String): Double? {
        val t = s.trim()
        val tienePunto = '.' in t
        val tieneComa = ',' in t
        val normal = when {
            tienePunto && tieneComa ->
                if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            tienePunto -> {
                val partes = t.split('.')
                if (partes.size > 2 || partes.last().length == 3) t.replace(".", "") else t
            }
            tieneComa -> {
                val partes = t.split(',')
                if (partes.size > 2 || partes.last().length == 3) t.replace(",", "") else t.replace(',', '.')
            }
            else -> t
        }
        return normal.toDoubleOrNull()
    }

    /**
     * Arma la fecha si es válida. Si queda en el futuro, se asume que hablaba del pasado:
     * "el 25 de octubre" (mes dicho) -> octubre del año pasado; "el día 25" (sin mes) -> el
     * 25 del mes anterior.
     */
    private fun fechaValida(anio: Int, mes: Int, dia: Int, hoy: LocalDate, mesExplicito: Boolean): LocalDate? = runCatching {
        val f = LocalDate.of(anio, mes, dia)
        when {
            !f.isAfter(hoy) -> f
            mesExplicito -> f.minusYears(1)
            else -> f.minusMonths(1)
        }
    }.getOrNull()
}

/**
 * Categorización inteligente por palabras clave, con aprendizaje: cuando corregís la
 * categoría de un movimiento, la palabra principal de la descripción se agrega a las claves
 * de la categoría elegida, y la próxima vez acierta sola.
 */
object Categorizador {

    private val NO_APRENDER = setOf(
        "compra", "pago", "gasto", "varios", "otro", "otros", "cosas", "algo", "cuota", "regalo",
        "para", "con", "del", "los", "las", "una", "uno", "pesos",
    )

    /** Palabras clave de la categoría (separadas por coma) más su nombre completo. */
    fun claves(c: Categoria): List<String> =
        (c.palabrasClave.split(',') + c.nombre)
            .map { normalizar(it).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 2 }
            .distinct()

    /**
     * Busca la categoría cuya palabra clave aparece en el texto. Las claves cortas (≤ 3 letras,
     * como "gas" o "bar") tienen que ser una palabra completa; las largas pueden ser el
     * comienzo de una palabra ("super" coincide con "supermercado"). Gana la clave más larga.
     */
    fun sugerir(texto: String, categorias: List<Categoria>, tipo: TipoMovimiento): Categoria? {
        val t = " " + normalizar(texto).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        var mejor: Categoria? = null
        var mejorLargo = 0
        for (c in categorias) {
            if (c.tipo != tipo || c.archivada) continue
            for (k in claves(c)) {
                if (k.length <= mejorLargo) continue
                val patron = if (k.length <= 3) """\b${Regex.escape(k)}\b""" else """\b${Regex.escape(k)}"""
                if (Regex(patron).containsMatchIn(t)) {
                    mejor = c
                    mejorLargo = k.length
                }
            }
        }
        return mejor
    }

    /**
     * Devuelve la categoría con una palabra clave nueva aprendida de [descripcion], o null si
     * no hay nada útil para aprender (la palabra ya existe en alguna categoría, es muy corta...).
     */
    fun aprender(elegida: Categoria, descripcion: String, todas: List<Categoria>): Categoria? {
        val existentes = todas.flatMap { claves(it) }.toSet()
        val palabra = normalizar(descripcion).replace(Regex("[^a-z ]"), " ").split(' ')
            .firstOrNull { it.length >= 4 && it !in NO_APRENDER && it !in existentes } ?: return null
        val nuevas = (elegida.palabrasClave.split(',').map { it.trim() }.filter { it.isNotEmpty() } + palabra).joinToString(",")
        return elegida.copy(palabrasClave = nuevas)
    }
}

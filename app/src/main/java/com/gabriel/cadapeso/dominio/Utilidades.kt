package com.gabriel.cadapeso.dominio

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

// ---------------------------------------------------------------------------------------
// Fechas: un mes se guarda como un número aaaamm (202610 = octubre 2026) porque es fácil
// de ordenar, comparar y usar como clave en la base de datos.
// ---------------------------------------------------------------------------------------

fun YearMonth.clave(): Int = year * 100 + monthValue
fun LocalDate.claveMes(): Int = year * 100 + monthValue
fun mesDeClave(clave: Int): YearMonth = YearMonth.of(clave / 100, (clave % 100).coerceIn(1, 12))
fun LocalDate.inicioSemana(): LocalDate = with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
fun YearMonth.contiene(fecha: LocalDate): Boolean = fecha.year == year && fecha.monthValue == monthValue
fun mesesEntre(desde: YearMonth, hasta: YearMonth): Long = ChronoUnit.MONTHS.between(desde, hasta)

// ---------------------------------------------------------------------------------------
// Texto
// ---------------------------------------------------------------------------------------

/** Pasa a minúsculas y saca los acentos: "Débito Café" -> "debito cafe". Sirve para comparar. */
fun normalizar(texto: String): String =
    Normalizer.normalize(texto.lowercase(Formato.locale), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

/** "1 deuda mala" / "3 deudas malas". */
fun plural(n: Int, singular: String, plural: String): String = "$n ${if (n == 1) singular else plural}"

/** Primera letra en mayúscula. */
fun String.capitalizar(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Formato.locale) else it.toString() }

// ---------------------------------------------------------------------------------------
// Formato para mostrar números y fechas al estilo argentino (1.234.567,89).
// Uso String.format (que es seguro entre hilos) y nombres de meses propios para que se vea
// igual en cualquier teléfono, sin depender de los datos de idioma de cada fabricante.
// ---------------------------------------------------------------------------------------

object Formato {
    val locale: Locale = Locale.forLanguageTag("es-AR")

    val MESES = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    val DIAS = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

    /** "$ 12.500" o "$ 1.234,50" (los decimales solo si existen y el monto es chico). */
    fun dinero(valor: Double, moneda: String = "$"): String {
        val redondeado = round(abs(valor) * 100) / 100
        val texto = if (redondeado >= 1000 || redondeado == floor(redondeado)) {
            String.format(locale, "%,.0f", redondeado)
        } else {
            String.format(locale, "%,.2f", redondeado)
        }
        return (if (valor < -0.004) "-" else "") + moneda + " " + texto
    }

    /** Versión corta para gráficos: "$ 1,2 M", "$ 850 mil", "$ 9.500". */
    fun dineroCorto(valor: Double, moneda: String = "$"): String {
        val a = abs(valor)
        val signo = if (valor < 0) "-" else ""
        return when {
            a >= 1_000_000 -> signo + moneda + " " + String.format(locale, "%,.1f", a / 1_000_000).removeSuffix(",0") + " M"
            a >= 100_000 -> signo + moneda + " " + String.format(locale, "%,.0f", a / 1000) + " mil"
            else -> dinero(valor, moneda)
        }
    }

    fun porcentaje(valor: Double, decimales: Int = 0): String =
        String.format(locale, "%.${decimales}f%%", valor)

    /** Número sin símbolo, para campos de texto: 12500.5 -> "12500,5". */
    fun numeroEditable(valor: Double): String =
        if (valor == floor(valor)) String.format(Locale.ROOT, "%.0f", valor)
        else String.format(Locale.ROOT, "%.2f", valor).trimEnd('0').trimEnd('.').replace('.', ',')

    /** "lun 29 sep" */
    fun fechaCorta(d: LocalDate): String = "${DIAS[d.dayOfWeek.value - 1].take(3)} ${d.dayOfMonth} ${MESES[d.monthValue - 1].take(3)}"

    /** "29 de septiembre de 2026" */
    fun fechaLarga(d: LocalDate): String = "${d.dayOfMonth} de ${MESES[d.monthValue - 1]} de ${d.year}"

    /** "29/09/2026" */
    fun fechaNumerica(d: LocalDate): String = String.format(Locale.ROOT, "%02d/%02d/%d", d.dayOfMonth, d.monthValue, d.year)

    /** "Septiembre 2026" */
    fun mes(m: YearMonth): String = "${MESES[m.monthValue - 1].capitalizar()} ${m.year}"

    /** "sep 26" */
    fun mesCorto(m: YearMonth): String = "${MESES[m.monthValue - 1].take(3)} ${m.year % 100}"

    fun diaRelativo(d: LocalDate, hoy: LocalDate): String = when (ChronoUnit.DAYS.between(d, hoy)) {
        0L -> "Hoy"
        1L -> "Ayer"
        else -> fechaCorta(d).capitalizar()
    }
}

/** Lee un número escrito por el usuario: "12.500", "12500,50", "1,5". Devuelve null si no es número. */
fun leerNumero(texto: String): Double? {
    val limpio = texto.trim().replace("$", "").replace(" ", "")
    if (limpio.isEmpty()) return null
    return ParserMovimiento.aNumero(limpio)
}

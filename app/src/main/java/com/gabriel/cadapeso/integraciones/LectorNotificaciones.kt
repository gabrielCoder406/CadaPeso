package com.gabriel.cadapeso.integraciones

import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.ParserMovimiento
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.TransaccionExterna
import com.gabriel.cadapeso.dominio.capitalizar
import com.gabriel.cadapeso.dominio.normalizar
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * Lee las notificaciones que muestran las apps de Banco Galicia y de Mercado Pago ("Compraste
 * $ 12.345 en SUPERMERCADO DIA", "Recibiste $ 5.000 de Juan") y las convierte al esquema
 * unificado. Todo pasa dentro del teléfono: no hace falta servidor ni internet.
 *
 * No depende de Android (se prueba en la PC). El servicio que recibe las notificaciones es
 * [OyenteNotificaciones].
 *
 * Es deliberadamente conservador: solo acepta textos con un verbo de movimiento ya hecho
 * ("pagaste", "recibiste", "se debitó") y un monto. Las promos, los códigos de seguridad, los
 * vencimientos y las operaciones rechazadas se ignoran. Igual, todo lo detectado pasa por la
 * bandeja de revisión antes de convertirse en un movimiento.
 */
object LectorNotificaciones {

    /** App "Galicia" de Banco Galicia en Google Play. */
    const val PAQUETE_GALICIA = "com.mosync.app_Banco_Galicia"
    const val PAQUETE_MERCADO_PAGO = "com.mercadopago.wallet"

    fun origenDe(paquete: String): OrigenMovimiento? = when (paquete) {
        PAQUETE_GALICIA -> OrigenMovimiento.GALICIA
        PAQUETE_MERCADO_PAGO -> OrigenMovimiento.MERCADO_PAGO
        else -> null
    }

    /** Signo $ escapado para las expresiones regulares (en los textos de Kotlin "$" inicia una plantilla). */
    private const val D = "\\$"

    // Todas las expresiones trabajan sobre el texto normalizado (minúsculas y sin acentos).

    /** Si aparece alguna de estas, no es un movimiento hecho: es una promo, un aviso o un rechazo. */
    private val EXCLUSIONES = listOf(
        Regex("""\brechaz|\bno pudimos\b|\bno se pudo\b|\bfallo\b|\bpendiente\b|\ben proceso\b"""),
        Regex("""\bprogramaste\b|\bprogramad[ao]\b|\bagendaste\b|\bvence\b|\bvencimiento\b|\brecorda|\brecordatorio\b"""),
        Regex("""\bcodigo\b|\bclave\b|\btoken\b|\bcontrasena\b|\bverificacion\b|\bsesion\b"""),
        Regex("""\baprovecha\b|\bsorteo\b|\bparticipa\b|\bcuotas sin interes\b|\bpreaprobad|\btenes disponible\b|\bpodes pedir\b|\bsolicitalo\b|\bactiva tu\b"""),
    )

    private val INGRESOS = listOf(
        Regex("""\brecibiste\b|\bcobraste\b"""),
        Regex("""\bte (transfirieron|transfirio|enviaron|envio|depositaron|deposito|pagaron|pago|acreditaron|acredito|devolvieron|devolvio|devolvimos|reintegramos|reintegraron)\b"""),
        Regex("""\b(acreditamos|se acredito|se acreditaron|ingreso|ingresaron|ingresaste)\b"""),
    )

    private val GASTOS = listOf(
        Regex("""\b(pagaste|compraste|transferiste|enviaste|abonaste|extrajiste|retiraste|recargaste)\b"""),
        Regex("""\brealizaste (un|una) (pago|compra|transferencia|extraccion|recarga)\b"""),
        Regex("""\b(se debito|se debitaron|debitamos|se realizo un debito|compra con tu tarjeta|consumo con tu tarjeta|extraccion)\b"""),
        Regex("""\btu (pago|compra|transferencia) (de|por)\b|\bpago aprobado\b|\bcompra aprobada\b"""),
    )

    /** "$ 12.345,67", "$1500", "US$ 20", "U$S 1.000". */
    private val REGEX_MONTO = Regex(
        """(?<![a-z])(u${D}s|us${D}|u${D}d|usd|ars|${D})\s?(\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?|\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:[.,]\d{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    /** Comercio o persona: "en SUPERMERCADO DIA", "a Juan Pérez", "de NETFLIX". */
    private val REGEX_DESTINO = Regex(
        """(?:^|\s)(?:en|a|de|para)\s+(.+?)(?=\s+(?:con|desde|el|por|mediante|usando|hoy|ayer)\b|[.,;:!(](?:\s|$)|\s+-\s|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val DESTINOS_NO_VALIDOS = Regex(
        """^(?:(?:tu|tus|su|sus|la|el|las|los|un|una|mercado ?pago|cuenta|caja)\b|[${D}\d]|u${D}s)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Interpreta una notificación. Devuelve null si no es un movimiento de dinero ya realizado.
     * @param postMillis cuándo se publicó (StatusBarNotification.postTime): da la fecha y forma
     *   parte del id, así la misma notificación vuelta a publicar no se duplica.
     */
    fun interpretar(
        paquete: String,
        titulo: String,
        texto: String,
        postMillis: Long,
        zona: ZoneId = ZoneId.systemDefault(),
    ): TransaccionExterna? {
        val origen = origenDe(paquete) ?: return null
        val completo = listOf(titulo.trim(), texto.trim()).filter { it.isNotEmpty() }.distinct().joinToString(". ")
        if (completo.isBlank()) return null
        val n = " " + normalizar(completo).replace(Regex("\\s+"), " ") + " "

        if (EXCLUSIONES.any { it.containsMatchIn(n) }) return null
        // "Te pagaron" es ingreso aunque contenga "pagaron"; por eso los ingresos se miran primero,
        // salvo que también haya un verbo de gasto en primera persona ("pagaste", "transferiste").
        val esIngreso = INGRESOS.any { it.containsMatchIn(n) }
        val esGasto = GASTOS.any { it.containsMatchIn(n) }
        val tipo = when {
            esIngreso && !esGasto -> TipoMovimiento.INGRESO
            esGasto -> TipoMovimiento.GASTO
            else -> return null
        }

        val montoEncontrado = REGEX_MONTO.find(completo) ?: return null
        val monto = ParserMovimiento.aNumero(montoEncontrado.groupValues[2])?.takeIf { it > 0 } ?: return null
        val moneda = if (montoEncontrado.groupValues[1].lowercase().contains('u')) "USD" else "ARS"

        val extraccion = Regex("""\b(extrajiste|retiraste|extraccion)\b""").containsMatchIn(n)
        val transferencia = Regex("""\btransfer|\bcvu\b|\bcbu\b|\balias\b""").containsMatchIn(n)
        val medio = when {
            extraccion -> MedioPago.EFECTIVO
            Regex("""\bdebito\b""").containsMatchIn(n) -> MedioPago.DEBITO
            Regex("""\bcredito\b|\bvisa\b|\bmaster|\bamex\b|\bamerican express\b""").containsMatchIn(n) -> MedioPago.CREDITO
            transferencia -> MedioPago.TRANSFERENCIA
            origen == OrigenMovimiento.MERCADO_PAGO -> MedioPago.BILLETERA
            tipo == TipoMovimiento.GASTO -> MedioPago.DEBITO
            else -> MedioPago.TRANSFERENCIA
        }

        // Plata que se mueve entre cuentas propias: sacar efectivo, pasar de Galicia a tu
        // Mercado Pago o ingresar dinero a Mercado Pago desde tu banco.
        val propia = extraccion ||
            (origen == OrigenMovimiento.GALICIA && transferencia && Regex("""mercado ?pago""").containsMatchIn(n)) ||
            (origen == OrigenMovimiento.MERCADO_PAGO && Regex("""\bingresaste\b|\bdesde tu (banco|cuenta)\b""").containsMatchIn(n))

        val contraparte = destino(completo.substring(montoEncontrado.range.last + 1))
            ?: destino(completo.substring(0, montoEncontrado.range.first))
        val descripcion = when {
            extraccion -> "Extracción de efectivo"
            contraparte != null -> contraparte
            else -> generica(origen, tipo, transferencia)
        }

        return TransaccionExterna(
            idTransaccion = "${origen.name}:N:" + huella("$paquete|$n|${postMillis / 60_000}"),
            origen = origen,
            tipo = tipo,
            monto = Math.round(monto * 100) / 100.0,
            moneda = moneda,
            fecha = Instant.ofEpochMilli(postMillis).atZone(zona).toLocalDate(),
            descripcion = descripcion,
            medioPago = medio,
            transferenciaPropia = propia,
            contraparte = contraparte.orEmpty(),
            detalle = completo.take(300),
        )
    }

    /**
     * El primer "en X / a X / de X" que nombre a alguien (no "a tu cuenta" ni "de $ 500"). Si un
     * candidato no sirve, se sigue buscando adentro de él: "de tu compra en Rappi" → "Rappi".
     */
    private fun destino(texto: String): String? {
        var desde = 0
        while (desde < texto.length) {
            val m = REGEX_DESTINO.find(texto, desde) ?: return null
            val grupo = m.groups[1] ?: return null
            val candidato = grupo.value.trim().trim('"', '\'', '*')
            if (candidato.length >= 2 && !DESTINOS_NO_VALIDOS.containsMatchIn(candidato) && candidato.any(Char::isLetter)) {
                return prolijo(candidato)
            }
            desde = grupo.range.first
        }
        return null
    }

    /** "SUPERMERCADO DIA" → "Supermercado Dia"; los nombres ya escritos con mayúsculas y minúsculas se respetan. */
    private fun prolijo(nombre: String): String {
        val corto = nombre.replace(Regex("\\s+"), " ").take(40).trim()
        if (corto.any(Char::isLowerCase)) return corto.capitalizar()
        return corto.lowercase().split(' ').joinToString(" ") { it.capitalizar() }
    }

    private fun generica(origen: OrigenMovimiento, tipo: TipoMovimiento, transferencia: Boolean): String = when {
        tipo == TipoMovimiento.INGRESO && transferencia -> "Transferencia recibida"
        tipo == TipoMovimiento.INGRESO -> "Ingreso en ${origen.etiqueta}"
        transferencia -> "Transferencia enviada"
        origen == OrigenMovimiento.MERCADO_PAGO -> "Pago con Mercado Pago"
        else -> "Compra con Galicia"
    }

    private fun huella(texto: String): String =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
}

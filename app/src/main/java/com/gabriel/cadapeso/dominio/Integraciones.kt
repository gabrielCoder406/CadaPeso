package com.gabriel.cadapeso.dominio

import java.time.LocalDate

// Formas de datos de las cuentas conectadas (Mercado Pago y Banco Galicia).
// Como en Enums.kt: Room guarda estos enums por su nombre, así que se pueden reordenar pero NO renombrar.

/** De dónde salió un movimiento. */
enum class OrigenMovimiento(val etiqueta: String, val emoji: String) {
    MANUAL("Cargado por vos", "✍️"),
    MERCADO_PAGO("Mercado Pago", "🤝"),
    GALICIA("Banco Galicia", "🏦"),
}

/** Cómo se detectó: leyendo la notificación en el teléfono o desde el servidor (API / Prometeo). */
enum class CanalDeteccion(val etiqueta: String) {
    NOTIFICACION("Notificación"),
    SERVIDOR("Cuenta conectada"),
}

/** ANULADA = un pago que ya se había informado y después se devolvió o canceló. */
enum class EstadoTransaccion { CONFIRMADA, ANULADA }

/** Estado de un movimiento detectado en la bandeja de revisión. */
enum class EstadoDetectado { PENDIENTE, ACEPTADO, DESCARTADO }

/**
 * Esquema unificado: TODAS las fuentes (API de Mercado Pago, Galicia vía Prometeo y las
 * notificaciones de las apps del banco) se convierten a este formato antes de entrar a la app.
 * Es el mismo que usa el servidor (id_transaccion, monto, tipo_movimiento, origen, fecha, categoria…).
 */
data class TransaccionExterna(
    /** Único en todas las fuentes: "MERCADO_PAGO:123", "GALICIA:1234:abc", "GALICIA:N:…". */
    val idTransaccion: String,
    val origen: OrigenMovimiento,
    val tipo: TipoMovimiento,
    /** Siempre positivo (como en Movimiento). */
    val monto: Double,
    val moneda: String = "ARS",
    val fecha: LocalDate,
    val descripcion: String,
    /** Pista de categoría del proveedor (nombre de una categoría de la app), si la hay. */
    val categoria: String? = null,
    val medioPago: MedioPago,
    val estado: EstadoTransaccion = EstadoTransaccion.CONFIRMADA,
    /** Plata que se movió entre cuentas tuyas (no es ni ingreso ni gasto). */
    val transferenciaPropia: Boolean = false,
    /** Cuenta o tarjeta ("Caja de ahorro ·1234", "Mercado Pago"). */
    val cuenta: String = "",
    /** Quién te pagó o a quién le pagaste, si se sabe. */
    val contraparte: String = "",
    /** Texto original (la notificación tal cual llegó), para que la revisión sea fácil. */
    val detalle: String = "",
)

/** Saldo de una cuenta conectada, según el último dato del servidor. */
data class SaldoCuenta(val origen: OrigenMovimiento, val cuenta: String, val moneda: String, val monto: Double)

package com.gabriel.cadapeso.integraciones

import com.gabriel.cadapeso.datos.Categoria
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.datos.MovimientoDetectado
import com.gabriel.cadapeso.dominio.CanalDeteccion
import com.gabriel.cadapeso.dominio.Categorizador
import com.gabriel.cadapeso.dominio.EstadoDetectado
import com.gabriel.cadapeso.dominio.EstadoTransaccion
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.TransaccionExterna
import com.gabriel.cadapeso.dominio.normalizar
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Lo que hay que hacer en la base con una transacción que llegó. */
sealed interface AccionImportacion {
    data object Ignorar : AccionImportacion
    data class Insertar(val detectado: MovimientoDetectado) : AccionImportacion
    data class Actualizar(val detectado: MovimientoDetectado) : AccionImportacion
}

/** Lo que ya hay en la base y sirve para decidir (se lee una vez por lote). */
data class ContextoImportacion(
    val categorias: List<Categoria>,
    /** Detectados de los últimos días, para reconocer el mismo movimiento llegado por dos vías. */
    val recientes: List<MovimientoDetectado>,
    /** Movimientos de los últimos días, para avisar si ya lo cargaste a mano. */
    val movimientos: List<Movimiento>,
)

/**
 * Decide qué hacer con cada transacción del esquema unificado antes de que llegue a tus
 * movimientos. No depende de Android ni de la base (se prueba en la PC); el Repositorio ejecuta
 * lo que decide.
 *
 * Reglas:
 * - Todo entra a la bandeja como PENDIENTE: vos lo aceptás, lo editás o lo descartás.
 * - El mismo movimiento puede llegar dos veces (la notificación en el teléfono y después la API
 *   o Prometeo): se reconoce por origen, tipo, monto y fecha cercana, y queda uno solo.
 * - Si ya lo cargaste a mano, se marca como posible duplicado.
 * - Si un pago que ya aceptaste se devuelve, aparece la devolución como un ingreso a revisar.
 */
object Conciliador {

    private const val DIAS_TOLERANCIA = 2L

    fun decidir(
        t: TransaccionExterna,
        canal: CanalDeteccion,
        existente: MovimientoDetectado?,
        ctx: ContextoImportacion,
        ahoraMillis: Long,
    ): AccionImportacion {
        // ---- Ya lo teníamos (mismo id): puede haber cambiado (devolución, monto corregido).
        if (existente != null) {
            return when (existente.estado) {
                EstadoDetectado.PENDIENTE ->
                    if (t.estado == EstadoTransaccion.ANULADA) {
                        AccionImportacion.Actualizar(existente.copy(estado = EstadoDetectado.DESCARTADO))
                    } else {
                        AccionImportacion.Actualizar(
                            existente.copy(monto = t.monto, fecha = t.fecha, descripcion = t.descripcion, medioPago = t.medioPago),
                        )
                    }
                EstadoDetectado.ACEPTADO -> devolucion(t, existente, ctx, ahoraMillis)
                EstadoDetectado.DESCARTADO -> AccionImportacion.Ignorar
            }
        }
        // Un pago que nunca vimos confirmado y ya viene anulado no movió plata.
        if (t.estado == EstadoTransaccion.ANULADA) return AccionImportacion.Ignorar

        val nuevo = aDetectado(t, canal, ctx, ahoraMillis)
        val gemelo = gemelo(t, canal, ctx.recientes) ?: return AccionImportacion.Insertar(nuevo)

        return when {
            // La notificación llegó después que el dato del servidor: queda el del servidor.
            canal == CanalDeteccion.NOTIFICACION -> AccionImportacion.Insertar(nuevo.copy(estado = EstadoDetectado.DESCARTADO))
            // El servidor confirma algo que vimos en una notificación todavía sin revisar: se
            // reemplaza por el dato del servidor (más completo y con el id que usarán las actualizaciones).
            gemelo.estado == EstadoDetectado.PENDIENTE -> AccionImportacion.Actualizar(
                nuevo.copy(id = gemelo.id, categoriaId = gemelo.categoriaId ?: nuevo.categoriaId, detectadoMillis = gemelo.detectadoMillis),
            )
            // Ya resuelto por la notificación: se guarda igual (para seguir sus cambios), sin crear nada nuevo.
            else -> AccionImportacion.Insertar(nuevo.copy(estado = gemelo.estado, movimientoId = gemelo.movimientoId))
        }
    }

    /** Convierte la transacción en un detectado PENDIENTE con categoría sugerida y avisos. */
    fun aDetectado(t: TransaccionExterna, canal: CanalDeteccion, ctx: ContextoImportacion, ahoraMillis: Long): MovimientoDetectado {
        val extra = listOf(t.cuenta, t.contraparte).filter { it.isNotBlank() && it != t.descripcion }.joinToString(" · ")
        return MovimientoDetectado(
            idExterno = t.idTransaccion,
            origen = t.origen,
            canal = canal,
            tipo = t.tipo,
            monto = t.monto,
            moneda = t.moneda,
            fecha = t.fecha,
            descripcion = t.descripcion.take(60),
            categoriaId = categoriaSugerida(t, ctx.categorias)?.id,
            medioPago = t.medioPago,
            detalle = listOf(extra, t.detalle).filter { it.isNotBlank() }.joinToString("\n").take(300),
            transferenciaPropia = t.transferenciaPropia,
            duplicadoDeId = posibleDuplicado(t, ctx)?.id,
            estado = EstadoDetectado.PENDIENTE,
            movimientoId = null,
            detectadoMillis = ahoraMillis,
        )
    }

    /**
     * Categoría sugerida: la pista del proveedor si coincide con una categoría tuya; si no, la
     * que reconoce el Categorizador (con lo que aprendió de tus correcciones) en la descripción
     * y el texto original.
     */
    fun categoriaSugerida(t: TransaccionExterna, categorias: List<Categoria>): Categoria? {
        val activas = categorias.filter { it.tipo == t.tipo && !it.archivada }
        t.categoria?.let { pista -> activas.firstOrNull { normalizar(it.nombre) == normalizar(pista) }?.let { return it } }
        return Categorizador.sugerir("${t.descripcion} ${t.contraparte} ${t.detalle}", categorias, t.tipo)
    }

    /** El mismo movimiento llegado por la otra vía (notificación ↔ servidor). */
    fun gemelo(t: TransaccionExterna, canal: CanalDeteccion, recientes: List<MovimientoDetectado>): MovimientoDetectado? =
        recientes.firstOrNull {
            it.canal != canal && it.idExterno != t.idTransaccion && it.origen == t.origen && it.tipo == t.tipo &&
                it.moneda == t.moneda && abs(it.monto - t.monto) < 0.01 &&
                abs(ChronoUnit.DAYS.between(it.fecha, t.fecha)) <= DIAS_TOLERANCIA
        }

    /** Un movimiento cargado a mano, todavía no vinculado a nada importado, que parece el mismo. */
    fun posibleDuplicado(t: TransaccionExterna, ctx: ContextoImportacion): Movimiento? {
        val vinculados = ctx.recientes.mapNotNull { it.movimientoId }.toSet()
        return ctx.movimientos.firstOrNull {
            it.origen == OrigenMovimiento.MANUAL && it.id !in vinculados && it.tipo == t.tipo &&
                abs(it.monto - t.monto) < 0.01 && abs(ChronoUnit.DAYS.between(it.fecha, t.fecha)) <= DIAS_TOLERANCIA
        }
    }

    /**
     * Un movimiento ya aceptado cambió: si se anuló del todo o bajó el monto (devolución
     * parcial), se propone la devolución como un ingreso. Si no cambió nada importante, se ignora.
     */
    private fun devolucion(t: TransaccionExterna, aceptado: MovimientoDetectado, ctx: ContextoImportacion, ahoraMillis: Long): AccionImportacion {
        if (aceptado.tipo != TipoMovimiento.GASTO) return AccionImportacion.Ignorar
        val totalDevuelto = if (t.estado == EstadoTransaccion.ANULADA) aceptado.monto else aceptado.monto - t.monto
        // Si ya se propusieron devoluciones parciales, solo falta la diferencia.
        val prefijo = "${t.idTransaccion}:devolucion:"
        val yaPropuesto = ctx.recientes.filter { it.idExterno.startsWith(prefijo) }.sumOf { it.monto }
        val devuelto = totalDevuelto - yaPropuesto
        if (devuelto < 0.01) return AccionImportacion.Ignorar
        val reintegro = TransaccionExterna(
            idTransaccion = prefijo + Math.round(totalDevuelto * 100),
            origen = t.origen,
            tipo = TipoMovimiento.INGRESO,
            monto = Math.round(devuelto * 100) / 100.0,
            moneda = t.moneda,
            fecha = t.fecha,
            descripcion = "Devolución: ${aceptado.descripcion}".take(60),
            categoria = "Otros ingresos",
            medioPago = aceptado.medioPago,
            cuenta = t.cuenta,
        )
        return AccionImportacion.Insertar(aDetectado(reintegro, aceptado.canal, ctx, ahoraMillis).copy(duplicadoDeId = null))
    }

    /**
     * ¿Se puede agregar sin que lo revises? Solo si lo activaste, vino de la cuenta conectada
     * (no de una notificación), es en pesos, tiene categoría y no hay nada raro.
     */
    fun aceptableSinRevision(d: MovimientoDetectado): Boolean =
        d.canal == CanalDeteccion.SERVIDOR && d.estado == EstadoDetectado.PENDIENTE && d.moneda == "ARS" &&
            d.categoriaId != null && !d.transferenciaPropia && d.duplicadoDeId == null

    /** El movimiento que se crea al aceptar. Si no tiene categoría, va a "Otros". */
    fun aMovimiento(d: MovimientoDetectado, categorias: List<Categoria>, categoriaId: Long? = d.categoriaId): Movimiento? {
        val categoria = categorias.firstOrNull { it.id == categoriaId && it.tipo == d.tipo }
            ?: categorias.firstOrNull { it.tipo == d.tipo && !it.archivada && it.nombre.startsWith("Otros") }
            ?: categorias.firstOrNull { it.tipo == d.tipo && !it.archivada && (d.tipo == TipoMovimiento.INGRESO || it.grupo == GrupoGasto.DESEO) }
            ?: return null
        val nota = buildString {
            append("${d.origen.emoji} ${d.origen.etiqueta}")
            if (d.moneda != "ARS") append(" · monto original en ${d.moneda}")
        }
        return Movimiento(
            fecha = d.fecha,
            monto = d.monto,
            tipo = d.tipo,
            categoriaId = categoria.id,
            descripcion = d.descripcion,
            medioPago = d.medioPago,
            nota = nota,
            origen = d.origen,
        )
    }
}

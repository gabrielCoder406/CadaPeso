package com.gabriel.cadapeso.datos

import androidx.room.withTransaction
import com.gabriel.cadapeso.dominio.CanalDeteccion
import com.gabriel.cadapeso.dominio.Categorizador
import com.gabriel.cadapeso.dominio.DatosFinancieros
import com.gabriel.cadapeso.dominio.EstadoCompra
import com.gabriel.cadapeso.dominio.EstadoDetectado
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.MotorPresupuesto
import com.gabriel.cadapeso.dominio.TipoActivo
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.TransaccionExterna
import com.gabriel.cadapeso.dominio.clave
import com.gabriel.cadapeso.integraciones.AccionImportacion
import com.gabriel.cadapeso.integraciones.Conciliador
import com.gabriel.cadapeso.integraciones.ContextoImportacion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

/** Resultado de importar un lote de transacciones de las cuentas conectadas. */
data class ResumenImportacion(val pendientes: Int = 0, val aceptados: Int = 0, val actualizados: Int = 0) {
    operator fun plus(otro: ResumenImportacion) =
        ResumenImportacion(pendientes + otro.pendientes, aceptados + otro.aceptados, actualizados + otro.actualizados)
}

/**
 * Única puerta de entrada a la base de datos. Las pantallas nunca hablan con Room directo:
 * así las reglas (ajustar el saldo de una deuda al pagarla, aprender categorías, etc.)
 * están en un solo lugar.
 */
class Repositorio(private val db: BaseDatos) {

    val perfil: Flow<Perfil> = db.perfil().observar().map { it ?: Perfil() }
    val configIA: Flow<ConfigIA> = db.configIA().observar().map { it ?: ConfigIA() }
    val chat: Flow<List<MensajeChat>> = db.chat().observar()

    /** Movimientos de las cuentas conectadas que esperan revisión. */
    val detectadosPendientes: Flow<List<MovimientoDetectado>> = db.detectados().observarPendientes()

    private data class Parte1(
        val movimientos: List<Movimiento>, val categorias: List<Categoria>, val asignaciones: List<Asignacion>,
        val planes: List<PlanMensual>, val activos: List<Activo>,
    )

    private data class Parte2(
        val deudas: List<Deuda>, val metas: List<Meta>, val compras: List<CompraEnEspera>,
        val eventos: List<EventoPlanificado>, val perfil: Perfil,
    )

    /**
     * Todas las tablas combinadas en un solo flujo: cada vez que cambia cualquier cosa,
     * emite un DatosFinancieros nuevo y el motor recalcula el análisis.
     * (combine acepta hasta 5 flujos tipados, por eso se arma en dos partes.)
     */
    val datos: Flow<DatosFinancieros> = combine(
        combine(
            db.movimientos().observar(), db.categorias().observar(), db.asignaciones().observar(),
            db.planes().observar(), db.activos().observar(),
        ) { m, c, a, p, ac -> Parte1(m, c, a, p, ac) },
        combine(
            db.deudas().observar(), db.metas().observar(), db.compras().observar(), db.eventos().observar(), perfil,
        ) { d, me, co, e, pe -> Parte2(d, me, co, e, pe) },
    ) { a, b ->
        DatosFinancieros(a.movimientos, a.categorias, a.asignaciones, a.planes, a.activos, b.deudas, b.metas, b.compras, b.eventos, b.perfil)
    }

    /** Lectura puntual de todo (para los recordatorios en segundo plano y el respaldo). */
    suspend fun cargarDatos(): DatosFinancieros = DatosFinancieros(
        movimientos = db.movimientos().todos(),
        categorias = db.categorias().todas(),
        asignaciones = db.asignaciones().todas(),
        planes = db.planes().todos(),
        activos = db.activos().todos(),
        deudas = db.deudas().todas(),
        metas = db.metas().todas(),
        compras = db.compras().todas(),
        eventos = db.eventos().todos(),
        perfil = db.perfil().obtener() ?: Perfil(),
    )

    /** Primera vez: categorías base y filas de perfil/configuración. */
    suspend fun inicializar() = db.withTransaction {
        if (db.categorias().contar() == 0) db.categorias().insertarTodos(CategoriasBase.lista())
        if (db.perfil().obtener() == null) db.perfil().guardar(Perfil())
        if (db.configIA().obtener() == null) db.configIA().guardar(ConfigIA())
    }

    // ------------------------------------------------------------------ Movimientos

    /**
     * Guarda un movimiento nuevo o editado.
     * - Si es (o era) un pago de deuda, ajusta el saldo de esa deuda.
     * - Si elegiste una categoría distinta a la sugerida, la app aprende la palabra.
     */
    suspend fun guardarMovimiento(m: Movimiento, categoriaSugerida: Long? = m.categoriaId): Long = db.withTransaction {
        val anterior = if (m.id != 0L) db.movimientos().obtener(m.id) else null
        anterior?.deudaId?.let { ajustarSaldoDeuda(it, +anterior.monto) }
        m.deudaId?.let { ajustarSaldoDeuda(it, -m.monto) }
        val id = if (m.id == 0L) db.movimientos().insertar(m) else m.id.also { db.movimientos().actualizar(m) }
        if (categoriaSugerida != m.categoriaId && m.descripcion.isNotBlank()) {
            val todas = db.categorias().todas()
            todas.firstOrNull { it.id == m.categoriaId }
                ?.let { Categorizador.aprender(it, m.descripcion, todas) }
                ?.let { db.categorias().actualizar(it) }
        }
        id
    }

    suspend fun borrarMovimiento(m: Movimiento) = db.withTransaction {
        m.deudaId?.let { ajustarSaldoDeuda(it, +m.monto) }
        db.movimientos().borrar(m)
    }

    /** Deshacer un borrado: vuelve a insertar el movimiento con su mismo id. */
    suspend fun restaurarMovimiento(m: Movimiento) = db.withTransaction {
        m.deudaId?.let { ajustarSaldoDeuda(it, -m.monto) }
        db.movimientos().insertar(m)
    }

    private suspend fun ajustarSaldoDeuda(id: Long, delta: Double) {
        val d = db.deudas().obtener(id) ?: return
        db.deudas().actualizar(d.copy(saldo = max(0.0, d.saldo + delta), actualizado = LocalDate.now()))
    }

    /** La categoría de un grupo (ahorro, deudas). Si el usuario la borró, se vuelve a crear. */
    private suspend fun categoriaDeGrupo(grupo: GrupoGasto): Categoria {
        db.categorias().todas().firstOrNull { it.grupo == grupo && !it.archivada }?.let { return it }
        val base = CategoriasBase.lista().first { it.grupo == grupo }
        return base.copy(id = db.categorias().insertar(base))
    }

    // ------------------------------------------------------------------ Categorías

    suspend fun guardarCategoria(c: Categoria) {
        if (c.id == 0L) db.categorias().insertar(c) else db.categorias().actualizar(c)
    }

    /** Borra una categoría pasando sus movimientos a otra (para no dejar huérfanos). */
    suspend fun borrarCategoria(c: Categoria, destino: Categoria) = db.withTransaction {
        db.movimientos().moverCategoria(c.id, destino.id)
        db.asignaciones().borrarDeCategoria(c.id)
        db.categorias().borrar(c)
    }

    // ------------------------------------------------------------------ Presupuesto

    suspend fun guardarAsignacion(mes: Int, categoriaId: Long, monto: Double) {
        val a = Asignacion(mes, categoriaId, monto)
        if (monto <= 0) db.asignaciones().borrar(a) else db.asignaciones().guardar(a)
    }

    suspend fun reemplazarAsignaciones(mes: Int, nuevas: List<Asignacion>) = db.withTransaction {
        db.asignaciones().borrarMes(mes)
        db.asignaciones().insertarTodos(nuevas)
    }

    suspend fun guardarPlan(mes: Int, ingreso: Double) = db.planes().guardar(PlanMensual(mes, ingreso))

    // ------------------------------------------------------------------ Patrimonio, deudas, metas

    suspend fun guardarActivo(a: Activo) = db.activos().guardar(a)
    suspend fun borrarActivo(a: Activo) = db.activos().borrar(a)

    suspend fun guardarDeuda(d: Deuda) = db.deudas().guardar(d)
    suspend fun borrarDeuda(d: Deuda) = db.withTransaction {
        db.movimientos().desvincularDeuda(d.id)
        db.deudas().borrar(d)
    }

    suspend fun guardarMeta(m: Meta) = db.metas().guardar(m)
    suspend fun borrarMeta(m: Meta) = db.withTransaction {
        db.movimientos().desvincularMeta(m.id)
        db.metas().borrar(m)
    }

    suspend fun guardarEvento(e: EventoPlanificado) = db.eventos().guardar(e)
    suspend fun borrarEvento(e: EventoPlanificado) = db.eventos().borrar(e)

    /** Aporte a una meta: es un gasto en la categoría de ahorro, vinculado a la meta. */
    suspend fun aportarAMeta(meta: Meta, monto: Double, fecha: LocalDate) {
        val cat = categoriaDeGrupo(GrupoGasto.AHORRO)
        db.movimientos().insertar(
            Movimiento(fecha = fecha, monto = monto, tipo = TipoMovimiento.GASTO, categoriaId = cat.id,
                descripcion = "Aporte a ${meta.nombre}", medioPago = MedioPago.TRANSFERENCIA, metaId = meta.id),
        )
    }

    /** Aporte al fondo de emergencia: registra el ahorro y suma el monto a la cuenta elegida. */
    suspend fun aportarAFondo(activo: Activo, monto: Double, fecha: LocalDate) = db.withTransaction {
        val cat = categoriaDeGrupo(GrupoGasto.AHORRO)
        db.movimientos().insertar(
            Movimiento(fecha = fecha, monto = monto, tipo = TipoMovimiento.GASTO, categoriaId = cat.id,
                descripcion = "Fondo de emergencia (${activo.nombre})", medioPago = MedioPago.TRANSFERENCIA),
        )
        db.activos().actualizar(activo.copy(valor = activo.valor + monto, actualizado = fecha))
    }

    /** "Págate a ti primero": registra el ahorro del mes. */
    suspend fun registrarAhorro(monto: Double, fecha: LocalDate, descripcion: String = "Págate a ti primero") {
        val cat = categoriaDeGrupo(GrupoGasto.AHORRO)
        db.movimientos().insertar(
            Movimiento(fecha = fecha, monto = monto, tipo = TipoMovimiento.GASTO, categoriaId = cat.id,
                descripcion = descripcion, medioPago = MedioPago.TRANSFERENCIA),
        )
    }

    /** Pago de una deuda: gasto en la categoría de deudas que además baja el saldo. */
    suspend fun pagarDeuda(deuda: Deuda, monto: Double, fecha: LocalDate, medio: MedioPago) {
        val cat = categoriaDeGrupo(GrupoGasto.DEUDA)
        guardarMovimiento(
            Movimiento(fecha = fecha, monto = monto, tipo = TipoMovimiento.GASTO, categoriaId = cat.id,
                descripcion = "Pago ${deuda.nombre}", medioPago = medio, deudaId = deuda.id),
            categoriaSugerida = cat.id,
        )
    }

    // ------------------------------------------------------------------ Compras en espera (24/48 h)

    suspend fun crearCompraEnEspera(c: CompraEnEspera): Long = db.compras().insertar(c)
    suspend fun obtenerCompra(id: Long): CompraEnEspera? = db.compras().obtener(id)

    /** Fin de la espera: si la comprás, se registra el gasto; si no, cuenta como ahorro evitado. */
    suspend fun resolverCompra(c: CompraEnEspera, comprar: Boolean, medio: MedioPago) = db.withTransaction {
        db.compras().actualizar(
            c.copy(estado = if (comprar) EstadoCompra.COMPRADA else EstadoCompra.DESCARTADA, resueltaMillis = System.currentTimeMillis()),
        )
        if (comprar) {
            db.movimientos().insertar(
                Movimiento(fecha = LocalDate.now(), monto = c.monto, tipo = TipoMovimiento.GASTO, categoriaId = c.categoriaId,
                    descripcion = c.descripcion, medioPago = medio),
            )
        }
    }

    suspend fun borrarCompra(c: CompraEnEspera) = db.compras().borrar(c)

    // ------------------------------------------------------------------ Perfil, IA, chat

    suspend fun obtenerPerfil(): Perfil = db.perfil().obtener() ?: Perfil()
    suspend fun guardarPerfil(p: Perfil) = db.perfil().guardar(p)
    suspend fun obtenerConfigIA(): ConfigIA = db.configIA().obtener() ?: ConfigIA()
    suspend fun guardarConfigIA(c: ConfigIA) = db.configIA().guardar(c)
    suspend fun agregarMensaje(m: MensajeChat) = db.chat().insertar(m)
    suspend fun borrarChat() = db.chat().borrarTodo()

    // ------------------------------------------------------------------ Cuentas conectadas (bandeja de revisión)

    /**
     * Importa transacciones del esquema unificado (de la API, de Prometeo o de una notificación).
     * Todo entra a la bandeja; si [aceptarSolas] está activo, lo que llegó de la cuenta conectada
     * y no tiene nada raro se convierte directamente en movimiento. El Conciliador decide; acá
     * solo se ejecuta, todo dentro de una transacción.
     */
    suspend fun importarTransacciones(
        lista: List<TransaccionExterna>,
        canal: CanalDeteccion,
        aceptarSolas: Boolean,
        ahoraMillis: Long = System.currentTimeMillis(),
    ): ResumenImportacion {
        if (lista.isEmpty()) return ResumenImportacion()
        return db.withTransaction {
            val desde = lista.minOf { it.fecha }.minusDays(3)
            val categorias = db.categorias().todas()
            var ctx = ContextoImportacion(categorias, db.detectados().desde(desde), db.movimientos().desde(desde))
            var pendientes = 0
            var aceptados = 0
            var actualizados = 0
            for (t in lista) {
                when (val accion = Conciliador.decidir(t, canal, db.detectados().porIdExterno(t.idTransaccion), ctx, ahoraMillis)) {
                    AccionImportacion.Ignorar -> Unit
                    is AccionImportacion.Insertar -> {
                        var d = accion.detectado.copy(id = db.detectados().insertar(accion.detectado))
                        if (aceptarSolas && Conciliador.aceptableSinRevision(d)) {
                            d = aceptarDentro(d, categorias, d.categoriaId)
                            aceptados++
                        } else if (d.estado == EstadoDetectado.PENDIENTE) {
                            pendientes++
                        }
                        ctx = ctx.copy(recientes = ctx.recientes + d)
                    }
                    is AccionImportacion.Actualizar -> {
                        db.detectados().actualizar(accion.detectado)
                        actualizados++
                        ctx = ctx.copy(recientes = ctx.recientes.map { if (it.id == accion.detectado.id) accion.detectado else it })
                    }
                }
            }
            // Los resueltos se recuerdan más de un año (para no volver a importarlos) y después se borran.
            db.detectados().purgarResueltos(ahoraMillis - 400L * 86_400_000L)
            ResumenImportacion(pendientes, aceptados, actualizados)
        }
    }

    suspend fun detectadosPendientesAhora(): List<MovimientoDetectado> = db.detectados().pendientes()

    /** Crea el movimiento y marca el detectado como aceptado. Aprende la categoría si la cambiaste. */
    private suspend fun aceptarDentro(d: MovimientoDetectado, categorias: List<Categoria>, categoriaId: Long?): MovimientoDetectado {
        val m = Conciliador.aMovimiento(d, categorias, categoriaId) ?: throw IllegalStateException("No hay una categoría para este movimiento")
        val id = guardarMovimiento(m, categoriaSugerida = d.categoriaId)
        return d.copy(estado = EstadoDetectado.ACEPTADO, movimientoId = id, categoriaId = m.categoriaId).also { db.detectados().actualizar(it) }
    }

    suspend fun aceptarDetectado(id: Long, categoriaId: Long?) = db.withTransaction<Unit> {
        val d = db.detectados().obtener(id)?.takeIf { it.estado == EstadoDetectado.PENDIENTE } ?: return@withTransaction
        aceptarDentro(d, db.categorias().todas(), categoriaId)
    }

    /** Aceptado después de editarlo en el editor de movimientos. */
    suspend fun aceptarDetectadoEditado(id: Long, m: Movimiento, categoriaSugerida: Long?) = db.withTransaction<Unit> {
        val d = db.detectados().obtener(id)?.takeIf { it.estado == EstadoDetectado.PENDIENTE } ?: return@withTransaction
        val movimientoId = guardarMovimiento(m.copy(origen = d.origen), categoriaSugerida)
        db.detectados().actualizar(d.copy(estado = EstadoDetectado.ACEPTADO, movimientoId = movimientoId, categoriaId = m.categoriaId))
    }

    /** "Es el mismo que cargué a mano": no se crea nada, se vincula con ese movimiento. */
    suspend fun vincularDetectadoConExistente(id: Long) = db.withTransaction<Unit> {
        val d = db.detectados().obtener(id) ?: return@withTransaction
        val existente = d.duplicadoDeId ?: return@withTransaction
        db.detectados().actualizar(d.copy(estado = EstadoDetectado.ACEPTADO, movimientoId = existente))
    }

    suspend fun descartarDetectado(id: Long) {
        val d = db.detectados().obtener(id) ?: return
        db.detectados().actualizar(d.copy(estado = EstadoDetectado.DESCARTADO))
    }

    /** Deshacer un descarte. */
    suspend fun restaurarDetectado(id: Long) {
        val d = db.detectados().obtener(id) ?: return
        if (d.estado == EstadoDetectado.DESCARTADO) db.detectados().actualizar(d.copy(estado = EstadoDetectado.PENDIENTE))
    }

    /**
     * Acepta de una vez todos los pendientes que no necesitan una decisión tuya (los que parecen
     * transferencias propias o duplicados quedan en la bandeja). Devuelve cuántos aceptó.
     */
    suspend fun aceptarTodosLosDetectados(): Int = db.withTransaction {
        val categorias = db.categorias().todas()
        db.detectados().pendientes()
            .filter { !it.transferenciaPropia && it.duplicadoDeId == null }
            .onEach { aceptarDentro(it, categorias, it.categoriaId) }
            .size
    }

    /**
     * Refleja el saldo de una cuenta conectada en un activo de Patrimonio (tipo "Cuenta o
     * billetera"). Con [activoId] null lo crea. Devuelve el id, o null si borraste ese activo
     * (en ese caso no se vuelve a crear).
     */
    suspend fun reflejarSaldo(activoId: Long?, nombre: String, monto: Double, hoy: LocalDate): Long? {
        if (activoId == null) {
            return db.activos().insertar(Activo(nombre = nombre, tipo = TipoActivo.CUENTA, valor = monto, liquido = true, actualizado = hoy))
        }
        val activo = db.activos().obtener(activoId) ?: return null
        if (activo.valor != monto) db.activos().actualizar(activo.copy(valor = monto, actualizado = hoy))
        return activoId
    }

    // ------------------------------------------------------------------ Datos de ejemplo / borrado / respaldo

    suspend fun cargarDatosEjemplo(hoy: LocalDate) = db.withTransaction {
        val paquete = DatosEjemplo.generar(db.categorias().todas(), hoy)
        db.movimientos().insertarTodos(paquete.movimientos)
        db.activos().insertarTodos(paquete.activos)
        db.deudas().insertarTodos(paquete.deudas)
        db.metas().insertarTodos(paquete.metas)
        db.eventos().insertarTodos(paquete.eventos)
        val perfil = obtenerPerfil()
        if (perfil.ingresoMensual <= 0) db.perfil().guardar(perfil.copy(ingresoMensual = paquete.ingresoMensual))
        // Presupuesto sugerido para el mes actual, así la pantalla de presupuesto no arranca vacía.
        val datos = cargarDatos()
        val mes = YearMonth.from(hoy)
        if (datos.asignacionesDe(mes).isEmpty()) {
            val ingreso = MotorPresupuesto.ingresoReferencia(datos, mes, MotorPresupuesto.resumenMes(datos, mes).ingresos)
            db.asignaciones().insertarTodos(MotorPresupuesto.sugerirAsignaciones(datos, mes, ingreso))
        }
    }

    /** Borra los datos financieros. Conserva el perfil y la configuración de la IA. */
    suspend fun borrarDatosFinancieros() = db.withTransaction {
        db.movimientos().borrarTodo()
        db.asignaciones().borrarTodo()
        db.planes().borrarTodo()
        db.activos().borrarTodo()
        db.deudas().borrarTodo()
        db.metas().borrarTodo()
        db.compras().borrarTodo()
        db.eventos().borrarTodo()
        db.chat().borrarTodo()
        db.detectados().borrarTodo()
        db.categorias().borrarTodo()
        db.categorias().insertarTodos(CategoriasBase.lista())
    }

    suspend fun exportarJson(): String = Respaldo.exportar(cargarDatos())

    suspend fun exportarCsv(): String = Respaldo.csvMovimientos(cargarDatos())

    /** Reemplaza TODO por el contenido del respaldo (conservando los ids originales). */
    suspend fun importarJson(json: String) {
        val d = Respaldo.importar(json)
        db.withTransaction {
            borrarDatosFinancieros()
            db.categorias().borrarTodo()
            db.categorias().insertarTodos(d.categorias)
            db.movimientos().insertarTodos(d.movimientos)
            db.asignaciones().insertarTodos(d.asignaciones)
            db.planes().insertarTodos(d.planes)
            db.activos().insertarTodos(d.activos)
            db.deudas().insertarTodos(d.deudas)
            db.metas().insertarTodos(d.metas)
            db.compras().insertarTodos(d.compras)
            db.eventos().insertarTodos(d.eventos)
            db.perfil().guardar(d.perfil.copy(onboardingCompleto = true))
        }
    }

    /** Mes en formato aaaamm (atajo para las pantallas). */
    fun claveMes(mes: YearMonth): Int = mes.clave()
}

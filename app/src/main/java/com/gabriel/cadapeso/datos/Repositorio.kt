package com.gabriel.cadapeso.datos

import androidx.room.withTransaction
import com.gabriel.cadapeso.dominio.Categorizador
import com.gabriel.cadapeso.dominio.DatosFinancieros
import com.gabriel.cadapeso.dominio.EstadoCompra
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.MotorPresupuesto
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.clave
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

/**
 * Única puerta de entrada a la base de datos. Las pantallas nunca hablan con Room directo:
 * así las reglas (ajustar el saldo de una deuda al pagarla, aprender categorías, etc.)
 * están en un solo lugar.
 */
class Repositorio(private val db: BaseDatos) {

    val perfil: Flow<Perfil> = db.perfil().observar().map { it ?: Perfil() }
    val configIA: Flow<ConfigIA> = db.configIA().observar().map { it ?: ConfigIA() }
    val chat: Flow<List<MensajeChat>> = db.chat().observar()

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

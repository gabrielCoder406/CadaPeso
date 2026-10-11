package com.gabriel.cadapeso.datos

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Room no sabe guardar LocalDate: la convertimos al número de día desde 1970 (y vuelta). */
class Convertidores {
    @TypeConverter
    fun fechaANumero(fecha: LocalDate?): Long? = fecha?.toEpochDay()

    @TypeConverter
    fun numeroAFecha(valor: Long?): LocalDate? = valor?.let(LocalDate::ofEpochDay)
}

/** Operaciones comunes a todas las tablas (Room genera el SQL a partir de las anotaciones). */
interface DaoBase<T> {
    @Insert
    suspend fun insertar(item: T): Long

    @Insert
    suspend fun insertarTodos(items: List<T>)

    @Update
    suspend fun actualizar(item: T)

    @Upsert
    suspend fun guardar(item: T)

    @Delete
    suspend fun borrar(item: T)
}

@Dao
interface MovimientoDao : DaoBase<Movimiento> {
    @Query("SELECT * FROM movimientos ORDER BY fecha DESC, id DESC")
    fun observar(): Flow<List<Movimiento>>

    @Query("SELECT * FROM movimientos ORDER BY fecha, id")
    suspend fun todos(): List<Movimiento>

    @Query("SELECT * FROM movimientos WHERE id = :id")
    suspend fun obtener(id: Long): Movimiento?

    @Query("SELECT * FROM movimientos WHERE fecha >= :desde")
    suspend fun desde(desde: LocalDate): List<Movimiento>

    @Query("UPDATE movimientos SET metaId = NULL WHERE metaId = :metaId")
    suspend fun desvincularMeta(metaId: Long)

    @Query("UPDATE movimientos SET deudaId = NULL WHERE deudaId = :deudaId")
    suspend fun desvincularDeuda(deudaId: Long)

    @Query("UPDATE movimientos SET categoriaId = :nueva WHERE categoriaId = :vieja")
    suspend fun moverCategoria(vieja: Long, nueva: Long)

    @Query("DELETE FROM movimientos")
    suspend fun borrarTodo()
}

@Dao
interface CategoriaDao : DaoBase<Categoria> {
    @Query("SELECT * FROM categorias ORDER BY orden, nombre")
    fun observar(): Flow<List<Categoria>>

    @Query("SELECT * FROM categorias ORDER BY orden, nombre")
    suspend fun todas(): List<Categoria>

    @Query("SELECT COUNT(*) FROM categorias")
    suspend fun contar(): Int

    @Query("DELETE FROM categorias")
    suspend fun borrarTodo()
}

@Dao
interface AsignacionDao : DaoBase<Asignacion> {
    @Query("SELECT * FROM asignaciones")
    fun observar(): Flow<List<Asignacion>>

    @Query("SELECT * FROM asignaciones")
    suspend fun todas(): List<Asignacion>

    @Query("DELETE FROM asignaciones WHERE mes = :mes")
    suspend fun borrarMes(mes: Int)

    @Query("DELETE FROM asignaciones WHERE categoriaId = :categoriaId")
    suspend fun borrarDeCategoria(categoriaId: Long)

    @Query("DELETE FROM asignaciones")
    suspend fun borrarTodo()
}

@Dao
interface PlanDao : DaoBase<PlanMensual> {
    @Query("SELECT * FROM planes")
    fun observar(): Flow<List<PlanMensual>>

    @Query("SELECT * FROM planes")
    suspend fun todos(): List<PlanMensual>

    @Query("DELETE FROM planes")
    suspend fun borrarTodo()
}

@Dao
interface ActivoDao : DaoBase<Activo> {
    @Query("SELECT * FROM activos ORDER BY valor DESC")
    fun observar(): Flow<List<Activo>>

    @Query("SELECT * FROM activos")
    suspend fun todos(): List<Activo>

    @Query("SELECT * FROM activos WHERE id = :id")
    suspend fun obtener(id: Long): Activo?

    @Query("DELETE FROM activos")
    suspend fun borrarTodo()
}

@Dao
interface DeudaDao : DaoBase<Deuda> {
    @Query("SELECT * FROM deudas ORDER BY tasaAnual DESC")
    fun observar(): Flow<List<Deuda>>

    @Query("SELECT * FROM deudas")
    suspend fun todas(): List<Deuda>

    @Query("SELECT * FROM deudas WHERE id = :id")
    suspend fun obtener(id: Long): Deuda?

    @Query("DELETE FROM deudas")
    suspend fun borrarTodo()
}

@Dao
interface MetaDao : DaoBase<Meta> {
    @Query("SELECT * FROM metas ORDER BY fechaLimite")
    fun observar(): Flow<List<Meta>>

    @Query("SELECT * FROM metas")
    suspend fun todas(): List<Meta>

    @Query("DELETE FROM metas")
    suspend fun borrarTodo()
}

@Dao
interface CompraDao : DaoBase<CompraEnEspera> {
    @Query("SELECT * FROM compras_espera ORDER BY creadaMillis DESC")
    fun observar(): Flow<List<CompraEnEspera>>

    @Query("SELECT * FROM compras_espera")
    suspend fun todas(): List<CompraEnEspera>

    @Query("SELECT * FROM compras_espera WHERE id = :id")
    suspend fun obtener(id: Long): CompraEnEspera?

    @Query("DELETE FROM compras_espera")
    suspend fun borrarTodo()
}

@Dao
interface EventoDao : DaoBase<EventoPlanificado> {
    @Query("SELECT * FROM eventos ORDER BY mes")
    fun observar(): Flow<List<EventoPlanificado>>

    @Query("SELECT * FROM eventos")
    suspend fun todos(): List<EventoPlanificado>

    @Query("DELETE FROM eventos")
    suspend fun borrarTodo()
}

@Dao
interface DetectadoDao : DaoBase<MovimientoDetectado> {
    @Query("SELECT * FROM detectados WHERE estado = 'PENDIENTE' ORDER BY fecha DESC, id DESC")
    fun observarPendientes(): Flow<List<MovimientoDetectado>>

    @Query("SELECT * FROM detectados WHERE id = :id")
    suspend fun obtener(id: Long): MovimientoDetectado?

    @Query("SELECT * FROM detectados WHERE idExterno = :idExterno")
    suspend fun porIdExterno(idExterno: String): MovimientoDetectado?

    @Query("SELECT * FROM detectados WHERE estado = 'PENDIENTE' ORDER BY fecha DESC, id DESC")
    suspend fun pendientes(): List<MovimientoDetectado>

    /** Los detectados de los últimos días (para reconocer el mismo movimiento llegado por dos vías). */
    @Query("SELECT * FROM detectados WHERE fecha >= :desde")
    suspend fun desde(desde: LocalDate): List<MovimientoDetectado>

    /** Los ya resueltos se guardan un tiempo para no volver a importarlos; después se borran. */
    @Query("DELETE FROM detectados WHERE estado != 'PENDIENTE' AND detectadoMillis < :antes")
    suspend fun purgarResueltos(antes: Long)

    @Query("DELETE FROM detectados")
    suspend fun borrarTodo()
}

@Dao
interface ChatDao : DaoBase<MensajeChat> {
    @Query("SELECT * FROM chat ORDER BY fechaMillis, id")
    fun observar(): Flow<List<MensajeChat>>

    @Query("DELETE FROM chat")
    suspend fun borrarTodo()
}

@Dao
interface PerfilDao : DaoBase<Perfil> {
    @Query("SELECT * FROM perfil WHERE id = 1")
    fun observar(): Flow<Perfil?>

    @Query("SELECT * FROM perfil WHERE id = 1")
    suspend fun obtener(): Perfil?
}

@Dao
interface ConfigIADao : DaoBase<ConfigIA> {
    @Query("SELECT * FROM config_ia WHERE id = 1")
    fun observar(): Flow<ConfigIA?>

    @Query("SELECT * FROM config_ia WHERE id = 1")
    suspend fun obtener(): ConfigIA?
}

@Database(
    entities = [
        Movimiento::class, Categoria::class, Asignacion::class, PlanMensual::class, Activo::class,
        Deuda::class, Meta::class, CompraEnEspera::class, EventoPlanificado::class, MensajeChat::class,
        Perfil::class, ConfigIA::class, MovimientoDetectado::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Convertidores::class)
abstract class BaseDatos : RoomDatabase() {
    abstract fun movimientos(): MovimientoDao
    abstract fun categorias(): CategoriaDao
    abstract fun asignaciones(): AsignacionDao
    abstract fun planes(): PlanDao
    abstract fun activos(): ActivoDao
    abstract fun deudas(): DeudaDao
    abstract fun metas(): MetaDao
    abstract fun compras(): CompraDao
    abstract fun eventos(): EventoDao
    abstract fun chat(): ChatDao
    abstract fun perfil(): PerfilDao
    abstract fun configIA(): ConfigIADao
    abstract fun detectados(): DetectadoDao

    companion object {
        fun crear(context: Context): BaseDatos =
            Room.databaseBuilder(context.applicationContext, BaseDatos::class.java, "cadapeso.db")
                .addMigrations(*MIGRACIONES)
                .build()

        /**
         * Versión 1 → 2 (cuentas conectadas): los movimientos guardan su origen y se agrega la
         * bandeja de movimientos detectados. NUNCA usar fallbackToDestructiveMigration: borraría
         * los datos del usuario. El SQL tiene que coincidir exactamente con lo que Room espera
         * (lo verifica MigracionTest).
         */
        val MIGRACION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `movimientos` ADD COLUMN `origen` TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `detectados` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`idExterno` TEXT NOT NULL, `origen` TEXT NOT NULL, `canal` TEXT NOT NULL, `tipo` TEXT NOT NULL, " +
                        "`monto` REAL NOT NULL, `moneda` TEXT NOT NULL, `fecha` INTEGER NOT NULL, `descripcion` TEXT NOT NULL, " +
                        "`categoriaId` INTEGER, `medioPago` TEXT NOT NULL, `detalle` TEXT NOT NULL, " +
                        "`transferenciaPropia` INTEGER NOT NULL, `duplicadoDeId` INTEGER, `estado` TEXT NOT NULL, " +
                        "`movimientoId` INTEGER, `detectadoMillis` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_detectados_idExterno` ON `detectados` (`idExterno`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_detectados_estado` ON `detectados` (`estado`)")
            }
        }

        val MIGRACIONES = arrayOf(MIGRACION_1_2)
    }
}

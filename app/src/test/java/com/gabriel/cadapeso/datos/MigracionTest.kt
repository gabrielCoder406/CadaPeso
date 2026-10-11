package com.gabriel.cadapeso.datos

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.TipoMovimiento
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * La actualización de la app NO puede perder datos: se arma una base como la de la versión 1
 * (sin la columna "origen" ni la bandeja de detectados), se abre con la versión actual y Room
 * tiene que migrarla y validar que el esquema quedó exactamente como espera.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MigracionTest {

    @Test
    fun migraDeLaVersion1SinPerderDatos() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteDatabase("cadapeso.db")

        // 1) Base actual con un movimiento cargado.
        val actual = BaseDatos.crear(app)
        runBlocking {
            actual.categorias().insertarTodos(CategoriasBase.lista())
            val categoria = actual.categorias().todas().first()
            actual.movimientos().insertar(
                Movimiento(fecha = LocalDate.of(2026, 9, 1), monto = 1234.5, tipo = TipoMovimiento.GASTO, categoriaId = categoria.id, descripcion = "Viejo"),
            )
        }
        actual.close()

        // 2) Se la "baja" a la versión 1: la tabla movimientos como la creaba Room en esa versión.
        val sqlite = SQLiteDatabase.openDatabase(app.getDatabasePath("cadapeso.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        sqlite.execSQL("DROP TABLE `detectados`")
        sqlite.execSQL("ALTER TABLE `movimientos` RENAME TO `movimientos_v2`")
        sqlite.execSQL(
            "CREATE TABLE IF NOT EXISTS `movimientos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fecha` INTEGER NOT NULL, " +
                "`monto` REAL NOT NULL, `tipo` TEXT NOT NULL, `categoriaId` INTEGER NOT NULL, `descripcion` TEXT NOT NULL, " +
                "`medioPago` TEXT NOT NULL, `nota` TEXT NOT NULL, `metaId` INTEGER, `deudaId` INTEGER, `creadoConIA` INTEGER NOT NULL)",
        )
        sqlite.execSQL(
            "INSERT INTO `movimientos` SELECT `id`, `fecha`, `monto`, `tipo`, `categoriaId`, `descripcion`, `medioPago`, `nota`, " +
                "`metaId`, `deudaId`, `creadoConIA` FROM `movimientos_v2`",
        )
        sqlite.execSQL("DROP TABLE `movimientos_v2`") // se lleva sus índices: se crean de nuevo abajo
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_movimientos_fecha` ON `movimientos` (`fecha`)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_movimientos_categoriaId` ON `movimientos` (`categoriaId`)")
        sqlite.execSQL("CREATE INDEX IF NOT EXISTS `index_movimientos_metaId` ON `movimientos` (`metaId`)")
        sqlite.version = 1
        sqlite.close()

        // 3) Al abrirla, Room corre MIGRACION_1_2 y valida el esquema (si algo no coincide, lanza una excepción).
        val migrada = BaseDatos.crear(app)
        runBlocking {
            val movimiento = migrada.movimientos().todos().single()
            assertEquals("Viejo", movimiento.descripcion)
            assertEquals(1234.5, movimiento.monto, 0.001)
            assertEquals(OrigenMovimiento.MANUAL, movimiento.origen)
            assertTrue(migrada.detectados().pendientes().isEmpty())
        }
        migrada.close()
    }
}

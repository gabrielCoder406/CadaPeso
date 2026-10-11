package com.gabriel.cadapeso.datos

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gabriel.cadapeso.dominio.CanalDeteccion
import com.gabriel.cadapeso.dominio.EstadoCompra
import com.gabriel.cadapeso.dominio.EstadoDetectado
import com.gabriel.cadapeso.dominio.FrecuenciaAhorro
import com.gabriel.cadapeso.dominio.GrupoGasto
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.OrigenMovimiento
import com.gabriel.cadapeso.dominio.ProveedorIA
import com.gabriel.cadapeso.dominio.ReglaPresupuesto
import com.gabriel.cadapeso.dominio.TipoActivo
import com.gabriel.cadapeso.dominio.TipoDeuda
import com.gabriel.cadapeso.dominio.TipoIngreso
import com.gabriel.cadapeso.dominio.TipoMovimiento
import java.time.LocalDate

// Cada data class con @Entity es una tabla de la base de datos SQLite (Room).
// Los montos son Double: para finanzas personales alcanza y simplifica todas las cuentas.

/** Un ingreso o un gasto. El monto siempre es positivo; el signo lo da [tipo]. */
@Entity(tableName = "movimientos", indices = [Index("fecha"), Index("categoriaId"), Index("metaId")])
data class Movimiento(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fecha: LocalDate,
    val monto: Double,
    val tipo: TipoMovimiento,
    val categoriaId: Long,
    val descripcion: String,
    val medioPago: MedioPago = MedioPago.DEBITO,
    val nota: String = "",
    /** Si es un aporte a una meta de ahorro, a cuál. */
    val metaId: Long? = null,
    /** Si es el pago de una deuda, a cuál (se descuenta del saldo). */
    val deudaId: Long? = null,
    val creadoConIA: Boolean = false,
    /**
     * De dónde vino: cargado a mano o importado de una cuenta conectada. La columna se agregó en
     * la versión 2 de la base; el valor por defecto es para los movimientos que ya existían.
     */
    @ColumnInfo(defaultValue = "'MANUAL'")
    val origen: OrigenMovimiento = OrigenMovimiento.MANUAL,
)

/**
 * Movimiento que llegó de una cuenta conectada (Mercado Pago, Galicia) y espera revisión en la
 * bandeja: el usuario lo acepta (se crea el Movimiento), lo edita o lo descarta. Queda guardado
 * aunque se descarte, así la misma transacción no vuelve a aparecer.
 */
@Entity(tableName = "detectados", indices = [Index(value = ["idExterno"], unique = true), Index("estado")])
data class MovimientoDetectado(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** id_transaccion del esquema unificado ("MERCADO_PAGO:123"). */
    val idExterno: String,
    val origen: OrigenMovimiento,
    val canal: CanalDeteccion,
    val tipo: TipoMovimiento,
    val monto: Double,
    val moneda: String,
    val fecha: LocalDate,
    val descripcion: String,
    /** Categoría sugerida al detectarlo (null = no se pudo adivinar). */
    val categoriaId: Long?,
    val medioPago: MedioPago,
    /** Texto original o datos extra (cuenta, contraparte) para mostrar en la revisión. */
    val detalle: String,
    /** Parece plata movida entre cuentas propias: se sugiere descartarlo. */
    val transferenciaPropia: Boolean,
    /** Un movimiento cargado a mano que parece ser el mismo (mismo monto, fecha cercana). */
    val duplicadoDeId: Long?,
    val estado: EstadoDetectado,
    /** El movimiento creado al aceptarlo. */
    val movimientoId: Long?,
    val detectadoMillis: Long,
)

@Entity(tableName = "categorias")
data class Categoria(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val emoji: String,
    val tipo: TipoMovimiento,
    val grupo: GrupoGasto,
    /** Se puede recortar ya mismo ante una crisis (mide la "capacidad de reacción"). */
    val recortable: Boolean,
    /** Gasto fijo mensual (alquiler, servicios): no cuenta para el ritmo del panel semanal. */
    val fijo: Boolean = false,
    /** Palabras separadas por coma para reconocer la categoría en un texto ("super,coto,carrefour"). */
    val palabrasClave: String = "",
    val color: Long = 0xFF607D8BL,
    val archivada: Boolean = false,
    val orden: Int = 0,
)

/** "Cada peso con una tarea": cuánto se le asigna a una categoría en un mes (aaaamm). */
@Entity(tableName = "asignaciones", primaryKeys = ["mes", "categoriaId"])
data class Asignacion(
    val mes: Int,
    val categoriaId: Long,
    val monto: Double,
)

/** Ingreso que se espera cobrar en un mes; es el dinero que hay que asignar. */
@Entity(tableName = "planes")
data class PlanMensual(
    @PrimaryKey val mes: Int,
    val ingresoPrevisto: Double,
)

@Entity(tableName = "activos")
data class Activo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val tipo: TipoActivo,
    /** Valor a precio de mercado actual. */
    val valor: Double,
    /** Se puede convertir en efectivo en días sin perder valor. */
    val liquido: Boolean,
    /** Genera flujo de efectivo (intereses, alquileres, dividendos). */
    val generaIngresos: Boolean = false,
    /** Es parte del fondo de emergencia ("airbag financiero"). */
    val esFondoEmergencia: Boolean = false,
    val actualizado: LocalDate,
)

@Entity(tableName = "deudas")
data class Deuda(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val tipo: TipoDeuda,
    /** Saldo pendiente (lo que falta pagar). */
    val saldo: Double,
    /** Tasa nominal anual (TNA) en %. */
    val tasaAnual: Double,
    /** Cuota mensual o pago mínimo. */
    val cuotaMensual: Double,
    /** Créditos UVA: la tasa cargada ya es "real" (el saldo además se ajusta por inflación). */
    val ajustaPorInflacion: Boolean = false,
    /** Si la deuda financió algo que rinde (un negocio, un alquiler), cuánto rinde en % anual. */
    val rendimientoActivo: Double? = null,
    val actualizado: LocalDate,
)

/** Meta de ahorro SMART con plan de ahorro incremental opcional. */
@Entity(tableName = "metas")
data class Meta(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val emoji: String = "🎯",
    /** Específica: qué querés lograr exactamente. */
    val especifica: String = "",
    /** Relevante: por qué te importa. */
    val relevancia: String = "",
    val montoObjetivo: Double,
    /** Lo que ya tenías ahorrado para esto antes de crear la meta. */
    val montoInicial: Double = 0.0,
    val fechaLimite: LocalDate,
    /** Ahorro incremental: primer aporte, cuánto crece cada vez y cada cuánto. */
    val aporteInicial: Double = 0.0,
    val incremento: Double = 0.0,
    val frecuencia: FrecuenciaAhorro = FrecuenciaAhorro.MENSUAL,
    val creada: LocalDate,
    val archivada: Boolean = false,
)

/** Compra en pausa por la regla de las 24/48 horas. */
@Entity(tableName = "compras_espera")
data class CompraEnEspera(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val descripcion: String,
    val monto: Double,
    val categoriaId: Long,
    val creadaMillis: Long,
    val horasEspera: Int,
    val estado: EstadoCompra = EstadoCompra.EN_ESPERA,
    /** Respuesta a "¿Esto me acerca o me aleja de mi libertad financiera?" */
    val respuestaLibertad: String = "",
    /** Respuesta a "¿Lo necesito o es un impulso emocional?" */
    val respuestaNecesidad: String = "",
    val comentarioIA: String = "",
    val resueltaMillis: Long? = null,
) {
    val venceMillis: Long get() = creadaMillis + horasEspera * 3_600_000L
}

/** Gasto (o ingreso) extraordinario previsto: impuestos anuales, vacaciones, matrícula, aguinaldo. */
@Entity(tableName = "eventos")
data class EventoPlanificado(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val monto: Double,
    val tipo: TipoMovimiento = TipoMovimiento.GASTO,
    /** Mes en formato aaaamm. */
    val mes: Int,
    /** Se repite todos los años en el mismo mes. */
    val repetirAnual: Boolean = false,
)

@Entity(tableName = "chat")
data class MensajeChat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val esUsuario: Boolean,
    val texto: String,
    val fechaMillis: Long,
)

/** Perfil financiero del usuario (una sola fila, id = 1). */
@Entity(tableName = "perfil")
data class Perfil(
    @PrimaryKey val id: Int = 1,
    val nombre: String = "",
    val edad: Int = 30,
    val ingresoMensual: Double = 0.0,
    val tipoIngreso: TipoIngreso = TipoIngreso.ESTABLE,
    val diaCobro: Int = 1,
    val moneda: String = "$",
    val regla: ReglaPresupuesto = ReglaPresupuesto.R_50_30_20,
    /** "Págate a ti primero": % del ingreso que se separa apenas se cobra. */
    val porcentajePagatePrimero: Double = 10.0,
    /** Inflación anual esperada en %. Si es > 0, las deudas se evalúan por su tasa real. */
    val inflacionAnual: Double = 0.0,
    /** Tasa (real) a partir de la cual una deuda se considera cara. */
    val umbralTasaMala: Double = 16.0,
    /** Monto máximo de un "gasto hormiga". 0 = automático (1,5% del ingreso). */
    val umbralHormiga: Double = 0.0,
    /** Gasto no planificado a partir del cual se sugiere esperar 24 h. 0 = automático. */
    val umbralImpulso: Double = 0.0,
    /** Resultado del test de conducta (0 = muy ahorrador, 100 = muy impulsivo). null = sin hacer. */
    val puntajeConducta: Int? = null,
    /** Resultado del test de riesgo (0 = muy conservador, 100 = muy arriesgado). null = sin hacer. */
    val puntajeRiesgo: Int? = null,
    val onboardingCompleto: Boolean = false,
)

/** Configuración del motor de IA (una sola fila, id = 1). */
@Entity(tableName = "config_ia")
data class ConfigIA(
    @PrimaryKey val id: Int = 1,
    val proveedor: ProveedorIA = ProveedorIA.NINGUNO,
    /** Ruta del archivo .task/.bin de Gemma 2 dentro del teléfono. */
    val rutaModelo: String = "",
    val usarGpu: Boolean = false,
    /** Tokens totales (pregunta + respuesta). El modelo Gemma2-2B-IT de LiteRT admite 1280. */
    val maxTokens: Int = 1280,
    /** Arma a mano el formato de turnos de Gemma (<start_of_turn>...). */
    val plantillaManual: Boolean = true,
    val temperatura: Double = 0.6,
    val ollamaUrl: String = "",
    val ollamaModelo: String = "gemma2:2b",
)

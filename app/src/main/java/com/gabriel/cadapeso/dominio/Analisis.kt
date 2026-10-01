package com.gabriel.cadapeso.dominio

import com.gabriel.cadapeso.datos.Activo
import com.gabriel.cadapeso.datos.Asignacion
import com.gabriel.cadapeso.datos.Categoria
import com.gabriel.cadapeso.datos.CompraEnEspera
import com.gabriel.cadapeso.datos.Deuda
import com.gabriel.cadapeso.datos.EventoPlanificado
import com.gabriel.cadapeso.datos.Meta
import com.gabriel.cadapeso.datos.Movimiento
import com.gabriel.cadapeso.datos.Perfil
import com.gabriel.cadapeso.datos.PlanMensual
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

// Este archivo solo define "formas de datos": lo que entra al motor financiero
// (DatosFinancieros) y lo que sale (Analisis). La lógica está en los archivos Motor*.kt.

/** Todo lo que hay en la base de datos, en un solo paquete. Es la entrada del motor. */
data class DatosFinancieros(
    val movimientos: List<Movimiento> = emptyList(),
    val categorias: List<Categoria> = emptyList(),
    val asignaciones: List<Asignacion> = emptyList(),
    val planes: List<PlanMensual> = emptyList(),
    val activos: List<Activo> = emptyList(),
    val deudas: List<Deuda> = emptyList(),
    val metas: List<Meta> = emptyList(),
    val compras: List<CompraEnEspera> = emptyList(),
    val eventos: List<EventoPlanificado> = emptyList(),
    val perfil: Perfil = Perfil(),
) {
    private val categoriaPorId: Map<Long, Categoria> by lazy { categorias.associateBy { it.id } }

    fun categoria(id: Long): Categoria? = categoriaPorId[id]

    /** El grupo de un movimiento (los ingresos siempre son INGRESO). */
    fun grupoDe(m: Movimiento): GrupoGasto =
        if (m.tipo == TipoMovimiento.INGRESO) GrupoGasto.INGRESO
        else categoria(m.categoriaId)?.grupo ?: GrupoGasto.DESEO

    fun movimientosDe(mes: YearMonth): List<Movimiento> = movimientos.filter { mes.contiene(it.fecha) }

    fun planDe(mes: YearMonth): PlanMensual? = planes.firstOrNull { it.mes == mes.clave() }

    fun asignacionesDe(mes: YearMonth): List<Asignacion> = asignaciones.filter { it.mes == mes.clave() }
}

/** Resumen de un mes calendario. */
data class ResumenMes(
    val mes: YearMonth,
    val ingresos: Double,
    /** Todo lo que salió (incluye lo que se mandó a ahorro y los pagos de deudas). */
    val gastos: Double,
    val ahorro: Double,
    val pagoDeudas: Double,
    val porCategoria: Map<Long, Double>,
    val porGrupo: Map<GrupoGasto, Double>,
    val conTarjeta: Double,
    /** Gasto en categorías que se pueden recortar ya mismo. */
    val recortable: Double,
    /** Gasto en categorías variables (no fijas, sin ahorro ni deudas): lo que mide el panel semanal. */
    val variable: Double,
    val cantidad: Int,
) {
    /** Lo que realmente "se consumió": todo menos lo ahorrado (el ahorro sigue siendo tuyo). */
    val consumo: Double get() = gastos - ahorro
    val balance: Double get() = ingresos - gastos
    val tieneDatos: Boolean get() = cantidad > 0
    fun grupo(g: GrupoGasto): Double = porGrupo[g] ?: 0.0
}

enum class EstadoLinea { OK, CERCA, EXCEDIDA, SIN_PLAN }

/** Una categoría dentro del presupuesto mensual: cuánto se le asignó y cuánto se gastó. */
data class LineaPresupuesto(val categoria: Categoria, val asignado: Double, val gastado: Double) {
    val restante: Double get() = asignado - gastado
    val usado: Double get() = if (asignado > 0) gastado / asignado * 100 else 0.0
    val estado: EstadoLinea
        get() = when {
            asignado <= 0.0 && gastado > 0.0 -> EstadoLinea.SIN_PLAN
            asignado <= 0.0 -> EstadoLinea.OK
            usado > 100.0 -> EstadoLinea.EXCEDIDA
            usado >= 80.0 -> EstadoLinea.CERCA
            else -> EstadoLinea.OK
        }
}

/** Una partida de la regla (ej. "Necesidades 50%") comparada con lo asignado y lo real. */
data class LineaPartida(
    val partida: Partida,
    val objetivo: Double,
    val asignado: Double,
    val real: Double,
    val porcentajeReal: Double,
) {
    val desvioPuntos: Double get() = porcentajeReal - partida.porcentaje
}

data class EstadoPresupuesto(
    val mes: YearMonth,
    val regla: ReglaPresupuesto,
    val ingresoAAsignar: Double,
    val totalAsignado: Double,
    val lineas: List<LineaPresupuesto>,
    val partidas: List<LineaPartida>,
) {
    /** "Cada peso con una tarea": lo ideal es que esto sea 0. */
    val sinAsignar: Double get() = ingresoAAsignar - totalAsignado
    val excedidas: List<LineaPresupuesto> get() = lineas.filter { it.estado == EstadoLinea.EXCEDIDA }
}

data class ResumenSemana(
    val inicio: LocalDate,
    val gastoPorDia: List<Double>,
    val total: Double,
    val totalSemanaAnterior: Double,
    val presupuestoSemanal: Double,
    val diasTranscurridos: Int,
    val topCategorias: List<Pair<Long, Double>>,
) {
    val fin: LocalDate get() = inicio.plusDays(6)
    val esperadoHastaHoy: Double get() = presupuestoSemanal * diasTranscurridos / 7.0
    val disponible: Double get() = presupuestoSemanal - total
    /** % por encima (+) o por debajo (-) del ritmo esperado a esta altura de la semana. */
    val desvio: Double get() = if (esperadoHastaHoy > 0) (total - esperadoHastaHoy) / esperadoHastaHoy * 100 else 0.0
}

/** Gastos chicos y repetidos en una categoría recortable. */
data class FugaHormiga(
    val categoria: Categoria,
    val cantidadMes: Int,
    val totalMes: Double,
    val ejemplos: List<String>,
    /** Lo que valdría ese dinero invertido todos los meses durante 10 años. */
    val costoOportunidad10Anios: Double,
) {
    val promedio: Double get() = if (cantidadMes > 0) totalMes / cantidadMes else 0.0
    val totalAnual: Double get() = totalMes * 12
}

data class SuscripcionDetectada(
    val nombre: String,
    val montoMensual: Double,
    val ultimaFecha: LocalDate,
    val categoriaId: Long,
    val vecesVista: Int,
)

data class MesProyectado(
    val mes: YearMonth,
    val ingreso: Double,
    val gasto: Double,
    val eventos: List<EventoPlanificado>,
    val saldoAcumulado: Double,
) {
    val eventosNeto: Double get() = eventos.sumOf { if (it.tipo == TipoMovimiento.INGRESO) it.monto else -it.monto }
    val libre: Double get() = ingreso - gasto + eventosNeto
}

data class Proyeccion(
    val ingresoBase: Double,
    val gastoBase: Double,
    val mesesDeHistoria: Int,
    val meses: List<MesProyectado>,
    /** Cuánto separar por mes desde ahora para cubrir los gastos extraordinarios que vienen. */
    val provisionMensual: Double,
) {
    val mesesEnRojo: List<MesProyectado> get() = meses.filter { it.libre < 0 }
}

data class EstadoPatrimonio(
    val activos: Double,
    val activosLiquidos: Double,
    val activosQueRinden: Double,
    val inversiones: Double,
    val pasivos: Double,
    val porTipo: Map<TipoActivo, Double>,
) {
    val neto: Double get() = activos - pasivos
}

data class Indicadores(
    val ingresoReferencia: Double,
    val gastoMensualPromedio: Double,
    val gastoBasicoMensual: Double,
    /** % del gasto mensual que podrías recortar ya mismo ante una crisis. */
    val capacidadReaccion: Double,
    /** Cuotas de deuda / ingreso (%). Alerta arriba de 30%. */
    val ratioEndeudamiento: Double,
    /** Consumo con tarjeta de crédito este mes / ingreso (%). Alerta arriba de 30%. */
    val usoTarjeta: Double,
    val tasaAhorro: Double,
    /** Necesidades (supervivencia) / ingreso (%). Si pasa de 80% se arma un plan de ajuste. */
    val porcentajeNecesidades: Double,
    /** Cuántos meses de gastos básicos cubren tus activos líquidos. */
    val mesesSupervivencia: Double,
    /** Lo que suele sobrar por mes (ingreso - consumo). */
    val capacidadAhorroMensual: Double,
)

enum class EstadoFondo(val etiqueta: String) {
    CRITICO("Crítico"),
    EN_CONSTRUCCION("En construcción"),
    SALUDABLE("Saludable"),
    COMPLETO("Completo"),
}

data class EstadoFondoEmergencia(
    val gastoBasicoMensual: Double,
    val mesesMin: Int,
    val mesesMax: Int,
    val acumulado: Double,
    val cuentas: List<Activo>,
) {
    val objetivoMin: Double get() = gastoBasicoMensual * mesesMin
    val objetivoMax: Double get() = gastoBasicoMensual * mesesMax
    val mesesCubiertos: Double get() = if (gastoBasicoMensual > 0) acumulado / gastoBasicoMensual else 0.0
    val faltanteMin: Double get() = max(0.0, objetivoMin - acumulado)
    val estado: EstadoFondo
        get() = when {
            mesesCubiertos < 1 -> EstadoFondo.CRITICO
            mesesCubiertos < mesesMin -> EstadoFondo.EN_CONSTRUCCION
            mesesCubiertos < mesesMax -> EstadoFondo.SALUDABLE
            else -> EstadoFondo.COMPLETO
        }
}

data class ChecklistSmart(
    val especifica: Boolean,
    val medible: Boolean,
    val alcanzable: Boolean,
    val relevante: Boolean,
    val temporal: Boolean,
) {
    val cumplidos: Int get() = listOf(especifica, medible, alcanzable, relevante, temporal).count { it }
}

data class CuotaPlan(val numero: Int, val fecha: LocalDate, val monto: Double, val acumulado: Double)

data class EstadoMeta(
    val meta: Meta,
    val acumulado: Double,
    val mesesRestantes: Double,
    val aporteMensualNecesario: Double,
    val smart: ChecklistSmart,
    val proximasCuotas: List<CuotaPlan>,
    val fechaEstimadaPlan: LocalDate?,
    val enRiesgo: Boolean,
) {
    val faltante: Double get() = max(0.0, meta.montoObjetivo - acumulado)
    val progreso: Double get() = if (meta.montoObjetivo > 0) (acumulado / meta.montoObjetivo).coerceIn(0.0, 1.0) else 0.0
    val completa: Boolean get() = acumulado >= meta.montoObjetivo
}

data class EstadoPagatePrimero(
    val porcentaje: Double,
    val objetivoMes: Double,
    val ahorradoMes: Double,
    val diaCobro: Int,
    val proximoCobro: LocalDate,
    val yaCobro: Boolean,
    val cumplidoMesAnterior: Boolean,
) {
    val cumplido: Boolean get() = objetivoMes > 0 && ahorradoMes >= objetivoMes * 0.999
    val faltante: Double get() = max(0.0, objetivoMes - ahorradoMes)
}

data class DeudaAnalizada(
    val deuda: Deuda,
    val clase: ClaseDeuda,
    val tasaReal: Double,
    val interesMensual: Double,
    /** La cuota apenas cubre (o ni cubre) los intereses: el saldo casi no baja. */
    val trampaPagoMinimo: Boolean,
    val motivo: String,
)

data class PasoPlan(val deuda: Deuda, val mesLiquidacion: Int, val interesPagado: Double)

data class ResultadoPlan(
    val meses: Int,
    val interesTotal: Double,
    val orden: List<PasoPlan>,
    val esViable: Boolean,
)

data class PlanDeudas(
    val deudas: List<DeudaAnalizada>,
    val pagoExtra: Double,
    val avalancha: ResultadoPlan,
    val bolaDeNieve: ResultadoPlan,
) {
    val totalSaldo: Double get() = deudas.sumOf { it.deuda.saldo }
    val totalCuotas: Double get() = deudas.sumOf { it.deuda.cuotaMensual }
    val malas: List<DeudaAnalizada> get() = deudas.filter { it.clase == ClaseDeuda.MALA }
    /** Cuánto se ahorra en intereses con avalancha frente a bola de nieve. */
    val ahorroAvalancha: Double get() = max(0.0, bolaDeNieve.interesTotal - avalancha.interesTotal)
}

data class PasoRuta(
    val numero: Int,
    val titulo: String,
    val descripcion: String,
    val cumplido: Boolean,
    val progreso: Double,
    val detalle: String,
)

data class HojaDeRuta(val pasos: List<PasoRuta>) {
    /** Primer paso sin cumplir (1 a 4). 5 = todos cumplidos. */
    val pasoActual: Int get() = pasos.firstOrNull { !it.cumplido }?.numero ?: 5
}

/** Plan trimestral para bajar las necesidades al 60% cuando superan el 80% del ingreso. */
data class PlanAjuste(
    val porcentajeActual: Double,
    val porcentajeObjetivo: Double,
    val metasMensuales: List<Pair<YearMonth, Double>>,
    /** Cuánto hay que recortar por mes (en $) para llegar al objetivo al final del trimestre. */
    val recorteMensualFinal: Double,
    val sugerencias: List<String>,
)

enum class AccionConsejo(val etiqueta: String) {
    VER_PRESUPUESTO("Ver presupuesto"),
    VER_SEMANA("Ver semana"),
    VER_FUGAS("Ver fugas"),
    VER_PROYECCION("Ver proyección"),
    VER_DEUDAS("Ver deudas"),
    VER_AHORRO("Ver ahorro"),
    VER_INVERSION("Ver inversión"),
    VER_BALANCE("Ver balance"),
    HACER_TEST("Hacer el test"),
    VER_COMPRAS("Ver compras en espera"),
}

data class Consejo(
    val id: String,
    val severidad: Severidad,
    val titulo: String,
    val mensaje: String,
    val accion: AccionConsejo? = null,
)

data class PerfilFinanciero(
    val etapa: EtapaVida,
    val conducta: PerfilConducta?,
    val riesgo: PerfilRiesgo?,
) {
    val conductaEfectiva: PerfilConducta get() = conducta ?: PerfilConducta.EQUILIBRADO
    val riesgoEfectivo: PerfilRiesgo get() = riesgo ?: PerfilRiesgo.MODERADO
}

/** La salida completa del motor financiero: todo lo que muestran las pantallas y lo que lee la IA. */
data class Analisis(
    val hoy: LocalDate,
    /** Momento del cálculo (para saber qué compras en espera ya vencieron). */
    val ahoraMillis: Long,
    val datos: DatosFinancieros,
    val mes: ResumenMes,
    val mesAnterior: ResumenMes,
    val presupuesto: EstadoPresupuesto,
    val semana: ResumenSemana,
    val fugas: List<FugaHormiga>,
    val suscripciones: List<SuscripcionDetectada>,
    val proyeccion: Proyeccion,
    val patrimonio: EstadoPatrimonio,
    val indicadores: Indicadores,
    val fondoEmergencia: EstadoFondoEmergencia,
    val pagatePrimero: EstadoPagatePrimero,
    val metas: List<EstadoMeta>,
    val deudas: PlanDeudas,
    val hojaDeRuta: HojaDeRuta,
    val planAjuste: PlanAjuste?,
    val perfil: PerfilFinanciero,
    val consejos: List<Consejo>,
    val umbralHormiga: Double,
    val umbralImpulso: Double,
    /** Total de compras impulsivas que decidiste no hacer (regla de las 24/48 h). */
    val ahorroPorImpulsosEvitados: Double,
) {
    val moneda: String get() = datos.perfil.moneda
}

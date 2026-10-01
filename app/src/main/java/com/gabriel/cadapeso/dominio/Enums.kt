package com.gabriel.cadapeso.dominio

// Enumeraciones del dominio. Room las guarda como texto (su nombre), así que se pueden
// reordenar sin romper la base de datos, pero NO renombrar.

enum class TipoMovimiento(val etiqueta: String) {
    GASTO("Gasto"),
    INGRESO("Ingreso"),
}

/** A qué "partida" del presupuesto pertenece cada categoría de gasto. */
enum class GrupoGasto(val etiqueta: String, val descripcion: String) {
    NECESIDAD("Necesidades", "Gastos imprescindibles: vivienda, comida, servicios, salud y transporte."),
    DESEO("Deseos", "Gustos y gastos variables: salidas, delivery, ropa, suscripciones."),
    EDUCACION("Educación financiera", "Cursos, libros y formación que aumentan tu capacidad de generar ingresos."),
    AHORRO("Ahorro e inversión", "Lo que te pagás a vos primero: ahorro, fondo de emergencia e inversiones."),
    DEUDA("Pago de deudas", "Cuotas de préstamos y pagos de tarjeta."),
    INGRESO("Ingresos", "Sueldo, honorarios y rendimientos."),
}

enum class MedioPago(val etiqueta: String, val emoji: String) {
    EFECTIVO("Efectivo", "💵"),
    DEBITO("Débito", "🏧"),
    CREDITO("Crédito", "💳"),
    TRANSFERENCIA("Transferencia", "🔁"),
    BILLETERA("Billetera virtual", "📱"),
}

enum class TipoIngreso(val etiqueta: String, val mesesFondoMin: Int, val mesesFondoMax: Int) {
    ESTABLE("Estable (sueldo fijo)", 3, 6),
    VARIABLE("Variable (autónomo, comisiones, changas)", 6, 12),
}

/** Una partida de una regla de presupuesto: un nombre, un porcentaje del ingreso y qué grupos cubre. */
data class Partida(
    val nombre: String,
    val porcentaje: Double,
    val grupos: Set<GrupoGasto>,
    val explicacion: String,
)

enum class ReglaPresupuesto(val etiqueta: String, val resumen: String, val partidas: List<Partida>) {
    R_50_30_20(
        "50/30/20",
        "50% necesidades, 30% deseos, 20% ahorro y deudas",
        listOf(
            Partida("Necesidades", 50.0, setOf(GrupoGasto.NECESIDAD), "Gastos fijos e imprescindibles."),
            Partida("Deseos", 30.0, setOf(GrupoGasto.DESEO, GrupoGasto.EDUCACION), "Gustos y gastos variables."),
            Partida("Ahorro y deudas", 20.0, setOf(GrupoGasto.AHORRO, GrupoGasto.DEUDA), "Ahorro, inversión y pago de deudas."),
        ),
    ),
    R_60_10_10_20(
        "60/10/10/20",
        "Partidas de control: 60% básicos, 10% ocio, 10% educación financiera, 20% ahorro",
        listOf(
            Partida("Gastos básicos", 60.0, setOf(GrupoGasto.NECESIDAD, GrupoGasto.DEUDA), "Supervivencia: vivienda, comida, servicios, cuotas."),
            Partida("Entretenimiento", 10.0, setOf(GrupoGasto.DESEO), "Ocio, salidas y gustos."),
            Partida("Educación financiera", 10.0, setOf(GrupoGasto.EDUCACION), "Formarte para ganar más y decidir mejor."),
            Partida("Ahorro e inversión", 20.0, setOf(GrupoGasto.AHORRO), "No negociable: se separa apenas cobrás."),
        ),
    ),
    ;

    fun partidaDe(grupo: GrupoGasto): Partida? = partidas.firstOrNull { grupo in it.grupos }
}

enum class TipoActivo(val etiqueta: String, val emoji: String, val liquidoPorDefecto: Boolean) {
    EFECTIVO("Efectivo", "💵", true),
    CUENTA("Cuenta o billetera", "🏦", true),
    AHORRO_LIQUIDO("Ahorro líquido (plazo fijo, FCI money market, cuenta remunerada)", "💧", true),
    INVERSION("Inversiones (FCI, bonos, acciones, CEDEARs)", "📈", false),
    INMUEBLE("Inmueble", "🏠", false),
    VEHICULO("Vehículo", "🚗", false),
    OTRO("Otro bien", "📦", false),
}

enum class TipoDeuda(val etiqueta: String, val emoji: String, val esConsumo: Boolean) {
    TARJETA("Tarjeta de crédito", "💳", true),
    PRESTAMO_PERSONAL("Préstamo personal", "🧾", true),
    HIPOTECARIO("Crédito hipotecario", "🏠", false),
    PRENDARIO("Crédito prendario (auto)", "🚗", false),
    ESTUDIOS("Para estudios", "🎓", false),
    NEGOCIO("Para un negocio o inversión", "🏪", false),
    FAMILIAR("Familia o amigos", "🤝", true),
    OTRO("Otra", "📦", true),
}

enum class ClaseDeuda(val etiqueta: String) {
    MALA("Deuda mala"),
    BUENA("Deuda buena"),
    NEUTRA("A vigilar"),
}

enum class FrecuenciaAhorro(val etiqueta: String, val dias: Long) {
    SEMANAL("Semanal", 7),
    MENSUAL("Mensual", 30),
}

enum class EstadoCompra(val etiqueta: String) {
    EN_ESPERA("En espera"),
    COMPRADA("Comprada"),
    DESCARTADA("Descartada"),
}

enum class ProveedorIA(val etiqueta: String, val detalle: String) {
    NINGUNO("Sin IA", "Solo el motor de reglas (funciona igual, sin chat libre)"),
    LOCAL("Gemma 2 en el teléfono", "Privado y sin internet. Necesita el archivo del modelo (~2,6 GB)"),
    OLLAMA("Gemma 2 vía Ollama", "Usa la PC de tu casa con Ollama en la misma red Wi-Fi"),
}

enum class EtapaVida(val etiqueta: String, val rango: String, val enfoque: String) {
    JUVENTUD(
        "Juventud", "18 a 30 años",
        "Construí hábitos: fondo de emergencia básico, eliminá deudas e invertí temprano para aprovechar el interés compuesto.",
    ),
    CONSOLIDACION(
        "Consolidación", "31 a 50 años",
        "Diversificá la cartera, protegé a tu familia con seguros y planificá metas grandes (vivienda, educación).",
    ),
    PRE_RETIRO(
        "Pre-retiro y retiro", "más de 50 años",
        "Preservá el capital: priorizá renta fija e instrumentos que generen ingresos recurrentes.",
    ),
}

enum class PerfilConducta(val etiqueta: String, val tono: String) {
    AHORRADOR("Ahorrador", "cálido y motivador; reconocé sus logros y animalo a invertir lo que ahorra"),
    EQUILIBRADO("Equilibrado", "claro, práctico y directo"),
    IMPULSIVO("Tendencia al gasto impulsivo", "firme pero empático; recordale sus metas y la regla de las 24 horas"),
}

enum class PerfilRiesgo(
    val etiqueta: String,
    val rendimientoRealAnual: Double,
    val caidaMalAnio: Double,
    val descripcion: String,
) {
    CONSERVADOR("Conservador", 3.0, 5.0, "Priorizás no perder: renta fija, plazo fijo y fondos money market."),
    MODERADO("Moderado", 5.0, 15.0, "Aceptás algo de volatilidad: mezcla de renta fija y renta variable."),
    AGRESIVO("Agresivo", 7.0, 30.0, "Buscás crecimiento a largo plazo y tolerás caídas fuertes: mayoría renta variable."),
}

enum class Severidad { ALTA, MEDIA, BAJA, POSITIVA }

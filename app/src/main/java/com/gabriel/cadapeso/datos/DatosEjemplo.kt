package com.gabriel.cadapeso.datos

import com.gabriel.cadapeso.dominio.FrecuenciaAhorro
import com.gabriel.cadapeso.dominio.MedioPago
import com.gabriel.cadapeso.dominio.TipoActivo
import com.gabriel.cadapeso.dominio.TipoDeuda
import com.gabriel.cadapeso.dominio.TipoMovimiento
import com.gabriel.cadapeso.dominio.clave
import java.time.LocalDate
import java.time.YearMonth
import java.util.Random

/**
 * Genera 3 meses de datos realistas (pesos argentinos) para probar la app sin cargar nada
 * a mano. El escenario tiene problemas a propósito: gastos básicos altos, una tarjeta en la
 * trampa del pago mínimo, gastos hormiga y un fondo de emergencia chico, para que se vean
 * todas las alertas y consejos.
 */
object DatosEjemplo {

    data class Paquete(
        val movimientos: List<Movimiento>,
        val activos: List<Activo>,
        val deudas: List<Deuda>,
        val metas: List<Meta>,
        val eventos: List<EventoPlanificado>,
        val ingresoMensual: Double,
    )

    fun generar(categorias: List<Categoria>, hoy: LocalDate): Paquete {
        val r = Random(7) // semilla fija: siempre genera los mismos datos
        fun cat(nombre: String): Long = categorias.firstOrNull { it.nombre == nombre }?.id ?: categorias.first().id
        fun <T> List<T>.alAzar(): T = this[r.nextInt(size)]
        val movs = mutableListOf<Movimiento>()
        val inicio = YearMonth.from(hoy).minusMonths(3)

        for (offset in 0..3) {
            val mes = inicio.plusMonths(offset.toLong())
            fun dia(d: Int): LocalDate = mes.atDay(d.coerceIn(1, mes.lengthOfMonth()))
            fun agregar(
                fecha: LocalDate, monto: Double, categoria: String, descripcion: String,
                medio: MedioPago, tipo: TipoMovimiento = TipoMovimiento.GASTO,
            ) {
                if (fecha.isAfter(hoy)) return
                movs += Movimiento(
                    fecha = fecha, monto = monto, tipo = tipo, categoriaId = cat(categoria),
                    descripcion = descripcion, medioPago = medio,
                )
            }

            agregar(dia(5), 1_250_000.0, "Sueldo", "Sueldo", MedioPago.TRANSFERENCIA, TipoMovimiento.INGRESO)
            if (offset % 2 == 1) agregar(dia(18), 180_000.0, "Trabajo independiente", "Proyecto freelance", MedioPago.TRANSFERENCIA, TipoMovimiento.INGRESO)

            agregar(dia(6), 50_000.0, "Ahorro e inversión", "Ahorro mensual", MedioPago.TRANSFERENCIA)
            agregar(dia(6), 420_000.0, "Vivienda", "Alquiler", MedioPago.TRANSFERENCIA)
            agregar(dia(6), 45_000.0, "Vivienda", "Expensas", MedioPago.TRANSFERENCIA)
            agregar(dia(10), 28_000.0 + r.nextInt(6000), "Servicios", "Luz Edenor", MedioPago.DEBITO)
            agregar(dia(12), 18_500.0, "Servicios", "Gas", MedioPago.DEBITO)
            agregar(dia(15), 24_000.0, "Servicios", "Internet Fibertel", MedioPago.DEBITO)
            agregar(dia(15), 16_000.0, "Servicios", "Celular", MedioPago.DEBITO)
            agregar(dia(10), 85_000.0, "Pago de deudas", "Cuota préstamo personal", MedioPago.DEBITO)
            agregar(dia(11), 120_000.0, "Pago de deudas", "Resumen tarjeta Visa", MedioPago.TRANSFERENCIA)
            agregar(dia(14), 22_000.0 + r.nextInt(8000), "Salud", "Farmacia", MedioPago.DEBITO)

            for (semana in 0..3) {
                agregar(dia(3 + semana * 7), 52_000.0 + r.nextInt(20_000), "Supermercado",
                    listOf("Coto", "Carrefour", "Chino de la esquina", "Verdulería").alAzar(), MedioPago.DEBITO)
            }
            for (k in 0..5) {
                agregar(dia(2 + k * 5), 6_000.0 + r.nextInt(4000), "Transporte",
                    listOf("Carga SUBE", "Uber", "Nafta YPF").alAzar(), MedioPago.BILLETERA)
            }
            // Gastos hormiga: un café o algo del kiosco casi día por medio.
            for (k in 0..11) {
                agregar(dia(2 + k * 2), 2_800.0 + r.nextInt(2500), "Café y kiosco",
                    listOf("Café", "Kiosco", "Alfajor y gaseosa", "Café con medialunas").alAzar(), MedioPago.BILLETERA)
            }
            for (d in listOf(9, 23)) {
                agregar(dia(d), 14_000.0 + r.nextInt(9000), "Comida afuera y delivery",
                    listOf("PedidosYa", "Rappi", "Pizza", "Hamburguesas").alAzar(), MedioPago.CREDITO)
            }
            agregar(dia(8), 11_999.0, "Suscripciones", "Netflix", MedioPago.CREDITO)
            agregar(dia(9), 4_999.0, "Suscripciones", "Spotify", MedioPago.CREDITO)
            agregar(dia(9), 35_000.0, "Suscripciones", "Gimnasio", MedioPago.DEBITO)
            if (offset % 2 == 0) {
                agregar(dia(20), 45_000.0 + r.nextInt(25_000), "Ropa y compras",
                    listOf("Zapatillas", "Remera y jean", "Mercado Libre").alAzar(), MedioPago.CREDITO)
                agregar(dia(22), 25_000.0 + r.nextInt(15_000), "Ocio y salidas",
                    listOf("Cine", "Salida con amigos", "Recital").alAzar(), MedioPago.BILLETERA)
            }
            if (offset == 2) agregar(dia(16), 15_000.0, "Formación", "Libro de finanzas personales", MedioPago.DEBITO)
        }

        val activos = listOf(
            Activo(nombre = "Cuenta sueldo", tipo = TipoActivo.CUENTA, valor = 210_000.0, liquido = true, actualizado = hoy),
            Activo(nombre = "Cuenta remunerada", tipo = TipoActivo.AHORRO_LIQUIDO, valor = 420_000.0, liquido = true,
                generaIngresos = true, esFondoEmergencia = true, actualizado = hoy),
            Activo(nombre = "Auto", tipo = TipoActivo.VEHICULO, valor = 9_500_000.0, liquido = false, actualizado = hoy),
        )
        val deudas = listOf(
            Deuda(nombre = "Tarjeta Visa", tipo = TipoDeuda.TARJETA, saldo = 520_000.0, tasaAnual = 98.0,
                cuotaMensual = 35_000.0, actualizado = hoy),
            Deuda(nombre = "Préstamo personal", tipo = TipoDeuda.PRESTAMO_PERSONAL, saldo = 950_000.0, tasaAnual = 72.0,
                cuotaMensual = 85_000.0, actualizado = hoy),
        )
        val metas = listOf(
            Meta(nombre = "Vacaciones en la costa", emoji = "🏖️", especifica = "Una semana en Mar del Plata con la familia",
                relevancia = "Descansar sin endeudarme con la tarjeta", montoObjetivo = 500_000.0, montoInicial = 100_000.0,
                fechaLimite = hoy.plusMonths(8), aporteInicial = 8_000.0, incremento = 500.0,
                frecuencia = FrecuenciaAhorro.SEMANAL, creada = hoy.minusWeeks(2)),
            Meta(nombre = "Notebook nueva", emoji = "💻", especifica = "Notebook para trabajar como freelance",
                montoObjetivo = 1_400_000.0, fechaLimite = hoy.plusMonths(14), creada = hoy.minusMonths(1)),
        )
        val eventos = listOf(
            EventoPlanificado(nombre = "Aguinaldo de junio", monto = 625_000.0, tipo = TipoMovimiento.INGRESO,
                mes = YearMonth.of(hoy.year, 6).clave(), repetirAnual = true),
            EventoPlanificado(nombre = "Aguinaldo de diciembre", monto = 625_000.0, tipo = TipoMovimiento.INGRESO,
                mes = YearMonth.of(hoy.year, 12).let { if (it.isBefore(YearMonth.from(hoy))) it.plusYears(1) else it }.clave(), repetirAnual = true),
            EventoPlanificado(nombre = "Patente del auto (anual)", monto = 180_000.0, mes = YearMonth.from(hoy).plusMonths(2).clave(), repetirAnual = true),
            EventoPlanificado(nombre = "Vacaciones de verano", monto = 900_000.0, mes = YearMonth.of(hoy.year + 1, 1).clave()),
        )
        return Paquete(movs, activos, deudas, metas, eventos, 1_250_000.0)
    }
}

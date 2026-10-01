package com.gabriel.cadapeso.notificaciones

import android.content.Context
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gabriel.cadapeso.CadaPesoApp
import com.gabriel.cadapeso.datos.CompraEnEspera
import com.gabriel.cadapeso.dominio.EstadoCompra
import com.gabriel.cadapeso.dominio.Formato
import com.gabriel.cadapeso.dominio.MotorFinanciero
import com.gabriel.cadapeso.dominio.Severidad
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Regla de las 24/48 horas: cuando termina la espera, avisa para decidir con la cabeza fría.
 * WorkManager garantiza que el aviso llegue aunque la app esté cerrada o el teléfono se reinicie.
 */
class RecordatorioCompraWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(CLAVE_ID, -1)
        val repo = (applicationContext as CadaPesoApp).contenedor.repo
        val compra = repo.obtenerCompra(id) ?: return Result.success()
        if (compra.estado != EstadoCompra.EN_ESPERA) return Result.success()
        val moneda = repo.obtenerPerfil().moneda
        Notificador.mostrar(
            applicationContext, (10_000 + id).toInt(), Notificador.CANAL_RECORDATORIOS,
            "Pasaron ${compra.horasEspera} horas",
            "¿Todavía querés \"${compra.descripcion}\" por ${Formato.dinero(compra.monto, moneda)}? Decidí con la cabeza fría.",
            Notificador.DESTINO_COMPRAS,
        )
        return Result.success()
    }

    companion object {
        private const val CLAVE_ID = "compraId"

        fun programar(context: Context, compra: CompraEnEspera) {
            val demora = (compra.venceMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val pedido = OneTimeWorkRequestBuilder<RecordatorioCompraWorker>()
                .setInitialDelay(demora, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(CLAVE_ID to compra.id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("compra_${compra.id}", ExistingWorkPolicy.REPLACE, pedido)
        }

        fun cancelar(context: Context, compraId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("compra_$compraId")
        }
    }
}

/**
 * Revisión diaria (9:30 aprox.): el día de cobro recuerda "págate a ti primero", los domingos
 * manda el resumen de la semana, y avisa si aparece una alerta grave nueva.
 */
class RevisionDiariaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as CadaPesoApp).contenedor.repo
        val datos = repo.cargarDatos()
        if (!datos.perfil.onboardingCompleto || datos.movimientos.isEmpty()) return Result.success()
        val hoy = LocalDate.now()
        val a = MotorFinanciero.analizar(datos, hoy)
        val m = a.moneda
        val prefs = applicationContext.getSharedPreferences("avisos", Context.MODE_PRIVATE)
        val hoyTexto = hoy.toString()

        val p = a.pagatePrimero
        if (hoy.dayOfMonth == p.diaCobro && !p.cumplido && p.objetivoMes > 0 && prefs.getString("cobro", "") != hoyTexto) {
            Notificador.mostrar(
                applicationContext, 1, Notificador.CANAL_RECORDATORIOS, "Día de cobro: págate a vos primero",
                "Antes de gastar, separá ${Formato.dinero(p.faltante, m)} (${Formato.porcentaje(p.porcentaje)} de tu ingreso) para tu ahorro.",
                Notificador.DESTINO_AHORRO,
            )
            prefs.edit { putString("cobro", hoyTexto) }
        }

        if (hoy.dayOfWeek == DayOfWeek.SUNDAY && prefs.getString("semana", "") != hoyTexto) {
            val s = a.semana
            val ritmo = if (s.presupuestoSemanal > 0) {
                if (s.desvio > 0) " (${Formato.porcentaje(s.desvio)} por encima del ritmo)" else " (dentro del ritmo 👏)"
            } else ""
            Notificador.mostrar(
                applicationContext, 2, Notificador.CANAL_ALERTAS, "Tu semana en CadaPeso",
                "Gastaste ${Formato.dinero(s.total, m)} en gastos variables$ritmo. " + (a.consejos.firstOrNull()?.titulo ?: ""),
                Notificador.DESTINO_SEMANA,
            )
            prefs.edit { putString("semana", hoyTexto) }
        }

        // Alertas graves: solo se avisa cuando cambia el conjunto (no todos los días lo mismo).
        val altas = a.consejos.filter { it.severidad == Severidad.ALTA }
        val firma = altas.joinToString(",") { it.id }
        if (altas.isNotEmpty() && firma != prefs.getString("altas", "")) {
            val primera = altas.first()
            Notificador.mostrar(applicationContext, 3, Notificador.CANAL_ALERTAS, primera.titulo, primera.mensaje, Notificador.DESTINO_INICIO)
        }
        prefs.edit { putString("altas", firma) }
        return Result.success()
    }

    companion object {
        fun programar(context: Context) {
            val ahora = LocalDateTime.now()
            var proxima = ahora.toLocalDate().atTime(9, 30)
            if (!proxima.isAfter(ahora)) proxima = proxima.plusDays(1)
            val pedido = PeriodicWorkRequestBuilder<RevisionDiariaWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(ahora, proxima).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("revision_diaria", ExistingPeriodicWorkPolicy.KEEP, pedido)
        }
    }
}

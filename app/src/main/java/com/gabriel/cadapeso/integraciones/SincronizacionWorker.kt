package com.gabriel.cadapeso.integraciones

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gabriel.cadapeso.CadaPesoApp
import com.gabriel.cadapeso.notificaciones.Notificador
import java.util.concurrent.TimeUnit

/**
 * Trae los movimientos de las cuentas conectadas cada unas horas, aunque la app esté cerrada.
 * (Los webhooks de Mercado Pago llegan al servidor al instante; el teléfono los baja acá o
 * cuando abrís la app.)
 */
class SincronizacionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val contenedor = (applicationContext as CadaPesoApp).contenedor
        val resumen = contenedor.integraciones.sincronizar()
        if (resumen.importacion.pendientes > 0) Notificador.avisarDetectados(applicationContext, contenedor.repo)
        return Result.success()
    }

    companion object {
        private const val PERIODICA = "sincronizacion_cuentas"
        private const val YA = "sincronizacion_cuentas_ya"

        private val conInternet = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun programar(context: Context) {
            val pedido = PeriodicWorkRequestBuilder<SincronizacionWorker>(6, TimeUnit.HOURS)
                .setConstraints(conInternet)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODICA, ExistingPeriodicWorkPolicy.KEEP, pedido)
        }

        /** Una sincronización ahora mismo (en segundo plano, apenas haya internet). */
        fun ahora(context: Context) {
            val pedido = OneTimeWorkRequestBuilder<SincronizacionWorker>().setConstraints(conInternet).build()
            WorkManager.getInstance(context).enqueueUniqueWork(YA, ExistingWorkPolicy.KEEP, pedido)
        }

        fun cancelar(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODICA)
            WorkManager.getInstance(context).cancelUniqueWork(YA)
        }
    }
}

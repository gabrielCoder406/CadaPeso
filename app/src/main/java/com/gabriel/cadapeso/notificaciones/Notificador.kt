package com.gabriel.cadapeso.notificaciones

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gabriel.cadapeso.MainActivity
import com.gabriel.cadapeso.R
import com.gabriel.cadapeso.datos.Repositorio
import com.gabriel.cadapeso.dominio.Formato

/** Crea los canales de notificación y muestra avisos que, al tocarlos, abren la pantalla indicada. */
object Notificador {
    const val CANAL_RECORDATORIOS = "recordatorios"
    const val CANAL_ALERTAS = "alertas"
    const val EXTRA_DESTINO = "destino"

    // Destinos a los que puede llevar una notificación.
    const val DESTINO_COMPRAS = "compras"
    const val DESTINO_AHORRO = "ahorro"
    const val DESTINO_SEMANA = "semana"
    const val DESTINO_INICIO = "inicio"
    const val DESTINO_DETECTADOS = "detectados"
    const val DESTINO_CONEXIONES = "conexiones"
    /** Al volver del navegador después de autorizar Mercado Pago (deep link cadapeso://conexion). */
    const val DESTINO_VINCULADO = "vinculado"
    const val DESTINO_VINCULACION_FALLIDA = "vinculacion_fallida"

    private const val ID_DETECTADOS = 4

    fun crearCanales(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CANAL_RECORDATORIOS, "Recordatorios", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Fin de la espera de 24/48 h y día de cobro"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CANAL_ALERTAS, "Alertas y resúmenes", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Alertas importantes y resumen semanal"
            },
        )
    }

    fun permisoConcedido(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Un solo aviso (que se actualiza) con lo que espera revisión en la bandeja de cuentas conectadas. */
    suspend fun avisarDetectados(context: Context, repo: Repositorio) {
        val pendientes = repo.detectadosPendientesAhora()
        if (pendientes.isEmpty()) return
        val moneda = repo.obtenerPerfil().moneda
        val texto = if (pendientes.size == 1) {
            val d = pendientes.first()
            "${d.origen.etiqueta}: ${d.descripcion} por ${Formato.dinero(d.monto, moneda)}. Tocá para revisarlo."
        } else {
            "${pendientes.size} movimientos de tus cuentas esperan que los revises."
        }
        mostrar(context, ID_DETECTADOS, CANAL_ALERTAS, "Movimientos para revisar", texto, DESTINO_DETECTADOS)
    }

    fun mostrar(context: Context, id: Int, canal: String, titulo: String, texto: String, destino: String) {
        if (!permisoConcedido(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DESTINO, destino)
        }
        val pendiente = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notificacion = NotificationCompat.Builder(context, canal)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(pendiente)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notificacion)
        } catch (_: SecurityException) {
            // El usuario revocó el permiso justo ahora: no hay nada que hacer.
        }
    }
}

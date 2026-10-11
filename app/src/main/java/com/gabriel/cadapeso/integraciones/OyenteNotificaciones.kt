package com.gabriel.cadapeso.integraciones

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.gabriel.cadapeso.CadaPesoApp
import com.gabriel.cadapeso.notificaciones.Notificador
import kotlinx.coroutines.launch

/**
 * Recibe las notificaciones del teléfono (con el permiso "Acceso a notificaciones" que da el
 * usuario) y se queda SOLO con las de las apps de Galicia y Mercado Pago. Las interpreta en el
 * teléfono con [LectorNotificaciones] y las manda a la bandeja de revisión. Nada sale del
 * teléfono y las notificaciones de otras apps se ignoran sin leerlas.
 */
class OyenteNotificaciones : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (LectorNotificaciones.origenDe(sbn.packageName) == null) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return // el resumen de un grupo repite lo mismo
        val extras = n.extras ?: return
        val titulo = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val texto = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val transaccion = LectorNotificaciones.interpretar(sbn.packageName, titulo, texto, sbn.postTime) ?: return

        val app = application as CadaPesoApp
        app.alcance.launch {
            val r = runCatching { app.contenedor.integraciones.importarNotificacion(transaccion) }.getOrNull() ?: return@launch
            if (r.pendientes > 0) Notificador.avisarDetectados(app, app.contenedor.repo)
        }
    }

    companion object {
        /** ¿El usuario ya le dio a CadaPeso el "Acceso a notificaciones"? */
        fun permisoConcedido(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Abre la pantalla del sistema donde se activa el acceso a notificaciones. */
        fun abrirAjustesDelSistema(context: Context) {
            val componente = ComponentName(context, OyenteNotificaciones::class.java).flattenToString()
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .putExtra(":settings:fragment_args_key", componente)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /**
         * En Android 13+ las apps instaladas desde un APK (no desde Play) tienen "configuración
         * restringida": antes de dar el acceso hay que permitirlo en Información de la app.
         */
        fun abrirInformacionDeLaApp(context: Context) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

package com.gabriel.cadapeso.integraciones

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda secretos (el token del servidor y, si lo pedís, el usuario y la clave de Galicia)
 * cifrados con AES-256-GCM. La clave vive en el Android Keystore: no se puede sacar del
 * teléfono (en muchos equipos está en hardware) y se pierde si desinstalás la app.
 *
 * Este archivo (cofre.xml) se excluye del backup de Google: en otro teléfono la clave del
 * Keystore no existe y los datos serían ilegibles. Ahí se vuelve a conectar.
 */
class Cofre(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    fun guardar(nombre: String, valor: String?) {
        if (valor == null) {
            prefs.edit { remove(nombre) }
            return
        }
        val cifrador = Cipher.getInstance(TRANSFORMACION).apply {
            init(Cipher.ENCRYPT_MODE, clave())
            updateAAD(nombre.toByteArray(Charsets.UTF_8)) // el dato queda atado a su nombre
        }
        val datos = cifrador.iv + cifrador.doFinal(valor.toByteArray(Charsets.UTF_8))
        prefs.edit { putString(nombre, Base64.encodeToString(datos, Base64.NO_WRAP)) }
    }

    /** null si no existe o no se puede descifrar (por ejemplo, la clave del Keystore se perdió). */
    fun leer(nombre: String): String? {
        val guardado = prefs.getString(nombre, null) ?: return null
        return runCatching {
            val datos = Base64.decode(guardado, Base64.NO_WRAP)
            val descifrador = Cipher.getInstance(TRANSFORMACION).apply {
                init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, datos, 0, LARGO_IV))
                updateAAD(nombre.toByteArray(Charsets.UTF_8))
            }
            String(descifrador.doFinal(datos, LARGO_IV, datos.size - LARGO_IV), Charsets.UTF_8)
        }.getOrNull()
    }

    fun existe(nombre: String): Boolean = prefs.contains(nombre)

    private fun clave(): SecretKey {
        val almacen = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (almacen.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generador.generateKey()
    }

    companion object {
        const val ARCHIVO = "cofre"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "cadapeso_cofre"
        private const val TRANSFORMACION = "AES/GCM/NoPadding"
        private const val LARGO_IV = 12

        const val TOKEN_SERVIDOR = "token_servidor"
        const val CREDENCIALES_GALICIA = "credenciales_galicia"
    }
}

package com.rommmobile.app.core.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small AES-GCM store backed by the Android Keystore. Replaces the deprecated
 * androidx.security EncryptedSharedPreferences with ~80 lines we fully control.
 * Values are stored as base64(iv || ciphertext) in a private SharedPreferences file.
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)
    private val lock = Any()

    fun put(key: String, value: String?) {
        synchronized(lock) {
            if (value == null) {
                prefs.edit().remove(key).apply()
                return
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = ByteArray(cipher.iv.size + ct.size)
            System.arraycopy(cipher.iv, 0, payload, 0, cipher.iv.size)
            System.arraycopy(ct, 0, payload, cipher.iv.size, ct.size)
            prefs.edit().putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        }
    }

    fun get(key: String): String? {
        synchronized(lock) {
            val raw = prefs.getString(key, null) ?: return null
            return try {
                val payload = Base64.decode(raw, Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload, 0, IV_SIZE))
                String(cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE), Charsets.UTF_8)
            } catch (t: GeneralSecurityException) {
                // Wrong key or tampered blob: the value is genuinely unrecoverable, drop it.
                // Keystore hiccups (device locked, provider busy) throw other types and must
                // NOT delete the credentials, or a transient failure would log the user out.
                prefs.edit().remove(key).apply()
                null
            } catch (t: IllegalArgumentException) {
                prefs.edit().remove(key).apply()
                null
            } catch (t: Throwable) {
                null
            }
        }
    }

    fun remove(vararg keys: String) {
        synchronized(lock) { prefs.edit().apply { keys.forEach { remove(it) } }.apply() }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "rommmobile_master_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

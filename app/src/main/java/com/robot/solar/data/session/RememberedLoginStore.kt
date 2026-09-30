package com.robot.solar.data.session

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 使用 Android Keystore 保存登录页的“记住登录信息”，不保存明文密码。 */
class RememberedLoginStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    fun load(): RememberedLogin? {
        val email = preferences.getString(KEY_EMAIL, null).orEmpty()
        val encrypted = preferences.getString(KEY_PASSWORD, null).orEmpty()
        val iv = preferences.getString(KEY_IV, null).orEmpty()
        if (email.isBlank() || encrypted.isBlank() || iv.isBlank()) return null
        return runCatching {
            RememberedLogin(email, decrypt(encrypted, iv))
        }.getOrElse {
            clear()
            null
        }
    }

    fun save(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) return
        val encrypted = encrypt(password)
        preferences.edit()
            .putString(KEY_EMAIL, email.trim())
            .putString(KEY_PASSWORD, encrypted.ciphertext)
            .putString(KEY_IV, encrypted.iv)
            .apply()
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_PASSWORD)
            .remove(KEY_IV)
            .apply()
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEY_STORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return EncryptedValue(
            ciphertext = encode(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))),
            iv = encode(cipher.iv)
        )
    }

    private fun decrypt(ciphertext: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, decode(iv))
        )
        return cipher.doFinal(decode(ciphertext)).toString(StandardCharsets.UTF_8)
    }

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "solar_robot_login_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFERENCES = "remembered_login"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_IV = "iv"
    }
}

data class RememberedLogin(
    val email: String,
    val password: String
)

private data class EncryptedValue(
    val ciphertext: String,
    val iv: String
)

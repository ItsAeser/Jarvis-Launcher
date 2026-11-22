package com.privateai.jarvis.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurityManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jarvis_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    fun isPinSet(): Boolean = prefs.contains("pin_hash")

    fun isBiometricEnabled(): Boolean = prefs.getBoolean("biometric_enabled", false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biometric_enabled", enabled).apply()
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hashPin(pin, salt)
        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        _isUnlocked.value = true
    }

    fun verifyPin(pin: String): Boolean {
        val saltB64 = prefs.getString("pin_salt", null) ?: return false
        val hashB64 = prefs.getString("pin_hash", null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expectedHash = Base64.decode(hashB64, Base64.NO_WRAP)
        val actualHash = hashPin(pin, salt)
        val match = MessageDigest.isEqual(expectedHash, actualHash)
        if (match) {
            _isUnlocked.value = true
        }
        return match
    }

    fun unlockViaBiometric() {
        if (isPinSet() && isBiometricEnabled()) {
            _isUnlocked.value = true
        }
    }

    fun lock() {
        _isUnlocked.value = false
    }

    // --- Cloud AI & Personalization Config (Encrypted in Keystore) ---

    fun getUserName(): String = prefs.getString("user_name", "Boss") ?: "Boss"

    fun setUserName(name: String) {
        prefs.edit().putString("user_name", name.trim().ifEmpty { "Boss" }).apply()
    }

    fun getApiBaseUrl(): String =
        prefs.getString("api_base_url", "https://openrouter.ai/api/v1") ?: "https://openrouter.ai/api/v1"

    fun setApiBaseUrl(url: String) {
        prefs.edit().putString("api_base_url", url.trim().removeSuffix("/")).apply()
    }

    fun getApiModel(): String =
        prefs.getString("api_model", "google/gemini-2.5-flash") ?: "google/gemini-2.5-flash"

    fun setApiModel(model: String) {
        prefs.edit().putString("api_model", model.trim()).apply()
    }

    fun getApiKey(): String = prefs.getString("api_key", "") ?: ""

    fun setApiKey(key: String) {
        prefs.edit().putString("api_key", key.trim()).apply()
    }

    fun isCloudConfigured(): Boolean = getApiKey().isNotBlank() && getApiBaseUrl().isNotBlank()

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(pin.toByteArray(Charsets.UTF_8))
    }

    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existingCipher = prefs.getString("db_key_cipher", null)
        val existingIv = prefs.getString("db_key_iv", null)
        val ksKey = getOrCreateKeystoreKey()

        if (existingCipher != null && existingIv != null) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                ksKey,
                GCMParameterSpec(128, Base64.decode(existingIv, Base64.NO_WRAP))
            )
            return cipher.doFinal(Base64.decode(existingCipher, Base64.NO_WRAP))
        }

        val rawPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, ksKey)
        val encrypted = cipher.doFinal(rawPassphrase)

        prefs.edit()
            .putString("db_key_cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("db_key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()

        return rawPassphrase
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val alias = "jarvis_db_master_key"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.getKey(alias, null)?.let { return it as SecretKey }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGen.init(spec)
        return keyGen.generateKey()
    }
}

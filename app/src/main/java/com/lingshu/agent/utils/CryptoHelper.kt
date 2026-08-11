package com.lingshu.agent.utils

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CryptoHelper @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "lingshu_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val SALT_LENGTH = 16
        private const val ITERATION_COUNT = 65536
        private const val KEY_LENGTH = 256
    }

    private val secureRandom: SecureRandom = SecureRandom()

    init {
        ensureMasterKey()
    }

    private fun ensureMasterKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    KEYSTORE_PROVIDER
                )

                val spec = KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).apply {
                    setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    setKeySize(KEY_LENGTH)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setUnlockedDeviceRequired(false)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setUserAuthenticationRequired(false)
                    }
                }.build()

                keyGenerator.init(spec)
                keyGenerator.generateKey()
            } else {
                val fallback = ByteArray(KEY_LENGTH / 8)
                secureRandom.nextBytes(fallback)
                val prefs = context.getSharedPreferences("lingshu_crypto_fallback", android.content.Context.MODE_PRIVATE)
                prefs.edit().putString("fallback_key", Base64.encodeToString(fallback, Base64.NO_WRAP)).apply()
            }
        }
    }

    private fun getMasterKey(): SecretKey {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        } else {
            val prefs = context.getSharedPreferences("lingshu_crypto_fallback", android.content.Context.MODE_PRIVATE)
            val keyStr = prefs.getString("fallback_key", null) ?: throw IllegalStateException("Fallback key not found")
            val keyBytes = Base64.decode(keyStr, Base64.NO_WRAP)
            SecretKeySpec(keyBytes, "AES")
        }
    }

    fun encrypt(plaintext: String): String {
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        val encrypted = encrypt(plainBytes)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun encrypt(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey(), gcmSpec)

        val ciphertext = cipher.doFinal(plainBytes)

        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)

        return result
    }

    fun decrypt(ciphertext: String): String {
        val cipherBytes = Base64.decode(ciphertext, Base64.NO_WRAP)
        val decrypted = decrypt(cipherBytes)
        return String(decrypted, Charsets.UTF_8)
    }

    fun decrypt(cipherBytes: ByteArray): ByteArray {
        if (cipherBytes.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Ciphertext too short")
        }

        val iv = cipherBytes.copyOfRange(0, GCM_IV_LENGTH)
        val actualCipher = cipherBytes.copyOfRange(GCM_IV_LENGTH, cipherBytes.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), gcmSpec)

        return cipher.doFinal(actualCipher)
    }

    fun encryptWithPassword(plaintext: String, password: String): String {
        val salt = generateSalt()
        val key = deriveKeyFromPassword(password, salt)
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val result = ByteArray(salt.size + iv.size + ciphertext.size)
        System.arraycopy(salt, 0, result, 0, salt.size)
        System.arraycopy(iv, 0, result, salt.size, iv.size)
        System.arraycopy(ciphertext, 0, result, salt.size + iv.size, ciphertext.size)

        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    fun decryptWithPassword(ciphertext: String, password: String): String {
        val data = Base64.decode(ciphertext, Base64.NO_WRAP)
        if (data.size < SALT_LENGTH + GCM_IV_LENGTH) {
            throw IllegalArgumentException("Ciphertext too short")
        }

        val salt = data.copyOfRange(0, SALT_LENGTH)
        val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + GCM_IV_LENGTH)
        val actualCipher = data.copyOfRange(SALT_LENGTH + GCM_IV_LENGTH, data.size)

        val key = deriveKeyFromPassword(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

        val decrypted = cipher.doFinal(actualCipher)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)
        return salt
    }

    fun sha256(input: String): String {
        return hash(input, "SHA-256")
    }

    fun sha512(input: String): String {
        return hash(input, "SHA-512")
    }

    fun hash(input: String, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(hashBytes.size * 2)
        hashBytes.forEach { b ->
            hex.append(String.format("%02x", b))
        }
        return hex.toString()
    }

    fun hashWithSalt(input: String, salt: ByteArray = generateSalt()): Pair<String, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(hashBytes.size * 2)
        hashBytes.forEach { b ->
            hex.append(String.format("%02x", b))
        }
        return hex.toString() to Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun verifyHashWithSalt(input: String, hash: String, saltStr: String): Boolean {
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(hashBytes.size * 2)
        hashBytes.forEach { b ->
            hex.append(String.format("%02x", b))
        }
        return hex.toString() == hash
    }

    fun generateSecureRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun generateSecureRandomString(length: Int): String {
        val bytes = generateSecureRandomBytes(length * 2)
        val hex = StringBuilder(length * 2)
        bytes.forEach { b ->
            hex.append(String.format("%02x", b))
        }
        return hex.toString().substring(0, length)
    }

    fun resetKeys() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        }
        val prefs = context.getSharedPreferences("lingshu_crypto_fallback", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        ensureMasterKey()
    }
}

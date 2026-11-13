package com.serkantkn.synapse.data.network.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores per-peer pairing tokens encrypted with a non-exportable Android Keystore key.
 * The prefs file only ever holds ciphertext, and it is excluded from backups; even if it leaked,
 * it can't be decrypted off this device because the Keystore key never leaves it.
 */
class PairingTokenStore(context: Context) {

    companion object {
        private const val TAG = "PairingTokenStore"
        const val PREFS_NAME = "synapse_secure_prefs"
        private const val KEY_ALIAS = "synapse_pairing_token_key"
        private const val ENTRY_PREFIX = "token_"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(deviceId: String): String? {
        val stored = prefs.getString("$ENTRY_PREFIX$deviceId", null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES))
            String(cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key lost (e.g. data restored onto another device): drop it, the peer re-pairs with a PIN.
            Log.w(TAG, "Stored token unreadable, discarding: ${e.javaClass.simpleName}")
            remove(deviceId)
            null
        }
    }

    fun put(deviceId: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("$ENTRY_PREFIX$deviceId", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun remove(deviceId: String) {
        prefs.edit().remove("$ENTRY_PREFIX$deviceId").apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}

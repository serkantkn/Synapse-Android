package com.serkantkn.synapse.data.network.crypto

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECParameterSpec
import java.security.KeyFactory
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Handles ECDH key exchange and AES-256-GCM encryption/decryption.
 *
 * IMPORTANT: Key derivation uses HKDF-SHA256 with salt "SynapseEncryption"
 * to match Apple CryptoKit's hkdfDerivedSymmetricKey on the macOS side.
 *
 * Public keys are exchanged in x963 (uncompressed point) format to match
 * CryptoKit's P256.KeyAgreement.PublicKey.x963Representation.
 *
 * Flow:
 * 1. Each side generates an ECDH key pair (P-256 / secp256r1)
 * 2. Public keys are exchanged as Base64-encoded x963 bytes during handshake
 * 3. Both sides derive the same shared secret via ECDH
 * 4. HKDF-SHA256 derives a 256-bit AES key from the shared secret
 * 5. All subsequent data is encrypted with AES-256-GCM
 */
object CryptoManager {

    private const val EC_CURVE = "secp256r1"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val HKDF_SALT = "SynapseEncryption"

    /**
     * Generates an ECDH key pair for this device.
     */
    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec(EC_CURVE))
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Encodes a public key to a Base64 string in x963 (uncompressed point) format.
     * Format: 0x04 || X (32 bytes) || Y (32 bytes) = 65 bytes total.
     * This matches CryptoKit's P256.KeyAgreement.PublicKey.x963Representation.
     */
    fun encodePublicKey(publicKey: java.security.PublicKey): String {
        val ecKey = publicKey as ECPublicKey
        val point = ecKey.w
        val x = toFixedLengthBytes(point.affineX, 32)
        val y = toFixedLengthBytes(point.affineY, 32)

        // x963 uncompressed format: 0x04 + X + Y
        val x963 = ByteArray(65)
        x963[0] = 0x04
        System.arraycopy(x, 0, x963, 1, 32)
        System.arraycopy(y, 0, x963, 33, 32)

        return Base64.getEncoder().encodeToString(x963)
    }

    /**
     * Decodes a Base64-encoded x963 public key back to a PublicKey object.
     */
    fun decodePublicKey(base64Key: String): java.security.PublicKey {
        val x963 = Base64.getDecoder().decode(base64Key)
        require(x963.size == 65 && x963[0] == 0x04.toByte()) {
            "Invalid x963 public key format"
        }

        val xBytes = x963.copyOfRange(1, 33)
        val yBytes = x963.copyOfRange(33, 65)

        val x = java.math.BigInteger(1, xBytes)
        val y = java.math.BigInteger(1, yBytes)
        val point = ECPoint(x, y)

        // Get the EC parameter spec from a temporary key pair
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE))
        val tempKeyPair = kpg.generateKeyPair()
        val ecParams = (tempKeyPair.public as ECPublicKey).params

        val pubKeySpec = ECPublicKeySpec(point, ecParams)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(pubKeySpec)
    }

    /**
     * Performs ECDH key agreement and derives a shared AES-256 key using HKDF.
     * Uses the same parameters as macOS CryptoKit's hkdfDerivedSymmetricKey:
     * - Hash: SHA-256
     * - Salt: "SynapseEncryption"
     * - Info: empty
     * - Output: 32 bytes (256-bit AES key)
     */
    fun deriveSharedSecret(myPrivateKey: java.security.PrivateKey, theirPublicKey: java.security.PublicKey): SecretKey {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(theirPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Derive key using HKDF-SHA256 matching CryptoKit
        val aesKeyBytes = hkdfSha256(
            ikm = sharedSecret,
            salt = HKDF_SALT.toByteArray(Charsets.UTF_8),
            info = ByteArray(0),
            length = 32
        )
        return SecretKeySpec(aesKeyBytes, "AES")
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     * Returns IV (12 bytes) + ciphertext + GCM tag concatenated.
     */
    fun encrypt(data: ByteArray, secretKey: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val cipherText = cipher.doFinal(data)
        // Prepend IV to ciphertext
        return iv + cipherText
    }

    /**
     * Decrypts data that was encrypted with [encrypt].
     * Expects IV (12 bytes) + ciphertext + tag.
     */
    fun decrypt(encryptedData: ByteArray, secretKey: SecretKey): ByteArray {
        val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(cipherText)
    }

    // ---- HKDF Implementation (RFC 5869) ----

    /**
     * HKDF-SHA256: Extract-then-Expand key derivation.
     * Matches Apple CryptoKit's HKDF implementation.
     */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Step 1: Extract — PRK = HMAC-SHA256(salt, IKM)
        val prk = hmacSha256(key = salt, data = ikm)

        // Step 2: Expand — derive output key material
        return hkdfExpand(prk = prk, info = info, length = length)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hashLen = 32 // SHA-256 output
        val n = (length + hashLen - 1) / hashLen
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0

        for (i in 1..n) {
            val input = t + info + byteArrayOf(i.toByte())
            t = hmacSha256(key = prk, data = input)
            val copyLen = minOf(hashLen, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
        }

        return okm
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key, "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    /**
     * Converts a BigInteger to a fixed-length byte array,
     * padding with leading zeros or trimming the leading sign byte as needed.
     */
    private fun toFixedLengthBytes(value: java.math.BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
            else -> ByteArray(length - bytes.size) + bytes
        }
    }
}


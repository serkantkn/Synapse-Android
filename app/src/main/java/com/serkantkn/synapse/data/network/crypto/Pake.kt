package com.serkantkn.synapse.data.network.crypto

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PIN pairing without an offline-guessable proof (CPace-style PAKE on P-256).
 *
 * The old PIN proof was an HMAC over public data, so anyone who captured it could try all
 * 1,000,000 PINs offline. Here the PIN only picks the curve point both sides use for one extra
 * Diffie-Hellman exchange. An eavesdropper can't check a PIN guess without breaking that
 * exchange, and an active attacker gets one guess per attempt, which the peer's attempt limit caps.
 *
 * Messages (this phone always initiates):
 * 1. phone → peer  HANDSHAKE_REQUEST   auth_mode "pake", public_key, pake_share (Ya)
 * 2. peer → phone  HANDSHAKE_RESPONSE  status "CHALLENGE", public_key, pake_share (Yb), pake_confirm (Cb)
 * 3. phone → peer  HANDSHAKE_CONFIRM   pake_confirm (Ca)
 * 4. peer → phone  HANDSHAKE_RESPONSE  status "ACCEPTED", public_key
 *
 * The resulting key (ISK) takes the PIN's place in the session-key derivation.
 * Byte-for-byte the same as Pake.swift in the macOS and iOS apps.
 */
object Pake {
    const val AUTH_MODE = "pake"
    const val ROLE_INITIATOR = "initiator"
    const val ROLE_RESPONDER = "responder"

    private const val LABEL = "synapse-cpace-v1"
    /** Candidates tried for the generator; each is on the curve with probability ~1/2. */
    private const val GENERATOR_ATTEMPTS = 64

    private val params: ECParameterSpec by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        (generator.generateKeyPair().public as ECPublicKey).params
    }

    /**
     * A curve point picked by the PIN and the initiator's session key. Nobody knows its
     * discrete logarithm, which is what keeps PIN guesses from being checked offline.
     */
    fun generator(pin: String, initiatorKey: String): ECPoint {
        var found: ECPoint? = null
        // Every candidate is tried, so the time taken doesn't depend on the PIN.
        for (counter in 0 until GENERATOR_ATTEMPTS) {
            val x = sha256(
                "$LABEL:generator".toByteArray(Charsets.UTF_8) +
                    byteArrayOf(counter.toByte()) +
                    lengthPrefixed(pin.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(initiatorKey.toByteArray(Charsets.UTF_8))
            )
            val candidate = pointOrNull(x)
            if (found == null) found = candidate
        }
        return found ?: throw GeneralSecurityException("No PAKE generator")
    }

    /**
     * One side of the exchange: a fresh secret scalar and the [share] sent to the peer.
     * [scalar] is only passed in tests; otherwise every exchange gets a fresh one.
     */
    class Party(
        private val role: String,
        pin: String,
        private val initiatorKey: String,
        private val scalar: PrivateKey = newScalar(),
    ) {
        val share: ByteArray = xCoordinate(scalar, generator(pin, initiatorKey))

        /** The shared key (ISK). Both sides get the same value only if they used the same PIN. */
        fun sharedKey(peerShare: ByteArray, responderKey: String): ByteArray {
            if (peerShare.size != 32) throw GeneralSecurityException("Invalid PAKE share")
            val peerPoint = pointOrNull(peerShare) ?: throw GeneralSecurityException("Invalid PAKE share")
            val k = xCoordinate(scalar, peerPoint)
            val initiatorShare = if (role == ROLE_INITIATOR) share else peerShare
            val responderShare = if (role == ROLE_INITIATOR) peerShare else share
            return sha256(
                "$LABEL:isk".toByteArray(Charsets.UTF_8) +
                    lengthPrefixed(initiatorKey.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(responderKey.toByteArray(Charsets.UTF_8)) +
                    lengthPrefixed(initiatorShare) +
                    lengthPrefixed(responderShare) +
                    lengthPrefixed(k)
            )
        }
    }

    /** Proves to the peer that [role] derived the same key. */
    fun confirmation(key: ByteArray, role: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal("$LABEL:confirm:$role".toByteArray(Charsets.UTF_8))
    }

    /** Constant-time check of the peer's confirmation. */
    fun verifyConfirmation(confirmation: ByteArray, key: ByteArray, role: String): Boolean =
        MessageDigest.isEqual(confirmation, confirmation(key, role))

    /** A random P-256 scalar (the private half of a throwaway key pair). */
    fun newScalar(): PrivateKey {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair().private
    }

    /** P-256 scalar from 32 big-endian bytes, for test vectors. */
    internal fun scalarOf(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(
            java.security.spec.ECPrivateKeySpec(BigInteger(1, bytes), params)
        )

    // ---- Helpers ----

    /** The curve point with this x and an even y, or null when x isn't on the curve. */
    internal fun pointOrNull(xBytes: ByteArray): ECPoint? {
        val p = (params.curve.field as ECFieldFp).p
        val x = BigInteger(1, xBytes)
        if (x >= p) return null
        val rhs = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        if (rhs.signum() == 0) return null // P-256 has no such point (prime order)
        // Euler's criterion: rhs must be a square mod p.
        if (rhs.modPow(p.subtract(BigInteger.ONE).shiftRight(1), p) != BigInteger.ONE) return null
        // p ≡ 3 (mod 4), so the square root is rhs^((p+1)/4).
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        if (y.testBit(0)) y = p.subtract(y)
        return ECPoint(x, y)
    }

    /**
     * x(scalar · point). Only the x coordinate is exchanged, which is all ECDH exposes; the
     * sign of y doesn't change the x of a multiple, so both sides still agree.
     */
    private fun xCoordinate(scalar: PrivateKey, point: ECPoint): ByteArray {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(scalar)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun lengthPrefixed(data: ByteArray): ByteArray {
        val n = data.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + data
    }
}

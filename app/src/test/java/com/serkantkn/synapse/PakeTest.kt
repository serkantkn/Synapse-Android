package com.serkantkn.synapse

import com.serkantkn.synapse.data.network.crypto.Pake
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PAKE must match the Swift implementation in the macOS and iOS apps byte for byte.
 * The expected values below come from running Pake.swift with the same inputs.
 */
class PakeTest {

    private val pin = "314159"
    private val initiatorKey = "BInitiatorSessionKeyBase64=="
    private val responderKey = "BResponderSessionKeyBase64=="
    private val ya = Pake.scalarOf(ByteArray(32) { 0x11 })
    private val yb = Pake.scalarOf(ByteArray(32) { 0x22 })

    @Test
    fun matchesSwiftTestVector() {
        val g = Pake.generator(pin, initiatorKey)
        assertEquals("33326e3487d6f348afbd68a522bee4a2d831e92604c35263c2867010e7646d2d", hex(g.affineX, 32))
        assertFalse("generator must have even y", g.affineY.testBit(0))

        val a = Pake.Party(Pake.ROLE_INITIATOR, pin, initiatorKey, ya)
        val b = Pake.Party(Pake.ROLE_RESPONDER, pin, initiatorKey, yb)
        assertEquals("7d8056069f6c1119d9e22d8e0e27b8051378e7ccd664c1b33b045040f1dc72f0", hex(a.share))
        assertEquals("30484b77063e0bfd915162f66b7e78374f9add8601e963a14491919e46ab3e86", hex(b.share))

        val iskA = a.sharedKey(b.share, responderKey)
        val iskB = b.sharedKey(a.share, responderKey)
        assertArrayEquals(iskA, iskB)
        assertEquals("a2b4fca75ab6a7ab5d6afce7e232e578880ba0b3a823b82e2c4e7d03d9fbd1cb", hex(iskA))
        assertEquals(
            "a4a84bcee964eaaed02cc2de6539d67723698e5c37cd37bd4d794faaec41c6d1",
            hex(Pake.confirmation(iskA, Pake.ROLE_INITIATOR))
        )
        assertEquals(
            "5ed573d13501f31ead3bd338fb4180c70e3a8b44565f441959672ba6e91638d0",
            hex(Pake.confirmation(iskB, Pake.ROLE_RESPONDER))
        )
    }

    @Test
    fun wrongPinIsRejected() {
        val b = Pake.Party(Pake.ROLE_RESPONDER, pin, initiatorKey, yb)
        val wrong = Pake.Party(Pake.ROLE_INITIATOR, "314158", initiatorKey, ya)
        val iskWrong = wrong.sharedKey(b.share, responderKey)
        val iskB = b.sharedKey(wrong.share, responderKey)
        assertFalse(iskWrong.contentEquals(iskB))
        assertFalse(
            Pake.verifyConfirmation(Pake.confirmation(iskWrong, Pake.ROLE_INITIATOR), iskB, Pake.ROLE_INITIATOR)
        )
        // The responder's confirmation tells the phone the PIN was wrong before it sends anything.
        assertFalse(
            Pake.verifyConfirmation(Pake.confirmation(iskB, Pake.ROLE_RESPONDER), iskWrong, Pake.ROLE_RESPONDER)
        )
    }

    @Test
    fun randomScalarsAgree() {
        repeat(50) { i ->
            val p = "%06d".format(i * 4999 % 1_000_000)
            val a = Pake.Party(Pake.ROLE_INITIATOR, p, initiatorKey)
            val b = Pake.Party(Pake.ROLE_RESPONDER, p, initiatorKey)
            val iskA = a.sharedKey(b.share, responderKey)
            val iskB = b.sharedKey(a.share, responderKey)
            assertArrayEquals(iskA, iskB)
            assertTrue(Pake.verifyConfirmation(Pake.confirmation(iskB, Pake.ROLE_RESPONDER), iskA, Pake.ROLE_RESPONDER))
        }
    }

    @Test
    fun rejectsSharesOffTheCurve() {
        val a = Pake.Party(Pake.ROLE_INITIATOR, pin, initiatorKey, ya)
        // Find an x that isn't on the curve.
        var x = 1
        while (Pake.pointOrNull(ByteArray(31) + byteArrayOf(x.toByte())) != null) x++
        val bad = ByteArray(31) + byteArrayOf(x.toByte())
        val failed = runCatching { a.sharedKey(bad, responderKey) }.isFailure
        assertTrue(failed)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun hex(value: java.math.BigInteger, length: Int): String {
        val raw = value.toByteArray().let { if (it.size > length) it.copyOfRange(it.size - length, it.size) else it }
        return hex(ByteArray(length - raw.size) + raw)
    }
}

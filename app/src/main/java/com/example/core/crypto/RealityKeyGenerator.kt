package com.example.core.crypto

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom
import java.util.UUID

data class RealityKeyPair(
    val privateKey: String,
    val publicKey: String,
    val uuid: String,
    val shortId: String,
)

/**
 * Pure RFC 7748 X25519 Curve25519 key generator compatible with `xray x25519`
 * across all Android API levels (minSdk 24+).
 */
object RealityKeyGenerator {
    private val random = SecureRandom()
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)

    fun generateBundle(): RealityKeyPair {
        val priv = ByteArray(32)
        random.nextBytes(priv)
        // RFC 7748 clamping
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = (priv[31].toInt() and 127).toByte()
        priv[31] = (priv[31].toInt() or 64).toByte()

        val pub = x25519BasePoint(priv)
        return RealityKeyPair(
            privateKey = encodeBase64UrlNoPad(priv),
            publicKey = encodeBase64UrlNoPad(pub),
            uuid = UUID.randomUUID().toString(),
            shortId = generateShortId(8),
        )
    }

    fun generateUuid(): String = UUID.randomUUID().toString()

    fun generateShortId(byteLength: Int = 8): String {
        val clamped = byteLength.coerceIn(1, 8)
        val bytes = ByteArray(clamped)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidRealityPublicKey(pbk: String): Boolean {
        if (pbk.isBlank()) return false
        return try {
            val normalized = pbk.trim().replace('+', '-').replace('/', '_')
            val decoded = Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_PADDING)
            decoded.size == 32
        } catch (_: Exception) {
            false
        }
    }

    fun isValidShortId(sid: String): Boolean {
        if (sid.isEmpty()) return true
        return sid.length <= 16 && sid.length % 2 == 0 && sid.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun encodeBase64UrlNoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed)
    }

    private fun encodeLittleEndian32(value: BigInteger): ByteArray {
        val reduced = value.mod(P)
        val raw = reduced.toByteArray()
        val out = ByteArray(32)
        var outIdx = 0
        for (i in raw.indices.reversed()) {
            if (outIdx < 32) {
                out[outIdx++] = raw[i]
            }
        }
        return out
    }

    private fun x25519BasePoint(scalarBytes: ByteArray): ByteArray {
        val k = decodeLittleEndian(scalarBytes)
        val u = BigInteger.valueOf(9)

        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            val daPlusCb = da.add(cb).mod(P)
            val daMinusCb = da.subtract(cb).mod(P)
            x3 = daPlusCb.multiply(daPlusCb).mod(P)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P)
        }

        if (swap != 0) {
            x2 = x3
            z2 = z3
        }

        val z2Inv = z2.modPow(P.subtract(BigInteger.valueOf(2)), P)
        val result = x2.multiply(z2Inv).mod(P)
        return encodeLittleEndian32(result)
    }
}

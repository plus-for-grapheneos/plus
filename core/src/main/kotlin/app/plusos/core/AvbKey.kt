// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** The AVB public key blob (`avb_custom_key`, `avbroot key encode-avb`): libavb's AvbRSAPublicKeyHeader plus n and rr. */
object AvbKey {
    fun encode(pub: RSAPublicKey): ByteArray {
        val n = pub.modulus
        val bits = n.bitLength()
        require(bits % 32 == 0) { "modulus size must be a multiple of 32 bits" }
        val len = bits / 8
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32))
        val rr = BigInteger.ONE.shiftLeft(bits).modPow(BigInteger.TWO, n)
        return ByteBuffer.allocate(8 + 2 * len)
            .putInt(bits)
            .putInt(n0inv.toInt())
            .put(fixed(n, len))
            .put(fixed(rr, len))
            .array()
    }

    /** First 8 bytes of SHA-256 over [encode]: identifies the owner key in the backup text and on screen. */
    fun fingerprint(pub: RSAPublicKey): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(encode(pub)).copyOf(8)

    internal fun fixed(v: BigInteger, len: Int): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == len -> raw
            raw.size == len + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < len -> ByteArray(len - raw.size) + raw
            else -> throw IllegalArgumentException("value does not fit in $len bytes")
        }
    }
}

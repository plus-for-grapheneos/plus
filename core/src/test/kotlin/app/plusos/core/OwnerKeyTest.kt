// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.security.Signature
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OwnerKeyTest {
    private val key = OwnerKey.generate()

    @Test
    fun rebuild_from_p_q_matches_generated() {
        val rebuilt = OwnerKey(key.p, key.q)
        assertEquals(key.publicKey.modulus, rebuilt.publicKey.modulus)
        assertArrayEquals(sign(key), sign(rebuilt))
    }

    @Test
    fun primes_are_256_bytes() {
        assertEquals(256, unsigned(key.p).size)
        assertEquals(256, unsigned(key.q).size)
        assertEquals(4096, key.publicKey.modulus.bitLength())
    }

    @Test
    fun signDigestInfo_equals_sha256withRsa() {
        val msg = "vbmeta test block".toByteArray()
        val di = SHA256_DIGEST_INFO_PREFIX + java.security.MessageDigest.getInstance("SHA-256").digest(msg)
        assertArrayEquals(sign(key), key.signDigestInfo(di))
    }

    private fun sign(k: OwnerKey): ByteArray = Signature.getInstance("SHA256withRSA").run {
        initSign(k.privateKey); update("vbmeta test block".toByteArray()); sign()
    }

    private fun unsigned(v: java.math.BigInteger): ByteArray =
        v.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    companion object {
        val SHA256_DIGEST_INFO_PREFIX = java.util.HexFormat.of().parseHex("3031300d060960864801650304020105000420")
    }
}

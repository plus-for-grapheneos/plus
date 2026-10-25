// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AvbKeyTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/avb/$name")!!.readBytes()

    private fun fixturePublicKey(): RSAPublicKey {
        val pem = String(resource("owner_pub.pem")).lines().filter { !it.startsWith("-----") }.joinToString("")
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pem))) as RSAPublicKey
    }

    @Test
    fun avb_encode_matches_avbroot_fixture() {
        assertArrayEquals(resource("owner_pkmd.bin"), AvbKey.encode(fixturePublicKey()))
    }

    @Test
    fun fingerprint_is_first_8_bytes_of_sha256() {
        val fp = AvbKey.fingerprint(fixturePublicKey())
        assertEquals(8, fp.size)
        val full = java.security.MessageDigest.getInstance("SHA-256").digest(resource("owner_pkmd.bin"))
        assertArrayEquals(full.copyOf(8), fp)
    }
}

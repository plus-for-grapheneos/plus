// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OwnerCertTest {
    private val key = OwnerKey.generate()

    @Test
    fun cert_is_deterministic() {
        assertArrayEquals(OwnerCert.derive(key).encoded, OwnerCert.derive(OwnerKey(key.p, key.q)).encoded)
    }

    @Test
    fun cert_public_key_matches() {
        val cert = OwnerCert.derive(key)
        assertArrayEquals(key.publicKey.encoded, cert.publicKey.encoded)
        cert.verify(key.publicKey)
        assertEquals("CN=\\+PLUS owner", cert.subjectX500Principal.name) // RFC 2253 escapes "+"
    }

    /** RFC 5280: dates before 2050 MUST be UTCTime; avbroot re-encodes otherwise and then rejects its own signature. */
    @Test
    fun validity_dates_are_utctime() {
        val der = OwnerCert.derive(key).encoded
        val notAfter = byteArrayOf(0x17, 0x0d) + "491231235959Z".toByteArray()
        val found = (0..der.size - notAfter.size).any { i -> notAfter.indices.all { der[i + it] == notAfter[it] } }
        assertEquals(true, found, "notAfter must be UTCTime 491231235959Z")
    }

    @Test
    fun pem_round_trip() {
        val cert = OwnerCert.derive(key)
        assertArrayEquals(cert.encoded, OwnerCert.fromPem(OwnerCert.toPem(cert)).encoded)
    }

    @Test
    fun pins_match_recorded_hashes() {
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        assertEquals("9e6a8f3e0d761a780179f93acd5721ba1ab7c8c537c7761073c0a754b0e932de", sha(Pins.forDevice("tokay")!!.avbPkmd))
        assertEquals("CN=GrapheneOS", Pins.forDevice("tokay")!!.otaCert.subjectX500Principal.name)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.security.KeyPairGenerator
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * T5 (+PLUS 13): a catalog link carries the SHA-256 of the catalog's public signing key in its fragment,
 * `https://host/path/index.json#sha256=<64 hex>`, so a catalog can be hosted anywhere and re-keyed by handing out a link.
 */
class CatalogLinkTest {
    private val hash = "ab".repeat(32)

    @Test
    fun a_link_names_the_catalog_file_its_folder_and_the_key_hash() {
        val l = CatalogLink.parse("https://example.org/plus/index.json#sha256=$hash")
        assertEquals("https://example.org/plus/index.json", l.indexUrl)
        assertEquals("https://example.org/plus/", l.base)
        assertEquals(hash, l.keySha256)
        assertEquals("https://example.org/plus/index.json#sha256=$hash", l.toString())
    }

    @Test
    fun the_hash_is_case_insensitive_and_spaces_around_the_link_are_ignored() {
        assertEquals(hash, CatalogLink.parse("  https://example.org/index.json#sha256=${hash.uppercase()} \n").keySha256)
    }

    /** The catalog is signed, so plain http is fine for an IP address (a LAN host has no certificate anyway). */
    @Test
    fun plain_http_for_an_ip_address_in_every_build() {
        assertEquals("http://10.0.2.2:8801/", CatalogLink.parse("http://10.0.2.2:8801/index.json#sha256=$hash").base)
        assertEquals("http://10.0.2.2/", CatalogLink.parse("http://10.0.2.2/index.json#sha256=$hash").base)
        assertEquals("http://[fd00::1]:8801/", CatalogLink.parse("http://[fd00::1]:8801/index.json#sha256=$hash").base)
    }

    @Test
    fun plain_http_for_a_named_host_only_where_allowed() {
        val e = assertThrows<CatalogLink.InvalidLink> { CatalogLink.parse("http://example.org/index.json#sha256=$hash") }
        assertTrue("IP address" in e.message!!, e.message)
        assertEquals("http://example.org/", CatalogLink.parse("http://example.org/index.json#sha256=$hash", allowHttp = true).base)
    }

    @Test
    fun names_that_only_look_like_ip_addresses_need_https() {
        for (host in listOf("203.0.113.999", "1.2.3", "1.2.3.4.example.org", "01x.2.3.4")) {
            assertThrows<CatalogLink.InvalidLink>(host) { CatalogLink.parse("http://$host/index.json#sha256=$hash") }
        }
    }

    @Test
    fun a_link_without_a_proper_key_hash_is_refused() {
        for (bad in listOf(
            "https://example.org/index.json",
            "https://example.org/index.json#sha256=abc",
            "https://example.org/index.json#sha256=" + "zz".repeat(32),
            "https://example.org/index.json#sha1=$hash",
            "https://example.org/#sha256=$hash",
            "https://user@example.org/index.json#sha256=$hash",
            "https://example.org/index.json?x=1#sha256=$hash",
            "ftp://example.org/index.json#sha256=$hash",
            "",
        )) assertThrows<CatalogLink.InvalidLink>(bad) { CatalogLink.parse(bad, allowHttp = true) }
    }

    @Test
    fun the_key_hash_is_the_sha256_of_the_public_keys_der_encoding() {
        val pub = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public
        val expected = MessageDigest.getInstance("SHA-256").digest(pub.encoded).joinToString("") { "%02x".format(it) }
        assertEquals(expected, CatalogLink.keySha256(pub.encoded))
    }
}

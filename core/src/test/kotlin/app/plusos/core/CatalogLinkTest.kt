// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.security.KeyPairGenerator
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun plain_http_only_where_allowed() {
        assertThrows<CatalogLink.InvalidLink> { CatalogLink.parse("http://10.0.2.2:8800/index.json#sha256=$hash") }
        assertEquals("http://10.0.2.2:8800/", CatalogLink.parse("http://10.0.2.2:8800/index.json#sha256=$hash", allowHttp = true).base)
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

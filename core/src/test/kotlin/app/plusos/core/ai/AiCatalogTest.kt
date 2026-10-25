// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.ai

import app.plusos.core.IndexException
import java.security.KeyPairGenerator
import java.security.Signature
import app.plusos.core.CatalogLink
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private val NOW = Instant.parse("2026-10-24T00:00:00Z")

class AiCatalogTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
    private val gemma = AiModel(
        id = "gemma-4-e2b", name = "Gemma 4 E2B", file = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", size = 2_588_147_712,
        license = "Apache-2.0", licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
        contextTokens = 4096, backends = listOf("gpu", "cpu"),
    )

    private fun catalog(serial: Long, models: List<AiModel>, expires: String = "2026-11-23T00:00:00Z") =
        AiCatalog(serial = serial, models = models, publicKey = java.util.Base64.getEncoder().encodeToString(key.public.encoded),
            expires = expires)

    private fun signed(c: AiCatalog): Pair<ByteArray, ByteArray> {
        val b = c.toJson().toByteArray()
        return b to Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(b); sign() }
    }

    @Test
    fun catalog_verifies_and_round_trips() {
        val (b, s) = signed(catalog(3, listOf(gemma)))
        val c = AiCatalog.verify(b, s, CatalogLink.keySha256(key.public.encoded), lastSerial = 2, now = NOW)
        assertEquals(gemma, c.models.single())
        assertEquals(gemma, c.model("gemma-4-e2b"))
    }

    @Test
    fun older_catalog_refused() {
        val (b, s) = signed(catalog(1, listOf(gemma)))
        assertEquals(IndexException.Reason.ROLLBACK,
            assertThrows<IndexException> { AiCatalog.verify(b, s, CatalogLink.keySha256(key.public.encoded), lastSerial = 2, now = NOW) }.reason)
    }

    @Test
    fun bad_signature_refused() {
        val (b, s) = signed(catalog(1, listOf(gemma)))
        s[3] = (s[3] + 1).toByte()
        assertEquals(IndexException.Reason.SIGNATURE,
            assertThrows<IndexException> { AiCatalog.verify(b, s, CatalogLink.keySha256(key.public.encoded), null, NOW) }.reason)
    }

    @Test
    fun model_file_name_must_be_plain() {
        assertThrows<IllegalArgumentException> { gemma.copy(file = "../../evil.litertlm").checked() }
        assertThrows<IllegalArgumentException> { gemma.copy(url = "http://example.com/x.litertlm").checked() }
        assertEquals(gemma, gemma.checked())
    }

    @Test
    fun expired_catalog_refused() {
        val (b, s) = signed(catalog(3, listOf(gemma), expires = "2026-10-01T00:00:00Z"))
        assertEquals(IndexException.Reason.EXPIRED,
            assertThrows<IndexException> { AiCatalog.verify(b, s, CatalogLink.keySha256(key.public.encoded), null, NOW) }.reason)
    }
}

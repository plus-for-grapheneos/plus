// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.ai

import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import app.plusos.core.CatalogLink
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private val NOW = Instant.parse("2026-10-24T00:00:00Z")

/** `plusbuild publish-models` (ai-service-design §3, §6). */
class ModelPublisherTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
    private val site: File = Files.createTempDirectory("site").toFile()
    private val gemma = AiModel(
        id = "gemma-4-e2b", name = "Gemma 4 E2B", file = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", size = 2_588_147_712,
        license = "Apache-2.0", licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
        contextTokens = 4096, backends = listOf("gpu", "cpu"),
    )

    @AfterEach
    fun cleanup() { site.deleteRecursively() }

    @Test
    fun published_catalog_verifies_and_serial_increases() {
        val first = ModelPublisher.publish(listOf(gemma), key.private, site, NOW)
        assertEquals(1L, first.serial)
        val again = ModelPublisher.publish(listOf(gemma), key.private, site, NOW)
        assertEquals(2L, again.serial)
        val c = AiCatalog.verify(File(site, "models.json").readBytes(), File(site, "models.json.sig").readBytes(),
            CatalogLink.keySha256(key.public.encoded), lastSerial = 1, now = NOW)
        assertEquals(listOf(gemma), c.models)
    }

    @Test
    fun invalid_model_is_not_published() {
        assertThrows<IllegalArgumentException> { ModelPublisher.publish(listOf(gemma.copy(url = "http://example.com/m.litertlm")), key.private, site) }
        assertFalse(File(site, "models.json").exists())
    }

    @Test
    fun duplicate_ids_are_not_published() {
        assertThrows<IllegalArgumentException> { ModelPublisher.publish(listOf(gemma, gemma.copy(name = "again")), key.private, site) }
    }

    @Test
    fun draft_reads_a_list_of_models() {
        val draft = File(site, "draft.json").apply { writeText("[" + AiCatalog(serial = 0, models = listOf(gemma)).toJson().let {
            it.substringAfter("\"models\": [").substringBeforeLast("]") } + "]") }
        assertEquals(listOf(gemma), ModelPublisher.readDraft(draft))
    }
}

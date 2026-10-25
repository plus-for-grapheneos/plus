// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublishTest {
    private val dir: File = Files.createTempDirectory("pub").toFile()
    private val apks = File(dir, "apks").apply { mkdirs() }
    private val site = File(dir, "site")
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()

    private val phone = File(apks, "PlusPhone.apk").apply { writeText("phone apk bytes") }
    private val info = mapOf(phone.name to Publisher.ApkInfo("app.plusos.phone", 31, "ab".repeat(32)))
    private val draft = Publisher.Draft(
        repo = "plus-main",
        apps = listOf(Publisher.DraftApp(phone.name, addon = "callscreen-phone", privileged = true,
            privappPermissions = listOf("android.permission.CAPTURE_AUDIO_OUTPUT"))),
    )

    @AfterEach
    fun cleanup() { dir.deleteRecursively() }

    private fun publish() = Publisher { f -> info.getValue(f.name) }.publish(draft, apks, key.private, site, "2026-10-22T00:00:00Z")

    @Test
    fun published_index_verifies() {
        publish()
        val index = RepoIndexVerifier.verify(File(site, "index.json").readBytes(), File(site, "index.json.sig").readBytes(),
            CatalogLink.keySha256(key.public.encoded), null, Instant.parse("2026-10-23T00:00:00Z"))
        val app = index.apps.single()
        assertEquals("app.plusos.phone", app.packageName)
        assertEquals(31, app.version)
        assertEquals(Publisher.sha256(phone), app.sha256)
        assertEquals("ab".repeat(32), app.signerSha256)
        assertEquals("app.plusos.phone-31.apk", app.apk)
        assertTrue(File(site, app.apk).exists())
    }

    @Test
    fun republishing_bumps_serial() {
        publish()
        val first = RepoIndex.JSON.decodeFromString(RepoIndex.serializer(), File(site, "index.json").readText()).serial
        publish()
        val second = RepoIndex.JSON.decodeFromString(RepoIndex.serializer(), File(site, "index.json").readText()).serial
        assertEquals(first + 1, second)
    }

    /** T5: the index carries its key and an expiry 28 days after publishing; the publisher prints the link. */
    @Test
    fun index_carries_its_key_an_expiry_and_a_ready_made_link() {
        val index = publish()
        assertEquals("2026-11-19T00:00:00Z", index.expires)
        val link = Publisher.link("https://example.org/plus/", key.private)
        assertEquals("https://example.org/plus/index.json#sha256=" + CatalogLink.keySha256(key.public.encoded), link)
        assertEquals(CatalogLink.keySha256(key.public.encoded), CatalogLink.parse(link).keySha256)
    }
}

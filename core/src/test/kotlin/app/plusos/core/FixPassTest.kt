// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import app.plusos.core.sign.SignProtocol
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Tests for the findings of the 2026-10-22 whole-branch review (C1, C4, I3, I4, I5, I6, I8, I1). */
class FixPassTest {
    private val dir: File = Files.createTempDirectory("fix").toFile()

    @AfterEach
    fun cleanup() { dir.deleteRecursively() }

    private fun app(pkg: String, addon: String, version: Int, privileged: Boolean = true) = RepoApp(
        packageName = pkg, addon = addon, version = version, apk = "$pkg-$version.apk", sha256 = "s", signerSha256 = "aa",
        privileged = privileged,
    )

    private val phone = app("app.plusos.phone", "callscreen-phone", 2900000)
    private val transcriber = app("app.plusos.phone.transcriber", "callscreen-phone", 37, privileged = false)
    private val index = RepoIndex(repo = "plus-dev", published = "p", serial = 2, apps = listOf(app("app.seamlessupdate.client", "plus", 2), phone, transcriber))

    private fun imageManifest() = BuildManifest(
        device = "tokay", grapheneos = BuildManifest.Os("2026101600", "x"), ownerKeyFingerprint = "f",
        installer = BuildManifest.Installer(2, "i"),
        addons = listOf(BuildManifest.Addon("callscreen-phone", 2900000, listOf(
            BuildManifest.Apk("app.plusos.phone", "s", AddonPlan.imagePath(phone), version = 2900000),
            BuildManifest.Apk("app.plusos.phone.transcriber", "s", AddonPlan.imagePath(transcriber), version = 37),
        ))),
    )

    // C1: each APK keeps its own version, so image and wanted agree and nothing rebuilds.
    @Test
    fun image_apps_use_each_apks_own_version() {
        val inImage = imageManifest().apps()
        assertEquals(mapOf("app.seamlessupdate.client" to 2, "app.plusos.phone" to 2900000, "app.plusos.phone.transcriber" to 37), inImage)
        assertEquals(inImage, AddonPlan.wanted(index, setOf("callscreen-phone"), installerVersion = 2))
    }

    // C4: no index -> the image keeps exactly its apps; a failed download keeps the image copy.
    @Test
    fun no_index_keeps_the_image_apps() {
        val p = AddonPlan.plan(index = null, selected = setOf("callscreen-phone"), removed = emptySet(), installerVersion = 2, image = imageManifest())
        assertEquals(setOf("app.plusos.phone", "app.plusos.phone.transcriber"), p.sources.map { it.packageName }.toSet())
        assertTrue(p.sources.all { it is AddonPlan.Source.Image })
    }

    @Test
    fun failed_download_falls_back_to_the_image_copy_or_drops() {
        val p = AddonPlan.plan(index, setOf("callscreen-phone"), emptySet(), 2, imageManifest())
        val r = AddonPlan.fetch(p, download = { throw java.io.IOException("offline") }, imageFile = { File(dir, it).apply { parentFile.mkdirs(); writeText("apk") } })
        assertEquals(2, r.files.size)
        assertTrue(r.warnings.single().contains("kept the installed version"))
        val fresh = AddonPlan.plan(index, setOf("callscreen-phone"), emptySet(), 2, image = null)
        val r2 = AddonPlan.fetch(fresh, download = { throw java.io.IOException("offline") }, imageFile = { error("no image") })
        assertTrue(r2.files.isEmpty())
        assertTrue(r2.warnings.single().contains("not added"))
    }

    // I5: an add-on in the image is never removed unless the owner removed it explicitly.
    @Test
    fun image_addons_are_kept_unless_explicitly_removed() {
        val keep = AddonPlan.plan(index, selected = emptySet(), removed = emptySet(), installerVersion = 2, image = imageManifest())
        assertEquals(setOf("app.plusos.phone", "app.plusos.phone.transcriber"), keep.sources.map { it.packageName }.toSet())
        val gone = AddonPlan.plan(index, selected = emptySet(), removed = setOf("callscreen-phone"), installerVersion = 2, image = imageManifest())
        assertTrue(gone.sources.isEmpty())
    }

    // I3: an applied update waiting for a restart is not rebuilt.
    @Test
    fun restart_pending_blocks_new_builds() {
        val release = GrapheneRelease("2026110100", 2_000L, "tokay", "stable")
        assertEquals(UpdateDecision.Decision.RestartPending,
            UpdateDecision.decide(1_000L, release, emptyMap(), emptyMap(), 20L shl 30, restartPending = true))
    }

    // I4: a +PLUS APK is baked into the image only if it carries the tools and its three system XMLs.
    @Test
    fun installer_check_requires_tools_and_xmls() {
        fun apk(vararg entries: String) = File(dir, "p${entries.size}.apk").also { f ->
            ZipOutputStream(f.outputStream()).use { z -> entries.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() } }
        }
        val good = apk("lib/arm64-v8a/libavbroot.so", "lib/arm64-v8a/libafsr.so", "lib/arm64-v8a/libsignhelper.so",
            "assets/system/app.seamlessupdate.client.permissions.xml", "assets/system/app.seamlessupdate.client.default-permissions.xml",
            "assets/system/app.seamlessupdate.client.sysconfig.xml")
        assertEquals(3, InstallerCheck.verify(good, File(dir, "xml")).size)
        assertThrows<InstallerCheck.Rejected> { InstallerCheck.verify(apk("lib/arm64-v8a/libavbroot.so"), File(dir, "x2")) }
    }

    // I6: a debuggable APK is never published.
    @Test
    fun debuggable_apk_is_not_published() {
        val a = File(dir, "a.apk").apply { writeText("a") }
        val draft = Publisher.Draft("plus-dev", listOf(Publisher.DraftApp("a.apk", "x", privileged = true)))
        val key = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertThrows<IllegalArgumentException> {
            Publisher { Publisher.ApkInfo("x.y", 1, "aa".repeat(32), debuggable = true) }.publish(draft, dir, key.private, File(dir, "site"), "p")
        }
    }

    // I8: retries back off 15 min, 1 h, then every 4 h.
    @Test
    fun retry_backoff() {
        assertEquals(listOf(15L, 60L, 240L, 240L), (1..4).map { Backoff.minutesAfterFailure(it) })
    }

    // I1: a request the key holder can't answer ends the helper with an error instead of hanging.
    @Test
    fun unanswerable_request_is_failed() {
        File(dir, "1-2.req").writeBytes("SHA256_RSA4096\n".toByteArray() + ByteArray(512))
        val p = SignProtocol(dir)
        p.fail(p.pending().single(), "no owner key")
        assertEquals("no owner key", File(dir, "1-2.err").readText())
        assertTrue(p.pending().isEmpty())
    }
}

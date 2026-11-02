// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DebugRootTest {
    private val apk = File("magisk.apk")
    private val official = DebugRoot.MAGISK_SIGNERS.single()

    @Test
    fun a_normal_build_stays_rootless() {
        assertEquals(listOf("--rootless"), DebugRoot.patchArgs(null))
    }

    @Test
    fun debug_root_passes_magisk_and_its_preinit_partition() {
        assertEquals(listOf("--magisk", "$apk", "--magisk-preinit-device", "metadata"), DebugRoot.patchArgs(DebugRoot.Magisk(apk, "metadata")))
    }

    @Test
    fun without_a_known_preinit_partition_avbroot_is_told_to_go_ahead() {
        assertEquals(listOf("--magisk", "$apk", "--ignore-magisk-warnings"), DebugRoot.patchArgs(DebugRoot.Magisk(apk, null)))
    }

    @Test
    fun only_an_official_magisk_apk_is_accepted() {
        DebugRoot.checkMagisk(DebugRoot.MAGISK_PACKAGE, 31000, official)
        assertThrows<DebugRoot.Refused> { DebugRoot.checkMagisk(DebugRoot.MAGISK_PACKAGE, 31000, "ab".repeat(32)) }
        assertThrows<DebugRoot.Refused> { DebugRoot.checkMagisk("com.example.magisk", 31000, official) }
        assertThrows<DebugRoot.Refused> { DebugRoot.checkMagisk(DebugRoot.MAGISK_PACKAGE, 25000, official) }
    }

    private fun image(debugRoot: Boolean) = BuildManifest(
        device = "tokay", grapheneos = BuildManifest.Os("1", "a"), ownerKeyFingerprint = "f",
        installer = BuildManifest.Installer(1, "b"), addons = emptyList(), debugRoot = debugRoot,
    )

    @Test
    fun the_restart_screen_says_when_debug_root_turns_on_or_off() {
        assertEquals(listOf("Debug root ON: root for adb shell over USB (development only)"), ChangeList.between(image(false), image(true)))
        assertEquals(listOf("Debug root off"), ChangeList.between(image(true), image(false)))
    }

    @Test
    fun the_manifest_records_debug_root_and_leaves_it_out_when_off() {
        assertEquals(image(true), BuildManifest.fromJson(image(true).toJson()))
        assertTrue("debug_root" in image(true).toJson())
        assertFalse("debug_root" in image(false).toJson())
    }

    @Test
    fun the_pixel_9_preinit_partition_is_known() {
        assertEquals("metadata", DebugRoot.PREINIT["tokay"])
    }
}

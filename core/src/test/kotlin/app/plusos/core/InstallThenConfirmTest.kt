// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** T3: an update is installed without activating it; the owner sees what changed and only then activates it. */
class InstallThenConfirmTest {
    private fun header(version: Long = 2, manifest: Long = 132_172 - 24, sig: Int = 267, magic: String = "CrAU") =
        ByteBuffer.allocate(24).put(magic.toByteArray()).putLong(version).putLong(manifest).putInt(sig).array()

    @Test
    fun payload_metadata_is_header_manifest_and_signature() {
        // The signed 2026101600 test OTA: METADATA_SIZE=132172 (header + manifest), then the metadata signature.
        assertEquals(132_172L + 267, PayloadMetadata.length(header(), metadataSizeProperty = 132_172))
    }

    @Test
    fun payload_metadata_refuses_what_it_does_not_understand() {
        assertThrows<IllegalArgumentException> { PayloadMetadata.length(header(magic = "PK\u0003\u0004"), 132_172) }
        assertThrows<IllegalArgumentException> { PayloadMetadata.length(header(version = 1), 132_172) }
        assertThrows<IllegalArgumentException> { PayloadMetadata.length(header(), metadataSizeProperty = 999) }
    }

    @Test
    fun an_install_only_update_counts_as_installed() {
        // Review of +PLUS 11: with SWITCH_SLOT_ON_REBOOT=0 update_engine reports kUpdatedButNotActive (52), not 0.
        assertEquals(true, PayloadMetadata.installed(0))
        assertEquals(true, PayloadMetadata.installed(52))
        assertEquals(false, PayloadMetadata.installed(51))
        assertEquals(false, PayloadMetadata.installed(7))
    }

    @Test
    fun the_switch_waits_for_the_owner() {
        assertEquals(listOf("FILE_HASH=x", "SWITCH_SLOT_ON_REBOOT=0"), PayloadMetadata.installOnly(listOf("FILE_HASH=x")))
        assertEquals(listOf("SWITCH_SLOT_ON_REBOOT=0"), PayloadMetadata.installOnly(listOf("SWITCH_SLOT_ON_REBOOT=1")))
    }

    private fun manifest(gos: String = "2026101600", plus: Int = 10, vararg addons: BuildManifest.Addon) = BuildManifest(
        device = "tokay", grapheneos = BuildManifest.Os(gos, "x"), ownerKeyFingerprint = "sha256:f",
        installer = BuildManifest.Installer(plus, "y"), addons = addons.toList(),
    )

    private fun phone(version: Int, perms: List<String> = emptyList()) = BuildManifest.Addon(
        "callscreen-phone", version, listOf(BuildManifest.Apk("app.plusos.phone", "z", "product/priv-app/Phone/Phone.apk", version)),
        if (perms.isEmpty()) emptyMap() else mapOf("app.plusos.phone" to perms),
    )

    @Test
    fun the_owner_sees_every_change() {
        val old = manifest("2026101600", 10, phone(2900017, listOf("android.permission.CAPTURE_AUDIO_OUTPUT")))
        val new = manifest("2026102100", 11, phone(2900018, listOf("android.permission.CAPTURE_AUDIO_OUTPUT", "android.permission.READ_LOGS")))
        assertEquals(listOf(
            "GrapheneOS 2026101600 → 2026102100",
            "+PLUS 10 → 11",
            "Phone+ 2900017 → 2900018",
            "Phone+ gets a new permission: READ_LOGS",
        ), ChangeList.between(old, new))
    }

    @Test
    fun added_and_removed_add_ons_and_a_new_key_are_listed() {
        assertEquals(listOf("Phone+ 2900018 (new)"), ChangeList.between(manifest(), manifest(addons = arrayOf(phone(2900018)))))
        assertEquals(listOf("Phone+ removed"), ChangeList.between(manifest(addons = arrayOf(phone(2900018))), manifest()))
        assertEquals(listOf("Signed with a different key"), ChangeList.between(manifest(), manifest().copy(ownerKeyFingerprint = "sha256:g")))
        assertEquals(listOf("No changes to GrapheneOS or the apps (a rebuild)"), ChangeList.between(manifest(), manifest()))
        assertEquals(listOf("GrapheneOS 2026101600", "+PLUS 10"), ChangeList.between(null, manifest()))
    }
}

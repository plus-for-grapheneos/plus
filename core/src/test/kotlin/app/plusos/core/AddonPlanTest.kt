// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AddonPlanTest {
    private fun app(pkg: String, addon: String, version: Int, privileged: Boolean = true) = RepoApp(
        packageName = pkg, addon = addon, version = version, apk = "$pkg-$version.apk", sha256 = "s", signerSha256 = "aa",
        privileged = privileged,
    )

    private val index = RepoIndex(
        repo = "plus-main", published = "2026-10-22T00:00:00Z", serial = 3,
        apps = listOf(
            app("app.seamlessupdate.client", "plus", 5),
            app("app.plusos.phone", "callscreen-phone", 31),
            app("app.plusos.phone.transcriber", "callscreen-phone", 31, privileged = false),
            app("app.other", "other", 2),
        ),
    )

    @Test
    fun image_paths_follow_privilege() {
        assertEquals("product/priv-app/app.plusos.phone/app.plusos.phone.apk", AddonPlan.imagePath(index.apps[1]))
        assertEquals("product/app/app.plusos.phone.transcriber/app.plusos.phone.transcriber.apk", AddonPlan.imagePath(index.apps[2]))
    }

    @Test
    fun wanted_has_newest_installer_and_selected_addons() {
        val wanted = AddonPlan.wanted(index, selected = setOf("callscreen-phone"), installerVersion = 4)
        assertEquals(mapOf("app.seamlessupdate.client" to 5, "app.plusos.phone" to 31, "app.plusos.phone.transcriber" to 31), wanted)
    }

    @Test
    fun wanted_keeps_running_installer_when_index_is_older() {
        assertEquals(7, AddonPlan.wanted(index, emptySet(), installerVersion = 7)["app.seamlessupdate.client"])
    }

    @Test
    fun pin_on_first_use_then_enforced() {
        val pins = SignerPins(mutableMapOf())
        pins.check("app.plusos.phone", "aa")
        pins.check("app.plusos.phone", "aa")
        assertThrows<SignerPins.SignerMismatch> { pins.check("app.plusos.phone", "bb") }
    }

    @Test
    fun pins_survive_a_new_instance() {
        val storage = mutableMapOf<String, String>()
        SignerPins(storage).check("app.plusos.phone", "aa")
        assertThrows<SignerPins.SignerMismatch> { SignerPins(storage).check("app.plusos.phone", "bb") }
    }

    /** B3 (owner, 2026-10-29): the store must show a newer +PLUS in the catalog as an update. */
    @Test
    fun installer_update_is_the_newest_plus_above_the_running_one() {
        assertEquals(5, AddonPlan.installerUpdate(index, runningVersion = 4)?.version)
        assertEquals(null, AddonPlan.installerUpdate(index, runningVersion = 5))
        assertEquals(null, AddonPlan.installerUpdate(index, runningVersion = 6))
        val two = index.copy(apps = index.apps + app("app.seamlessupdate.client", "plus", 7))
        assertEquals(7, AddonPlan.installerUpdate(two, runningVersion = 4)?.version)
    }
}

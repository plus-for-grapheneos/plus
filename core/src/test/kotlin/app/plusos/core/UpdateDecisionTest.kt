// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import app.plusos.core.UpdateDecision.BuildReason
import app.plusos.core.UpdateDecision.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateDecisionTest {
    private val release = GrapheneRelease("2026110100", 2_000L, "tokay", "stable")
    private val image = mapOf("app.seamlessupdate.client" to 3, "app.plusos.phone" to 31)
    private val plenty = 20L * 1024 * 1024 * 1024

    @Test
    fun no_build_when_same_release() {
        assertEquals(Decision.UpToDate, UpdateDecision.decide(2_000L, release, image, image, plenty))
    }

    @Test
    fun build_when_newer_release() {
        assertEquals(Decision.Build(BuildReason.GRAPHENEOS_RELEASE), UpdateDecision.decide(1_000L, release, image, image, plenty))
    }

    @Test
    fun build_when_addon_selection_changed() {
        val wanted = image - "app.plusos.phone"
        assertEquals(Decision.Build(BuildReason.ADDON_CHANGE), UpdateDecision.decide(2_000L, release, image, wanted, plenty))
        val newer = image + ("app.plusos.phone" to 32)
        assertEquals(Decision.Build(BuildReason.ADDON_CHANGE), UpdateDecision.decide(2_000L, release, image, newer, plenty))
    }

    @Test
    fun build_when_installer_updated() {
        val newer = image + ("app.seamlessupdate.client" to 4)
        assertEquals(Decision.Build(BuildReason.INSTALLER_UPDATE), UpdateDecision.decide(2_000L, release, image, newer, plenty))
    }

    @Test
    fun no_build_below_8GB() {
        val free = 7L * 1024 * 1024 * 1024
        assertEquals(Decision.WaitForSpace(free), UpdateDecision.decide(1_000L, release, image, image, free))
    }

    @Test
    fun release_never_waits_for_addons() {
        // Whatever the add-ons do (even an unknown newer add-on version), a newer GrapheneOS release is built.
        val odd = image + ("app.plusos.phone" to 99) + ("some.new.addon" to 1)
        assertEquals(Decision.Build(BuildReason.GRAPHENEOS_RELEASE), UpdateDecision.decide(1_000L, release, image, odd, plenty))
    }

    @Test
    fun manual_rebuild_when_up_to_date() {
        assertEquals(Decision.Build(BuildReason.MANUAL), UpdateDecision.decide(2_000L, release, image, image, plenty, manual = true))
    }

    @Test
    fun older_release_never_builds() {
        // S6 (user): the phone ran Beta 2026102100 while +PLUS followed Stable 2026101600; an add-on change built from
        // the older release and update_engine refused the downgrade (error 51). Like GrapheneOS's Updater, wait instead.
        val newer = image + ("app.seamlessupdate.client" to 4)
        assertEquals(Decision.ChannelBehind("2026110100"), UpdateDecision.decide(3_000L, release, image, newer, plenty))
        assertEquals(Decision.ChannelBehind("2026110100"), UpdateDecision.decide(3_000L, release, image, image, plenty, manual = true))
    }
}

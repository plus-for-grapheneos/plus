// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import app.plusos.core.PlusStatus.Action
import app.plusos.core.PlusStatus.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlusStatusTest {
    private val now = 1_800_000_000_000L
    private val available = PlusStatus.Check(
        atMs = now - 60_000, kind = PlusStatus.CheckKind.UPDATE, grapheneOs = "2026110100", plus = 17,
        addons = listOf("Phone+ 2900019"),
    )
    private val facts = PlusStatus.Facts(nowMs = now, supported = true, keyReady = true, lastCheck = available)

    @Test
    fun an_update_waiting_for_wifi_says_what_and_why() {
        val h = PlusStatus.headline(facts.copy(waitingForWifi = true))
        assertEquals(Phase.AVAILABLE, h.phase)
        assertEquals("Update available: GrapheneOS 2026110100, +PLUS 17, Phone+ 2900019", h.title)
        assertTrue("Waiting for Wi-Fi" in h.detail, h.detail)
        assertEquals(Action.UPDATE_NOW, h.action)
        assertNull(h.percent)
    }

    @Test
    fun an_update_not_yet_started_on_wifi_waits_for_charging_and_idle() {
        val h = PlusStatus.headline(facts)
        assertTrue("installs by itself" in h.detail && "charging" in h.detail, h.detail)
    }

    @Test
    fun a_running_build_shows_the_step_and_an_overall_percentage() {
        val h = PlusStatus.headline(facts.copy(running = true, step = BuildProgress.download("2026110100", 50)))
        assertEquals(Phase.IN_PROGRESS, h.phase)
        assertEquals("Updating: step 1 of 5", h.title)
        assertEquals("Downloading GrapheneOS 2026110100: 50%", h.detail)
        assertEquals(10, h.percent)
        val install = PlusStatus.headline(facts.copy(running = true, step = BuildProgress.install(BuildProgress.UE_DOWNLOADING, 0.5f)))
        assertEquals(90, install.percent)
        assertEquals(Action.NONE, install.action)
    }

    @Test
    fun a_running_build_before_its_first_step_says_preparing() {
        val h = PlusStatus.headline(facts.copy(running = true, step = null))
        assertEquals("Updating", h.title)
        assertEquals("Preparing…", h.detail)
        assertEquals(0, h.percent)
    }

    @Test
    fun an_installed_update_asks_for_the_restart_before_anything_else() {
        val h = PlusStatus.headline(facts.copy(restartPending = true, waitingForWifi = true))
        assertEquals(Phase.READY, h.phase)
        assertEquals(Action.RESTART, h.action)
    }

    @Test
    fun a_failed_build_says_why_and_when_it_tries_again() {
        val h = PlusStatus.headline(facts.copy(failures = 2, lastError = "No space left on device", retryAtMs = now + 45 * 60_000))
        assertEquals(Phase.FAILED, h.phase)
        assertTrue("No space left on device" in h.detail && "45 min" in h.detail, h.detail)
        assertEquals(Action.UPDATE_NOW, h.action)
    }

    @Test
    fun up_to_date_says_when_it_last_checked() {
        val h = PlusStatus.headline(facts.copy(lastCheck = PlusStatus.Check(atMs = now - 3 * 3_600_000, kind = PlusStatus.CheckKind.UP_TO_DATE)))
        assertEquals(Phase.UP_TO_DATE, h.phase)
        assertEquals("Up to date", h.title)
        assertTrue("3 h ago" in h.detail, h.detail)
        assertEquals(Action.CHECK, h.action)
    }

    @Test
    fun never_checked_offers_a_check() {
        val h = PlusStatus.headline(facts.copy(lastCheck = null))
        assertEquals(Phase.UNKNOWN, h.phase)
        assertEquals(Action.CHECK, h.action)
    }

    @Test
    fun no_key_blocks_with_the_way_out() {
        val h = PlusStatus.headline(facts.copy(keyReady = false))
        assertEquals(Phase.BLOCKED, h.phase)
        assertEquals(Action.IMPORT_KEY, h.action)
    }

    @Test
    fun channel_behind_and_low_space_are_explained() {
        val behind = PlusStatus.headline(facts.copy(lastCheck = PlusStatus.Check(now, PlusStatus.CheckKind.CHANNEL_BEHIND, grapheneOs = "2026102100", channel = "stable")))
        assertTrue("Stable" in behind.title, behind.title)
        val space = PlusStatus.headline(facts.copy(lastCheck = available.copy(kind = PlusStatus.CheckKind.NEEDS_SPACE, freeBytes = 3_000_000_000)))
        assertTrue("8 GB" in space.detail && "3 GB" in space.detail, space.detail)
    }

    @Test
    fun a_check_that_failed_says_so_without_hiding_the_last_good_one() {
        val h = PlusStatus.headline(facts.copy(lastCheck = PlusStatus.Check(now, PlusStatus.CheckKind.CHECK_FAILED, error = "no network")))
        assertEquals(Phase.UNKNOWN, h.phase)
        assertTrue("no network" in h.detail, h.detail)
    }

    @Test
    fun unsupported_model_wins_over_everything() {
        assertEquals(Phase.BLOCKED, PlusStatus.headline(facts.copy(supported = false, running = true)).phase)
    }

    @Test
    fun a_check_round_trips_through_its_stored_text() {
        assertEquals(available, PlusStatus.Check.decode(available.encode()))
        assertNull(PlusStatus.Check.decode("not json"))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import app.plusos.core.UpdateDecision.BuildReason
import app.plusos.core.UpdateDecision.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone's periodic check as the app runs it (review C4, I3, I5, I8). */
class UpdateCheckTest {
    private fun app(pkg: String, addon: String, version: Int, privileged: Boolean = true) = RepoApp(
        packageName = pkg, addon = addon, version = version, apk = "$pkg-$version.apk", sha256 = "s", signerSha256 = "aa",
        privileged = privileged,
    )

    private val phone = app("app.plusos.phone", "callscreen-phone", 2900000)
    private val transcriber = app("app.plusos.phone.transcriber", "callscreen-phone", 37, privileged = false)
    private fun index(vararg apps: RepoApp) = RepoIndex(repo = "plus-dev", published = "p", serial = 2,
        apps = listOf(app(AddonPlan.INSTALLER, "plus", 2)) + apps)

    private val image = BuildManifest(
        device = "tokay", grapheneos = BuildManifest.Os("2026101600", "x"), ownerKeyFingerprint = "f",
        installer = BuildManifest.Installer(2, "i"),
        addons = listOf(BuildManifest.Addon("callscreen-phone", 2900000, listOf(
            BuildManifest.Apk("app.plusos.phone", "s", AddonPlan.imagePath(phone), version = 2900000),
            BuildManifest.Apk("app.plusos.phone.transcriber", "s", AddonPlan.imagePath(transcriber), version = 37),
        ))),
    )
    private val sameRelease = GrapheneRelease("2026101600", 1_000L, "tokay", "stable")
    private val newRelease = GrapheneRelease("2026110100", 2_000L, "tokay", "stable")
    private val space = 20L shl 30
    private fun state(selected: Set<String> = emptySet(), deferred: Map<String, Int> = emptyMap()) =
        UpdateCheck.State(image = image, runningInstallerVersion = 2, selected = selected, removed = emptySet(), deferred = deferred)

    /**
     * +PLUS 13 (owner): with no catalog link, +PLUS only keeps GrapheneOS updated and signed with the owner key. Nothing
     * else happens: no add-on is added (even one marked as selected), the installed add-ons are kept as they are.
     */
    @Test
    fun without_a_catalog_only_grapheneos_updates() {
        assertEquals(Decision.Build(BuildReason.GRAPHENEOS_RELEASE),
            UpdateCheck.decide(1_000L, newRelease, state(selected = setOf("ai-service")), index = null, freeBytes = space))
        assertEquals(Decision.UpToDate,
            UpdateCheck.decide(1_000L, sameRelease, state(selected = setOf("ai-service")), index = null, freeBytes = space))
        val plan = AddonPlan.plan(null, setOf("ai-service"), emptySet(), 2, image)
        assertEquals(setOf("app.plusos.phone", "app.plusos.phone.transcriber"), plan.sources.map { it.packageName }.toSet())
        assertTrue(plan.sources.all { it is AddonPlan.Source.Image })
    }

    @Test
    fun repository_outage_still_builds_a_new_release() {
        assertEquals(Decision.Build(BuildReason.GRAPHENEOS_RELEASE),
            UpdateCheck.decide(1_000L, newRelease, state(), index = null, freeBytes = space))
    }

    @Test
    fun repository_outage_with_nothing_new_is_up_to_date() {
        assertEquals(Decision.UpToDate, UpdateCheck.decide(1_000L, sameRelease, state(), index = null, freeBytes = space))
    }

    /** The image's add-ons count as selected even though the store never saved a selection (I5). */
    @Test
    fun image_matching_the_repository_is_up_to_date() {
        assertEquals(Decision.UpToDate, UpdateCheck.decide(1_000L, sameRelease, state(), index(phone, transcriber), space))
    }

    @Test
    fun newer_addon_builds() {
        val newer = phone.copy(version = 2900001)
        assertEquals(Decision.Build(BuildReason.ADDON_CHANGE),
            UpdateCheck.decide(1_000L, sameRelease, state(), index(newer, transcriber), space))
    }

    /** A version that failed to download is not retried by a rebuild after every restart (I8). */
    @Test
    fun deferred_version_does_not_rebuild_again() {
        val newer = phone.copy(version = 2900001)
        assertEquals(Decision.UpToDate, UpdateCheck.decide(1_000L, sameRelease,
            state(deferred = mapOf("app.plusos.phone" to 2900001)), index(newer, transcriber), space))
    }

    @Test
    fun deferred_new_addon_is_left_out() {
        val ai = app("app.plusos.ai", "ai-service", 1, privileged = false)
        assertEquals(Decision.UpToDate, UpdateCheck.decide(1_000L, sameRelease,
            state(selected = setOf("ai-service"), deferred = mapOf("app.plusos.ai" to 1)), index(phone, transcriber, ai), space))
    }

    @Test
    fun deferred_lists_what_the_build_could_not_fetch() {
        val newer = phone.copy(version = 2900001)
        val plan = AddonPlan.plan(index(newer, transcriber), emptySet(), emptySet(), 2, image)
        val kept = AddonPlan.Fetched(
            sources = plan.sources.map { s -> if (s.packageName == "app.plusos.phone") AddonPlan.Source.Image(s.packageName, s.addon, 2900000, s.imagePath, s.privappPermissions) else s },
            files = emptyMap(), sha256 = emptyMap(), warnings = listOf("kept"),
        )
        assertEquals(mapOf("app.plusos.phone" to 2900001), UpdateCheck.deferred(plan, kept))
    }

    /**
     * I3: an applied update waits for the restart. Review of +PLUS 11: Android's boot counter decides, not the wall
     * clock (a network time correction after the install made the old boot-time comparison say "restarted").
     */
    @Test
    fun restart_pending_until_the_next_boot() {
        assertTrue(UpdateCheck.restartPending(appliedAtBoot = 41, currentBoot = 41))
        assertFalse(UpdateCheck.restartPending(appliedAtBoot = 41, currentBoot = 42))
        assertFalse(UpdateCheck.restartPending(appliedAtBoot = null, currentBoot = 41))
    }

    /** S6 (official phone): Apply asked "Remove plus?" — +PLUS itself is never an add-on the owner can remove. */
    @Test
    fun removals_never_include_plus_itself() {
        val idx = index(phone, transcriber)
        val inImage = setOf(AddonPlan.INSTALLER)
        assertEquals(emptySet<String>(), AddonPlan.removals(idx, inImage, chosen = setOf("callscreen-phone")))
        assertEquals(setOf("callscreen-phone"),
            AddonPlan.removals(idx, inImage + "app.plusos.phone" + "app.plusos.phone.transcriber", chosen = emptySet()))
    }
}

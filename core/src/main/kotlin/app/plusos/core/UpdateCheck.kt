// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core


/**
 * The phone's periodic check (spec §4.3) with the rules from the review: the image's own add-ons count as selected
 * (I5), a missing repository index never holds back a GrapheneOS release (C4), an applied update waits for its restart
 * (I3), and a version that could not be fetched is not retried by a rebuild after every restart (I8).
 */
object UpdateCheck {
    /**
     * @param image the manifest of the running image, null when it has none
     * @param deferred package → version the last build could not fetch; that version is not wanted again
     */
    data class State(
        val image: BuildManifest?,
        val runningInstallerVersion: Int,
        val selected: Set<String>,
        val removed: Set<String>,
        val deferred: Map<String, Int> = emptyMap(),
        val restartPending: Boolean = false,
    )

    fun decide(
        runningBuildDateUtc: Long,
        release: GrapheneRelease,
        state: State,
        index: RepoIndex?,
        freeBytes: Long,
        manual: Boolean = false,
    ): UpdateDecision.Decision {
        val inImage = state.image?.apps() ?: mapOf(AddonPlan.INSTALLER to state.runningInstallerVersion)
        val plan = AddonPlan.plan(index, state.selected, state.removed, state.runningInstallerVersion, state.image)
        val wanted = AddonPlan.versions(plan, state.runningInstallerVersion, index).mapNotNull { (pkg, version) ->
            if (state.deferred[pkg] == version) inImage[pkg]?.let { pkg to it } else pkg to version
        }.toMap()
        return UpdateDecision.decide(runningBuildDateUtc, release, inImage, wanted, freeBytes, manual, state.restartPending)
    }

    /** What [plan] wanted but the build got only as an older copy, or not at all. */
    fun deferred(plan: AddonPlan.Plan, fetched: AddonPlan.Fetched): Map<String, Int> {
        val got = fetched.sources.associate { it.packageName to it.version }
        return plan.sources.filter { got[it.packageName] != it.version }.associate { it.packageName to it.version }
    }

    /**
     * True while the phone has not restarted since the update was applied: same value of Android's boot counter
     * (`Settings.Global.BOOT_COUNT`). Not the boot time from the wall clock, which a network time correction moves.
     */
    fun restartPending(appliedAtBoot: Long?, currentBoot: Long): Boolean = appliedAtBoot != null && appliedAtBoot == currentBoot
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

/**
 * Whether the phone should rebuild its OS now (spec §4.3). A newer GrapheneOS release always builds, whatever the
 * add-ons do; any change in the apps baked into the image (selection or version, including +PLUS itself) also builds,
 * because system apps can only be updated through the image on GrapheneOS.
 */
object UpdateDecision {
    const val MIN_FREE_BYTES = 8L * 1024 * 1024 * 1024

    enum class BuildReason { GRAPHENEOS_RELEASE, ADDON_CHANGE, INSTALLER_UPDATE, MANUAL }

    sealed interface Decision {
        data object UpToDate : Decision
        data object RestartPending : Decision
        data class Build(val reason: BuildReason) : Decision
        data class WaitForSpace(val freeBytes: Long) : Decision
        /** The followed channel's newest release ([build]) is older than the running OS; nothing builds until it catches up. */
        data class ChannelBehind(val build: String) : Decision
        /** +PLUS has no GrapheneOS keys for this phone model ([device], `Build.DEVICE`): it never builds (+PLUS 11). */
        data class UnsupportedDevice(val device: String) : Decision
    }

    /**
     * Checked before anything else: GrapheneOS releases for [device] can only be verified with that model's pinned keys.
     * A [customChannel] (our own source builds) brings its own keys. null = the model is supported.
     */
    fun deviceCheck(device: String, customChannel: Boolean): Decision? =
        if (!customChannel && Pins.forDevice(device) == null) Decision.UnsupportedDevice(device) else null

    /**
     * @param inImage package → versionCode of the apps in the running image (+PLUS included)
     * @param wanted package → versionCode the image should contain (selected add-ons and the newest +PLUS)
     */
    fun decide(
        runningBuildDateUtc: Long,
        release: GrapheneRelease,
        inImage: Map<String, Int>,
        wanted: Map<String, Int>,
        freeBytes: Long,
        manual: Boolean = false,
        restartPending: Boolean = false,
    ): Decision {
        // Review I3: an applied update waits for the restart; building again would overwrite it.
        if (restartPending) return Decision.RestartPending
        // S6: update_engine refuses a payload older than the running OS (error 51), so an older release never builds,
        // not even for an add-on change or a manual rebuild; as in GrapheneOS's Updater, the phone waits for the channel.
        if (release.isOlderThan(runningBuildDateUtc)) return Decision.ChannelBehind(release.build)
        val reason = when {
            release.isNewerThan(runningBuildDateUtc) -> BuildReason.GRAPHENEOS_RELEASE
            inImage[INSTALLER] != wanted[INSTALLER] -> BuildReason.INSTALLER_UPDATE
            inImage != wanted -> BuildReason.ADDON_CHANGE
            manual -> BuildReason.MANUAL
            else -> return Decision.UpToDate
        }
        return if (freeBytes < MIN_FREE_BYTES) Decision.WaitForSpace(freeBytes) else Decision.Build(reason)
    }

    private const val INSTALLER = "app.seamlessupdate.client"
}

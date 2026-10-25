// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

/**
 * One line of `https://releases.grapheneos.org/<device>-<channel>`: `<build> <build-date-utc> <device> <channel>`
 * (spec §6, verified 2026-10-21). Validation mirrors GrapheneOS's own Updater: device and channel must match.
 */
data class GrapheneRelease(val build: String, val buildDateUtc: Long, val device: String, val channel: String) {
    val fullOtaName: String get() = "$device-ota_update-$build.zip"

    fun isNewerThan(currentBuildDateUtc: Long): Boolean = buildDateUtc > currentBuildDateUtc

    /** True when the phone already runs a newer build (e.g. Beta while following Stable): building this is a downgrade. */
    fun isOlderThan(currentBuildDateUtc: Long): Boolean = buildDateUtc < currentBuildDateUtc

    companion object {
        const val BASE_URL = "https://releases.grapheneos.org/"
        /** GrapheneOS's channels an owner may follow, as in GrapheneOS's own Updater (alpha is for testers). */
        val CHANNELS = listOf("stable", "beta")

        private val DEVICE_NAME = Regex("[a-z0-9]{2,32}")

        /** The release file for [device] (the phone's own model, `Build.DEVICE`) on [channel]. */
        fun metadataUrl(base: String, channel: String, device: String): String {
            require(DEVICE_NAME.matches(device)) { "bad device name '$device'" }
            return "$base$device-$channel"
        }

        fun parse(line: String, channel: String, device: String): GrapheneRelease {
            require(channel in CHANNELS) { "unknown release channel $channel" }
            val f = line.trim().split(' ')
            require(f.size == 4) { "release metadata must have 4 fields, got ${f.size}" }
            require(f[0].matches(Regex("[0-9]{10,12}"))) { "bad build number '${f[0]}'" }
            val date = requireNotNull(f[1].toLongOrNull()) { "bad build date '${f[1]}'" }
            require(f[2] == device) { "release is for device ${f[2]}, not $device" }
            require(f[3] == channel) { "release is for channel ${f[3]}, not $channel" }
            return GrapheneRelease(f[0], date, f[2], f[3])
        }
    }
}

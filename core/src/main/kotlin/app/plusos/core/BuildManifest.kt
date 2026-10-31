// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything one build depends on (spec §4.1). The phone keeps the last successful one; its hash keys stage markers. */
@Serializable
data class BuildManifest(
    val format: Int = 1,
    val device: String,
    val grapheneos: Os,
    @SerialName("owner_key_fingerprint") val ownerKeyFingerprint: String,
    val installer: Installer,
    val addons: List<Addon>,
    /** "Your own app" add-ons: apps from this phone made privileged system apps ([OwnApp]). Left out when empty, so
     *  manifests without one keep their bytes (the web setup page builds the same JSON). */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("own_apps") val ownApps: List<OwnApp.Entry> = emptyList(),
) {
    @Serializable
    data class Os(val build: String, @SerialName("ota_sha256") val otaSha256: String)

    @Serializable
    data class Installer(val version: Int, @SerialName("apk_sha256") val apkSha256: String)

    @Serializable
    data class Addon(
        val id: String,
        val version: Int,
        val apks: List<Apk>,
        @SerialName("privapp_permissions") val privappPermissions: Map<String, List<String>> = emptyMap(),
    )

    @Serializable
    data class Apk(@SerialName("package") val packageName: String, val sha256: String, val path: String, val version: Int = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    /** package → versionCode of every app in this image, +PLUS included (each APK keeps its own version: review C1). */
    fun apps(): Map<String, Int> = mapOf(AddonPlan.INSTALLER to installer.version) +
        addons.flatMap { a -> a.apks.map { it.packageName to (it.version.takeIf { v -> v > 0 } ?: a.version) } }

    companion object {
        internal val JSON = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }
        fun fromJson(s: String): BuildManifest = JSON.decodeFromString(serializer(), s)
    }
}

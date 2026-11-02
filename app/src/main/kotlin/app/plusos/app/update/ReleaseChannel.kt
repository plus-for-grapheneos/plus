// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.update

import android.content.Context
import app.plusos.app.R
import app.plusos.core.GrapheneRelease
import app.plusos.core.Pins
import java.io.File

/**
 * Where GrapheneOS releases come from and which keys verify them. A release +PLUS has no overrides (empty resources):
 * releases.grapheneos.org, verified with the GrapheneOS keys pinned in core. Debug and staging builds (src/debug/res)
 * point at a development channel on the build box that serves our own source build, with that build's public keys.
 */
class ReleaseChannel(private val ctx: Context) {
    val baseUrl: String = ctx.getString(R.string.release_url).trim().ifEmpty { GrapheneRelease.BASE_URL }
        .let { if (it.endsWith("/")) it else "$it/" }
    private val prefs = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)

    /**
     * GrapheneOS's channel the owner follows: Stable by default, or Beta, as in GrapheneOS's own Updater. Only for the
     * official releases; a source-build channel always serves "stable".
     */
    var channel: String
        get() = if (official) prefs.getString("release_channel", Pins.CHANNEL)!!.takeIf { it in GrapheneRelease.CHANNELS } ?: Pins.CHANNEL
            else Pins.CHANNEL
        set(v) { require(v in GrapheneRelease.CHANNELS); prefs.edit().putString("release_channel", v).apply() }

    /**
     * B4: security preview releases ("<channel>-security-preview"), chosen in GrapheneOS's setup or on the +PLUS screen.
     * Null until chosen (then off, as in GrapheneOS's Updater). Official releases only.
     */
    val securityPreviewChoice: Boolean?
        get() = when (prefs.getInt("security_preview", -1)) { 0 -> false; 1 -> true; else -> null }

    var securityPreview: Boolean
        get() = official && securityPreviewChoice == true
        set(v) { prefs.edit().putInt("security_preview", if (v) 1 else 0).apply() }

    /** The release channel actually fetched: the chosen channel, with security previews if on. */
    val releaseChannel: String get() = if (official) GrapheneRelease.channelName(channel, securityPreview) else channel

    /** This phone's model (`Build.DEVICE`, e.g. tokay): its releases and its GrapheneOS keys (+PLUS 11). */
    val device: String = android.os.Build.DEVICE

    val metadataUrl: String get() = GrapheneRelease.metadataUrl(baseUrl, releaseChannel, device)

    fun parse(line: String): GrapheneRelease = GrapheneRelease.parse(line, releaseChannel, device)
    val official: Boolean get() = baseUrl == GrapheneRelease.BASE_URL

    /** Only a source-build channel may lack the Updater app +PLUS replaces; never the official one. */
    val allowMissingUpdater: Boolean get() = !official && ctx.resources.getBoolean(R.bool.release_allow_missing_updater)

    /** The channel's AVB public key (pkmd), or null for GrapheneOS's own (pinned). */
    fun inputAvbPkmd(dir: File): File? = raw(R.raw.release_avb_pkmd, File(dir, "channel_avb_pkmd.bin"))

    /** The channel's OTA certificate, or null for GrapheneOS's own (pinned). */
    fun inputCert(dir: File): File? = raw(R.raw.release_cert, File(dir, "channel_ota.x509.pem"))

    private fun raw(id: Int, dest: File): File? {
        val bytes = ctx.resources.openRawResource(id).use { it.readBytes() }
        if (bytes.isEmpty() || official) return null
        return dest.apply { parentFile?.mkdirs(); writeBytes(bytes) }
    }

    companion object {
        /** What the owner reads when the followed channel is behind the running OS (S6: Beta running, Stable chosen). */
        fun behindMessage(channel: String, build: String) =
            "This phone already runs a newer GrapheneOS than $channel ($build). Updates resume when $channel catches up, " +
                "or switch the GrapheneOS channel to the one this phone runs."
    }
}

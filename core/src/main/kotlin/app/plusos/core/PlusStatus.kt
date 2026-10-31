// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the +PLUS screen says first (owner, 2026-10-31: "what isn't clear is what is loading, what is available, and the
 * current action or progress"). One headline from everything +PLUS knows: a build running, an update installed and
 * waiting for the restart, a failure and its retry, what the last check found and why it hasn't installed yet.
 */
object PlusStatus {
    enum class Phase { IN_PROGRESS, READY, FAILED, AVAILABLE, UP_TO_DATE, BLOCKED, UNKNOWN }
    enum class Action { NONE, RESTART, UPDATE_NOW, CHECK, IMPORT_KEY }
    enum class CheckKind { UPDATE, UP_TO_DATE, CHANNEL_BEHIND, NEEDS_SPACE, CHECK_FAILED }

    /** The last update check, kept between runs (UpdateState). [addons]: names with their new versions. */
    @Serializable
    data class Check(
        val atMs: Long,
        val kind: CheckKind,
        val grapheneOs: String? = null,
        val plus: Int? = null,
        val addons: List<String> = emptyList(),
        val channel: String? = null,
        val freeBytes: Long? = null,
        val error: String? = null,
    ) {
        fun encode(): String = JSON.encodeToString(serializer(), this)

        companion object {
            private val JSON = Json { ignoreUnknownKeys = true }
            fun decode(s: String?): Check? = s?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
        }
    }

    data class Facts(
        val nowMs: Long,
        val supported: Boolean,
        val keyReady: Boolean,
        val lastCheck: Check?,
        val running: Boolean = false,
        /** The build's last step line (BuildProgress), null before the first one. */
        val step: String? = null,
        val restartPending: Boolean = false,
        val waitingForWifi: Boolean = false,
        val failures: Int = 0,
        val lastError: String? = null,
        val retryAtMs: Long? = null,
    )

    data class Headline(val phase: Phase, val title: String, val detail: String, val action: Action, val percent: Int? = null)

    private val STEP = Regex("^\\[(\\d+)/(\\d+)] (.*)$")
    private val PERCENT = Regex("(\\d+)%\\s*$")

    fun headline(f: Facts): Headline {
        if (!f.supported) return Headline(Phase.BLOCKED, "This phone model isn't supported",
            "+PLUS has no GrapheneOS keys for it, so it won't install updates.", Action.NONE)
        if (f.restartPending) return Headline(Phase.READY, "Update installed: restart to finish",
            "The phone keeps running the current version until you restart. Restart to finish shows what changes.", Action.RESTART)
        if (f.running) return running(f.step)
        if (!f.keyReady) return Headline(Phase.BLOCKED, "Import your key to keep receiving updates",
            "+PLUS signs every update with your key; without it nothing can be installed.", Action.IMPORT_KEY)
        if (f.failures > 0) {
            val retry = f.retryAtMs?.let { at -> if (at > f.nowMs) "Trying again in ${duration(at - f.nowMs)} (on Wi-Fi)." else "Trying again soon." }
                ?: "Trying again at the next check."
            return Headline(Phase.FAILED, "The last update attempt failed",
                "${f.lastError?.trim()?.removeSuffix(".") ?: "See Share log for why"}. $retry", Action.UPDATE_NOW)
        }
        val c = f.lastCheck ?: return Headline(Phase.UNKNOWN, "Not checked yet", "+PLUS checks for updates every 6 hours.", Action.CHECK)
        val ago = ago(f.nowMs - c.atMs)
        return when (c.kind) {
            CheckKind.UPDATE -> {
                val what = listOfNotNull(c.grapheneOs?.let { "GrapheneOS $it" }, c.plus?.let { "+PLUS $it" }) + c.addons
                Headline(Phase.AVAILABLE, if (what.isEmpty()) "Update available" else "Update available: " + what.joinToString(", "),
                    if (f.waitingForWifi) "Waiting for Wi-Fi: automatic updates don't use mobile data. Update now downloads it over mobile data (about 1.5 to 2 GB)."
                    else "It installs by itself on Wi-Fi while the phone is charging and idle. Update now starts it right away.",
                    Action.UPDATE_NOW)
            }
            CheckKind.UP_TO_DATE -> Headline(Phase.UP_TO_DATE, "Up to date", "Checked $ago.", Action.CHECK)
            CheckKind.CHANNEL_BEHIND -> {
                val ch = (c.channel ?: "the channel").replaceFirstChar { it.uppercase() }
                Headline(Phase.UP_TO_DATE, "Waiting for $ch to catch up",
                    "This phone already runs a newer GrapheneOS than $ch (${c.grapheneOs}). Updates resume when $ch catches up. Checked $ago.",
                    Action.CHECK)
            }
            CheckKind.NEEDS_SPACE -> Headline(Phase.BLOCKED, "Update available, but storage is too full",
                "Building an update needs 8 GB free; ${(c.freeBytes ?: 0) / 1_000_000_000} GB free. Checked $ago.", Action.CHECK)
            CheckKind.CHECK_FAILED -> Headline(Phase.UNKNOWN, "Couldn't check for updates",
                "Last try $ago: ${c.error ?: "unknown error"}.", Action.CHECK)
        }
    }

    private fun running(step: String?): Headline {
        val m = step?.let { STEP.find(it) } ?: return Headline(Phase.IN_PROGRESS, "Updating", "Preparing…", Action.NONE, 0)
        val k = m.groupValues[1].toInt()
        val n = m.groupValues[2].toInt().coerceAtLeast(1)
        val text = m.groupValues[3]
        val within = PERCENT.find(text)?.groupValues?.get(1)?.toInt()?.coerceIn(0, 100) ?: 0
        return Headline(Phase.IN_PROGRESS, "Updating: step $k of $n", text, Action.NONE, (((k - 1) * 100 + within) / n).coerceIn(0, 100))
    }

    fun ago(ms: Long): String = when {
        ms < 90_000 -> "just now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 48 * 3_600_000L -> "${ms / 3_600_000} h ago"
        else -> "${ms / 86_400_000} days ago"
    }

    private fun duration(ms: Long): String = if (ms < 3_600_000) "${(ms + 59_999) / 60_000} min" else "${(ms + 1_799_999) / 3_600_000} h"
}

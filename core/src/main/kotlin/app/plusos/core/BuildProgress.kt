// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import app.plusos.core.pipeline.Stage

/**
 * What the update notification says (S6, user: it showed raw stage names, then "Installing" again and again; "put
 * numbers in it like [1/5] checking, [2/5] patching"). Five steps: download, check, add-ons, sign, install. The
 * pipeline's own log lines stay in the build log; only these reach the notification.
 */
object BuildProgress {
    const val STEPS = 5

    /** update_engine's status codes (UpdateEngine.UpdateStatusConstants) during an install. */
    const val UE_DOWNLOADING = 3
    const val UE_VERIFYING = 4
    const val UE_FINALIZING = 5

    private val ADDON_STAGES = listOf(Stage.EXTRACT, Stage.AVB_UNPACK, Stage.FS_UNPACK, Stage.EDIT, Stage.FS_PACK, Stage.AVB_PACK)

    fun download(build: String, percent: Long) = "[1/$STEPS] Downloading GrapheneOS $build: $percent%"

    fun stage(s: Stage): String = when (s) {
        Stage.VERIFY_INPUT -> "[2/$STEPS] Checking the GrapheneOS download"
        Stage.PATCH -> "[4/$STEPS] Signing the update with your key"
        Stage.VERIFY_OUTPUT -> "[4/$STEPS] Checking the signed update"
        else -> "[3/$STEPS] Adding your add-ons (${ADDON_STAGES.indexOf(s) + 1} of ${ADDON_STAGES.size})"
    }

    /** The step for a pipeline log line ("EDIT ..." or "EDIT already done"); null for every other line. */
    fun fromLog(line: String): String? {
        val m = Regex("^([A-Z_]+) (\\.\\.\\.|already done)$").find(line) ?: return null
        val s = Stage.entries.firstOrNull { it.name == m.groupValues[1] } ?: return null
        return stage(s)
    }

    fun install(status: Int, fraction: Float): String {
        val phase = when (status) {
            UE_VERIFYING -> "checking"
            UE_FINALIZING -> "finishing"
            else -> "writing"
        }
        return "[$STEPS/$STEPS] Installing: $phase ${(fraction * 100).toInt()}%"
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.update

import android.content.Context
import app.plusos.core.BuildManifest
import app.plusos.core.UpdateCheck
import java.io.File

/**
 * What the update check remembers between runs: add-ons the owner removed, versions the last build could not fetch,
 * when an update was applied (restart pending) and failed builds (backoff).
 */
class UpdateState(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val pendingFile = File(ctx.filesDir, "pending-manifest.json")

    /** Add-ons the owner removed in the store; an image add-on is only ever dropped through this (its data goes with it). */
    var removed: Set<String>
        get() = prefs.getStringSet("removed", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("removed", v).apply() }

    var deferred: Map<String, Int>
        get() = prefs.getStringSet("deferred", emptySet())!!.mapNotNull { e ->
            e.substringAfter('=').toIntOrNull()?.let { e.substringBefore('=') to it }
        }.toMap()
        set(v) { prefs.edit().putStringSet("deferred", v.map { (p, n) -> "$p=$n" }.toSet()).apply() }

    var failures: Int
        get() = prefs.getInt("failures", 0)
        set(v) { prefs.edit().putInt("failures", v).putLong("last_failure", System.currentTimeMillis()).apply() }

    val lastFailureMs: Long get() = prefs.getLong("last_failure", 0)

    fun markApplied() { prefs.edit().putLong("applied_at_boot_count", bootCount()).commit() }

    /** T3: the manifest of the update that is installed but not yet active (what "Restart to finish" shows). */
    fun savePending(m: BuildManifest) = pendingFile.writeText(m.toJson())

    fun pendingManifest(): BuildManifest? = runCatching { BuildManifest.fromJson(pendingFile.readText()) }.getOrNull()

    val restartPending: Boolean
        get() = UpdateCheck.restartPending(prefs.getLong("applied_at_boot_count", -1).takeIf { it >= 0 }, bootCount())

    fun check(image: BuildManifest?, runningInstallerVersion: Int, selected: Set<String>) =
        UpdateCheck.State(image, runningInstallerVersion, selected, removed, deferred, restartPending)

    /** Android's boot counter: changes on every boot, unlike a boot time computed from the wall clock. */
    private fun bootCount(): Long = android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1).toLong()

    companion object {
        private val IMAGE_MANIFEST = File("/product/etc/plus/build-manifest.json")

        /** The manifest the pipeline wrote into the running image, null on an image without one. */
        fun imageManifest(): BuildManifest? = runCatching { BuildManifest.fromJson(IMAGE_MANIFEST.readText()) }.getOrNull()

    }
}

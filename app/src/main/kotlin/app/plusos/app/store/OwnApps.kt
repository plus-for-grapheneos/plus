// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.store

import android.content.Context
import android.content.pm.PackageManager
import app.plusos.core.OwnApp
import app.plusos.core.OwnAppRecipe
import java.io.File
import java.security.MessageDigest

/**
 * The phone side of "your own app" add-ons ([OwnApp]): what is installed, which recipes the owner chose, and the last
 * catalog copy of the recipes (so a build without the catalog keeps the owner's own apps).
 */
class OwnApps(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("own_apps", Context.MODE_PRIVATE)
    private val savedFile = File(ctx.filesDir, "own-app-recipes.json")

    var selected: Set<String>
        get() = prefs.getStringSet("selected", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("selected", v).apply() }

    var saved: List<OwnAppRecipe>
        get() = runCatching { OwnApp.decodeRecipes(savedFile.readText()) }.getOrDefault(emptyList())
        set(v) { savedFile.writeText(OwnApp.encodeRecipes(v)) }

    /**
     * The installed app as the phone sees it: version, the signer of its APK files, the files themselves (base and
     * splits; under /data/app after Play updates, else the image copy) and every permission it requests. Null when the
     * package isn't installed.
     */
    fun installed(packageName: String): OwnApp.Installed? {
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val app = requireNotNull(info.applicationInfo) { "$packageName has no application info" }
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        check(signers.size == 1) { "$packageName must have exactly one signer" }
        val signer = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).joinToString("") { "%02x".format(it) }
        val apks = (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).map { File(it) }
        apks.forEach { check(it.canRead()) { "can't read ${it.path}" } }
        return OwnApp.Installed(packageName, info.longVersionCode, signer, apks, info.requestedPermissions.orEmpty().toSet())
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.store

import android.content.Context
import android.content.pm.PackageManager
import app.plusos.app.BuildConfigCompat
import app.plusos.app.update.BuildRunner
import app.plusos.core.AddonPlan
import app.plusos.core.CatalogLink
import app.plusos.core.Downloader
import app.plusos.core.RepoApp
import app.plusos.core.RepoIndex
import app.plusos.core.RepoIndexVerifier
import app.plusos.core.SignerPins
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * The +PLUS add-on catalog (spec §8, T5): a signed index plus APKs, found through a catalog link
 * (`…/index.json#sha256=<key hash>`). The owner's link, entered at key import, wins over this build's built-in one.
 * The rollback serial and the signer pins are kept per catalog key. Every APK is checked by sha256, package, version and
 * signer before use.
 */
class RepoClient(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("repo", Context.MODE_PRIVATE)

    /** The catalog in use, or null when there is none (no built-in link and none entered). */
    fun link(): CatalogLink? = (prefs.getString(LINK, null) ?: builtIn(ctx)).takeIf { it.isNotBlank() }
        ?.let { runCatching { CatalogLink.parse(it, allowHttp(ctx)) }.getOrNull() }

    val configured: Boolean get() = link() != null

    fun fetchIndex(): RepoIndex {
        val link = requireNotNull(link()) { "no catalog link" }
        val bytes = URL(link.indexUrl).readBytes()
        val sig = URL(link.indexUrl + ".sig").readBytes()
        val serialKey = "serial:${link.keySha256}"
        val last = prefs.getLong(serialKey, -1).takeIf { it >= 0 }
        val index = RepoIndexVerifier.verify(bytes, sig, link.keySha256, last)
        prefs.edit().putLong(serialKey, index.serial).apply()
        return index
    }

    var selected: Set<String>
        get() = prefs.getStringSet("selected", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("selected", v).apply() }

    /** Downloads [app] into [dir] and checks everything the index promises about it. */
    fun download(app: RepoApp, dir: File): File {
        dir.mkdirs()
        val dest = File(dir, "${app.packageName}-${app.version}.apk")
        if (!dest.exists() || BuildRunner.sha256(dest) != app.sha256) {
            dest.delete()
            Downloader().fetch(URL(requireNotNull(link()).base + app.apk), dest, null) { _, _ -> }
        }
        check(BuildRunner.sha256(dest).equals(app.sha256, ignoreCase = true)) { "${app.apk} does not match the index" }
        val info = requireNotNull(ctx.packageManager.getPackageArchiveInfo(dest.path, PackageManager.GET_SIGNING_CERTIFICATES)) {
            "${app.apk} is not a valid APK"
        }
        check(info.packageName == app.packageName && info.longVersionCode == app.version.toLong()) { "${app.apk} is not ${app.packageName} ${app.version}" }
        // Review I6: a debuggable app in a privileged image is a way in; only a debuggable (dev) +PLUS accepts one.
        val debuggable = ((info.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        check(!debuggable || BuildConfigCompat.isDebuggable(ctx)) { "${app.apk} is a debug build; refusing it" }
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        check(signers.size == 1) { "${app.apk} must have exactly one signer" }
        val signer = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).joinToString("") { "%02x".format(it) }
        check(signer.equals(app.signerSha256, ignoreCase = true)) { "${app.apk} signer does not match the index" }
        val pins = ctx.getSharedPreferences("signer_pins:${requireNotNull(link()).keySha256}", Context.MODE_PRIVATE)
        SignerPins(PrefsMap(pins)).check(app.packageName, signer)
        return dest
    }

    companion object {
        private const val LINK = "catalog_link"

        fun builtIn(ctx: Context): String = ctx.getString(app.plusos.app.R.string.catalog_link)
        fun allowHttp(ctx: Context): Boolean = ctx.resources.getBoolean(app.plusos.app.R.bool.catalog_allow_http)

        /** Only from key import: changing the catalog means re-importing the key. Null = back to the built-in link. */
        fun saveLink(ctx: Context, link: CatalogLink?) {
            val prefs = ctx.getSharedPreferences("repo", Context.MODE_PRIVATE)
            if (link == null || link.toString() == builtIn(ctx)) prefs.edit().remove(LINK).apply()
            else prefs.edit().putString(LINK, link.toString()).apply()
        }

        fun installerOf(index: RepoIndex) = index.apps.filter { it.packageName == AddonPlan.INSTALLER }.maxByOrNull { it.version }
    }
}

/** SharedPreferences as the MutableMap SignerPins persists into. */
private class PrefsMap(private val p: android.content.SharedPreferences) : AbstractMutableMap<String, String>() {
    override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
        get() = p.all.mapValues { it.value.toString() }.toMutableMap().entries
    override fun put(key: String, value: String): String? = get(key).also { p.edit().putString(key, value).commit() }
    override fun get(key: String): String? = p.getString(key, null)
}

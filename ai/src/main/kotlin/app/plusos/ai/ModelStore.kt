// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.plusos.core.Downloader
import app.plusos.core.Publisher
import app.plusos.core.CatalogLink
import app.plusos.core.ai.AiCatalog
import app.plusos.core.ai.AiModel
import java.io.File
import java.net.URL

/**
 * Models: the signed catalog (ai-service-design §3) and the files in the app's private storage. A file counts as
 * installed only after its size and sha256 matched the catalog; that check is remembered per file.
 */
class ModelStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("models", Context.MODE_PRIVATE)
    val dir = File(ctx.filesDir, "models").apply { mkdirs() }

    /** T5: the catalog link (…/models.json#sha256=<catalog key hash>) built into this app; null = none. */
    private fun link(): CatalogLink? = ctx.getString(R.string.catalog_link).takeIf { it.isNotBlank() }
        ?.let { runCatching { CatalogLink.parse(it, ctx.resources.getBoolean(R.bool.catalog_allow_http)) }.getOrNull() }

    fun catalog(): AiCatalog? {
        val link = link() ?: return cached()
        return runCatching {
            val bytes = URL(link.indexUrl).readBytes()
            val sig = URL(link.indexUrl + ".sig").readBytes()
            val c = AiCatalog.verify(bytes, sig, link.keySha256, prefs.getLong("serial:${link.keySha256}", -1).takeIf { it >= 0 })
            prefs.edit().putLong("serial:${link.keySha256}", c.serial).putString("catalog", c.toJson()).apply()
            c
        }.getOrElse { cached() }
    }

    private fun cached(): AiCatalog? = prefs.getString("catalog", null)?.let {
        runCatching { AiCatalog.fromTrustedJson(it) }.getOrNull()
    }

    fun fileOf(m: AiModel) = File(dir, m.checked().file)

    fun installed(m: AiModel): Boolean {
        val f = fileOf(m)
        return f.exists() && f.length() == m.size && prefs.getString("verified:${m.file}", null) == m.sha256
    }

    fun onUnmeteredNetwork(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Downloads (resumable) and verifies [m]; a mismatching file is deleted. */
    fun download(m: AiModel, progress: (Long, Long) -> Unit) {
        val f = fileOf(m)
        Downloader().fetch(URL(m.url), f, m.size, progress)
        val sha = Publisher.sha256(f)
        if (sha != m.sha256) { f.delete(); throw IllegalStateException("${m.name}: downloaded file does not match the catalog") }
        prefs.edit().putString("verified:${m.file}", sha).apply()
    }

    fun delete(m: AiModel) {
        fileOf(m).delete()
        File(fileOf(m).path + ".part").delete()
        prefs.edit().remove("verified:${m.file}").apply()
    }
}

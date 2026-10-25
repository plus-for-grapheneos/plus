// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import java.util.zip.ZipFile

/**
 * Before a new +PLUS is baked into the image (spec §7, review I4): it must carry the tools it runs (avbroot, afsr, the
 * signing helper) and its own three system XMLs (privileged permissions, default permissions, sysconfig). The XMLs are
 * extracted into [outDir] and used for the image, so they always match the APK being baked.
 */
object InstallerCheck {
    class Rejected(message: String) : Exception(message)

    val TOOLS = listOf("libavbroot.so", "libafsr.so", "libsignhelper.so")

    /** image path → asset name inside the APK (assets/system/...). */
    val XMLS = mapOf(
        "system/etc/permissions/app.seamlessupdate.client.xml" to "app.seamlessupdate.client.permissions.xml",
        "system/etc/default-permissions/app.seamlessupdate.client.xml" to "app.seamlessupdate.client.default-permissions.xml",
        "system/etc/sysconfig/app.seamlessupdate.client.xml" to "app.seamlessupdate.client.sysconfig.xml",
    )

    /** Returns image path → extracted XML file. */
    fun verify(apk: File, outDir: File): Map<String, File> {
        val zip = try { ZipFile(apk) } catch (e: Exception) { throw Rejected("${apk.name} is not an APK") }
        zip.use { z ->
            val missing = TOOLS.filter { z.getEntry("lib/arm64-v8a/$it") == null }
            if (missing.isNotEmpty()) throw Rejected("${apk.name} lacks $missing")
            outDir.mkdirs()
            return XMLS.mapValues { (_, asset) ->
                val e = z.getEntry("assets/system/$asset") ?: throw Rejected("${apk.name} lacks assets/system/$asset")
                File(outDir, asset).also { f -> z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } } }
            }
        }
    }
}

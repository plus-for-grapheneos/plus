// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.nio.ByteBuffer

/**
 * T3, install without activating: update_engine writes the inactive slot but leaves it inactive
 * (`SWITCH_SLOT_ON_REBOOT=0`); the owner's "Restart to finish" activates it with `setShouldSwitchSlotOnReboot`, which
 * needs the payload's metadata as a file. avbroot's OTAs have no `payload_metadata.bin`, so it is cut from the start
 * of `payload.bin`: the 24-byte header (`CrAU`, version 2, manifest size, metadata signature size), the manifest, and
 * the metadata signature.
 */
object PayloadMetadata {
    const val HEADER_SIZE = 24
    const val INSTALL_ONLY = "SWITCH_SLOT_ON_REBOOT=0"

    /** Bytes of metadata to copy from the start of payload.bin; [metadataSizeProperty] is METADATA_SIZE (header + manifest). */
    fun length(header: ByteArray, metadataSizeProperty: Long): Long {
        require(header.size >= HEADER_SIZE) { "payload header too short" }
        val b = ByteBuffer.wrap(header)
        require(String(header, 0, 4, Charsets.US_ASCII) == "CrAU") { "not an update payload" }
        require(b.getLong(4) == 2L) { "unsupported payload version ${b.getLong(4)}" }
        val manifest = b.getLong(12)
        val signature = b.getInt(20).toLong() and 0xffffffffL
        require(HEADER_SIZE + manifest == metadataSizeProperty) { "payload header and METADATA_SIZE disagree" }
        return HEADER_SIZE + manifest + signature
    }

    /** update_engine's kUpdatedButNotActive: what a successful install-only apply reports (review of +PLUS 11). */
    const val UPDATED_BUT_NOT_ACTIVE = 52

    /** True when update_engine's result [code] means the update is installed (active or waiting for activation). */
    fun installed(code: Int): Boolean = code == 0 || code == UPDATED_BUT_NOT_ACTIVE

    /** update_engine headers for an install that leaves the new slot inactive. */
    fun installOnly(headers: List<String>): List<String> =
        headers.filterNot { it.startsWith("SWITCH_SLOT_ON_REBOOT=") } + INSTALL_ONLY

    /** METADATA_SIZE from payload_properties.txt lines. */
    fun metadataSize(headers: List<String>): Long =
        requireNotNull(headers.firstOrNull { it.startsWith("METADATA_SIZE=") }?.substringAfter('=')?.trim()?.toLongOrNull()) {
            "no METADATA_SIZE in payload_properties.txt"
        }
}

/** T3: what the owner is about to activate, in plain words, from the running image's manifest to the new one. */
object ChangeList {
    fun between(old: BuildManifest?, new: BuildManifest): List<String> {
        if (old == null) return listOf("GrapheneOS ${new.grapheneos.build}", "+PLUS ${new.installer.version}") +
            new.addons.map { "${AddonNames.addon(it.id)} ${it.version} (new)" } + ownApps(emptyList(), new.ownApps)
        val out = mutableListOf<String>()
        if (old.grapheneos.build != new.grapheneos.build) out += "GrapheneOS ${old.grapheneos.build} → ${new.grapheneos.build}"
        if (old.installer.version != new.installer.version) out += "+PLUS ${old.installer.version} → ${new.installer.version}"
        val before = old.addons.associateBy { it.id }
        for (a in new.addons) {
            val name = AddonNames.addon(a.id)
            val was = before[a.id]
            when {
                was == null -> out += "$name ${a.version} (new)"
                was.version != a.version -> out += "$name ${was.version} → ${a.version}"
            }
            for ((pkg, perms) in a.privappPermissions) {
                val had = was?.privappPermissions?.get(pkg).orEmpty().toSet()
                val added = perms.filterNot { it in had }
                if (was != null && added.isNotEmpty()) {
                    out += "${AddonNames.pkg(pkg)} gets ${if (added.size == 1) "a new permission" else "new permissions"}: " +
                        added.joinToString(", ") { it.substringAfterLast('.') }
                }
            }
        }
        old.addons.filter { o -> new.addons.none { it.id == o.id } }.forEach { out += "${AddonNames.addon(it.id)} removed" }
        out += ownApps(old.ownApps, new.ownApps)
        if (!old.debugRoot && new.debugRoot) out += "Debug root ON: root for adb shell over USB (development only)"
        if (old.debugRoot && !new.debugRoot) out += "Debug root off"
        if (old.ownerKeyFingerprint != new.ownerKeyFingerprint) out += "Signed with a different key"
        return out.ifEmpty { listOf("No changes to GrapheneOS or the apps (a rebuild)") }
    }

    /** "Your own app" add-ons: every grant is listed when one is added, since it becomes part of the trusted OS. */
    private fun ownApps(old: List<OwnApp.Entry>, new: List<OwnApp.Entry>): List<String> {
        val out = mutableListOf<String>()
        val before = old.associateBy { it.packageName }
        for (a in new) {
            val was = before[a.packageName]
            val names = { p: List<String> -> p.sorted().joinToString(", ") { it.substringAfterLast('.') } }
            if (was == null) {
                out += "${a.name} ${a.version} from this phone, as a system app with ${a.grants.size} system permissions: ${names(a.grants)}"
                continue
            }
            if (was.version != a.version) out += "${a.name} ${was.version} → ${a.version}"
            val added = a.grants.filterNot { it in was.grants }
            if (added.isNotEmpty()) {
                out += "${a.name} gets ${if (added.size == 1) "a new permission" else "new permissions"}: ${names(added)}"
            }
        }
        old.filter { o -> new.none { it.packageName == o.packageName } }
            .forEach { out += "${it.name} is no longer a system app (it stays installed as a normal app)" }
        return out
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File

/**
 * Debug root (T2, owner 2026-10-23 and 2026-10-31): a development tool, used rarely with the phone on the owner's desk,
 * so tools can be debugged without building GrapheneOS from source as userdebug. When on, avbroot adds Magisk to the
 * boot image while +PLUS signs it with the owner's key (bootloader stays locked); turning it off is the normal rootless
 * rebuild, a few minutes later. It is turned on only from the key-import screen (the owner's key backup), shown on the
 * Restart screen before it takes effect, and announced by a notification for as long as it is on.
 *
 * Root for the adb shell only: no Magisk app is installed, modules and Zygisk stay off, and Magisk's policy is set to
 * ADB only on the phone (docs/debug-root.md).
 */
object DebugRoot {
    class Refused(message: String) : Exception(message)

    const val MAGISK_PACKAGE = "com.topjohnwu.magisk"

    /** Official Magisk releases (CN=John Wu; read from Magisk v31.0 from its GitHub release, 2026-09-25). */
    val MAGISK_SIGNERS = setOf("b4cb83b4dad99f997dbe872f013aa16c14eec41d167021f371f7e1330f273ee6")

    /** Oldest Magisk accepted (31.0 is the version tested). */
    const val MIN_MAGISK_VERSION = 30000L

    /**
     * Magisk's early-boot data partition per phone model (`--magisk-preinit-device`), as recorded by the phone test.
     * Without one avbroot is told to go ahead: Magisk then runs without it, which only matters for modules (off here).
     */
    val PREINIT: Map<String, String> = emptyMap()

    data class Magisk(val apk: File, val preinitDevice: String?)

    /** avbroot `ota patch` arguments for the boot image: rootless normally, Magisk when debug root is on. */
    fun patchArgs(magisk: Magisk?): List<String> = when {
        magisk == null -> listOf("--rootless")
        magisk.preinitDevice != null -> listOf("--magisk", "${magisk.apk}", "--magisk-preinit-device", magisk.preinitDevice)
        else -> listOf("--magisk", "${magisk.apk}", "--ignore-magisk-warnings")
    }

    /** The owner's Magisk APK must be an official release, new enough. */
    fun checkMagisk(packageName: String, versionCode: Long, signerSha256: String) {
        if (packageName != MAGISK_PACKAGE) throw Refused("this is $packageName, not Magisk")
        if (signerSha256.lowercase() !in MAGISK_SIGNERS) throw Refused("this Magisk APK is not signed by the Magisk project")
        if (versionCode < MIN_MAGISK_VERSION) throw Refused("Magisk $versionCode is too old; use 31.0 or newer")
    }
}

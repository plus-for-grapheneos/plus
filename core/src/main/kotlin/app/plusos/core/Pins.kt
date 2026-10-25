// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Values +PLUS trusts without asking: GrapheneOS's own signing keys, per phone model (GrapheneOS signs each model with
 * its own keys), in resources `pins/<device>/`. A model without keys here is not supported: +PLUS never builds for it
 * (+PLUS 11, device-aware). +PLUS 12: every model GrapheneOS supports, Pixel 6 and later. Collected by
 * tools/collect-pins.py from each model's official OTA: the AVB key's SHA-256 matches the verified boot key hash
 * GrapheneOS publishes for that model, and avbroot verified the whole OTA against both keys (2026-10-23; AllModelsPinsTest
 * holds the hashes).
 */
object Pins {
    /** plus-setup's default model (the phone it was first built for). */
    const val DEFAULT_DEVICE = "tokay"
    const val CHANNEL = "stable"

    /** GrapheneOS's codename → the model's name, for every model with keys pinned here. */
    private val MODEL_NAMES = linkedMapOf(
        "frankel" to "Pixel 10",
        "blazer" to "Pixel 10 Pro",
        "rango" to "Pixel 10 Pro Fold",
        "mustang" to "Pixel 10 Pro XL",
        "stallion" to "Pixel 10a",
        "oriole" to "Pixel 6",
        "raven" to "Pixel 6 Pro",
        "bluejay" to "Pixel 6a",
        "panther" to "Pixel 7",
        "cheetah" to "Pixel 7 Pro",
        "lynx" to "Pixel 7a",
        "shiba" to "Pixel 8",
        "husky" to "Pixel 8 Pro",
        "akita" to "Pixel 8a",
        "tokay" to "Pixel 9",
        "caiman" to "Pixel 9 Pro",
        "comet" to "Pixel 9 Pro Fold",
        "komodo" to "Pixel 9 Pro XL",
        "tegu" to "Pixel 9a",
        "felix" to "Pixel Fold",
        "tangorpro" to "Pixel Tablet",
    )

    /** Models with GrapheneOS keys pinned here. */
    val SUPPORTED_DEVICES: List<String> = MODEL_NAMES.keys.toList()

    /** "Pixel 9" for "tokay"; the codename itself for a model +PLUS doesn't know. */
    fun modelName(device: String): String = MODEL_NAMES[device] ?: device

    class DevicePins(val device: String, val avbPkmd: ByteArray, val otaCert: X509Certificate)

    /** GrapheneOS's keys for [device], or null when +PLUS doesn't support that model. */
    fun forDevice(device: String): DevicePins? {
        if (device !in SUPPORTED_DEVICES) return null
        val pkmd = resource("$device/grapheneos_avb_pkmd.bin") ?: return null
        val cert = resource("$device/grapheneos_ota.x509.pem") ?: return null
        return DevicePins(device, pkmd,
            CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate)
    }

    private fun resource(name: String): ByteArray? =
        Pins::class.java.getResourceAsStream("/pins/$name")?.use { it.readBytes() }
}

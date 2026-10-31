// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.pipeline

import java.io.File

enum class Stage { VERIFY_INPUT, EXTRACT, AVB_UNPACK, FS_UNPACK, EDIT, FS_PACK, AVB_PACK, PATCH, VERIFY_OUTPUT }

class PipelineException(val stage: Stage, val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NO_SPACE, TOOL_FAILED, VERIFY_FAILED, IMAGE_LAYOUT, BAD_INPUT }
}

/** How the owner key signs: a key file (PC setup), or through the signing helper (phone keystore, spec §5.4). */
sealed interface SignerConfig {
    val cert: File
    val avbPkmd: File

    /** [passphrase]: the key file is encrypted PKCS#8; avbroot gets the passphrase through PLUS_KEY_PASS (review I10). */
    data class KeyFile(val key: File, override val cert: File, override val avbPkmd: File, val passphrase: String? = null) : SignerConfig

    data class Helper(
        val publicKey: File,
        override val cert: File,
        override val avbPkmd: File,
        val helperPath: String,
        val env: Map<String, String> = emptyMap(),
    ) : SignerConfig
}

/**
 * The files a build adds. Keys of [installerXmls], [addonApks] and [ownAppApks] are image paths as in the build manifest:
 * `system/...` (inside the system partition's `/system`) or `product/...`. [ownAppApks]: the owner's installed apps,
 * copied unchanged; [dynamicCodeOverlay]: +PLUS's overlay, used only when an own app needs it.
 */
data class AddedFiles(
    val installerApk: File,
    val installerXmls: Map<String, File>,
    val addonApks: Map<String, File>,
    val ownAppApks: Map<String, File> = emptyMap(),
    val dynamicCodeOverlay: File? = null,
)

data class PipelineResult(val output: File)

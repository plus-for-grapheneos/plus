// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Builds a +PLUS repository (spec §8) from a draft: copies each APK as `<package>-<version>.apk`, fills in its
 * sha256, package, version and signer digest, bumps `serial` past the previous index, and signs `index.json`.
 * [apkInfo] reads an APK's package, versionCode and signer (apksigner/aapt2 on the PC; a fake in tests).
 */
class Publisher(private val apkInfo: (File) -> ApkInfo) {
    data class ApkInfo(val packageName: String, val versionCode: Int, val signerSha256: String, val debuggable: Boolean = false)

    @Serializable
    data class Draft(val repo: String, val apps: List<DraftApp>, val recipes: List<OwnAppRecipe> = emptyList()) {
        companion object {
            fun fromJson(s: String): Draft = DRAFT_JSON.decodeFromString(serializer(), s)
        }
    }

    @Serializable
    data class DraftApp(
        val file: String,
        val addon: String,
        val privileged: Boolean,
        @SerialName("privapp_permissions") val privappPermissions: List<String> = emptyList(),
        @SerialName("min_installer") val minInstaller: Int = 1,
    )

    /** [validDays]: T5 expiry; the repository must be republished before then or phones stop taking add-on updates from it. */
    fun publish(draft: Draft, apkDir: File, key: PrivateKey, site: File, published: String, validDays: Long = VALID_DAYS): RepoIndex {
        site.mkdirs()
        val previous = File(site, "index.json").takeIf { it.exists() }
            ?.let { RepoIndex.JSON.decodeFromString(RepoIndex.serializer(), it.readText()).serial } ?: 0L
        val apps = draft.apps.map { d ->
            val src = File(apkDir, d.file)
            val info = apkInfo(src)
            // Review I6: a debuggable +PLUS or add-on would let `adb run-as` into a privileged app (and the owner key).
            require(!info.debuggable) { "${d.file} is a debuggable build; publish release builds only" }
            val name = "${info.packageName}-${info.versionCode}.apk"
            src.copyTo(File(site, name), overwrite = true)
            RepoApp(
                packageName = info.packageName, addon = d.addon, version = info.versionCode, apk = name,
                sha256 = sha256(src), signerSha256 = info.signerSha256.lowercase(), privileged = d.privileged,
                privappPermissions = d.privappPermissions, minInstaller = d.minInstaller,
            )
        }
        // A recipe +PLUS would refuse on every phone is a publishing mistake: catch it here (same rules as OwnApp.plan).
        for (r in draft.recipes) {
            val policy = requireNotNull(OwnAppPolicy.ALL[r.packageName]) { "recipe ${r.id}: +PLUS can't make ${r.packageName} a system app" }
            require(policy.signers.containsAll(r.signers.map { it.lowercase() })) { "recipe ${r.id}: a signer +PLUS doesn't accept" }
            require(policy.maxGrant.containsAll(r.grant)) { "recipe ${r.id}: grants beyond +PLUS's limit: ${r.grant - policy.maxGrant}" }
        }
        val expires = java.time.Instant.parse(published).plus(java.time.Duration.ofDays(validDays)).toString()
        val index = RepoIndex(repo = draft.repo, published = published, serial = previous + 1, apps = apps,
            publicKey = SignedCatalog.encodedKey(key), expires = expires, recipes = draft.recipes)
        val bytes = index.toJson().toByteArray()
        File(site, "index.json").writeBytes(bytes)
        File(site, "index.json.sig").writeBytes(SignedCatalog.sign(bytes, key))
        return index
    }

    companion object {
        const val VALID_DAYS = 28L

        /** T5: the link owners enter in +PLUS, for a repository hosted at [base] (the folder holding index.json). */
        fun link(base: String, key: PrivateKey): String =
            CatalogLink(base.trimEnd('/') + "/index.json", CatalogLink.keySha256(CatalogLink.publicKeyOf(key).encoded)).toString()

        val DRAFT_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }

        fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

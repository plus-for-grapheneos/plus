// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A repository's signed list of apps (spec §8): `index.json` plus `index.json.sig`. T5: it carries its own public key
 * (base64 DER), which must hash to the owner's catalog link, and an expiry date.
 */
@Serializable
data class RepoIndex(
    val format: Int = 1,
    val repo: String,
    val published: String,
    val serial: Long,
    val apps: List<RepoApp>,
    @SerialName("public_key") val publicKey: String = "",
    val expires: String = "",
    /** "Your own app" recipes ([OwnAppRecipe]): facts about apps the owner installs themselves; never the apps. */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER)
    val recipes: List<OwnAppRecipe> = emptyList(),
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        internal val JSON = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = true }
    }
}

@Serializable
data class RepoApp(
    @SerialName("package") val packageName: String,
    val addon: String,
    val version: Int,
    val apk: String,
    val sha256: String,
    @SerialName("signer_sha256") val signerSha256: String,
    val privileged: Boolean,
    @SerialName("privapp_permissions") val privappPermissions: List<String> = emptyList(),
    @SerialName("min_installer") val minInstaller: Int = 1,
)

class IndexException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { SIGNATURE, ROLLBACK, FORMAT, KEY_MISMATCH, EXPIRED }
}

object RepoIndexVerifier {
    /**
     * T5: the key the index carries must hash to [keySha256] (from the owner's catalog link), the signature over the
     * exact bytes must verify with it, the index must not be past its expiry ([SignedCatalog]); then [RepoIndex.serial]
     * never goes backwards.
     */
    fun verify(indexBytes: ByteArray, sig: ByteArray, keySha256: String, lastSerial: Long?, now: Instant = Instant.now()): RepoIndex {
        SignedCatalog.check(indexBytes, sig, keySha256, now, "repository index")
        val index = try {
            RepoIndex.JSON.decodeFromString(RepoIndex.serializer(), indexBytes.decodeToString())
        } catch (e: SerializationException) {
            throw IndexException(IndexException.Reason.FORMAT, "repository index is not readable: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw IndexException(IndexException.Reason.FORMAT, "repository index is not readable: ${e.message}")
        }
        if (lastSerial != null && index.serial < lastSerial) {
            throw IndexException(IndexException.Reason.ROLLBACK, "repository index ${index.serial} is older than $lastSerial")
        }
        return index
    }
}

object AddonResolver {
    data class Resolution(val apps: List<RepoApp>, val skipped: Map<String, String>)

    /** The apps of the selected add-ons, for any GrapheneOS release (no tested-release gate, spec §8). */
    fun resolve(index: RepoIndex, selected: Set<String>, installerVersion: Int): Resolution {
        val skipped = linkedMapOf<String, String>()
        val apps = index.apps.filter { it.addon in selected }.filter { app ->
            (app.minInstaller <= installerVersion).also { ok ->
                if (!ok) skipped[app.addon] = "needs +PLUS version ${app.minInstaller} or newer"
            }
        }
        return Resolution(apps, skipped)
    }
}

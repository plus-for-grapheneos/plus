// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.ai

import app.plusos.core.IndexException
import app.plusos.core.SignedCatalog
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The signed model catalog (ai-service-design §3): `models.json` + `models.json.sig`, signed with the +PLUS repository
 * key, `serial` never going backwards. Model files are pinned by sha256, so where they are hosted isn't trusted.
 */
@Serializable
data class AiCatalog(
    val format: Int = 1,
    val serial: Long,
    val models: List<AiModel>,
    // T5: the catalog's own public key (must hash to the catalog link) and its expiry date.
    @SerialName("public_key") val publicKey: String = "",
    val expires: String = "",
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)
    fun model(id: String): AiModel? = models.firstOrNull { it.id == id }

    companion object {
        internal val JSON = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = true }

        /** A catalog that was already verified and stored by this app (not for untrusted input). */
        fun fromTrustedJson(s: String): AiCatalog = JSON.decodeFromString(serializer(), s)

        fun verify(bytes: ByteArray, sig: ByteArray, keySha256: String, lastSerial: Long?, now: Instant = Instant.now()): AiCatalog {
            SignedCatalog.check(bytes, sig, keySha256, now, "model catalog")
            val c = try {
                JSON.decodeFromString(serializer(), bytes.decodeToString())
            } catch (e: SerializationException) {
                throw IndexException(IndexException.Reason.FORMAT, "model catalog is not readable: ${e.message}")
            }
            if (lastSerial != null && c.serial < lastSerial) {
                throw IndexException(IndexException.Reason.ROLLBACK, "model catalog ${c.serial} is older than $lastSerial")
            }
            c.models.forEach { it.checked() }
            return c
        }
    }
}

@Serializable
data class AiModel(
    val id: String,
    val name: String,
    val file: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val license: String,
    @SerialName("license_url") val licenseUrl: String,
    @SerialName("context_tokens") val contextTokens: Int,
    val backends: List<String>,
) {
    /** The file name becomes a path in the app's storage and the URL is fetched: both must be plain. */
    fun checked(): AiModel {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "bad model id $id" }
        require(file.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.litertlm"))) { "bad model file name $file" }
        require(url.startsWith("https://")) { "model URLs must be https" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "bad sha256 for $id" }
        require(size > 0 && contextTokens > 0) { "bad size or context for $id" }
        require(backends.isNotEmpty() && backends.all { it == "gpu" || it == "cpu" }) { "bad backends for $id" }
        return this
    }
}

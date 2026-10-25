// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.ai

import app.plusos.core.Publisher
import app.plusos.core.SignedCatalog
import java.io.File
import java.security.PrivateKey
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer

/**
 * Publishes the model catalog next to the repository index (ai-service-design §3): `models.json` signed with the
 * repository key into `models.json.sig`, with `serial` one above the catalog already in [site].
 */
object ModelPublisher {
    fun publish(models: List<AiModel>, key: PrivateKey, site: File, now: Instant = Instant.now(), validDays: Long = Publisher.VALID_DAYS): AiCatalog {
        models.forEach { it.checked() }
        val dup = models.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "duplicate model ids: $dup" }
        site.mkdirs()
        val previous = File(site, "models.json").takeIf { it.exists() }?.let { AiCatalog.fromTrustedJson(it.readText()).serial } ?: 0L
        val catalog = AiCatalog(serial = previous + 1, models = models, publicKey = SignedCatalog.encodedKey(key),
            expires = now.plus(Duration.ofDays(validDays)).toString())
        val bytes = catalog.toJson().toByteArray()
        File(site, "models.json").writeBytes(bytes)
        File(site, "models.json.sig").writeBytes(SignedCatalog.sign(bytes, key))
        return catalog
    }

    /** The draft is a JSON list of complete model entries (sha256 and size taken from the files being pinned). */
    fun readDraft(f: File): List<AiModel> = AiCatalog.JSON.decodeFromString(ListSerializer(AiModel.serializer()), f.readText())
}

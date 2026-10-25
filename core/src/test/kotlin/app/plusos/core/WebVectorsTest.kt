// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import app.plusos.core.pipeline.FsEdit
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Test vectors for the browser setup page (web-setup/), which re-implements the key backup, the AVB key blob, the
 * owner certificate, the build manifest and the fs_metadata edit in JavaScript. The page must produce the same bytes
 * as this code, or the phone would reject what it builds. The first run writes web-setup/test/vectors.json from a
 * fresh key; every run checks that this Kotlin code still produces exactly what the file says.
 */
class WebVectorsTest {
    private val file = File("../web-setup/test/vectors.json")

    @Test
    fun kotlin_still_produces_the_web_page_vectors() {
        if (!file.exists()) file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), vectors(OwnerKey.generate())))
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        val key = BackupCodec.decode(saved.getValue("backup").jsonPrimitive.content)
        assertEquals(saved, vectors(key))
    }

    private fun vectors(k: OwnerKey): JsonObject {
        val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it) } }
        val b64 = { b: ByteArray -> Base64.getEncoder().encodeToString(b) }
        val fp = AvbKey.fingerprint(k.publicKey)
        val manifest = BuildManifest(
            device = "tokay",
            grapheneos = BuildManifest.Os("2026101600", "0f".repeat(32)),
            ownerKeyFingerprint = "sha256:" + hex(fp),
            installer = BuildManifest.Installer(11, "ab".repeat(32)),
            addons = emptyList(),
        )
        val toml = "[[entries]]\npath = \"/\"\nfile_type = \"Directory\"\n\n[[entries]]\npath = \"/system\"\nfile_type = \"Directory\"\n"
        val edited = FsEdit.addEntries(toml, listOf(
            FsEdit.FsEntry("/system/priv-app/Updater/Updater.apk", dir = false),
            FsEdit.FsEntry("/system/priv-app/Updater/lib/arm64/libavbroot.so", dir = false, executable = true),
            FsEdit.FsEntry("/system/etc/permissions/app.seamlessupdate.client.xml", dir = false),
        ))
        return JsonObject(mapOf(
            "backup" to JsonPrimitive(BackupCodec.encode(k)),
            "qr" to JsonPrimitive(BackupCodec.qrPayload(k)),
            "p" to JsonPrimitive(k.p.toString(16)),
            "q" to JsonPrimitive(k.q.toString(16)),
            "spki" to JsonPrimitive(b64(k.publicKey.encoded)),
            "avb_pkmd" to JsonPrimitive(b64(AvbKey.encode(k.publicKey))),
            "fingerprint" to JsonPrimitive(hex(fp)),
            "cert_pem" to JsonPrimitive(OwnerCert.toPem(OwnerCert.derive(k))),
            "manifest_json" to JsonPrimitive(manifest.toJson()),
            "fs_toml_in" to JsonPrimitive(toml),
            "fs_toml_out" to JsonPrimitive(edited),
        ))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ManifestAndIndexTest {
    private val phone = RepoApp(
        packageName = "app.plusos.phone", addon = "callscreen-phone", version = 31, apk = "PlusPhone-31.apk",
        sha256 = "aa", signerSha256 = "bb", privileged = true,
        privappPermissions = listOf("android.permission.CAPTURE_AUDIO_OUTPUT"), minInstaller = 1,
    )

    private val repoKey = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
    private val keyHash = CatalogLink.keySha256(repoKey.public.encoded)
    private val now = Instant.parse("2026-10-24T00:00:00Z")

    private fun index(
        serial: Long, apps: List<RepoApp> = listOf(phone),
        publicKey: String = Base64.getEncoder().encodeToString(repoKey.public.encoded), expires: String = "2026-11-21T12:00:00Z",
    ) = RepoIndex(format = 1, repo = "plus-main", published = "2026-10-21T12:00:00Z", serial = serial, apps = apps,
        publicKey = publicKey, expires = expires)

    private fun signed(i: RepoIndex, by: java.security.PrivateKey = repoKey.private): Pair<ByteArray, ByteArray> {
        val bytes = i.toJson().toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(by); update(bytes); sign() }
        return bytes to sig
    }

    @Test
    fun manifest_round_trip_is_stable() {
        val m = BuildManifest(
            device = "tokay",
            grapheneos = BuildManifest.Os("2026101600", "fb75fa63"),
            ownerKeyFingerprint = "sha256:0011",
            installer = BuildManifest.Installer(12, "cc"),
            addons = listOf(
                BuildManifest.Addon(
                    "callscreen-phone", 31,
                    listOf(BuildManifest.Apk("app.plusos.phone", "aa", "product/priv-app/PlusPhone/PlusPhone.apk")),
                    mapOf("app.plusos.phone" to listOf("android.permission.CAPTURE_AUDIO_OUTPUT")),
                ),
            ),
        )
        val json = m.toJson()
        assertEquals(json, BuildManifest.fromJson(json).toJson())
        assertEquals(m, BuildManifest.fromJson(json))
        assertEquals(true, json.contains("\"owner_key_fingerprint\""))
    }

    @Test
    fun index_good_signature() {
        val (bytes, sig) = signed(index(42))
        assertEquals(42, RepoIndexVerifier.verify(bytes, sig, keyHash, lastSerial = 41, now = now).serial)
    }

    @Test
    fun index_bad_signature_SIGNATURE() {
        val (bytes, sig) = signed(index(42))
        sig[10] = (sig[10] + 1).toByte()
        assertEquals(IndexException.Reason.SIGNATURE,
            assertThrows<IndexException> { RepoIndexVerifier.verify(bytes, sig, keyHash, null, now) }.reason)
    }

    /** T5: the key inside the catalog must be the one the owner's link names; a catalog can't bring its own. */
    @Test
    fun index_with_another_key_KEY_MISMATCH() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
        val (bytes, sig) = signed(index(42, publicKey = Base64.getEncoder().encodeToString(other.public.encoded)), other.private)
        assertEquals(IndexException.Reason.KEY_MISMATCH,
            assertThrows<IndexException> { RepoIndexVerifier.verify(bytes, sig, keyHash, null, now) }.reason)
    }

    /** T5: the right key embedded, but signed by someone else. */
    @Test
    fun index_signed_by_another_key_SIGNATURE() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
        val (bytes, sig) = signed(index(42), other.private)
        assertEquals(IndexException.Reason.SIGNATURE,
            assertThrows<IndexException> { RepoIndexVerifier.verify(bytes, sig, keyHash, null, now) }.reason)
    }

    /** T5: an old catalog can't be served forever to hide a newer one (freeze attack). */
    @Test
    fun index_past_its_expiry_EXPIRED() {
        val (bytes, sig) = signed(index(42, expires = "2026-10-23T23:59:59Z"))
        assertEquals(IndexException.Reason.EXPIRED,
            assertThrows<IndexException> { RepoIndexVerifier.verify(bytes, sig, keyHash, null, now) }.reason)
    }

    @Test
    fun index_without_its_key_FORMAT() {
        val bytes = ("{\"format\":1,\"repo\":\"r\",\"published\":\"2026-10-21T12:00:00Z\",\"serial\":1,\"apps\":[]," +
            "\"expires\":\"2026-11-21T12:00:00Z\"}").toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(repoKey.private); update(bytes); sign() }
        assertEquals(IndexException.Reason.FORMAT,
            assertThrows<IndexException> { RepoIndexVerifier.verify(bytes, sig, keyHash, null, now) }.reason)
    }

    @Test
    fun index_older_serial_ROLLBACK() {
        val (bytes, sig) = signed(index(40))
        assertEquals(IndexException.Reason.ROLLBACK,
            assertThrows<IndexException> { RepoIndexVerifier.verify(bytes, sig, keyHash, 41, now) }.reason)
    }

    @Test
    fun index_same_serial_ok() {
        val (bytes, sig) = signed(index(41))
        assertEquals(41, RepoIndexVerifier.verify(bytes, sig, keyHash, 41, now).serial)
    }

    @Test
    fun index_garbage_FORMAT() {
        val bytes = "not json".toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(repoKey.private); update(bytes); sign() }
        assertEquals(IndexException.Reason.FORMAT,
            assertThrows<IndexException> { RepoIndexVerifier.verify(bytes, sig, keyHash, null, now) }.reason)
    }

    @Test
    fun privapp_xml_exact() {
        val xml = PrivappXml.generate(
            mapOf("app.plusos.phone" to listOf("android.permission.MODIFY_PHONE_STATE", "android.permission.CAPTURE_AUDIO_OUTPUT")),
        )
        assertEquals(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- Generated by +PLUS for GrapheneOS. Not affiliated with or endorsed by the GrapheneOS project. -->
            <permissions>
                <privapp-permissions package="app.plusos.phone">
                    <permission name="android.permission.CAPTURE_AUDIO_OUTPUT"/>
                    <permission name="android.permission.MODIFY_PHONE_STATE"/>
                </privapp-permissions>
            </permissions>

            """.trimIndent(),
            xml,
        )
    }

    @Test
    fun resolver_includes_addon_for_any_release() {
        val r = AddonResolver.resolve(index(1), selected = setOf("callscreen-phone"), installerVersion = 1)
        assertEquals(listOf(phone), r.apps)
        assertEquals(emptyMap<String, String>(), r.skipped)
    }

    @Test
    fun resolver_skips_min_installer() {
        val r = AddonResolver.resolve(index(1, listOf(phone.copy(minInstaller = 5))), setOf("callscreen-phone"), 4)
        assertEquals(emptyList<RepoApp>(), r.apps)
        assertEquals(setOf("callscreen-phone"), r.skipped.keys)
    }

    @Test
    fun resolver_ignores_unselected() {
        assertEquals(emptyList<RepoApp>(), AddonResolver.resolve(index(1), emptySet(), 1).apps)
    }
}

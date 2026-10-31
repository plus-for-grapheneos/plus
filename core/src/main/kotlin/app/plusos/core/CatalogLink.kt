// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * T5 (+PLUS 13): where a catalog lives and which key signs it, in one link:
 * `https://host/path/index.json#sha256=<64 hex>`. The hash is the SHA-256 of the catalog's public key (DER), which the
 * catalog carries inside its signed bytes. Anyone can host or move a catalog, or change its key, by handing out a link;
 * the fragment is never sent to the server.
 */
data class CatalogLink(val indexUrl: String, val keySha256: String) {
    /** The folder the catalog's other files (APKs, models.json) are fetched from. */
    val base: String get() = indexUrl.substringBeforeLast('/') + "/"

    override fun toString() = "$indexUrl#sha256=$keySha256"

    class InvalidLink(message: String) : IllegalArgumentException(message)

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")

        /**
         * Plain http is accepted for an IP address in every build (2026-10-29, owner): the catalog is checked by its
         * signature, key hash, expiry and serial, and every APK by its hash, so the transport adds no integrity; a LAN
         * host has no certificate anyway. A named host needs https, unless [allowHttp] (development builds).
         */
        fun parse(link: String, allowHttp: Boolean = false): CatalogLink {
            val text = link.trim()
            val hashAt = text.indexOf("#sha256=")
            if (hashAt < 0) throw InvalidLink("the link has no key hash (#sha256=…)")
            val hash = text.substring(hashAt + "#sha256=".length).lowercase()
            if (!HASH.matches(hash)) throw InvalidLink("the key hash must be 64 hexadecimal characters")
            val url = text.substring(0, hashAt)
            val uri = runCatching { URI(url) }.getOrElse { throw InvalidLink("not a web address") }
            when (uri.scheme) {
                "https" -> {}
                "http" -> if (!allowHttp && !isIpAddress(uri.host.orEmpty())) {
                    throw InvalidLink("plain http:// only works with an IP address; use https:// for a named host")
                }
                else -> throw InvalidLink("the catalog link must start with https://")
            }
            if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null) throw InvalidLink("not a plain web address")
            val file = uri.path.orEmpty().substringAfterLast('/')
            if (!file.endsWith(".json")) throw InvalidLink("the link must name the catalog file (…/index.json)")
            return CatalogLink(url, hash)
        }

        private val IPV4 = Regex("""(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}""")
        private val IPV6 = Regex("""\[[0-9A-Fa-f:.]+]""")

        /** An IPv4 address or a bracketed IPv6 address as URI.host gives it; no name lookup. */
        internal fun isIpAddress(host: String): Boolean = IPV4.matches(host) || (IPV6.matches(host) && host.count { it == ':' } >= 2)

        fun keySha256(publicKeyDer: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(publicKeyDer).joinToString("") { "%02x".format(it) }

        /** The public half of a publisher's RSA private key, for embedding in a catalog and for its link. */
        fun publicKeyOf(key: PrivateKey): RSAPublicKey {
            val k = key as RSAPrivateCrtKey
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(k.modulus, k.publicExponent)) as RSAPublicKey
        }
    }
}

/**
 * The checks every signed catalog (repository index, model catalog) goes through before anything in it is used, in this
 * order: the key it carries hashes to the link's value, the signature over the exact bytes verifies with that key, and
 * it is not past its expiry date (an old catalog can't be served forever to hide a newer one). The serial check is the
 * caller's, after decoding.
 */
object SignedCatalog {
    private val LOOSE = Json { ignoreUnknownKeys = true }

    fun check(bytes: ByteArray, sig: ByteArray, keySha256: String, now: Instant, what: String) {
        val obj = try {
            LOOSE.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IndexException(IndexException.Reason.FORMAT, "$what is not readable")
        val keyText = obj["public_key"]?.jsonPrimitive?.contentOrNull
            ?: throw IndexException(IndexException.Reason.FORMAT, "$what carries no public key")
        val der = runCatching { Base64.getDecoder().decode(keyText) }
            .getOrElse { throw IndexException(IndexException.Reason.FORMAT, "$what has an unreadable public key") }
        if (CatalogLink.keySha256(der) != keySha256.lowercase()) {
            throw IndexException(IndexException.Reason.KEY_MISMATCH, "$what is signed with a different key than the catalog link names")
        }
        val key = runCatching { KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) }
            .getOrElse { throw IndexException(IndexException.Reason.FORMAT, "$what has an unreadable public key") }
        val ok = runCatching { Signature.getInstance("SHA256withRSA").run { initVerify(key); update(bytes); verify(sig) } }.getOrDefault(false)
        if (!ok) throw IndexException(IndexException.Reason.SIGNATURE, "$what signature is not valid")
        val expires = obj["expires"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: throw IndexException(IndexException.Reason.FORMAT, "$what has no expiry date")
        if (now.isAfter(expires)) throw IndexException(IndexException.Reason.EXPIRED, "$what expired on $expires; the publisher has not refreshed it")
    }

    fun sign(bytes: ByteArray, key: PrivateKey): ByteArray =
        Signature.getInstance("SHA256withRSA").run { initSign(key); update(bytes); sign() }

    fun encodedKey(key: PrivateKey): String = Base64.getEncoder().encodeToString(CatalogLink.publicKeyOf(key).encoded)
}

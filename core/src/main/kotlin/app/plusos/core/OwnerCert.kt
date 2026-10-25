// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.ByteArrayOutputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The owner's OTA certificate, derived deterministically from the key (spec §5.2): X.509 v1, serial 1,
 * `CN=+PLUS owner`, valid 2025-01-01 to 2049-12-31 (both UTCTime, as RFC 5280 requires before 2050),
 * sha256WithRSAEncryption. PKCS#1 v1.5 signatures are deterministic,
 * so the PC and the phone produce identical bytes; the phone compares it with the running OS's otacerts.zip.
 */
object OwnerCert {
    private val SHA256_WITH_RSA = Der.seq(Der.oid("2a864886f70d01010b"), Der.tlv(0x05, ByteArray(0)))
    private val NAME = Der.seq(Der.tlv(0x31, Der.seq(Der.oid("550403"), Der.tlv(0x0c, "+PLUS owner".toByteArray()))))
    private val VALIDITY = Der.seq(
        Der.tlv(0x17, "250101000000Z".toByteArray()),
        Der.tlv(0x17, "491231235959Z".toByteArray()),
    )

    fun derive(k: OwnerKey): X509Certificate {
        val tbs = Der.seq(Der.tlv(0x02, byteArrayOf(1)), SHA256_WITH_RSA, NAME, VALIDITY, NAME, k.publicKey.encoded)
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(k.privateKey); update(tbs); sign() }
        val der = Der.seq(tbs, SHA256_WITH_RSA, Der.tlv(0x03, byteArrayOf(0) + sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    fun toPem(c: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(c.encoded) +
            "\n-----END CERTIFICATE-----\n"

    fun fromPem(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
}

/** Minimal DER writer: definite lengths up to 3 bytes, which covers every structure +PLUS builds. */
internal object Der {
    fun tlv(tag: Int, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag)
        val l = body.size
        when {
            l < 0x80 -> o.write(l)
            l < 0x100 -> { o.write(0x81); o.write(l) }
            l < 0x10000 -> { o.write(0x82); o.write(l shr 8); o.write(l and 0xff) }
            else -> { o.write(0x83); o.write(l shr 16); o.write((l shr 8) and 0xff); o.write(l and 0xff) }
        }
        o.write(body)
        return o.toByteArray()
    }

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun oid(hex: String): ByteArray = tlv(0x06, java.util.HexFormat.of().parseHex(hex))
}

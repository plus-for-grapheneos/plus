// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * The owner's RSA-4096 signing key (spec §5.1), held as its two primes: everything else is derived from p and q, so the
 * backup text and QR code only need to carry those (spec §5.2). The public exponent is always 65537.
 */
class OwnerKey(val p: BigInteger, val q: BigInteger) {
    val publicKey: RSAPublicKey
    val privateKey: RSAPrivateCrtKey

    init {
        require(p != q) { "p and q must differ" }
        val n = p.multiply(q)
        val d = E.modInverse(p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE)))
        val kf = KeyFactory.getInstance("RSA")
        publicKey = kf.generatePublic(RSAPublicKeySpec(n, E)) as RSAPublicKey
        privateKey = kf.generatePrivate(
            RSAPrivateCrtKeySpec(
                n, E, d, p, q,
                d.mod(p.subtract(BigInteger.ONE)), d.mod(q.subtract(BigInteger.ONE)), q.modInverse(p),
            ),
        ) as RSAPrivateCrtKey
    }

    /** PKCS#1 v1.5 signature over an already-encoded DigestInfo (what keystore NONEwithRSA does on the phone). */
    fun signDigestInfo(di: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run {
        initSign(privateKey); update(di); sign()
    }

    companion object {
        val E: BigInteger = BigInteger.valueOf(65537)
        const val BITS = 4096

        fun generate(random: SecureRandom = SecureRandom()): OwnerKey {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(BITS, E), random)
            return fromPrivate(g.generateKeyPair().private as RSAPrivateCrtKey)
        }

        fun fromPrivate(k: RSAPrivateCrtKey): OwnerKey {
            require(k.publicExponent == E) { "public exponent must be 65537" }
            return OwnerKey(k.primeP, k.primeQ)
        }
    }
}

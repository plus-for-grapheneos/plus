// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.sign

/**
 * avbroot hands its signing helper a PKCS#1 v1.5 type-1 block, `00 01 FF… 00 ‖ DigestInfo` (spike S2b). The phone's
 * keystore key can only do PKCS#1 signing (never raw RSA), so the helper path checks the block strictly and passes
 * on just the DigestInfo; keystore NONEwithRSA re-applies the identical padding (spike S1).
 */
object Pkcs1 {
    private val hex = java.util.HexFormat.of()
    private val PREFIX = mapOf(
        "SHA256" to hex.parseHex("3031300d060960864801650304020105000420"),
        "SHA512" to hex.parseHex("3051300d060960864801650304020305000440"),
    )
    private val SIZE = mapOf("SHA256" to 32, "SHA512" to 64)

    fun digestInfo(block: ByteArray, algo: String): ByteArray {
        val m = requireNotNull(Regex("(SHA256|SHA512)_RSA4096").matchEntire(algo)) { "unsupported algorithm $algo" }
        val hash = m.groupValues[1]
        require(block.size == 512) { "a 4096-bit block is 512 bytes, got ${block.size}" }
        require(block[0] == 0.toByte() && block[1] == 1.toByte()) { "not a PKCS#1 type-1 block" }
        var i = 2
        while (i < block.size && block[i] == 0xff.toByte()) i++
        require(i - 2 >= 8) { "padding too short" }
        require(i < block.size && block[i] == 0.toByte()) { "padding not terminated" }
        val di = block.copyOfRange(i + 1, block.size)
        val prefix = PREFIX.getValue(hash)
        require(di.size == prefix.size + SIZE.getValue(hash) && di.copyOf(prefix.size).contentEquals(prefix)) {
            "DigestInfo does not match $algo"
        }
        return di
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.sign

import app.plusos.core.OwnerKey
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.Signature
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SignTest {
    private val hex = java.util.HexFormat.of()
    private val di256 = hex.parseHex("3031300d060960864801650304020105000420") +
        MessageDigest.getInstance("SHA-256").digest("x".toByteArray())
    private val di512 = hex.parseHex("3051300d060960864801650304020305000440") +
        MessageDigest.getInstance("SHA-512").digest("x".toByteArray())
    private val dir: File = Files.createTempDirectory("sign").toFile()

    private fun block(di: ByteArray, type: Int = 1, padLen: Int = 512 - 3 - di.size): ByteArray =
        byteArrayOf(0, type.toByte()) + ByteArray(padLen) { 0xff.toByte() } + byteArrayOf(0) + di

    @AfterEach
    fun cleanup() { dir.deleteRecursively() }

    @Test
    fun extracts_sha256_digestinfo() {
        val di = Pkcs1.digestInfo(block(di256), "SHA256_RSA4096")
        assertEquals(51, di.size)
        assertArrayEquals(di256, di)
    }

    @Test
    fun extracts_sha512_digestinfo() {
        assertArrayEquals(di512, Pkcs1.digestInfo(block(di512), "SHA512_RSA4096"))
    }

    @Test
    fun rejects_type2_block() {
        assertThrows<IllegalArgumentException> { Pkcs1.digestInfo(block(di256, type = 2), "SHA256_RSA4096") }
    }

    @Test
    fun rejects_short_padding() {
        val short = byteArrayOf(0, 1) + ByteArray(4) { 0xff.toByte() } + byteArrayOf(0) + di256
        assertThrows<IllegalArgumentException> { Pkcs1.digestInfo(short, "SHA256_RSA4096") }
    }

    @Test
    fun rejects_algo_mismatch() {
        assertThrows<IllegalArgumentException> { Pkcs1.digestInfo(block(di256), "SHA512_RSA4096") }
        assertThrows<IllegalArgumentException> { Pkcs1.digestInfo(block(di256), "SHA256_RSA2048") }
    }

    @Test
    fun protocol_round_trip() {
        // Written exactly as libsignhelper.so does: line 1 = algorithm, then the raw block; .req.part renamed to .req.
        val id = "123-456"
        File(dir, "$id.req.part").writeBytes("SHA256_RSA4096\n".toByteArray() + block(di256))
        File(dir, "$id.req.part").renameTo(File(dir, "$id.req"))
        File(dir, "999.req.part").writeBytes("SHA256_RSA4096\n".toByteArray())
        val p = SignProtocol(dir)
        val reqs = p.pending()
        assertEquals(listOf(id), reqs.map { it.id })
        assertEquals("SHA256_RSA4096", reqs[0].algo)
        assertArrayEquals(block(di256), reqs[0].block)

        val key = OwnerKey.generate()
        val sig = key.signDigestInfo(Pkcs1.digestInfo(reqs[0].block, reqs[0].algo))
        p.respond(reqs[0], sig)
        assertArrayEquals(sig, File(dir, "$id.resp").readBytes())
        assertTrue(p.pending().isEmpty(), "an answered request is not pending")
        val expected = Signature.getInstance("SHA256withRSA").run { initSign(key.privateKey); update("x".toByteArray()); sign() }
        assertArrayEquals(expected, sig)
    }
}

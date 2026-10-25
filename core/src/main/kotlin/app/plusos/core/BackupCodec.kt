// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.zip.CRC32

class BackupFormatException(val reason: Reason, message: String) : IllegalArgumentException(message) {
    enum class Reason { PREFIX, LENGTH, CHARSET, VERSION, CRC, FINGERPRINT }
}

/**
 * The owner key as backup text and QR string (spec §5.2): `PLUSKEY1` + base32 of
 * `0x01 ‖ p(256) ‖ q(256) ‖ fingerprint(8) ‖ CRC-32(previous 521 bytes)`, 525 bytes = 840 characters.
 */
object BackupCodec {
    const val PREFIX = "PLUSKEY1"
    private const val VERSION: Byte = 1
    private const val PRIME_LEN = 256
    private const val PAYLOAD_LEN = 1 + 2 * PRIME_LEN + 8 + 4
    private const val TEXT_LEN = PAYLOAD_LEN * 8 / 5
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Display form for the password manager: `PLUSKEY1-XXXXX-XXXXX-…`. */
    fun encode(k: OwnerKey): String = "$PREFIX-" + base32(payload(k)).chunked(5).joinToString("-")

    /** QR form: the same characters without separators (QR alphanumeric mode). */
    fun qrPayload(k: OwnerKey): String = PREFIX + base32(payload(k))

    /** Accepts either form, any case, with whitespace and `-` anywhere. */
    fun decode(text: String): OwnerKey {
        val clean = text.filterNot { it.isWhitespace() || it == '-' }.uppercase()
        if (!clean.startsWith(PREFIX)) fail(BackupFormatException.Reason.PREFIX, "the text must start with $PREFIX")
        val body = clean.substring(PREFIX.length)
        if (body.length != TEXT_LEN) fail(BackupFormatException.Reason.LENGTH, "expected $TEXT_LEN characters, got ${body.length}")
        val bytes = unbase32(body)
        val crc = CRC32().apply { update(bytes, 0, PAYLOAD_LEN - 4) }.value.toInt()
        if (ByteBuffer.wrap(bytes, PAYLOAD_LEN - 4, 4).int != crc) fail(BackupFormatException.Reason.CRC, "a character is wrong")
        if (bytes[0] != VERSION) fail(BackupFormatException.Reason.VERSION, "unknown backup version ${bytes[0]}")
        val p = BigInteger(1, bytes.copyOfRange(1, 1 + PRIME_LEN))
        val q = BigInteger(1, bytes.copyOfRange(1 + PRIME_LEN, 1 + 2 * PRIME_LEN))
        val key = try {
            OwnerKey(p, q)
        } catch (e: RuntimeException) {
            fail(BackupFormatException.Reason.FINGERPRINT, "the key material is invalid")
        }
        val fp = bytes.copyOfRange(1 + 2 * PRIME_LEN, 1 + 2 * PRIME_LEN + 8)
        if (!fp.contentEquals(AvbKey.fingerprint(key.publicKey))) {
            fail(BackupFormatException.Reason.FINGERPRINT, "the key does not match its fingerprint")
        }
        return key
    }

    private fun payload(k: OwnerKey): ByteArray {
        val b = ByteBuffer.allocate(PAYLOAD_LEN)
        b.put(VERSION).put(AvbKey.fixed(k.p, PRIME_LEN)).put(AvbKey.fixed(k.q, PRIME_LEN)).put(AvbKey.fingerprint(k.publicKey))
        val crc = CRC32().apply { update(b.array(), 0, PAYLOAD_LEN - 4) }.value.toInt()
        return b.putInt(crc).array()
    }

    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buf = 0
        var bits = 0
        for (byte in data) {
            buf = (buf shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buf shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    private fun unbase32(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) fail(BackupFormatException.Reason.CHARSET, "'$c' is not a backup character")
            buf = (buf shl 5) or v
            bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray()
    }

    private fun fail(r: BackupFormatException.Reason, msg: String): Nothing = throw BackupFormatException(r, msg)
}

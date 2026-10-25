// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackupCodecTest {
    private val key = OwnerKey.generate()

    @Test
    fun round_trip() {
        val back = BackupCodec.decode(BackupCodec.encode(key))
        assertEquals(key.p, back.p)
        assertEquals(key.q, back.q)
        assertEquals(840 + 8, BackupCodec.qrPayload(key).length)
        assertEquals(key.publicKey.modulus, BackupCodec.decode(BackupCodec.qrPayload(key)).publicKey.modulus)
    }

    @Test
    fun display_blocks() {
        val text = BackupCodec.encode(key)
        assertTrue(text.startsWith("PLUSKEY1-"))
        val blocks = text.removePrefix("PLUSKEY1-").split("-")
        assertEquals(168, blocks.size)
        assertTrue(blocks.all { it.length == 5 })
    }

    @Test
    fun one_wrong_char_is_CRC() {
        val qr = BackupCodec.qrPayload(key)
        val i = 400
        val wrong = qr.substring(0, i) + (if (qr[i] == 'A') 'B' else 'A') + qr.substring(i + 1)
        assertEquals(BackupFormatException.Reason.CRC, assertThrows<BackupFormatException> { BackupCodec.decode(wrong) }.reason)
    }

    @Test
    fun truncated_is_LENGTH() {
        val qr = BackupCodec.qrPayload(key)
        assertEquals(BackupFormatException.Reason.LENGTH,
            assertThrows<BackupFormatException> { BackupCodec.decode(qr.dropLast(5)) }.reason)
    }

    @Test
    fun wrong_prefix_is_PREFIX() {
        assertEquals(BackupFormatException.Reason.PREFIX,
            assertThrows<BackupFormatException> { BackupCodec.decode("HELLO" + BackupCodec.qrPayload(key)) }.reason)
    }

    @Test
    fun bad_character_is_CHARSET() {
        val qr = BackupCodec.qrPayload(key)
        assertEquals(BackupFormatException.Reason.CHARSET,
            assertThrows<BackupFormatException> { BackupCodec.decode(qr.substring(0, 20) + "8" + qr.substring(21)) }.reason)
    }

    @Test
    fun lowercase_and_spaces_accepted() {
        val messy = BackupCodec.encode(key).lowercase().replace("-", " - ").chunked(70).joinToString("\n")
        assertEquals(key.p, BackupCodec.decode(messy).p)
    }

    @Test
    fun swapped_p_q_still_valid() {
        val swapped = OwnerKey(key.q, key.p)
        assertEquals(key.publicKey.modulus, BackupCodec.decode(BackupCodec.encode(swapped)).publicKey.modulus)
    }
}

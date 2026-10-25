// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PayloadLocatorTest {
    private val dir: File = Files.createTempDirectory("payload").toFile()
    private val payload = Random(3).nextBytes(200_000)
    private val props = "FILE_HASH=abc=\nFILE_SIZE=200000\nMETADATA_HASH=def=\nMETADATA_SIZE=1234\n"

    @AfterEach
    fun cleanup() { dir.deleteRecursively() }

    private fun zip(storedPayload: Boolean): File {
        val f = File(dir, "update.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("META-INF/com/android/metadata").apply { method = ZipEntry.DEFLATED })
            z.write("ota-type=AB\n".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("payload.bin").apply {
                if (storedPayload) {
                    method = ZipEntry.STORED; size = payload.size.toLong(); compressedSize = size
                    crc = CRC32().apply { update(payload) }.value
                } else {
                    method = ZipEntry.DEFLATED
                }
            })
            z.write(payload); z.closeEntry()
            z.putNextEntry(ZipEntry("payload_properties.txt").apply { method = ZipEntry.DEFLATED })
            z.write(props.toByteArray()); z.closeEntry()
        }
        return f
    }

    @Test
    fun locates_stored_payload() {
        val f = zip(storedPayload = true)
        val info = PayloadLocator.locate(f)
        assertEquals(payload.size.toLong(), info.size)
        val read = ByteArray(info.size.toInt())
        RandomAccessFile(f, "r").use { it.seek(info.offset); it.readFully(read) }
        assertArrayEquals(payload, read)
        assertEquals(listOf("FILE_HASH=abc=", "FILE_SIZE=200000", "METADATA_HASH=def=", "METADATA_SIZE=1234"), info.headers)
    }

    @Test
    fun rejects_deflated_payload() {
        assertThrows<IllegalArgumentException> { PayloadLocator.locate(zip(storedPayload = false)) }
    }
}

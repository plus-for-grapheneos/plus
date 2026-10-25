// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Where `payload.bin` sits inside an A/B update zip, for `UpdateEngine.applyPayload(url, offset, size, headers)`.
 * The payload must be stored (not deflated) so update_engine can read it in place; the headers are the lines of
 * `payload_properties.txt`.
 */
object PayloadLocator {
    data class PayloadInfo(val offset: Long, val size: Long, val headers: List<String>)

    fun locate(zip: File): PayloadInfo {
        val headers = ZipFile(zip).use { z ->
            val props = requireNotNull(z.getEntry("payload_properties.txt")) { "no payload_properties.txt in $zip" }
            z.getInputStream(props).bufferedReader().readLines().filter { it.isNotBlank() }
        }
        RandomAccessFile(zip, "r").use { f ->
            val entry = centralDirectory(f).firstOrNull { it.name == "payload.bin" }
                ?: throw IllegalArgumentException("no payload.bin in $zip")
            require(entry.method == 0) { "payload.bin is compressed; update_engine needs it stored" }
            val local = ByteArray(30)
            f.seek(entry.localHeaderOffset)
            f.readFully(local)
            val lb = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
            require(lb.getInt(0) == 0x04034b50) { "bad local header for payload.bin" }
            val dataOffset = entry.localHeaderOffset + 30 + (lb.getShort(26).toInt() and 0xffff) + (lb.getShort(28).toInt() and 0xffff)
            return PayloadInfo(dataOffset, entry.size, headers)
        }
    }

    private data class Entry(val name: String, val method: Int, val size: Long, val localHeaderOffset: Long)

    private fun centralDirectory(f: RandomAccessFile): List<Entry> {
        val tailLen = minOf(f.length(), 65_557L).toInt()
        val tail = ByteArray(tailLen)
        f.seek(f.length() - tailLen)
        f.readFully(tail)
        val tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (tailLen - 22 downTo 0).firstOrNull { tb.getInt(it) == 0x06054b50 }
            ?: throw IllegalArgumentException("not a zip file")
        var cdOffset = tb.getInt(eocd + 16).toLong() and 0xffffffffL
        var cdSize = tb.getInt(eocd + 12).toLong() and 0xffffffffL
        if (cdOffset == 0xffffffffL || cdSize == 0xffffffffL) {
            // ZIP64: the locator sits 20 bytes before the EOCD and points at the ZIP64 EOCD record.
            val loc = eocd - 20
            require(loc >= 0 && tb.getInt(loc) == 0x07064b50) { "missing ZIP64 locator" }
            val rec = ByteArray(56)
            f.seek(tb.getLong(loc + 8))
            f.readFully(rec)
            val rb = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
            require(rb.getInt(0) == 0x06064b50) { "bad ZIP64 end record" }
            cdSize = rb.getLong(40)
            cdOffset = rb.getLong(48)
        }
        val cd = ByteArray(cdSize.toInt())
        f.seek(cdOffset)
        f.readFully(cd)
        val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        val out = mutableListOf<Entry>()
        var p = 0
        while (p + 46 <= cd.size && b.getInt(p) == 0x02014b50) {
            val method = b.getShort(p + 10).toInt() and 0xffff
            var size = b.getInt(p + 24).toLong() and 0xffffffffL
            var local = b.getInt(p + 42).toLong() and 0xffffffffL
            val nameLen = b.getShort(p + 28).toInt() and 0xffff
            val extraLen = b.getShort(p + 30).toInt() and 0xffff
            val commentLen = b.getShort(p + 32).toInt() and 0xffff
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            var e = p + 46 + nameLen
            val extraEnd = e + extraLen
            while (e + 4 <= extraEnd) {
                val id = b.getShort(e).toInt() and 0xffff
                val len = b.getShort(e + 2).toInt() and 0xffff
                if (id == 0x0001) {
                    var q = e + 4
                    if (size == 0xffffffffL) { size = b.getLong(q); q += 8 }
                    if ((b.getInt(p + 20).toLong() and 0xffffffffL) == 0xffffffffL) q += 8
                    if (local == 0xffffffffL) local = b.getLong(q)
                }
                e += 4 + len
            }
            out += Entry(name, method, size, local)
            p = extraEnd + commentLen
        }
        return out
    }
}

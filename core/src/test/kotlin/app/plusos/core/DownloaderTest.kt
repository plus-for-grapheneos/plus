// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.random.Random
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DownloaderTest {
    private val source = Random(7).nextBytes(3 * 1024 * 1024)
    private val ranges = mutableListOf<String?>()
    @Volatile private var dropAfter: Int? = null
    @Volatile private var honourRange = true

    /** A minimal HTTP/1.1 server on a plain socket, so a "dropped connection" is a clean close after N bytes. */
    private val server = ServerSocket(0)
    private val serverThread = thread(isDaemon = true) {
        while (!server.isClosed) {
            val s = try { server.accept() } catch (e: IOException) { break }
            s.use { sock ->
                val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val range = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
                synchronized(ranges) { ranges += range }
                val from = if (honourRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
                val status = if (from > 0) "206 Partial Content" else "200 OK"
                val out = sock.getOutputStream()
                val contentRange = if (from > 0) "Content-Range: bytes $from-${source.size - 1}/${source.size}\r\n" else ""
                out.write(("HTTP/1.1 $status\r\nContent-Length: ${source.size - from}\r\n${contentRange}Connection: close\r\n\r\n").toByteArray())
                val n = dropAfter?.also { dropAfter = null } ?: (source.size - from)
                out.write(source, from, n)
                out.flush()
            }
        }
    }
    private val url = URL("http://127.0.0.1:${server.localPort}/ota.zip")
    private val dir: File = Files.createTempDirectory("dl").toFile()

    @AfterEach
    fun stop() { server.close(); dir.deleteRecursively() }

    @Test
    fun resumes_after_drop() {
        val dest = File(dir, "ota.zip")
        dropAfter = 1024 * 1024
        assertThrows<IOException> { Downloader().fetch(url, dest, source.size.toLong()) { _, _ -> } }
        assertFalse(dest.exists())
        assertEquals(1024L * 1024, File(dir, "ota.zip.part").length(), "the partial download must be kept")
        Downloader().fetch(url, dest, source.size.toLong()) { _, _ -> }
        assertEquals("bytes=1048576-", ranges.last())
        assertArrayEquals(source, dest.readBytes())
    }

    /** Review C3: with no expected size, the server's declared length decides; a short download is never accepted. */
    @Test
    fun drop_without_expected_size_is_not_accepted() {
        val dest = File(dir, "ota.zip")
        dropAfter = 1024 * 1024
        assertThrows<IOException> { Downloader().fetch(url, dest, null) { _, _ -> } }
        assertFalse(dest.exists())
        Downloader().fetch(url, dest, null) { _, _ -> }
        assertArrayEquals(source, dest.readBytes())
    }

    @Test
    fun no_range_support_restarts() {
        honourRange = false
        val dest = File(dir, "ota.zip")
        File(dir, "ota.zip.part").writeBytes(ByteArray(1000) { 9 })
        Downloader().fetch(url, dest, source.size.toLong()) { _, _ -> }
        assertArrayEquals(source, dest.readBytes())
    }

    @Test
    fun size_mismatch_throws() {
        val dest = File(dir, "ota.zip")
        assertThrows<DownloadException> { Downloader().fetch(url, dest, source.size + 1L) { _, _ -> } }
        assertFalse(dest.exists())
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DownloadException(message: String) : IOException(message)

/**
 * Resumable download into `<dest>.part`, renamed to [dest] only when complete and of the expected size.
 * A dropped connection leaves the part file; the next [fetch] continues it with an HTTP Range request.
 */
class Downloader(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun fetch(url: URL, dest: File, expectedSize: Long?, progress: (done: Long, total: Long) -> Unit): File {
        val part = File(dest.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        var declaredTotal = -1L
        val conn = open(url).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416) { part.delete(); throw DownloadException("the server refused the resume point; starting over next time") }
            // Review C3: resume only when the server continues exactly where the part file ends.
            val rangeStart = conn.getHeaderField("Content-Range")?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLong() }
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> have > 0 && rangeStart == have
                HttpURLConnection.HTTP_OK -> false
                else -> throw DownloadException("server answered HTTP $code for $url")
            }
            if (code == HttpURLConnection.HTTP_PARTIAL && !append) { part.delete(); throw DownloadException("the server resumed at the wrong place; starting over next time") }
            val start = if (append) have else 0L
            val declared = if (conn.contentLengthLong >= 0) start + conn.contentLengthLong else -1L
            val total = expectedSize ?: declared
            declaredTotal = declared
            var done = start
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val got = part.length()
        val want = expectedSize ?: declaredTotal
        if (want < 0) { part.delete(); throw DownloadException("the server did not say how big the file is") }
        if (got < want) {
            // A dropped connection ends the stream early without an exception: keep the part file to resume from.
            throw DownloadException("download incomplete: $got of $want bytes; it will resume")
        }
        if (got > want) {
            part.delete()
            throw DownloadException("downloaded $got bytes, expected $want")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw DownloadException("could not move ${part.name} into place")
        return dest
    }
}

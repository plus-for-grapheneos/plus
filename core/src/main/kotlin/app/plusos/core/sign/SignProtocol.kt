// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.sign

import java.io.File

/**
 * The file protocol between `libsignhelper.so` (run by avbroot) and the process holding the key:
 * the helper writes `<id>.req.part` (line 1: algorithm, then the raw block) and renames it to `<id>.req`; the key
 * holder answers with `<id>.resp` (written as `.resp.part`, then renamed); the helper prints it and removes both.
 */
class SignProtocol(private val dir: File) {
    data class Request(val id: String, val algo: String, val block: ByteArray)

    fun pending(): List<Request> =
        dir.listFiles { f -> f.name.endsWith(".req") }.orEmpty()
            .filter { val id = it.name.removeSuffix(".req"); !File(dir, "$id.resp").exists() && !File(dir, "$id.err").exists() }
            .sortedBy { it.name }
            .mapNotNull { f ->
                val bytes = f.readBytes()
                val nl = bytes.indexOf('\n'.code.toByte())
                if (nl <= 0) null else Request(f.name.removeSuffix(".req"), String(bytes, 0, nl), bytes.copyOfRange(nl + 1, bytes.size))
            }

    /** Review I1: a request that can't be answered ends the helper with an error instead of leaving it waiting. */
    fun fail(r: Request, message: String) {
        val part = File(dir, "${r.id}.err.part")
        part.writeText(message)
        check(part.renameTo(File(dir, "${r.id}.err"))) { "could not publish the failure for ${r.id}" }
    }

    fun respond(r: Request, signature: ByteArray) {
        val part = File(dir, "${r.id}.resp.part")
        part.writeBytes(signature)
        check(part.renameTo(File(dir, "${r.id}.resp"))) { "could not publish the signature for ${r.id}" }
    }
}

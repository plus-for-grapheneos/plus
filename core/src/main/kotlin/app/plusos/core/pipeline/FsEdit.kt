// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.pipeline

/**
 * Appends entries to an afsr `fs_metadata.toml`. afsr packs only what the TOML lists, so every added file and every
 * missing parent directory needs an entry. New entries get the Global Constraints metadata: `system_file` label,
 * 755 directories / 644 files, timestamps 2009-01-01 (the value GrapheneOS images use, so builds stay reproducible).
 */
object FsEdit {
    data class FsEntry(val path: String, val dir: Boolean, val executable: Boolean = false)

    private const val TIME = "2009-01-01T00:00:00Z"
    private val PATH = Regex("^path = \"(.*)\"$", RegexOption.MULTILINE)

    fun addEntries(fsToml: String, entries: List<FsEntry>): String {
        val known = PATH.findAll(fsToml).map { it.groupValues[1] }.toMutableSet()
        val sb = StringBuilder(fsToml)
        for (e in entries) {
            require(e.path.startsWith("/") && !e.path.contains("..") && e.path.none { it == '"' || it == '\\' || it.isISOControl() }) {
                "bad image path ${e.path}"
            }
            val parents = generateSequence(e.path.substringBeforeLast('/')) { p -> p.substringBeforeLast('/').takeIf { p.contains('/') } }
                .filter { it.isNotEmpty() }.toList().reversed()
            for (dir in parents) if (known.add(dir)) append(sb, dir, dir = true)
            if (known.add(e.path)) append(sb, e.path, e.dir, e.executable)
        }
        return sb.toString()
    }

    private fun append(sb: StringBuilder, path: String, dir: Boolean, executable: Boolean = false) {
        if (!sb.endsWith("\n")) sb.append('\n')
        sb.append('\n')
            .append("[[entries]]\n")
            .append("path = \"").append(path).append("\"\n")
            .append("file_type = \"").append(if (dir) "Directory" else "RegularFile").append("\"\n")
            .append("file_mode = \"").append(if (dir || executable) "755" else "644").append("\"\n")
            .append("atime = \"$TIME\"\nctime = \"$TIME\"\nmtime = \"$TIME\"\ncrtime = \"$TIME\"\n\n")
            .append("[entries.xattrs]\n")
            .append("\"security.selinux\" = 'u:object_r:system_file:s0\\0'\n")
    }
}

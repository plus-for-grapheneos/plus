// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FsEditTest {
    private val base = """
        [metadata]
        type = "ext"

        [[entries]]
        path = "/"
        file_type = "Directory"
        file_mode = "755"

        [[entries]]
        path = "/priv-app"
        file_type = "Directory"
        file_mode = "755"
    """.trimIndent() + "\n"

    @Test
    fun fsedit_adds_parents_once() {
        val once = FsEdit.addEntries(base, listOf(FsEdit.FsEntry("/priv-app/PlusPhone/PlusPhone.apk", dir = false)))
        val dirAt = once.indexOf("path = \"/priv-app/PlusPhone\"")
        val fileAt = once.indexOf("path = \"/priv-app/PlusPhone/PlusPhone.apk\"")
        assertTrue(dirAt in 0 until fileAt, "the parent directory entry must come first")
        assertEquals(1, Regex("path = \"/priv-app\"\n").findAll(once).count(), "existing parents are not duplicated")
        assertEquals(once, FsEdit.addEntries(once, listOf(FsEdit.FsEntry("/priv-app/PlusPhone/PlusPhone.apk", dir = false))))
    }

    @Test
    fun fsedit_entry_has_label_mode_and_timestamps() {
        val out = FsEdit.addEntries(base, listOf(FsEdit.FsEntry("/etc/permissions/privapp-permissions-plus.xml", dir = false)))
        val entry = out.substringAfter("path = \"/etc/permissions/privapp-permissions-plus.xml\"")
        assertTrue(entry.contains("file_type = \"RegularFile\""))
        assertTrue(entry.contains("file_mode = \"644\""))
        assertTrue(entry.contains("mtime = \"2009-01-01T00:00:00Z\""))
        assertTrue(entry.contains("\"security.selinux\" = 'u:object_r:system_file:s0\\0'"))
        val dir = out.substringAfter("path = \"/etc\"").substringBefore("[[entries]]")
        assertTrue(dir.contains("file_type = \"Directory\"") && dir.contains("file_mode = \"755\""))
    }

    /** Review I7: control characters and backslashes can't reach the TOML. */
    @Test
    fun fsedit_rejects_control_characters() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            FsEdit.addEntries(base, listOf(FsEdit.FsEntry("/a\n[[entries]]", dir = false)))
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            FsEdit.addEntries(base, listOf(FsEdit.FsEntry("/a\\b", dir = false)))
        }
    }
}

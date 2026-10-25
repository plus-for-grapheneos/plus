// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GrapheneReleaseTest {
    /** The device is required (review of +PLUS 11): these tests are about the Pixel 9's release file. */
    private fun parse(line: String, channel: String = "stable") = GrapheneRelease.parse(line, channel, "tokay")

    @Test
    fun parse_live_format() {
        val r = parse("2026101600 1792176552 tokay stable\n")
        assertEquals(GrapheneRelease("2026101600", 1792176552L, "tokay", "stable"), r)
        assertEquals("tokay-ota_update-2026101600.zip", r.fullOtaName)
    }

    @Test
    fun rejects_wrong_device() {
        assertThrows<IllegalArgumentException> { parse("2026101600 1792176552 comet stable") }
    }

    @Test
    fun rejects_wrong_channel() {
        assertThrows<IllegalArgumentException> { parse("2026101600 1792176552 tokay beta") }
    }

    @Test
    fun rejects_garbage() {
        assertThrows<IllegalArgumentException> { parse("2026101600 1792176552 tokay") }
        assertThrows<IllegalArgumentException> { parse("2026101600 soon tokay stable") }
        assertThrows<IllegalArgumentException> { parse("../x 1792176552 tokay stable") }
        assertThrows<IllegalArgumentException> { parse("2026101600a 1792176552 tokay stable") }
        assertThrows<IllegalArgumentException> { parse("12345678901234567 1792176552 tokay stable") }
    }

    /** The owner may follow GrapheneOS's Beta channel, as in GrapheneOS's own Updater (S6: 2026102100 was Beta only). */
    @Test
    fun beta_channel_parses_when_chosen() {
        val line = "2026102100 1792583192 tokay beta"
        assertEquals("2026102100", parse(line, channel = "beta").build)
        assertThrows<IllegalArgumentException> { parse(line) }  // stable expected by default
        assertThrows<IllegalArgumentException> { parse(line, channel = "nightly") }  // only stable/beta
        assertEquals("https://releases.grapheneos.org/tokay-beta", GrapheneRelease.metadataUrl(GrapheneRelease.BASE_URL, "beta", "tokay"))
    }

    /** Source builds (the development channel) number releases with 11 digits; only the digits matter (a file name). */
    @Test
    fun source_build_number_parses() {
        assertEquals("20261016970", parse("20261016970 1792471623 tokay stable").build)
    }

    @Test
    fun not_newer_when_equal_date() {
        val r = parse("2026101600 1792176552 tokay stable")
        assertFalse(r.isNewerThan(1792176552L))
        assertTrue(r.isNewerThan(1792176551L))
    }

    @Test
    fun older_than_running_build() {
        val r = GrapheneRelease("2026101600", 1_792_176_552L, "tokay", "stable")
        assertTrue(r.isOlderThan(1_792_583_192L))
        assertFalse(r.isOlderThan(1_792_176_552L))
    }
}

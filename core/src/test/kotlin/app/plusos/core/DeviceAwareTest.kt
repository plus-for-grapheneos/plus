// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** +PLUS 11: the phone's own model decides the release and the GrapheneOS keys; a model without keys never builds. */
class DeviceAwareTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun pixel9_has_its_grapheneos_keys() {
        val p = Pins.forDevice("tokay")!!
        assertEquals("9e6a8f3e0d761a780179f93acd5721ba1ab7c8c537c7761073c0a754b0e932de", sha(p.avbPkmd))
        assertEquals("CN=GrapheneOS", p.otaCert.subjectX500Principal.name)
        assertTrue("tokay" in Pins.SUPPORTED_DEVICES)
    }

    @Test
    fun a_model_without_keys_has_none() {
        assertNull(Pins.forDevice("sunfish"))
        assertNull(Pins.forDevice("../tokay"))
        assertNull(Pins.forDevice(""))
    }

    @Test
    fun the_release_url_is_the_phones_model() {
        assertEquals("https://releases.grapheneos.org/comet-beta", GrapheneRelease.metadataUrl(GrapheneRelease.BASE_URL, "beta", "comet"))
    }

    @Test
    fun a_release_for_another_model_is_refused() {
        assertEquals("comet", GrapheneRelease.parse("2026101600 1792176552 comet stable", "stable", "comet").device)
        assertThrows<IllegalArgumentException> { GrapheneRelease.parse("2026101600 1792176552 tokay stable", "stable", "comet") }
    }

    @Test
    fun an_unsupported_model_never_builds() {
        assertEquals(UpdateDecision.Decision.UnsupportedDevice("sunfish"), UpdateDecision.deviceCheck("sunfish", customChannel = false))
        assertNull(UpdateDecision.deviceCheck("tokay", customChannel = false))
        // Our own source-build channel brings its own keys.
        assertNull(UpdateDecision.deviceCheck("sunfish", customChannel = true))
    }
}

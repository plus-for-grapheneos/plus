// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AddonNamesTest {
    @Test
    fun addonsHaveNamesPeopleKnow() {
        // S6 UI audit: Add-ons listed "callscreen-phone" and "ai-service".
        assertEquals("Phone+", AddonNames.addon("callscreen-phone"))
        assertEquals("+PLUS AI", AddonNames.addon("ai-service"))
        assertEquals("some-new-addon", AddonNames.addon("some-new-addon"))
    }

    @Test
    fun packagesToo() {
        assertEquals("+PLUS", AddonNames.pkg("app.seamlessupdate.client"))
        assertEquals("Phone+", AddonNames.pkg("app.plusos.phone"))
        assertEquals("Phone+ call transcriber", AddonNames.pkg("app.plusos.phone.transcriber"))
        assertEquals("+PLUS AI", AddonNames.pkg("app.plusos.ai"))
        assertEquals("org.example", AddonNames.pkg("org.example"))
    }

    @Test
    fun theImageAsOneLine() {
        assertEquals("+PLUS 8, Phone+ 2900011, Phone+ call transcriber 39, +PLUS AI 3",
            AddonNames.image(linkedMapOf("app.seamlessupdate.client" to 8, "app.plusos.phone" to 2900011,
                "app.plusos.phone.transcriber" to 39, "app.plusos.ai" to 3)))
    }
}

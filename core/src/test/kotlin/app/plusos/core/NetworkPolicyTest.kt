// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * +PLUS 13 (owner, 2026-10-24): updates run automatically on unmetered networks; on a metered network the phone shows
 * "Update available" with Update now, and Update now downloads and installs over any network.
 */
class NetworkPolicyTest {
    @Test
    fun an_update_found_on_wifi_just_waits_for_the_automatic_build() {
        assertEquals(NetworkPolicy.OnUpdateFound.AUTOMATIC, NetworkPolicy.onUpdateFound(unmetered = true))
    }

    @Test
    fun an_update_found_on_mobile_data_is_offered_with_update_now() {
        assertEquals(NetworkPolicy.OnUpdateFound.OFFER_UPDATE_NOW, NetworkPolicy.onUpdateFound(unmetered = false))
    }

    @Test
    fun automatic_builds_download_only_on_unmetered_networks_and_update_now_on_any() {
        assertTrue(NetworkPolicy.mayDownload(automatic = true, unmetered = true))
        assertFalse(NetworkPolicy.mayDownload(automatic = true, unmetered = false))
        assertTrue(NetworkPolicy.mayDownload(automatic = false, unmetered = false))
    }

    @Test
    fun an_automatic_download_stops_when_the_network_turns_metered() {
        var unmetered = true
        val guard = NetworkPolicy.downloadGuard(automatic = true) { unmetered }
        guard()
        unmetered = false
        assertThrows<NetworkPolicy.WaitForUnmetered> { guard() }
        NetworkPolicy.downloadGuard(automatic = false) { false }() // Update now: never stops
    }
}

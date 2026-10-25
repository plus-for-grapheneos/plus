// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** The phone's current network as the update rules see it (NetworkPolicy). */
object Network {
    /** True on an unmetered network (normally Wi-Fi); false on mobile data, a metered Wi-Fi or no network. */
    fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

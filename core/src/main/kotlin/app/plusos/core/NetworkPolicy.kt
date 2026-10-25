// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

/**
 * +PLUS 13 (owner, 2026-10-24): updates run automatically on unmetered networks (Wi-Fi), while charging and idle. On a
 * metered network (mobile data) the phone shows "Update available" with Update now; Update now downloads and installs
 * over any network. An automatic build that finds itself on a metered network stops and waits for Wi-Fi; the download
 * resumes from its part file.
 */
object NetworkPolicy {
    enum class OnUpdateFound { AUTOMATIC, OFFER_UPDATE_NOW }

    /** The automatic build is queued either way; on a metered network the owner is also offered Update now. */
    fun onUpdateFound(unmetered: Boolean): OnUpdateFound = if (unmetered) OnUpdateFound.AUTOMATIC else OnUpdateFound.OFFER_UPDATE_NOW

    fun mayDownload(automatic: Boolean, unmetered: Boolean): Boolean = !automatic || unmetered

    /** Thrown from inside an automatic download when the network is (or becomes) metered. Not a build failure. */
    class WaitForUnmetered : java.io.IOException("waiting for Wi-Fi (automatic updates don't use mobile data)")

    /** A check to call before and during a download (e.g. from its progress callback). */
    fun downloadGuard(automatic: Boolean, unmetered: () -> Boolean): () -> Unit = {
        if (!mayDownload(automatic, unmetered())) throw WaitForUnmetered()
    }
}

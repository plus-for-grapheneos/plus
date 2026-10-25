// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.key

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import app.plusos.core.sign.Pkcs1
import app.plusos.core.sign.SignProtocol
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Answers libsignhelper.so's requests (spec §5.4) with the keystore key while a build runs. It never answers with an
 * error for lack of authentication: it shows "Unlock your phone to finish the update" and waits for the next unlock,
 * while avbroot waits on the helper.
 */
class SignBroker(
    private val ctx: Context,
    private val dir: File,
    private val keys: KeyService = KeyService(ctx),
) {
    private val protocol = SignProtocol(dir)
    private val unlocked = Semaphore(0)

    /** Serves requests until [stop] returns true. Call on a background thread. */
    fun serve(stop: () -> Boolean) {
        dir.mkdirs()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = unlocked.release()
        }
        ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
        try {
            while (!stop()) {
                val pending = protocol.pending()
                if (pending.isEmpty()) { Thread.sleep(200); continue }
                for (r in pending) answer(r, stop)
            }
        } finally {
            ctx.unregisterReceiver(receiver)
            notifications().cancel(NOTIFICATION_ID)
        }
    }

    private fun answer(r: SignProtocol.Request, stop: () -> Boolean) {
        val di = Pkcs1.digestInfo(r.block, r.algo)
        while (!stop()) {
            try {
                protocol.respond(r, keys.sign(di))
                notifications().cancel(NOTIFICATION_ID)
                return
            } catch (e: UserNotAuthenticatedException) {
                Log.i(KeyService.TAG, "signing waits for the next unlock")
                askForUnlock()
                unlocked.drainPermits()
                unlocked.tryAcquire(60, TimeUnit.SECONDS)
            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                // The lock screen was removed: the key is gone for good until the owner imports it again.
                protocol.fail(r, "the owner key is no longer usable; import it again")
                throw IllegalStateException("the owner key is no longer usable; import it again", e)
            } catch (e: Exception) {
                // The key needs an unlocked device: while locked, keystore answers DEVICE_LOCKED, which reaches us as a
                // plain InvalidKeyException (S6: every overnight build failed at PATCH). Wait for the owner's unlock.
                if (ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
                    Log.i(KeyService.TAG, "signing waits for the next unlock (device locked)")
                    askForUnlock()
                    unlocked.drainPermits()
                    unlocked.tryAcquire(60, TimeUnit.SECONDS)
                    continue
                }
                // Review I1: anything else (key deleted, keystore error) ends the helper instead of leaving avbroot waiting.
                protocol.fail(r, "signing failed: ${e.message}")
                throw e
            }
        }
    }

    private fun askForUnlock() {
        val nm = notifications()
        // Its own channel: the "updates" channel is silent (progress), and this one needs the owner.
        nm.createNotificationChannel(NotificationChannel(UNLOCK_CHANNEL, "Key needed", NotificationManager.IMPORTANCE_HIGH))
        // Only the system lock screen ever asks for the PIN (an app PIN screen looks like phishing, S6 user feedback).
        // Unlocked but past the approval window: a fresh unlock is what counts.
        val locked = ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(ctx, UNLOCK_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (locked) "Unlock your phone to approve the update" else "Lock and unlock your phone to approve the update")
                .setContentText("+PLUS then signs the update with your key. One unlock covers the whole update.")
                .setOngoing(true)
                .build(),
        )
    }

    private fun notifications() = ctx.getSystemService(NotificationManager::class.java)

    companion object {
        const val CHANNEL = "updates"
        const val UNLOCK_CHANNEL = "key_needed"
        const val NOTIFICATION_ID = 2
    }
}

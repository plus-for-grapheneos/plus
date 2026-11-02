// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.key

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.plusos.app.update.UpdateState
import app.plusos.core.DebugRoot
import java.io.File
import java.security.MessageDigest

/**
 * T2 debug root on the phone ([DebugRoot]): the owner's choice (made only on the key-import screen), their Magisk APK,
 * the log of every on and off, and the notification shown for as long as the running image has root.
 */
class DebugRootState(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("debug_root", Context.MODE_PRIVATE)
    val magiskApk = File(ctx.filesDir, "debug/magisk.apk")
    private val log = File(ctx.filesDir, "debug-root.log")

    /** The next builds include Magisk. Turned on only at key import; turned off from anywhere. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) {
            if (v != enabled) log.appendText("${java.time.Instant.now()} debug root turned ${if (v) "on" else "off"} for the next build\n")
            prefs.edit().putBoolean("enabled", v).apply()
        }

    /** Copies the owner's Magisk APK in and checks it is an official release; throws [DebugRoot.Refused] otherwise. */
    fun storeMagisk(input: java.io.InputStream) {
        val tmp = File(ctx.filesDir, "debug/magisk.apk.part").apply { parentFile?.mkdirs() }
        input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        try {
            check(tmp)
            check(tmp.renameTo(magiskApk) || run { tmp.copyTo(magiskApk, overwrite = true); tmp.delete() }) { "could not store Magisk" }
        } finally {
            tmp.delete()
        }
    }

    /** The stored Magisk, re-checked before each build; null if missing or not acceptable. */
    fun magisk(device: String): DebugRoot.Magisk? = magiskApk.takeIf { it.exists() && runCatching { check(it) }.isSuccess }
        ?.let { DebugRoot.Magisk(it, DebugRoot.PREINIT[device]) }

    private fun check(apk: File) {
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw DebugRoot.Refused("this file is not an APK")
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.size != 1) throw DebugRoot.Refused("this Magisk APK must have exactly one signer")
        val sha = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).joinToString("") { "%02x".format(it) }
        DebugRoot.checkMagisk(info.packageName, info.longVersionCode, sha)
    }

    fun logText(): String = if (log.exists()) log.readText() else ""

    /** R2: the one-time setup guide opens by itself once per image with debug root (keyed by the image's manifest). */
    private fun imageKey(): String? = UpdateState.imageManifest()?.takeIf { it.debugRoot }?.let { "${it.grapheneos.otaSha256}:${it.installer.apkSha256}" }

    fun guideDue(): Boolean = imageKey()?.let { it != prefs.getString("guide_shown_for", null) } ?: false

    fun guideShown() { imageKey()?.let { prefs.edit().putString("guide_shown_for", it).apply() } }

    /** Shown while the running image has root; its action turns debug root off (no key needed to turn it off). */
    fun updateNotification() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (UpdateState.imageManifest()?.debugRoot != true) { nm.cancel(NOTIFICATION_ID); return }
        // IMPORTANCE_LOW: a persistent status, not an alert. Re-posted on every status-screen refresh, so it must be
        // silent and alert-once, or it buzzes every few seconds (first phone test, 2026-10-31).
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Debug root", NotificationManager.IMPORTANCE_LOW))
        val end = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, EndReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = if (enabled) "Root for adb shell over USB (development only). End it to rebuild without root (a few minutes)."
            else "Ending: the next update is built without root. Restart to finish it."
        nm.notify(NOTIFICATION_ID, Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Debug root is on")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(ctx, 1, Intent(ctx, DebugRootGuideActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .apply { if (enabled) addAction(Notification.Action.Builder(null, "End debug root", end).build()) }
            .build())
    }

    /** "End debug root": off for the next build, which starts now. */
    fun end() {
        enabled = false
        updateNotification()
        app.plusos.app.update.BuildService.start(ctx, app.plusos.core.UpdateDecision.BuildReason.MANUAL)
    }

    class EndReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = DebugRootState(context).end()
    }

    /** After every boot: the notification reflects the image just started. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) DebugRootState(context).updateNotification()
        }
    }

    companion object {
        const val CHANNEL = "debug_root"
        const val NOTIFICATION_ID = 7
    }
}

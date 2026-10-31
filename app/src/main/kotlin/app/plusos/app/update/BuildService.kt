// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import app.plusos.app.UpdateEngineClient
import app.plusos.core.UpdateDecision
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs one build in the foreground (spec §6) and, on success, the install. Ends with a "Restart to finish"
 * notification. T3 (+PLUS 11): the update is installed but not active; the notification's action opens
 * [RestartActivity], which shows the changes and activates the update only when the owner confirms on an unlocked phone.
 */
class BuildService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val state = UpdateState(this)
        // Review I3: an applied update waits for the restart; a new build would replace it on the other slot.
        if (state.restartPending) {
            notifications().notify(RESULT_ID, restartNote("An update is already installed. Restart to finish it first."))
            stopSelf()
            return START_NOT_STICKY
        }
        // +PLUS 11: "Update now", the store's Apply and the periodic check all land here; an unsupported model
        // never downloads or builds (review: those paths skipped the check in UpdateJob.decide).
        val channel = ReleaseChannel(this)
        if (devConfig(intent) == null && UpdateDecision.deviceCheck(channel.device, customChannel = !channel.official) != null) {
            notifications().notify(RESULT_ID, note("+PLUS doesn't support this phone model (${channel.device}) yet, so it won't install updates.", ongoing = false))
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        Live.running = true
        Live.step = null
        state.waitingForWifi = false
        val reason = UpdateDecision.BuildReason.valueOf(intent.getStringExtra(EXTRA_REASON) ?: "MANUAL")
        val automatic = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)
        notifications().cancel(AVAILABLE_ID)
        val dev = devConfig(intent)
        startForeground(NOTIFICATION_ID, note("Preparing the update…", ongoing = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        thread(name = "plus-build") {
            // A late progress callback (update_engine reports after the result) must not re-post the progress note.
            val runner = BuildRunner(this) { msg -> if (running) { Live.step = msg; notifications().notify(NOTIFICATION_ID, note(msg, ongoing = true)) } }
            try {
                val result = runner.run(reason, dev, automatic)
                if (dev != null && !dev.install) {
                    finish("Test build finished (not installed): ${result.output.name}")
                } else {
                    runner.install(result.output) { ok ->
                        if (ok) { state.markApplied(); state.failures = 0; state.lastError = null; state.retryAtMs = null }
                        else failed(state, "update_engine could not install it")
                        finish(if (ok) null else "The update could not be installed. Share the log from +PLUS.", result.warnings)
                    }
                }
            } catch (e: app.plusos.core.NetworkPolicy.WaitForUnmetered) {
                // Not a failure: the automatic build waits for Wi-Fi; the owner can still choose Update now.
                File(filesDir, "build.log").appendText("STOPPED: ${e.message}\n")
                UpdateJob.scheduleAutoBuild(this)
                state.waitingForWifi = true
                running = false
                Live.running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                notifications().cancel(NOTIFICATION_ID)
                notifications().notify(AVAILABLE_ID, availableNote(this))
                stopSelf()
            } catch (e: Exception) {
                Log.e(UpdateEngineClient.TAG, "build failed", e)
                File(filesDir, "build.log").appendText("FAILED: $e\n")
                if (dev == null) failed(state, e.message ?: e.toString())
                finish("The update could not be prepared: ${e.message}")
            }
        }
        return START_NOT_STICKY
    }

    /** Review I8: retry on the backoff schedule. */
    private fun failed(state: UpdateState, why: String) {
        state.failures = state.failures + 1
        state.lastError = why
        state.retryAtMs = System.currentTimeMillis() + app.plusos.core.Backoff.minutesAfterFailure(state.failures) * 60_000L
        UpdateJob.scheduleRetry(this, state.failures)
    }

    /** null message = success: offer the restart. [warnings] (add-ons not fetched this time) are shown with it. */
    private fun finish(error: String?, warnings: List<String> = emptyList()) {
        running = false
        Live.running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications().cancel(NOTIFICATION_ID)
        val n = if (error == null) {
            restartNote(if (warnings.isEmpty()) "Your add-ons and the latest GrapheneOS are ready." else "Ready, with notes: " + warnings.joinToString("; "))
        } else {
            note(error, ongoing = false)
        }
        notifications().notify(RESULT_ID, n)
        stopSelf()
    }

    private fun restartNote(text: String): Notification {
        notifications().createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW))
        // An activity, not a service: from the lock screen Android asks to unlock before opening it.
        val restart = PendingIntent.getActivity(this, 0, Intent(this, RestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Restart to finish the update")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            // From the lock screen the phone must be unlocked first (also enforced by RestartActivity not showing when locked).
            .addAction(Notification.Action.Builder(null, "Restart", restart).setAuthenticationRequired(true).build())
            .build()
    }

    private fun note(text: String, ongoing: Boolean): Notification {
        notifications().createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("+PLUS update").setContentText(text).setOngoing(ongoing).build()
    }

    private fun notifications() = getSystemService(NotificationManager::class.java)

    /** Debug builds only: overrides for the dev phone (our own source builds), passed as intent extras. */
    private fun devConfig(i: Intent): BuildRunner.DevConfig? {
        if (!app.plusos.app.BuildConfigCompat.isDebuggable(this)) return null
        val ota = i.getStringExtra("dev_ota") ?: return null
        fun file(name: String) = i.getStringExtra(name)?.let { File(getExternalFilesDir(null), it) }
        return BuildRunner.DevConfig(
            ota = File(getExternalFilesDir(null), ota),
            inputAvbPkmd = file("dev_input_avb"),
            inputCert = file("dev_input_cert"),
            allowMissingUpdater = i.getBooleanExtra("dev_allow_missing_updater", false),
            throwawayKey = i.getBooleanExtra("dev_throwaway_key", false),
            install = i.getBooleanExtra("dev_install", false),
        )
    }

    /** The running build as the status screen sees it (same process). */
    object Live {
        @Volatile var running = false
        @Volatile var step: String? = null
    }

    companion object {
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 3
        const val RESULT_ID = 4
        const val AVAILABLE_ID = 5
        const val EXTRA_REASON = "reason"
        const val EXTRA_AUTOMATIC = "automatic"

        /** [automatic]: from the update job (unmetered networks only); otherwise the owner asked (any network). */
        fun start(ctx: Context, reason: UpdateDecision.BuildReason, automatic: Boolean = false) {
            ctx.startForegroundService(Intent(ctx, BuildService::class.java).putExtra(EXTRA_REASON, reason.name)
                .putExtra(EXTRA_AUTOMATIC, automatic))
        }

        /**
         * "Update available" with Update now: shown when an update is found on a metered network. Update now downloads
         * and installs over any network; otherwise the update runs by itself on Wi-Fi while charging.
         */
        fun availableNote(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_LOW))
            val now = PendingIntent.getForegroundService(ctx, 1, Intent(ctx, BuildService::class.java)
                .putExtra(EXTRA_REASON, UpdateDecision.BuildReason.MANUAL.name), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, app.plusos.app.StatusActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val text = "It installs by itself on Wi-Fi while charging. Update now downloads it over mobile data (about 1.5 to 2 GB)."
            return Notification.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Update available")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Update now", now).build())
                .build()
        }
    }
}

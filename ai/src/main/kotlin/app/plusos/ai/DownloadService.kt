// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Downloads one model in the foreground. A plain thread in the activity loses the network as soon as the screen turns
 * off (Android blocks network access for cached apps; seen on the Pixel 9 at 1.8 of 2.6 GB), so the download runs
 * here, with its progress in a notification. An interrupted download resumes from its .part file next time.
 */
class DownloadService : Service() {
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val id = intent.getStringExtra(EXTRA_MODEL)
        if (running || id == null) return START_NOT_STICKY
        running = true
        startForeground(NOTE_ID, note("Preparing the download…", ongoing = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        thread(name = "model-download") {
            val store = ModelStore(this)
            val result = runCatching {
                val m = requireNotNull(store.catalog()?.model(id)) { "$id is not in the model catalog" }
                var shown = -1L
                store.download(m) { d, t ->
                    val pct = if (t > 0) d * 100 / t else -1
                    if (pct != shown) { shown = pct; notifications().notify(NOTE_ID, note("Downloading ${m.name}: $pct%", ongoing = true, pct.toInt())) }
                }
                "${m.name} downloaded and verified."
            }.getOrElse { e -> Log.w(ModelHost.TAG, "download failed", e); "Download failed: ${e.message}. It resumes next time." }
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifications().notify(RESULT_ID, note(result, ongoing = false))
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun note(text: String, ongoing: Boolean, progress: Int = -1): Notification {
        notifications().createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("+PLUS AI").setContentText(text).setOngoing(ongoing)
            .apply { if (progress >= 0) setProgress(100, progress, false) }
            .build()
    }

    private fun notifications() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTE_ID = 2
        private const val RESULT_ID = 3
        private const val EXTRA_MODEL = "model"

        fun start(ctx: Context, modelId: String) {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).putExtra(EXTRA_MODEL, modelId))
        }
    }
}

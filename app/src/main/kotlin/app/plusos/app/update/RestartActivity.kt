// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.update

import android.app.Activity
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.plusos.app.UpdateEngineClient
import app.plusos.core.ChangeList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * T3 (+PLUS 11): "Restart to finish". Lists what the installed update changes (the running image against the update
 * just built) and activates it only when the owner confirms. Never shown over the lock screen: from the lock-screen
 * notification Android asks to unlock first, so someone holding a locked phone can't activate an update.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val state = UpdateState(this)
        val pending = state.pendingManifest()?.takeIf { state.restartPending }
        val status = TextView(this)
        val restart = Button(this).apply { text = "Restart now" }
        val later = Button(this).apply { text = "Not now"; setOnClickListener { finish() } }
        if (activating.get()) {
            // Recreated (rotation) while an activation runs: show it, never start a second one (review of +PLUS 11).
            restart.isEnabled = false
            later.isEnabled = false
            status.text = "Preparing the restart…"
        } else if (pending == null) {
            status.text = "No update is waiting. If you restarted since it was installed, +PLUS installs it again at the next check."
            restart.isEnabled = false
        } else {
            status.text = "This update changes:\n\n" + ChangeList.between(UpdateState.imageManifest(), pending).joinToString("\n") { "• $it" } +
                "\n\nThe phone starts using it after the restart. If anything here is unexpected, tap Not now and share the log from +PLUS."
        }
        restart.setOnClickListener {
            if (!activating.compareAndSet(false, true)) return@setOnClickListener
            restart.isEnabled = false
            later.isEnabled = false
            status.text = "Preparing the restart…"
            thread {
                val result = runCatching { UpdateEngineClient().activate() }
                    .onFailure { Log.e(UpdateEngineClient.TAG, "activation failed", it) }.getOrDefault(UpdateEngineClient.Activation.FAILED)
                if (result == UpdateEngineClient.Activation.ACTIVATED) {
                    getSystemService(PowerManager::class.java).reboot(null)
                    return@thread
                }
                activating.set(false)
                runOnUiThread {
                    status.text = if (result == UpdateEngineClient.Activation.TIMED_OUT) {
                        "Preparing the restart is taking longer than expected. Wait a few minutes, then open +PLUS: if it still " +
                            "offers Restart to finish, try again. Share the log from +PLUS if it keeps happening."
                    } else {
                        "The update could not be activated. The phone keeps the version it runs now. Share the log from +PLUS."
                    }
                    later.isEnabled = true
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@RestartActivity).apply { text = "Restart to finish the update"; textSize = 20f })
            addView(status)
            addView(restart)
            addView(later)
        })
    }

    private companion object {
        /** One activation per process, across recreations of this screen. */
        val activating = AtomicBoolean(false)
    }
}

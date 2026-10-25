// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * The +PLUS status screen (Task 8 skeleton). Also opened from Settings > System update. It shows what the S4 gate
 * checks: the SELinux domain, that the bundled tools run from the native library directory, and a test install.
 */
class StatusActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var restartButton: Button
    private val updates = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { setTextIsSelectable(true) }
        details = TextView(this).apply { setTextIsSelectable(true); visibility = android.view.View.GONE }
        val install = Button(this).apply {
            text = "Install test update"
            setOnClickListener { installTestUpdate() }
        }
        val importKey = Button(this).apply {
            text = "Import your key"
            setOnClickListener { startActivity(android.content.Intent(this@StatusActivity, app.plusos.app.key.KeyImportActivity::class.java)) }
        }
        val store = Button(this).apply {
            text = "Add-ons"
            setOnClickListener { startActivity(android.content.Intent(this@StatusActivity, app.plusos.app.store.StoreActivity::class.java)) }
        }
        val shareLog = Button(this).apply {
            text = "Share log"
            setOnClickListener { shareLog() }
        }
        // T3: an installed update waits for the owner; this opens the same confirm screen as the notification.
        restartButton = Button(this).apply {
            text = "Restart to finish the update…"
            visibility = android.view.View.GONE
            setOnClickListener { startActivity(android.content.Intent(this@StatusActivity, app.plusos.app.update.RestartActivity::class.java)) }
        }
        val updateNow = Button(this).apply {
            text = "Update now"
            setOnClickListener { app.plusos.app.update.BuildService.start(this@StatusActivity, app.plusos.core.UpdateDecision.BuildReason.MANUAL) }
            // +PLUS 11: an unsupported model never builds; the button says why instead of downloading a release.
            val ch = app.plusos.app.update.ReleaseChannel(this@StatusActivity)
            isEnabled = app.plusos.core.UpdateDecision.deviceCheck(ch.device, customChannel = !ch.official) == null
        }
        // GrapheneOS's release channel, as in GrapheneOS's own Updater; switching checks for an update right away.
        val releaseChannel = app.plusos.app.update.ReleaseChannel(this)
        val channelButton = Button(this).apply {
            fun label() { text = "GrapheneOS channel: ${releaseChannel.channel.replaceFirstChar { it.uppercase() }} (tap to change)" }
            label()
            isEnabled = releaseChannel.official
            setOnClickListener {
                releaseChannel.channel = if (releaseChannel.channel == "stable") "beta" else "stable"
                label()
                thread {
                    val d = runCatching { app.plusos.app.update.UpdateJob.decide(this@StatusActivity) }.getOrNull()
                    if (d is app.plusos.core.UpdateDecision.Decision.Build) app.plusos.app.update.BuildService.start(this@StatusActivity, d.reason)
                    runOnUiThread {
                        show(when (d) {
                            is app.plusos.core.UpdateDecision.Decision.ChannelBehind ->
                                app.plusos.app.update.ReleaseChannel.behindMessage(releaseChannel.channel, d.build)
                            is app.plusos.core.UpdateDecision.Decision.UnsupportedDevice -> unsupportedMessage(d.device)
                            else -> "Update check on ${releaseChannel.channel}: ${d ?: "failed"}"
                        })
                    }
                }
            }
        }
        app.plusos.app.update.UpdateJob.schedule(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@StatusActivity).apply {
                text = "+PLUS for GrapheneOS\nGrapheneOS, plussed up a bit: official releases with a few extras.\n" +
                    "Not affiliated with or endorsed by the GrapheneOS project.\n"
            })
            addView(status)
            addView(TextView(this@StatusActivity).apply {
                text = "Technical details (show)"
                setTextColor(0xFF3F5BA9.toInt())
                setPadding(0, pad / 2, 0, pad / 2)
                setOnClickListener {
                    details.visibility = if (details.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
                    text = if (details.visibility == android.view.View.VISIBLE) "Technical details (hide)" else "Technical details (show)"
                }
            })
            addView(details)
            addView(restartButton)
            addView(importKey)
            addView(store)
            addView(updateNow)
            addView(channelButton)
            addView(shareLog)
            // Review I6: the test install is a dev path; a release +PLUS installs only what it built and verified.
            if (BuildConfigCompat.isDebuggable(this@StatusActivity)) addView(install)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
        updates.postDelayed(object : Runnable { override fun run() { if (!isFinishing) { refresh(); updates.postDelayed(this, 5_000) } } }, 5_000)
        // S4 gate over adb: am start -n app.seamlessupdate.client/app.plusos.app.StatusActivity --ez install_test true
        if (BuildConfigCompat.isDebuggable(this) && intent.getBooleanExtra("install_test", false)) installTestUpdate()
    }

    /** +PLUS 11: this phone model has no pinned GrapheneOS keys, so +PLUS doesn't update it. */
    private fun unsupportedMessage(device: String) =
        "+PLUS doesn't support this phone model ($device) yet, so it won't install updates. Tell the +PLUS project."

    /** The last build step in words (S6 UI audit: "update_engine finished: 0" meant nothing to an owner). */
    private fun friendly(step: String?): String {
        step ?: return "none yet"
        val code = Regex("^update_engine finished: (-?\\d+)").find(step)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            code != null && app.plusos.core.PayloadMetadata.installed(code) -> "installed; it starts after you tap Restart to finish"
            code != null -> "could not be installed (error $code). Share log tells why."
            step.startsWith("applyPayload") -> "installing…"
            step.startsWith("build finished") -> "built, installing…"
            step.startsWith("input:") || step.endsWith("...") || step.contains(" already done") -> "building…"
            step.startsWith("Downloading GrapheneOS") -> step
            else -> step
        }
    }

    private fun refresh() = thread {
        val libs = applicationInfo.nativeLibraryDir
        // S6 (user): the screen showed nothing while a build ran; the last step of the current or last build.
        val step = File(filesDir, "build.log").takeIf { it.exists() }?.readLines()?.lastOrNull { it.startsWith("[plus] ") }?.removePrefix("[plus] ")
        val channel = app.plusos.app.update.ReleaseChannel(this)
        val apps = app.plusos.app.update.UpdateJob.imageApps()
        // S6 UI audit: the first lines say what an owner wants to know; the tools' details follow below the toggle.
        val pending = app.plusos.app.update.UpdateState(this).restartPending
        val supported = app.plusos.core.UpdateDecision.deviceCheck(channel.device, customChannel = !channel.official) == null
        val lines = listOfNotNull(
            if (supported) null else unsupportedMessage(channel.device),
            "GrapheneOS ${Build.VERSION.INCREMENTAL}" + (if (channel.official) " · ${channel.channel.replaceFirstChar { it.uppercase() }} channel" else ""),
            "Installed: " + (if (apps.isEmpty()) "no +PLUS add-ons" else app.plusos.core.AddonNames.image(apps)),
            "Last update: " + friendly(step),
            "Owner key: " + when (app.plusos.app.key.KeyService(this).state()) {
                app.plusos.app.key.KeyService.KeyState.READY -> "imported. " +
                    (if (app.plusos.app.key.KeyService(this).level() == app.plusos.app.key.KeyService.Level.AUTOMATIC)
                        "Updates install automatically." else "Each update waits until you unlock the phone once.") +
                    " To change this, import your key again."
                app.plusos.app.key.KeyService.KeyState.ABSENT -> "not imported. Import your key to keep receiving updates."
                app.plusos.app.key.KeyService.KeyState.INVALIDATED -> "no longer usable (lock screen removed). Import your key again."
            },
        )
        val technical = listOf(
            "Phone model: ${app.plusos.core.Pins.modelName(channel.device)} (${channel.device})" + (if (supported) "" else " (not supported)"),
            "Build date ${Build.TIME / 1000}",
            "SELinux domain: ${runCatching { File("/proc/self/attr/current").readText().trim('\u0000', '\n') }.getOrElse { "unreadable" }}",
            "avbroot: ${runTool("$libs/libavbroot.so", "--version")}",
            "afsr: ${runTool("$libs/libafsr.so", "--version")}",
            "Signing helper present: ${File(libs, "libsignhelper.so").canExecute()}",
            "In this image: ${apps.ifEmpty { mapOf("(no +PLUS manifest)" to 0) }}",
            "Last update step: ${step ?: "none yet"}",
        )
        (lines + technical).forEach { Log.i(UpdateEngineClient.TAG, it) }
        runOnUiThread {
            status.text = lines.joinToString("\n")
            details.text = technical.joinToString("\n")
            restartButton.visibility = if (pending) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun runTool(vararg cmd: String): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        "exit ${p.waitFor()}: $out"
    }.getOrElse { "failed: $it" }

    /** Review I8: the last two build logs as text, for the owner to send to us (nothing leaves the phone otherwise). */
    private fun shareLog() {
        val text = listOf("build.log.1", "build.log").map { File(filesDir, it) }.filter { it.exists() }
            .joinToString("\n") { it.readText() }.takeLast(200_000).ifEmpty { "No build log yet." }
        startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "+PLUS build log")
                .putExtra(android.content.Intent.EXTRA_TEXT, text),
            "Share log"))
    }

    /** S4 gate (debug builds only): installs /sdcard/Android/data/app.seamlessupdate.client/files/test-update.zip through update_engine. */
    private fun installTestUpdate() = thread {
        val src = File(getExternalFilesDir(null), "test-update.zip")
        val dest = File(UpdateEngineClient.OTA_DIR, "plus-update.zip")
        try {
            require(src.exists()) { "put test-update.zip into ${src.parent}" }
            show("copying ${src.length() / 1_000_000} MB to $dest ...")
            src.copyTo(dest, overwrite = true)
            UpdateEngineClient().apply(dest, { st, p -> show(app.plusos.core.BuildProgress.install(st, p)) }) { code ->
                show(if (app.plusos.core.PayloadMetadata.installed(code)) "Installed. Restart to finish." else "update_engine error $code")
            }
        } catch (e: Exception) {
            Log.e(UpdateEngineClient.TAG, "test install failed", e)
            show("failed: $e")
        }
    }

    private fun show(s: String) {
        Log.i(UpdateEngineClient.TAG, s)
        runOnUiThread { status.text = status.text.toString().substringBefore("\n>> ") + "\n>> " + s }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.plusos.app.key.KeyService
import app.plusos.app.update.BuildService
import app.plusos.app.update.ReleaseChannel
import app.plusos.app.update.UpdateJob
import app.plusos.app.update.UpdateState
import app.plusos.core.AddonNames
import app.plusos.core.PlusStatus
import app.plusos.core.UpdateDecision
import java.io.File
import kotlin.concurrent.thread

/**
 * The +PLUS screen, also opened from Settings > System update. +PLUS 17 (owner, 2026-10-31): first what is happening
 * now (a build and its step, an update waiting for Wi-Fi or for the restart, a failure and its retry) with the one
 * button that matters, then what is available, then what this phone runs. The tools' details stay behind a toggle.
 */
class StatusActivity : Activity() {
    private lateinit var card: LinearLayout
    private lateinit var dot: View
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var availableBox: LinearLayout
    private lateinit var available: TextView
    private lateinit var installed: TextView
    private lateinit var details: TextView
    private lateinit var message: TextView
    private lateinit var endDebugRoot: Button
    @Volatile private var checking = false
    private val updates = android.os.Handler(android.os.Looper.getMainLooper())
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * dp).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply { text = "+PLUS"; textSize = 28f; setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply {
            text = "GrapheneOS, plussed up a bit: official releases signed with your key, with your add-ons."
            alpha = 0.75f
            setPadding(0, 0, 0, pad)
        })

        // 1. Now: what is happening, how far it is, and the one action that matters.
        dot = View(this)
        title = TextView(this).apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD) }
        detail = TextView(this).apply { setPadding(0, (4 * dp).toInt(), 0, 0) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        action = Button(this).apply { setOnClickListener { onAction(tag as? PlusStatus.Action ?: PlusStatus.Action.NONE) } }
        message = TextView(this).apply { alpha = 0.75f; visibility = View.GONE }
        card = card().apply {
            addView(LinearLayout(this@StatusActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams((12 * dp).toInt(), (12 * dp).toInt()).apply { rightMargin = (10 * dp).toInt() })
                addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(detail)
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
            addView(action)
            addView(message)
        }
        root.addView(card)

        // 2. Available: what the last check found.
        available = TextView(this)
        availableBox = card().apply { addView(heading("Available")); addView(available) }
        root.addView(availableBox)

        // 3. On this phone.
        installed = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(card().apply { addView(heading("On this phone")); addView(installed) })

        // 4. Everything else, as before.
        val releaseChannel = ReleaseChannel(this)
        root.addView(button("Add-ons and your own apps") { startActivity(android.content.Intent(this, app.plusos.app.store.StoreActivity::class.java)) })
        root.addView(Button(this).apply {
            fun label() { text = "GrapheneOS channel: ${releaseChannel.channel.replaceFirstChar { it.uppercase() }} (tap to change)" }
            label()
            isEnabled = releaseChannel.official
            setOnClickListener {
                // GrapheneOS's release channel, as in GrapheneOS's own Updater; switching checks right away.
                releaseChannel.channel = if (releaseChannel.channel == "stable") "beta" else "stable"
                label()
                check()
            }
        })
        root.addView(button("Import your key") { startActivity(android.content.Intent(this, app.plusos.app.key.KeyImportActivity::class.java)) })
        endDebugRoot = button("End debug root (rebuild without root)") {
            android.app.AlertDialog.Builder(this).setTitle("End debug root?")
                .setMessage("+PLUS builds the current GrapheneOS again without root (a few minutes), then asks you to restart.")
                .setPositiveButton("End it") { _, _ -> app.plusos.app.key.DebugRootState(this).end(); refresh() }
                .setNegativeButton("Cancel", null).show()
        }.apply { visibility = View.GONE }
        root.addView(endDebugRoot)
        root.addView(button("Share log") { shareLog() })
        details = TextView(this).apply { setTextIsSelectable(true); visibility = View.GONE; alpha = 0.8f; textSize = 12f }
        root.addView(TextView(this).apply {
            text = "Technical details (show)"
            setTextColor(ACCENT)
            setPadding(0, pad / 2, 0, pad / 2)
            setOnClickListener {
                details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (details.visibility == View.VISIBLE) "Technical details (hide)" else "Technical details (show)"
            }
        })
        root.addView(details)
        // A rebuild of the current version (the old "Update now" when nothing was new), for testing and repairs.
        root.addView(button("Rebuild this version now") { BuildService.start(this, UpdateDecision.BuildReason.MANUAL); refresh() })
        // Review I6: the test install is a dev path; a release +PLUS installs only what it built and verified.
        if (BuildConfigCompat.isDebuggable(this)) root.addView(button("Install test update") { installTestUpdate() })
        root.addView(TextView(this).apply {
            text = "\nNot affiliated with or endorsed by the GrapheneOS project."
            alpha = 0.6f
            textSize = 12f
        })

        setContentView(ScrollView(this).apply { addView(root) })
        UpdateJob.schedule(this)
        refresh()
        updates.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                refresh()
                updates.postDelayed(this, if (BuildService.Live.running) 1_500 else 5_000)
            }
        }, 1_500)
        // S4 gate over adb: am start -n app.seamlessupdate.client/app.plusos.app.StatusActivity --ez install_test true
        if (BuildConfigCompat.isDebuggable(this) && intent.getBooleanExtra("install_test", false)) installTestUpdate()
    }

    private fun onAction(a: PlusStatus.Action) {
        when (a) {
            PlusStatus.Action.RESTART -> startActivity(android.content.Intent(this, app.plusos.app.update.RestartActivity::class.java))
            PlusStatus.Action.UPDATE_NOW -> { BuildService.start(this, UpdateDecision.BuildReason.MANUAL); refresh() }
            PlusStatus.Action.CHECK -> check()
            PlusStatus.Action.IMPORT_KEY -> startActivity(android.content.Intent(this, app.plusos.app.key.KeyImportActivity::class.java))
            PlusStatus.Action.NONE -> {}
        }
    }

    /** Checks now; the result shows in the card and under Available (an update found is not started without the owner). */
    private fun check() {
        if (checking) return
        checking = true
        action.isEnabled = false
        action.text = "Checking…"
        thread {
            runCatching { UpdateJob.decide(this) }.onFailure { Log.w(UpdateEngineClient.TAG, "check failed", it) }
            checking = false
            refresh()
        }
    }

    private fun refresh() = thread {
        val state = UpdateState(this)
        val channel = ReleaseChannel(this)
        val key = KeyService(this)
        val supported = UpdateDecision.deviceCheck(channel.device, customChannel = !channel.official) == null
        val h = PlusStatus.headline(PlusStatus.Facts(
            nowMs = System.currentTimeMillis(), supported = supported, keyReady = key.state() == KeyService.KeyState.READY,
            lastCheck = state.lastCheck, running = BuildService.Live.running, step = BuildService.Live.step,
            restartPending = state.restartPending, waitingForWifi = state.waitingForWifi, failures = state.failures,
            lastError = state.lastError, retryAtMs = state.retryAtMs,
        ))
        val c = state.lastCheck
        val now = System.currentTimeMillis()
        val availableText = when {
            c == null -> null
            c.kind == PlusStatus.CheckKind.UPDATE || c.kind == PlusStatus.CheckKind.NEEDS_SPACE ->
                (listOfNotNull(c.grapheneOs?.let { "GrapheneOS $it" }, c.plus?.let { "+PLUS $it" }) + c.addons)
                    .ifEmpty { listOf("A rebuild with your add-on choices") }.joinToString("\n") { "• $it" } +
                    "\nChecked ${PlusStatus.ago(now - c.atMs)}."
            c.kind == PlusStatus.CheckKind.CHECK_FAILED -> "Couldn't check ${PlusStatus.ago(now - c.atMs)}: ${c.error}"
            else -> "Nothing new. Checked ${PlusStatus.ago(now - c.atMs)}."
        }
        val manifest = UpdateState.imageManifest()
        val apps = manifest?.apps().orEmpty()
        val plusVersion = packageManager.getPackageInfo(packageName, 0).longVersionCode
        val installedText = listOfNotNull(
            "GrapheneOS ${Build.VERSION.INCREMENTAL}" + (if (channel.official) " · ${channel.channel.replaceFirstChar { it.uppercase() }} channel" else ""),
            "+PLUS $plusVersion",
            "Add-ons: " + apps.filterKeys { it != app.plusos.core.AddonPlan.INSTALLER }.entries
                .joinToString(", ") { (p, v) -> "${AddonNames.pkg(p)} $v" }.ifEmpty { "none" },
            manifest?.ownApps?.takeIf { it.isNotEmpty() }?.let { own -> "Your own apps: " + own.joinToString(", ") { "${it.name} ${it.version}" } },
            if (manifest?.debugRoot == true) "Debug root: ON (root for adb shell over USB, development only)" else null,
            "Owner key: " + when (key.state()) {
                KeyService.KeyState.READY -> if (key.level() == KeyService.Level.AUTOMATIC) "ready; updates install automatically"
                    else "ready; each update waits until you unlock the phone once"
                KeyService.KeyState.ABSENT -> "not imported"
                KeyService.KeyState.INVALIDATED -> "no longer usable (lock screen removed); import it again"
            },
        )
        val libs = applicationInfo.nativeLibraryDir
        val step = File(filesDir, "build.log").takeIf { it.exists() }?.readLines()?.lastOrNull { it.startsWith("[plus] ") }?.removePrefix("[plus] ")
        val technical = listOf(
            "Phone model: ${app.plusos.core.Pins.modelName(channel.device)} (${channel.device})" + (if (supported) "" else " (not supported)"),
            "Build date ${Build.TIME / 1000}",
            "SELinux domain: ${runCatching { File("/proc/self/attr/current").readText().trim('\u0000', '\n') }.getOrElse { "unreadable" }}",
            "avbroot: ${runTool("$libs/libavbroot.so", "--version")}",
            "afsr: ${runTool("$libs/libafsr.so", "--version")}",
            "Signing helper present: ${File(libs, "libsignhelper.so").canExecute()}",
            "In this image: ${apps.ifEmpty { mapOf("(no +PLUS manifest)" to 0) }}",
            "Last build log line: ${step ?: "none yet"}",
        )
        val debug = app.plusos.app.key.DebugRootState(this)
        debug.updateNotification()
        val showEnd = debug.enabled
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            endDebugRoot.visibility = if (showEnd) View.VISIBLE else View.GONE
            val color = COLORS.getValue(h.phase)
            (card.background as GradientDrawable).setColor(color and 0x00FFFFFF or 0x1F000000)
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            title.text = h.title
            detail.text = h.detail
            progress.visibility = if (h.percent != null) View.VISIBLE else View.GONE
            h.percent?.let { progress.progress = it }
            if (!checking) {
                action.tag = h.action
                action.isEnabled = true
                action.visibility = if (h.action == PlusStatus.Action.NONE) View.GONE else View.VISIBLE
                action.text = when (h.action) {
                    PlusStatus.Action.RESTART -> "Restart to finish…"
                    PlusStatus.Action.UPDATE_NOW -> if (h.phase == PlusStatus.Phase.FAILED) "Try again now" else "Update now"
                    PlusStatus.Action.CHECK -> "Check for updates"
                    PlusStatus.Action.IMPORT_KEY -> "Import your key"
                    PlusStatus.Action.NONE -> ""
                }
            }
            availableBox.visibility = if (availableText == null) View.GONE else View.VISIBLE
            available.text = availableText
            installed.text = installedText.joinToString("\n")
            details.text = technical.joinToString("\n")
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (14 * dp).toInt()
        setPadding(p, p, p, p)
        background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(0x143F5BA9) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (12 * dp).toInt() }
    }

    private fun heading(s: String) = TextView(this).apply {
        text = s
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ACCENT)
        setPadding(0, 0, 0, (6 * dp).toInt())
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }

    private fun runTool(vararg cmd: String): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        "exit ${p.waitFor()}: $out"
    }.getOrElse { "failed: $it" }

    /** Review I8: the last two build logs as text, for the owner to send to us (nothing leaves the phone otherwise). */
    private fun shareLog() {
        val text = listOf("debug-root.log", "build.log.1", "build.log").map { File(filesDir, it) }.filter { it.exists() }
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
        runOnUiThread { message.visibility = View.VISIBLE; message.text = s }
    }

    private companion object {
        const val ACCENT = 0xFF3F5BA9.toInt()
        val COLORS = mapOf(
            PlusStatus.Phase.IN_PROGRESS to ACCENT,
            PlusStatus.Phase.READY to 0xFF2E7D32.toInt(),
            PlusStatus.Phase.UP_TO_DATE to 0xFF2E7D32.toInt(),
            PlusStatus.Phase.AVAILABLE to 0xFFB26A00.toInt(),
            PlusStatus.Phase.FAILED to 0xFFC62828.toInt(),
            PlusStatus.Phase.BLOCKED to 0xFFC62828.toInt(),
            PlusStatus.Phase.UNKNOWN to 0xFF757575.toInt(),
        )
    }
}

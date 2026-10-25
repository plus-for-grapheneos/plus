// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.ai

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * +PLUS AI's own screen (ai-service-design §5): the catalog with Download/Delete, and "Try it" through the same bound
 * service other apps use. Downloads need Wi-Fi unless the owner taps "Use mobile data".
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var models: LinearLayout
    private var service: IAiService? = null
    private val store by lazy { ModelStore(this) }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = IAiService.Stub.asInterface(binder); refresh() }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this)
        models = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val prompt = EditText(this).apply { hint = "Try it: ask something"; intent.getStringExtra("prompt")?.let { setText(it) } }
        val answer = TextView(this).apply { setTextIsSelectable(true) }
        val run = Button(this).apply { text = "Run on the phone"; setOnClickListener { tryIt(prompt.text.toString(), answer) } }
        setContentView(ScrollView(this).apply {
            // Android 15 draws targetSdk 35 apps edge to edge: keep the content clear of the app bar, status bar,
            // navigation bar and keyboard (on the dev phone the model list sat under the title bar).
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()
                    or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(TextView(this@MainActivity).apply {
                    text = "+PLUS AI: GrapheneOS plussed up with on-device language models, for apps you allow.\nPrompts never leave this phone and are never stored.\n" +
                        "Not affiliated with or endorsed by the GrapheneOS project.\n"
                })
                addView(models); addView(status); addView(prompt); addView(run); addView(answer)
            })
        })
        bindService(Intent(this, AiService::class.java).setAction("app.plusos.ai.BIND"), conn, BIND_AUTO_CREATE)
        if (intent.getBooleanExtra("run", false)) run.post { run.performClick() }
    }

    private fun refresh(): Thread = thread {
        val catalog = store.catalog()
        runOnUiThread {
            models.removeAllViews()
            if (catalog == null) { models.addView(TextView(this).apply { text = "No model catalog available." }); return@runOnUiThread }
            for (m in catalog.models) {
                val installed = store.installed(m)
                models.addView(TextView(this).apply {
                    text = "${m.name}: ${m.size / 1_000_000} MB, ${m.license}" + if (installed) " (installed)" else ""
                })
                models.addView(Button(this).apply {
                    text = if (installed) "Delete" else "Download"
                    setOnClickListener { if (installed) confirmDelete(m) else download(m, mobileOk = false) }
                })
                if (!installed) models.addView(Button(this).apply { text = "Download using mobile data"; setOnClickListener { download(m, true) } })
            }
            showApps()
        }
    }

    /** S6 UI audit: one tap deleted a 2.6 GB model that apps rely on; now it asks first. */
    private fun confirmDelete(m: app.plusos.core.ai.AiModel) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${m.name}?")
            .setMessage("Apps that use +PLUS AI (like Phone+ call summaries and search) stop using it until you download it " +
                "again (${m.size / 1_000_000} MB).")
            .setPositiveButton("Delete") { _, _ -> store.delete(m); refresh() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * "Apps with access" (ai-service-design §5): each app that asks for +PLUS AI, and whether you allowed it; a tap opens
     * its Android permission page, where access is given or taken away.
     */
    private fun showApps() {
        val pm = packageManager
        val holders = pm.getPackagesHoldingPermissions(arrayOf(AiService.PERMISSION), 0).map { it.packageName }
        val askers = (KNOWN_CLIENTS + holders).distinct().filter { pkg ->
            runCatching { pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(AiService.PERMISSION) == true }
                .getOrDefault(false)
        }
        models.addView(TextView(this).apply {
            text = "\nApps that may use +PLUS AI"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (askers.isEmpty()) models.addView(TextView(this).apply { text = "No app has asked yet." })
        for (pkg in askers) {
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            models.addView(TextView(this).apply {
                text = "$label: " + (if (pkg in holders) "allowed" else "not allowed yet") + "  (change)"
                setPadding(0, 12, 0, 12)
                setOnClickListener {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null)))
                }
            })
        }
    }

    private fun download(m: app.plusos.core.ai.AiModel, mobileOk: Boolean) {
        if (!mobileOk && !store.onUnmeteredNetwork()) return show("Connect to Wi-Fi to download ${m.name}, or choose mobile data.")
        DownloadService.start(this, m.id)
        show("Downloading ${m.name} (${m.size / 1_000_000} MB). Progress is in the notification; you can leave this screen.")
    }

    override fun onResume() {
        super.onResume()
        if (service != null) refresh()
    }

    private fun tryIt(text: String, out: TextView) {
        val s = service ?: return show("Service not connected yet.")
        val installed = store.catalog()?.models?.firstOrNull { store.installed(it) } ?: return show("Download a model first.")
        out.text = ""
        val cb = object : IAiCallback.Stub() {
            override fun onReady(infoJson: String) {
                val info = JSONObject(infoJson)
                show("Loaded ${info.getString("model")} on ${info.getString("backend")} in ${info.getLong("init_ms")} ms")
                s.generate(JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text))).put("max_tokens", 256).toString(), this)
            }
            override fun onToken(t: String) { runOnUiThread { out.append(t) } }
            override fun onResult(resultJson: String) {
                val r = JSONObject(resultJson)
                show("TTFT ${r.getLong("ttft_ms")} ms · prefill ${r.getInt("prefill_tokens")} tok at ${r.getLong("prefill_tps")} t/s · " +
                    "decode ${r.getInt("decode_tokens")} tok at ${r.getDouble("decode_tps")} t/s · total ${r.getLong("total_ms")} ms")
                android.util.Log.i(ModelHost.TAG, "try-it timing: " + r.apply { remove("text") })
            }
            override fun onError(code: String, message: String) = show("$code: $message")
        }
        s.prepare(installed.id, "gpu", cb)
    }

    private fun show(s: String) { runOnUiThread { status.text = s } }

    private companion object {
        /** Apps known to ask for +PLUS AI; they may not hold the permission yet, so Android wouldn't list them. */
        val KNOWN_CLIENTS = listOf("app.plusos.phone")
    }

    override fun onDestroy() { runCatching { service?.release() }; unbindService(conn); super.onDestroy() }
}

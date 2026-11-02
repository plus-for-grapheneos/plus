// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.key

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import app.plusos.core.AvbKey
import app.plusos.core.BackupCodec
import app.plusos.core.BackupFormatException
import app.plusos.core.CatalogLink
import app.plusos.core.OwnerKey
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * First-run key import (spec §2 step 6): scan the QR code plus-setup shows, or paste the backup text from the password
 * manager. Shows the key fingerprint (4 groups of 4) to compare with plus-setup's screen, then imports into the TEE.
 */
private const val NL = "\n"

class KeyImportActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var importButton: Button
    @Volatile private var level = KeyService.Level.APPROVAL
    private var pending: OwnerKey? = null
    @Volatile private var scanning = true
    private lateinit var catalog: EditText
    private lateinit var debugRoot: android.widget.CheckBox
    private val pickMagisk = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        onMagiskPicked(uri)
    }
    private val analysis = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val preview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (320 * resources.displayMetrics.density).toInt())
        }
        // Two lines at most: the masked backup is about 1,000 characters and would push the buttons off the screen.
        val paste = EditText(this).apply {
            hint = "…or paste the backup text (PLUSKEY1-…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 2
            intent.getStringExtra("backup_text")?.let { setText(it) }
        }
        status = TextView(this).apply { text = "Scan the QR code shown by plus-setup." }
        importButton = Button(this).apply { text = "Import key"; isEnabled = false; setOnClickListener { doImport() } }
        // The owner's choice (fixed in the keystore at import; to change it, import the key again).
        val automatic = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Update automatically, like stock GrapheneOS.\nUpdates install even while the phone is locked."
        }
        val approval = android.widget.RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Ask me before each update.\nEach update waits until you unlock the phone once (PIN or fingerprint)."
        }
        val levels = android.widget.RadioGroup(this).apply {
            addView(automatic); addView(approval)
            check(if (KeyService(this@KeyImportActivity).level() == KeyService.Level.AUTOMATIC) automatic.id else approval.id)
            setOnCheckedChangeListener { _, checked -> level = if (checked == automatic.id) KeyService.Level.AUTOMATIC else KeyService.Level.APPROVAL }
        }
        level = if (levels.checkedRadioButtonId == automatic.id) KeyService.Level.AUTOMATIC else KeyService.Level.APPROVAL
        // T5: where add-ons come from. Only settable here, so changing it means importing the key again.
        val catalogLabel = TextView(this).apply {
            text = "Catalog link (where add-ons come from). Leave it as it is unless you were given another one."
        }
        catalog = EditText(this).apply {
            setText(app.plusos.app.store.RepoClient(this@KeyImportActivity).link()?.toString().orEmpty())
            hint = "https://…/index.json#sha256=…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 3
        }
        // T2: debug root, a development tool. Only this screen turns it on, so it needs the owner's key backup.
        debugRoot = android.widget.CheckBox(this).apply {
            text = "Debug root for adb (development only)" + NL + "Root for the adb shell over USB, no app gets it. Weakens " +
                "GrapheneOS's protections while on; turn it off again when you are done. Needs an official Magisk APK."
            isChecked = DebugRootState(this@KeyImportActivity).enabled
        }
        // R3: what turning it on involves, on request.
        val debugRootHelp = TextView(this).apply {
            val shortText = "What happens (show)"
            text = shortText
            setTextColor(0xFF3F5BA9.toInt())
            setPadding(pad * 2, 0, 0, pad / 2)
            setOnClickListener {
                text = if (text == shortText) "You choose an official Magisk APK (31.0 or newer, from Magisk's GitHub " +
                    "release). The next update adds Magisk to the boot image, signed with your key; the bootloader stays " +
                    "locked. After the restart +PLUS shows a one-time setup: Magisk installs its app once, you allow " +
                    "Shell and set Superuser access to ADB only. Then `adb shell /debug_ramdisk/su` has root. While on, " +
                    "root over USB can read everything on the unlocked phone. End it from the notification." else shortText
            }
        }
        val check = Button(this).apply {
            text = "Check pasted text"
            setOnClickListener { offer(paste.text.toString()); paste.text.clear() }
        }
        setContentView(android.widget.ScrollView(this).apply {
            addView(LinearLayout(this@KeyImportActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(preview); addView(status); addView(paste); addView(check); addView(levels)
                addView(catalogLabel); addView(catalog); addView(debugRoot); addView(debugRootHelp); addView(importButton)
            })
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera(preview)
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) recreate()
    }

    private fun startCamera(view: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val reader = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
            }
            val analyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analyzer.setAnalyzer(analysis) { img ->
                img.use {
                    if (!scanning) return@use
                    val plane = it.planes[0]
                    val bytes = ByteArray(plane.buffer.remaining()).also { b -> plane.buffer.get(b) }
                    val src = PlanarYUVLuminanceSource(bytes, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
                    val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text }.getOrNull()
                    reader.reset()
                    if (text != null && text.startsWith(BackupCodec.PREFIX)) runOnUiThread { offer(text) }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
        }, mainExecutor)
    }

    private fun offer(text: String) {
        pending = null
        importButton.isEnabled = false
        val key = try {
            BackupCodec.decode(text)
        } catch (e: BackupFormatException) {
            status.text = when (e.reason) {
                BackupFormatException.Reason.CRC -> "A character is wrong. Check the text and try again."
                BackupFormatException.Reason.FINGERPRINT -> "This is not a valid +PLUS key."
                else -> "This doesn't look like a +PLUS backup: ${e.message}"
            }
            return
        }
        scanning = false
        pending = key
        val fp = AvbKey.fingerprint(key.publicKey).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
        status.text = "Key fingerprint: $fp\nCheck it matches the one plus-setup shows, then import."
        importButton.isEnabled = true
    }

    private fun doImport() {
        val key = pending ?: return
        val text = catalog.text.toString().trim()
        val link = if (text.isEmpty()) null else try {
            CatalogLink.parse(text, app.plusos.app.store.RepoClient.allowHttp(this))
        } catch (e: CatalogLink.InvalidLink) {
            status.text = "That catalog link can't be used: ${e.message}. Fix it or clear it, then import."
            return
        }
        importButton.isEnabled = false
        thread {
            val result = KeyService(this).import(key, KeyService.runningOtaCert(), level)
            pending = null // drop the decoded key material (best effort: the JVM may still hold copies)
            if (result == KeyService.ImportResult.OK) app.plusos.app.store.RepoClient.saveLink(this, link)
            runOnUiThread {
                if (result == KeyService.ImportResult.OK) applyDebugRoot()
                status.text = when (result) {
                    KeyService.ImportResult.OK -> if (level == KeyService.Level.AUTOMATIC) "Key imported. Updates install automatically."
                        else "Key imported. Each update waits until you unlock the phone once."
                    KeyService.ImportResult.NO_LOCK_SCREEN -> "Set a PIN or password first (Settings > Security), then import again."
                    KeyService.ImportResult.FINGERPRINT_MISMATCH -> "This key is not the one this phone was set up with. Use the backup from this phone's setup."
                    KeyService.ImportResult.KEYSTORE_ERROR -> "The phone's keystore refused the key. Share the log from the +PLUS screen."
                }
                if (result != KeyService.ImportResult.OK) { scanning = true }
            }
        }
    }

    /** After a successful import: debug root as the box says. Off rebuilds without root; on needs the owner's Magisk APK. */
    private fun applyDebugRoot() {
        val state = DebugRootState(this)
        val device = app.plusos.app.update.ReleaseChannel(this).device
        when {
            debugRoot.isChecked == state.enabled -> {}
            !debugRoot.isChecked -> {
                state.enabled = false
                state.updateNotification()
                offerBuild("Debug root will be removed in the next update.")
            }
            state.magisk(device) != null -> turnOnDebugRoot(state)
            else -> {
                status.append(NL + "Choose the official Magisk APK (31.0 or newer) to turn on debug root.")
                pickMagisk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            }
        }
    }

    private fun onMagiskPicked(uri: android.net.Uri?) {
        if (uri == null) { status.append(NL + "No Magisk APK chosen; debug root stays off."); return }
        thread {
            val state = DebugRootState(this)
            val error = runCatching { contentResolver.openInputStream(uri)!!.let { state.storeMagisk(it) } }.exceptionOrNull()
            runOnUiThread {
                if (error != null) status.append(NL + "Debug root stays off: ${error.message}")
                else turnOnDebugRoot(state)
            }
        }
    }

    private fun turnOnDebugRoot(state: DebugRootState) {
        state.enabled = true
        offerBuild("Debug root will be in the next update. Restart to finish shows it before it takes effect.")
    }

    /** R1 (owner): changing debug root records the choice; the update starts only when the owner says so. */
    private fun offerBuild(what: String) {
        status.append(NL + what)
        android.app.AlertDialog.Builder(this)
            .setTitle("Build the update now?")
            .setMessage("$what Build it now, or later with your other changes (Update now on the +PLUS screen, or the next automatic update).")
            .setPositiveButton("Build now") { _, _ ->
                app.plusos.app.update.BuildService.start(this, app.plusos.core.UpdateDecision.BuildReason.MANUAL)
                status.append(NL + "Building the update now.")
            }
            .setNegativeButton("Later", null)
            .show()
    }

    override fun onDestroy() { analysis.shutdown(); super.onDestroy() }
}

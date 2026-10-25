// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app

import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import android.util.Log
import app.plusos.core.PayloadLocator
import app.plusos.core.PayloadMetadata
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/**
 * Hands a finished, verified update to update_engine (A/B, inactive slot). The zip must live in /data/ota_package
 * (label ota_package_file, writable by the updater_app domain); update_engine reads payload.bin in place.
 *
 * T3 (+PLUS 11): the update is installed WITHOUT activating it (`SWITCH_SLOT_ON_REBOOT=0`), so a restart, a crash or
 * holding the power button boots the image the owner already had. [activate] switches to it, only from the owner's
 * "Restart to finish" on an unlocked phone ([app.plusos.app.update.RestartActivity]).
 */
class UpdateEngineClient {
    private val engine = UpdateEngine()

    /** [onProgress] gets update_engine's status (writing, checking, finishing) and that phase's fraction. */
    fun apply(zip: File, onProgress: (status: Int, fraction: Float) -> Unit, onDone: (code: Int) -> Unit) {
        val info = PayloadLocator.locate(zip)
        // update_engine runs as root but without CAP_DAC_OVERRIDE: the file must be world-readable (as GrapheneOS's
        // Updater does). care_map.pb goes next to it for update_verifier, also as the Updater does.
        check(zip.setReadable(true, false)) { "could not make ${zip.name} readable for update_engine" }
        java.util.zip.ZipFile(zip).use { z ->
            z.getEntry("care_map.pb")?.let { e ->
                val careMap = File(zip.parentFile, "care_map.pb")
                z.getInputStream(e).use { input -> careMap.outputStream().use { input.copyTo(it) } }
                careMap.setReadable(true, false)
            }
        }
        writeMetadata(zip, info)
        // A declined update (the owner restarted without confirming) leaves snapshots behind; reset clears them. Never
        // reached while an update waits in this boot: BuildService refuses to build while restartPending (boot counter),
        // so this can't undo an activation the owner already confirmed (review of +PLUS 11).
        runCatching { engine.resetStatus() }
        engine.bind(object : UpdateEngineCallback() {
            override fun onStatusUpdate(status: Int, percent: Float) = onProgress(status, percent)
            override fun onPayloadApplicationComplete(errorCode: Int) {
                Log.i(TAG, "update_engine finished: $errorCode")
                // Unbind: a later activation's completion must not reach this callback (review of +PLUS 11).
                runCatching { engine.unbind() }
                onDone(errorCode)
            }
        })
        val headers = PayloadMetadata.installOnly(info.headers)
        Log.i(TAG, "applyPayload ${zip.path} offset=${info.offset} size=${info.size} (install only)")
        engine.applyPayload("file://${zip.path}", info.offset, info.size, headers.toTypedArray())
    }

    enum class Activation { ACTIVATED, FAILED, TIMED_OUT }

    /**
     * Makes the installed update the one the next boot starts (the owner confirmed). Waits for update_engine's
     * completion callback only: [UpdateEngine.bind] reports the current status at once, so a status update could be
     * an earlier state, not this activation (review of +PLUS 11).
     */
    fun activate(timeoutS: Long = 300): Activation {
        val metadata = File(OTA_DIR, METADATA_NAME)
        check(metadata.isFile) { "no installed update to activate" }
        val done = CountDownLatch(1)
        val result = AtomicInteger(Int.MIN_VALUE)
        engine.bind(object : UpdateEngineCallback() {
            override fun onStatusUpdate(status: Int, percent: Float) {}
            override fun onPayloadApplicationComplete(errorCode: Int) {
                Log.i(TAG, "activation finished: $errorCode")
                result.set(errorCode)
                done.countDown()
            }
        })
        try {
            Log.i(TAG, "setShouldSwitchSlotOnReboot ${metadata.path}")
            engine.setShouldSwitchSlotOnReboot(metadata.path)
            if (!done.await(timeoutS, TimeUnit.SECONDS)) return Activation.TIMED_OUT
            return if (result.get() == SUCCESS) Activation.ACTIVATED else Activation.FAILED
        } finally {
            runCatching { engine.unbind() }
        }
    }

    /** The payload's metadata (header, manifest, signature) as a file update_engine can read, for [activate]. */
    private fun writeMetadata(zip: File, info: PayloadLocator.PayloadInfo) {
        val dest = File(zip.parentFile, METADATA_NAME)
        RandomAccessFile(zip, "r").use { f ->
            val header = ByteArray(PayloadMetadata.HEADER_SIZE)
            f.seek(info.offset)
            f.readFully(header)
            val length = PayloadMetadata.length(header, PayloadMetadata.metadataSize(info.headers))
            val bytes = ByteArray(Math.toIntExact(length))
            f.seek(info.offset)
            f.readFully(bytes)
            dest.writeBytes(bytes)
        }
        check(dest.setReadable(true, false)) { "could not make ${dest.name} readable for update_engine" }
    }

    companion object {
        const val TAG = "PLUS"
        val OTA_DIR = File("/data/ota_package")
        const val METADATA_NAME = "plus-update.metadata"
        const val SUCCESS = UpdateEngine.ErrorCodeConstants.SUCCESS
    }
}

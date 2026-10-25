// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.update

import android.content.Context
import android.os.StatFs
import android.util.Log
import app.plusos.app.UpdateEngineClient
import app.plusos.app.key.KeyService
import app.plusos.app.key.SignBroker
import app.plusos.app.store.RepoClient
import app.plusos.core.AddonPlan
import app.plusos.core.AvbKey
import app.plusos.core.BuildManifest
import app.plusos.core.BuildProgress
import app.plusos.core.Downloader
import app.plusos.core.GrapheneRelease
import app.plusos.core.InstallerCheck
import app.plusos.core.OwnerCert
import app.plusos.core.OwnerKey
import app.plusos.core.UpdateCheck
import app.plusos.core.UpdateDecision
import app.plusos.core.pipeline.AddedFiles
import app.plusos.core.pipeline.Pipeline
import app.plusos.core.pipeline.PipelineException
import app.plusos.core.pipeline.ProcessToolRunner
import app.plusos.core.pipeline.SignerConfig
import app.plusos.core.pipeline.Stage
import app.plusos.core.pipeline.Tools
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

/**
 * One on-phone build (spec §6): fetch the official release, build with the pipeline, sign through the keystore, and
 * hand the result to update_engine. Everything is logged to `files/build.log` for "Share log".
 */
class BuildRunner(private val ctx: Context, private val progress: (String) -> Unit) {
    /** Debug-only overrides for building from our own source builds on the dev phone (never in a release build). */
    data class DevConfig(
        val ota: File,
        val inputAvbPkmd: File?,
        val inputCert: File?,
        val allowMissingUpdater: Boolean,
        val throwawayKey: Boolean,
        val install: Boolean,
    )

    private val work = File(ctx.filesDir, "build")
    private val logFile = File(ctx.filesDir, "build.log")
    private val channel = ReleaseChannel(ctx)
    private var downloadGuard: () -> Unit = {}

    /** [warnings]: add-ons that could not be fetched this time (kept at the installed version or left out). */
    data class Result(val output: File, val warnings: List<String>)

    /** [automatic]: started by the update job; it downloads only on an unmetered network (NetworkPolicy). */
    fun run(reason: UpdateDecision.BuildReason, dev: DevConfig? = null, automatic: Boolean = false): Result {
        downloadGuard = app.plusos.core.NetworkPolicy.downloadGuard(automatic) { Network.unmetered(ctx) }
        downloadGuard()
        // +PLUS 11, defence in depth: never download or build for a model without pinned GrapheneOS keys.
        if (dev == null) check(UpdateDecision.deviceCheck(channel.device, customChannel = !channel.official) == null) {
            "+PLUS doesn't support this phone model (${channel.device})"
        }
        rotateLog()
        logFile.writeText("+PLUS build ($reason) at ${System.currentTimeMillis()}\n")
        work.mkdirs()
        val free = StatFs(ctx.filesDir.path).availableBytes
        check(free >= UpdateDecision.MIN_FREE_BYTES) { "Needs 8 GB free to build; ${free / 1_000_000_000} GB free" }
        // Review I1: without a usable key the build would stop at signing; say so before downloading anything.
        if (dev?.throwawayKey != true) {
            val state = KeyService(ctx).state()
            check(state == KeyService.KeyState.READY) {
                if (state == KeyService.KeyState.INVALIDATED) "Your key is no longer usable (the lock screen was removed). Import your key again."
                else "Import your key first (+PLUS > Import your key)."
            }
        }

        val ota = dev?.ota ?: downloadRelease()
        log("input: ${ota.name} (${ota.length() / 1_000_000} MB)")
        val repo = RepoClient(ctx)
        val updateState = UpdateState(ctx)
        // Review C4: a repository that can't be reached or verified never holds back the GrapheneOS update.
        val index = if (repo.configured && dev == null) runCatching { repo.fetchIndex() }
            .onFailure { log("repository unavailable, keeping the installed add-ons: ${it.message}") }.getOrNull() else null
        val runningVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
        val apkDir = File(work, "apks")
        val warnings = mutableListOf<String>()

        // A newer +PLUS from the repository is baked in by this build (system apps update only through the image). It
        // must carry its tools and its own system XMLs (review I4); otherwise this build keeps the running +PLUS.
        val running = File(ctx.applicationInfo.sourceDir)
        val newer = index?.let { RepoClient.installerOf(it) }?.takeIf { it.version > runningVersion }?.let { app ->
            runCatching { repo.download(app, apkDir).let { apk -> Triple(apk, app.version, InstallerCheck.verify(apk, File(work, "xml-new"))) } }
                .onFailure { warnings += "+PLUS ${app.version}: ${it.message}; kept the running version" }.getOrNull()
        }
        val (installerApk, installerVersion, xmls) = newer ?: Triple(running, runningVersion, InstallerCheck.verify(running, File(work, "xml")))

        val image = UpdateState.imageManifest()
        val plan = AddonPlan.plan(index, repo.selected, updateState.removed, installerVersion, image)
        val fetched = AddonPlan.fetch(plan, download = { repo.download(it, apkDir) }, imageFile = { path -> File("/$path") })
        warnings += fetched.warnings
        warnings.forEach { log("warning: $it") }
        val manifest = BuildManifest(
            device = channel.device,
            grapheneos = BuildManifest.Os(ota.name.substringAfter("ota_update-").substringBefore(".zip"), sha256(ota)),
            ownerKeyFingerprint = ownerFingerprint(),
            installer = BuildManifest.Installer(installerVersion, sha256(installerApk)),
            addons = fetched.addons(),
        )

        val signDir = File(work, "sign").apply { deleteRecursively(); mkdirs() }
        val signer = if (dev?.throwawayKey == true) throwawaySigner() else helperSigner(signDir)
        val out = if (dev != null && !dev.install) File(work, "out.zip") else File(UpdateEngineClient.OTA_DIR, "plus-update.zip")
        val libs = ctx.applicationInfo.nativeLibraryDir

        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val broker = if (signer is SignerConfig.Helper) thread(name = "sign-broker") {
            runCatching { SignBroker(ctx, signDir).serve { done.get() } }.onFailure { log("signing stopped: $it") }
        } else null
        val started = System.currentTimeMillis()
        try {
            Pipeline(
                work = File(work, "pipeline"),
                tools = Tools("$libs/libavbroot.so", "$libs/libafsr.so"),
                runner = ProcessToolRunner { line -> appendLog(line) },
                options = Pipeline.Options(dev?.inputAvbPkmd ?: channel.inputAvbPkmd(work), dev?.inputCert ?: channel.inputCert(work),
                    dev?.allowMissingUpdater ?: channel.allowMissingUpdater),
                log = { log(it) },
            ).run(ota, manifest, AddedFiles(installerApk, xmls, fetched.files), signer, out)
        } catch (e: PipelineException) {
            // Review C3: a download that doesn't verify is fetched again next time, not reused forever.
            if (e.stage == Stage.VERIFY_INPUT && dev == null) { ota.delete(); log("the GrapheneOS download did not verify; removed it") }
            throw e
        } finally {
            done.set(true)
            broker?.join(5_000)
        }
        updateState.deferred = UpdateCheck.deferred(plan, fetched)
        updateState.savePending(manifest) // T3: what "Restart to finish" lists before the owner activates it
        log("build finished in ${(System.currentTimeMillis() - started) / 1000} s: $out (${out.length() / 1_000_000} MB)")
        return Result(out, warnings)
    }

    /** Hands [zip] to update_engine; [onDone] gets true when the update is ready for a restart. */
    fun install(zip: File, onDone: (Boolean) -> Unit) {
        UpdateEngineClient().apply(zip, { st, p -> progress(BuildProgress.install(st, p)) }) { code ->
            log("update_engine finished: $code")
            onDone(app.plusos.core.PayloadMetadata.installed(code)) // T3: 52 = installed, waiting for the owner
        }
    }

    private fun downloadRelease(): File {
        val release = channel.parse(URL(channel.metadataUrl).readText())
        // S6: "Update now" skips the check, so the build refuses a downgrade too (update_engine would, with error 51).
        check(!release.isOlderThan(android.os.Build.TIME / 1000)) { ReleaseChannel.behindMessage(channel.channel, release.build) }
        val dest = File(work, release.fullOtaName)
        if (!dest.exists()) {
            work.listFiles { f -> f.name.endsWith(".zip") && f.name != dest.name }?.forEach { it.delete() }
            Downloader().fetch(URL(channel.baseUrl + release.fullOtaName), dest, null) { d, t ->
                // An automatic build stops if the phone moves to mobile data; the part file resumes later on Wi-Fi.
                if (d % (8L * 1024 * 1024) < 256 * 1024) downloadGuard()
                if (t > 0 && d % (50L * 1024 * 1024) < 256 * 1024) progress(BuildProgress.download(release.build, d * 100 / t))
            }
        } else {
            progress(BuildProgress.download(release.build, 100))
        }
        return dest
    }

    /** The running OS's owner key, as public material only: the private half stays in the keystore. */
    private fun helperSigner(signDir: File): SignerConfig.Helper {
        val cert = KeyService.runningOtaCert()
        val pub = File(work, "owner.pub").apply { writeText(pem("PUBLIC KEY", cert.publicKey.encoded)) }
        val certFile = File(work, "owner.crt").apply { writeText(OwnerCert.toPem(cert)) }
        val pkmd = File(work, "owner_pkmd.bin").apply { writeBytes(AvbKey.encode(cert.publicKey as java.security.interfaces.RSAPublicKey)) }
        return SignerConfig.Helper(pub, certFile, pkmd, "${ctx.applicationInfo.nativeLibraryDir}/libsignhelper.so",
            mapOf("PLUS_SIGN_DIR" to signDir.path))
    }

    /** Dev only: a fresh key in a file, to measure the pipeline in-app without the owner key. */
    private fun throwawaySigner(): SignerConfig.KeyFile {
        val k = OwnerKey.generate()
        val key = File(work, "throwaway.key").apply { writeText(pem("PRIVATE KEY", k.privateKey.encoded)) }
        val cert = File(work, "throwaway.crt").apply { writeText(OwnerCert.toPem(OwnerCert.derive(k))) }
        val pkmd = File(work, "throwaway_pkmd.bin").apply { writeBytes(AvbKey.encode(k.publicKey)) }
        return SignerConfig.KeyFile(key, cert, pkmd)
    }

    private fun ownerFingerprint(): String = runCatching {
        "sha256:" + hex(AvbKey.fingerprint(KeyService.runningOtaCert().publicKey as java.security.interfaces.RSAPublicKey))
    }.getOrDefault("sha256:unknown")

    /** Review I8: the previous build's log is kept as build.log.1 for "Share log". */
    private fun rotateLog() { if (logFile.exists()) logFile.renameTo(File(ctx.filesDir, "build.log.1")) }

    /** Every line goes to the log; only the numbered steps reach the notification (S6, user). */
    private fun log(s: String) { Log.i(UpdateEngineClient.TAG, s); appendLog("[plus] $s"); BuildProgress.fromLog(s)?.let(progress) }
    private fun appendLog(s: String) = synchronized(logFile) { logFile.appendText(s + "\n") }

    companion object {
        fun sha256(f: File): String = hex(MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest()
        })
        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun pem(type: String, der: ByteArray) =
            "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"
    }
}

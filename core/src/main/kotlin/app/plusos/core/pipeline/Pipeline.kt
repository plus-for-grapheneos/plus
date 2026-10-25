// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.pipeline

import app.plusos.core.BuildManifest
import app.plusos.core.Pins
import app.plusos.core.PrivappXml
import java.io.File
import java.security.MessageDigest

/**
 * One build (spec §4.2): official OTA + manifest → a signed update for the owner key. The command sequence is the one
 * proven in spikes S2/S3. Each stage leaves a marker holding the manifest hash, so an interrupted build resumes after
 * its last finished stage; a different manifest starts over. The output appears only after it verified.
 */
class Pipeline(
    private val work: File,
    private val tools: Tools,
    private val runner: ToolRunner,
    private val options: Options = Options(),
    private val log: (String) -> Unit,
) {
    /**
     * Test hooks for our own GrapheneOS source builds, which have no Updater app and other signing keys. Production
     * (official GrapheneOS) always uses the defaults: the pinned GrapheneOS keys and a required Updater.
     */
    data class Options(
        val inputAvbPkmd: File? = null,
        val inputCert: File? = null,
        val allowMissingUpdater: Boolean = false,
    )

    private val parts = listOf("system", "product")

    fun run(ota: File, manifest: BuildManifest, files: AddedFiles, signer: SignerConfig, out: File): PipelineResult {
        val hash = sha256(manifest.toJson().toByteArray())
        if (work.exists() && Stage.entries.any { marker(it).exists() && marker(it).readText() != hash }) {
            log("build inputs changed; starting over")
            work.deleteRecursively()
        }
        work.mkdirs()
        val pins = File(work, "pins").apply { mkdirs() }
        // +PLUS 11: GrapheneOS signs each phone model with its own keys; a model without pinned keys never builds.
        val devicePins = if (options.inputAvbPkmd != null && options.inputCert != null) null else Pins.forDevice(manifest.device)
            ?: throw PipelineException(Stage.VERIFY_INPUT, PipelineException.Reason.BAD_INPUT,
                "+PLUS has no GrapheneOS keys for this phone model (${manifest.device})")
        val gosAvb = options.inputAvbPkmd ?: File(pins, "grapheneos_avb_pkmd.bin").apply { writeBytes(devicePins!!.avbPkmd) }
        val gosCert = options.inputCert ?: File(pins, "grapheneos_ota.x509.pem").apply { writeBytes(devicePins!!.otaCert.pemBytes()) }
        val part = File(out.path + ".part")

        // Review I2: each stage clears its own partial outputs on entry, and deletes the previous stage's files only after
        // its own marker is written, so a kill at any moment leaves a state the next run can resume from.
        stage(Stage.VERIFY_INPUT, hash, outputs = {}) {
            tool(Stage.VERIFY_INPUT, av("ota", "verify", "--input", "$ota", "--public-key-avb", "$gosAvb", "--cert-ota", "$gosCert"))
        }
        stage(Stage.EXTRACT, hash, outputs = { f("ex").deleteRecursively() }) {
            tool(Stage.EXTRACT, av("ota", "extract", "-i", "$ota", "-d", "${f("ex")}", "-p", "system", "-p", "product"))
        }
        stage(Stage.AVB_UNPACK, hash, outputs = { parts.forEach { f("$it.avb.toml").delete(); f("$it.raw").delete() } },
            after = { f("ex").deleteRecursively() }) {
            for (p in parts) {
                tool(Stage.AVB_UNPACK, av("avb", "unpack", "-i", "${f("ex/$p.img")}", "--output-info", "${f("$p.avb.toml")}",
                    "--output-raw", "${f("$p.raw")}", "-q"))
            }
        }
        stage(Stage.FS_UNPACK, hash, outputs = { parts.forEach { f("$it.fs.toml").delete(); f("$it.tree").deleteRecursively() } },
            after = { parts.forEach { f("$it.raw").delete() } }) {
            for (p in parts) {
                tool(Stage.FS_UNPACK, af("unpack", "-i", "${f("$p.raw")}", "--output-metadata", "${f("$p.fs.toml")}",
                    "--output-tree", "${f("$p.tree")}"))
            }
        }
        stage(Stage.EDIT, hash, outputs = {}) { edit(manifest, files) }
        stage(Stage.FS_PACK, hash, outputs = { parts.forEach { f("$it.new.raw").delete() } },
            after = { parts.forEach { f("$it.tree").deleteRecursively() } }) {
            for (p in parts) {
                tool(Stage.FS_PACK, af("pack", "--input-metadata", "${f("$p.fs.toml")}", "--input-tree", "${f("$p.tree")}",
                    "-o", "${f("$p.new.raw")}"))
            }
        }
        stage(Stage.AVB_PACK, hash, outputs = { parts.forEach { f("$it.new.img").delete() } },
            after = { parts.forEach { f("$it.new.raw").delete() } }) {
            for (p in parts) {
                tool(Stage.AVB_PACK, av("avb", "pack", "-o", "${f("$p.new.img")}", "--input-info", "${f("$p.avb.toml")}",
                    "--input-raw", "${f("$p.new.raw")}", "--recompute-size", "-q"))
            }
        }
        stage(Stage.PATCH, hash, outputs = { part.delete() }) {
            val (keyArgs, env) = when (signer) {
                is SignerConfig.KeyFile -> {
                    val pass = signer.passphrase
                    val passArgs = if (pass != null) listOf("--pass-avb-env-var", PASS_ENV, "--pass-ota-env-var", PASS_ENV) else emptyList()
                    listOf("--key-avb", "${signer.key}", "--key-ota", "${signer.key}") + passArgs to
                        (if (pass != null) mapOf(PASS_ENV to pass) else emptyMap())
                }
                is SignerConfig.Helper -> listOf("--key-avb", "${signer.publicKey}", "--key-ota", "${signer.publicKey}") to signer.env
            }
            val helperArgs = if (signer is SignerConfig.Helper) listOf("--signing-helper", signer.helperPath) else emptyList()
            tool(Stage.PATCH, av("ota", "patch", "--input", "$ota", "--output", "$part", "--rootless") + keyArgs +
                listOf("--cert-ota", "${signer.cert}") + helperArgs +
                listOf("--replace", "product", "${f("product.new.img")}", "--replace", "system", "${f("system.new.img")}"), env)
        }
        stage(Stage.VERIFY_OUTPUT, hash, outputs = { out.delete() }, after = { parts.forEach { f("$it.new.img").delete() } }) {
            try {
                tool(Stage.VERIFY_OUTPUT, av("ota", "verify", "--input", "$part", "--public-key-avb", "${signer.avbPkmd}",
                    "--cert-ota", "${signer.cert}"), failReason = PipelineException.Reason.VERIFY_FAILED)
            } catch (e: PipelineException) {
                part.delete() // an output that doesn't verify is never kept
                throw e
            }
            if (out.exists()) out.delete()
            check(part.renameTo(out)) { "could not move the verified update into place" }
        }
        work.listFiles { f -> f.name.startsWith("failures-") }?.forEach { it.delete() }
        log("VERIFY_OUTPUT OK: $out")
        return PipelineResult(out)
    }

    private fun edit(manifest: BuildManifest, files: AddedFiles) {
        val system = f("system.tree")
        val product = f("product.tree")
        val updater = File(system, "system/priv-app/Updater/Updater.apk")
        val systemNew = mutableListOf<FsEdit.FsEntry>()
        if (!updater.exists()) {
            if (!options.allowMissingUpdater) {
                throw PipelineException(Stage.EDIT, PipelineException.Reason.IMAGE_LAYOUT, "this GrapheneOS image has no Updater app")
            }
            updater.parentFile.mkdirs()
            systemNew += FsEdit.FsEntry("/system/priv-app/Updater/Updater.apk", dir = false)
        }
        files.installerApk.copyTo(updater, overwrite = true)
        systemNew += unpackNativeLibs(updater, "/system/priv-app/Updater")
        for ((path, src) in files.installerXmls) {
            require(path.startsWith("system/")) { "installer files go to system/, got $path" }
            val dest = File(system, path)
            if (!dest.exists()) systemNew += FsEdit.FsEntry("/$path", dir = false)
            dest.parentFile.mkdirs()
            src.copyTo(dest, overwrite = true)
        }
        if (systemNew.isNotEmpty()) f("system.fs.toml").writeText(FsEdit.addEntries(f("system.fs.toml").readText(), systemNew))

        val productNew = mutableListOf<FsEdit.FsEntry>()
        for ((path, src) in files.addonApks) {
            require(path.startsWith("product/")) { "add-ons go to product/, got $path" }
            val rel = path.removePrefix("product/")
            val dest = File(product, rel).apply { parentFile.mkdirs() }.also { src.copyTo(it, overwrite = true) }
            productNew += FsEdit.FsEntry("/$rel", dir = false)
            if (rel.endsWith(".apk")) productNew += unpackNativeLibs(dest, "/" + rel.substringBeforeLast('/'))
        }
        val grants = manifest.addons.flatMap { it.privappPermissions.entries }
            .groupBy({ it.key }, { it.value }).mapValues { (_, v) -> v.flatten().distinct() }
        if (grants.isNotEmpty()) {
            File(product, PRIVAPP_XML).apply { parentFile.mkdirs() }.writeText(PrivappXml.generate(grants))
            productNew += FsEdit.FsEntry("/$PRIVAPP_XML", dir = false)
        }
        File(product, MANIFEST).apply { parentFile.mkdirs() }.writeText(manifest.toJson())
        productNew += FsEdit.FsEntry("/$MANIFEST", dir = false)
        f("product.fs.toml").writeText(FsEdit.addEntries(f("product.fs.toml").readText(), productNew))
    }

    /**
     * Preinstalled apps get no native-library extraction from the system: their arm64 libraries must sit in
     * `<app dir>/lib/arm64/` in the image (found on the dev phone, S4). They are made executable, because +PLUS runs
     * avbroot, afsr and the signing helper from there.
     */
    private fun unpackNativeLibs(apk: File, imageDir: String): List<FsEdit.FsEntry> {
        val entries = mutableListOf<FsEdit.FsEntry>()
        val libDir = File(apk.parentFile, "lib/arm64")
        java.util.zip.ZipFile(apk).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || !e.name.startsWith("lib/arm64-v8a/")) continue
                val name = e.name.removePrefix("lib/arm64-v8a/")
                // Review I7: the name becomes a TOML path and a file name: plain characters only.
                require(name.matches(Regex("[A-Za-z0-9._+-]+\\.so"))) { "unsafe native library name in ${apk.name}" }
                libDir.mkdirs()
                z.getInputStream(e).use { input -> File(libDir, name).outputStream().use { input.copyTo(it) } }
                entries += FsEdit.FsEntry("$imageDir/lib/arm64/$name", dir = false, executable = true)
            }
        }
        return entries
    }

    private inline fun stage(s: Stage, hash: String, outputs: () -> Unit, after: () -> Unit = {}, body: () -> Unit) {
        if (marker(s).exists()) { after(); log("$s already done"); return }
        log("$s ...")
        outputs() // partial output of an earlier, killed attempt
        try {
            body()
        } catch (e: Exception) {
            outputs()
            val pe = e as? PipelineException ?: PipelineException(s, PipelineException.Reason.BAD_INPUT, "$s: ${e.message}")
            val failures = File(work, "failures-${s.name}").let { c -> (c.takeIf { it.exists() }?.readText()?.toIntOrNull() ?: 0) + 1 }
            if (s == Stage.VERIFY_INPUT || failures >= MAX_STAGE_FAILURES) {
                // The input doesn't verify, or this stage keeps failing: start from scratch next time (review C3, I2).
                log("$s failed ${failures}x; starting over from scratch next time")
                work.deleteRecursively()
            } else {
                File(work, "failures-${s.name}").writeText("$failures")
            }
            throw pe
        }
        marker(s).writeText(hash)
        after()
    }

    private fun tool(
        s: Stage,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        failReason: PipelineException.Reason = PipelineException.Reason.TOOL_FAILED,
    ) {
        val r = runner.run(args, env, null)
        if (r.exit != 0) {
            val noSpace = listOf("No space left on device", "ENOSPC", "os error 28").any { it in r.stderrTail }
            throw PipelineException(s, if (noSpace) PipelineException.Reason.NO_SPACE else failReason,
                "$s failed (exit ${r.exit}): ${r.stderrTail.trim().lines().lastOrNull().orEmpty()}")
        }
    }

    private fun av(vararg a: String) = listOf(tools.avbroot) + a
    private fun af(vararg a: String) = listOf(tools.afsr) + a
    private fun f(name: String) = File(work, name)
    private fun marker(s: Stage) = File(work, "stage-${s.name}.done")

    companion object {
        const val PRIVAPP_XML = "etc/permissions/privapp-permissions-plus.xml"
        const val PASS_ENV = "PLUS_KEY_PASS"
        const val MAX_STAGE_FAILURES = 3

        /** What the image contains; read by +PLUS at runtime as /product/etc/plus/build-manifest.json. */
        const val MANIFEST = "etc/plus/build-manifest.json"

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

internal fun java.security.cert.X509Certificate.pemBytes(): ByteArray = app.plusos.core.OwnerCert.toPem(this).toByteArray()

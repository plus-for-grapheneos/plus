// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core.pipeline

import app.plusos.core.BuildManifest
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PipelineTest {
    private val root: File = Files.createTempDirectory("pipe").toFile()
    private val work = File(root, "work")
    private val out = File(root, "plus-update.zip")
    private val ota = File(root, "tokay-ota_update-2026101600.zip").apply { writeText("ota") }
    private val tools = Tools(avbroot = "AV", afsr = "AF")
    private val fake = FakeTools()

    /** A minimal APK: a zip holding one marker entry. */
    private fun fakeApk(name: String, marker: String) = File(root, name).also { f ->
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("marker.txt")); z.write(marker.toByteArray()); z.closeEntry()
        }
    }

    private val files = AddedFiles(
        installerApk = fakeApk("plus.apk", "plus"),
        installerXmls = mapOf(
            "system/etc/permissions/app.seamlessupdate.client.xml" to File(root, "a.xml").apply { writeText("a") },
        ),
        addonApks = mapOf(
            "product/priv-app/PlusPhone/PlusPhone.apk" to fakeApk("phone.apk", "phone"),
            "product/app/PlusTranscriber/PlusTranscriber.apk" to fakeApk("tr.apk", "tr"),
        ),
    )
    private val signer = SignerConfig.KeyFile(
        key = File(root, "owner.key").apply { writeText("k") },
        cert = File(root, "owner.crt").apply { writeText("c") },
        avbPkmd = File(root, "owner_pkmd.bin").apply { writeText("p") },
    )

    private fun manifest(version: Int = 31) = BuildManifest(
        device = "tokay", grapheneos = BuildManifest.Os("2026101600", "ab"), ownerKeyFingerprint = "sha256:00",
        installer = BuildManifest.Installer(1, "cc"),
        addons = listOf(
            BuildManifest.Addon(
                "callscreen-phone", version,
                listOf(
                    BuildManifest.Apk("app.plusos.phone", "aa", "product/priv-app/PlusPhone/PlusPhone.apk"),
                    BuildManifest.Apk("app.plusos.phone.transcriber", "bb", "product/app/PlusTranscriber/PlusTranscriber.apk"),
                ),
                mapOf("app.plusos.phone" to listOf("android.permission.CAPTURE_AUDIO_OUTPUT")),
            ),
        ),
    )

    private fun pipeline() = Pipeline(work, tools, fake) { }

    @AfterEach
    fun cleanup() { root.deleteRecursively() }

    @Test
    fun a_model_without_pinned_keys_never_builds() {
        // +PLUS 11: a Pixel 9 Pro Fold (comet) has no GrapheneOS keys pinned yet: nothing runs, nothing is signed.
        val e = assertThrows<PipelineException> { pipeline().run(ota, manifest().copy(device = "sunfish"), files, signer, out) }
        assertEquals(Stage.VERIFY_INPUT, e.stage)
        assertTrue(fake.calls.isEmpty())
        assertFalse(out.exists())
    }

    @Test
    fun stages_in_order_with_exact_args() {
        val result = pipeline().run(ota, manifest(), files, signer, out)
        val pins = File(work, "pins")
        val expected = listOf(
            "AV ota verify --input $ota --public-key-avb ${File(pins, "grapheneos_avb_pkmd.bin")} --cert-ota ${File(pins, "grapheneos_ota.x509.pem")}",
            "AV ota extract -i $ota -d ${File(work, "ex")} -p system -p product",
            "AV avb unpack -i ${File(work, "ex/system.img")} --output-info ${File(work, "system.avb.toml")} --output-raw ${File(work, "system.raw")} -q",
            "AV avb unpack -i ${File(work, "ex/product.img")} --output-info ${File(work, "product.avb.toml")} --output-raw ${File(work, "product.raw")} -q",
            "AF unpack -i ${File(work, "system.raw")} --output-metadata ${File(work, "system.fs.toml")} --output-tree ${File(work, "system.tree")}",
            "AF unpack -i ${File(work, "product.raw")} --output-metadata ${File(work, "product.fs.toml")} --output-tree ${File(work, "product.tree")}",
            "AF pack --input-metadata ${File(work, "system.fs.toml")} --input-tree ${File(work, "system.tree")} -o ${File(work, "system.new.raw")}",
            "AF pack --input-metadata ${File(work, "product.fs.toml")} --input-tree ${File(work, "product.tree")} -o ${File(work, "product.new.raw")}",
            "AV avb pack -o ${File(work, "system.new.img")} --input-info ${File(work, "system.avb.toml")} --input-raw ${File(work, "system.new.raw")} --recompute-size -q",
            "AV avb pack -o ${File(work, "product.new.img")} --input-info ${File(work, "product.avb.toml")} --input-raw ${File(work, "product.new.raw")} --recompute-size -q",
            "AV ota patch --input $ota --output $out.part --rootless --key-avb ${signer.key} --key-ota ${signer.key} " +
                "--cert-ota ${signer.cert} --replace product ${File(work, "product.new.img")} --replace system ${File(work, "system.new.img")}",
            "AV ota verify --input $out.part --public-key-avb ${signer.avbPkmd} --cert-ota ${signer.cert}",
        )
        assertEquals(expected, fake.calls.map { it.joinToString(" ") })
        assertTrue(out.exists())
        assertEquals(out, result.output)
        val product = File(work, "product.tree.snapshot")
        assertArrayEquals(files.addonApks.values.first().readBytes(), File(product, "priv-app/PlusPhone/PlusPhone.apk").readBytes())
        assertTrue(File(product, "etc/permissions/privapp-permissions-plus.xml").readText().contains("CAPTURE_AUDIO_OUTPUT"))
        assertArrayEquals(files.installerApk.readBytes(), File(work, "system.tree.snapshot/system/priv-app/Updater/Updater.apk").readBytes())
        assertTrue(fake.productToml.contains("path = \"/priv-app/PlusPhone/PlusPhone.apk\""))
    }

    @Test
    fun helper_signer_passes_public_key_and_helper() {
        val helper = SignerConfig.Helper(
            publicKey = File(root, "owner.pub").apply { writeText("pub") }, cert = signer.cert,
            avbPkmd = signer.avbPkmd, helperPath = "/data/app/x/lib/arm64/libsignhelper.so",
        )
        pipeline().run(ota, manifest(), files, helper, out)
        val patch = fake.calls.single { it.getOrNull(2) == "patch" }.joinToString(" ")
        assertTrue(patch.contains("--key-avb ${helper.publicKey} --key-ota ${helper.publicKey}"))
        assertTrue(patch.contains("--signing-helper /data/app/x/lib/arm64/libsignhelper.so"))
    }

    /**
     * Preinstalled apps get no native-library extraction: their libs must already sit in `<app dir>/lib/arm64/`
     * (found on the dev phone, S4). +PLUS runs avbroot/afsr/the helper from there, so they must be executable.
     */
    @Test
    fun native_libs_are_unpacked_next_to_the_apk() {
        val apk = File(root, "plus-with-libs.apk")
        java.util.zip.ZipOutputStream(apk.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("classes.dex")); z.write("dex".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libavbroot.so")); z.write("avbroot-elf".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libsignhelper.so")); z.write("#!/system/bin/sh".toByteArray()); z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("lib/x86_64/libignored.so")); z.write("x".toByteArray()); z.closeEntry()
        }
        pipeline().run(ota, manifest(), files.copy(installerApk = apk), signer, out)
        val lib = File(work, "system.tree.snapshot/system/priv-app/Updater/lib/arm64")
        assertEquals("avbroot-elf", File(lib, "libavbroot.so").readText())
        assertEquals("#!/system/bin/sh", File(lib, "libsignhelper.so").readText())
        assertFalse(File(lib, "libignored.so").exists())
        val entry = fake.systemToml.substringAfter("path = \"/system/priv-app/Updater/lib/arm64/libavbroot.so\"").substringBefore("[[entries]]")
        assertTrue(entry.contains("file_mode = \"755\""), "bundled tools must be executable")
        assertTrue(fake.systemToml.contains("path = \"/system/priv-app/Updater/lib\"\n"))
    }

    private val ownEntry = app.plusos.core.OwnApp.Entry(
        id = "google-phone", name = "Google Phone", packageName = "com.google.android.dialer", version = 5,
        signerSha256 = "e2", paths = listOf("product/priv-app/GooglePhone/base.apk", "product/priv-app/GooglePhone/split_config.arm64_v8a.apk"),
        grants = listOf("android.permission.MODIFY_PHONE_STATE"), denies = listOf("android.permission.WRITE_SECURE_SETTINGS"),
        dynamicCode = true, powerAllowlist = true,
    )

    private fun ownAppFiles(): AddedFiles {
        val split = File(root, "split_config.arm64_v8a.apk")
        java.util.zip.ZipOutputStream(split.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libdialer.so")); z.write("elf".toByteArray()); z.closeEntry()
        }
        return files.copy(
            ownAppApks = mapOf(ownEntry.paths[0] to fakeApk("base.apk", "google"), ownEntry.paths[1] to split),
            dynamicCodeOverlay = fakeApk("overlay.apk", "overlay"),
        )
    }

    /** "Your own app": the owner's installed copy goes in unchanged, with grants, denies, overlay and sysconfig. */
    @Test
    fun own_apps_are_copied_unchanged_with_their_permission_files() {
        val f = ownAppFiles()
        pipeline().run(ota, manifest().copy(ownApps = listOf(ownEntry)), f, signer, out)
        val product = File(work, "product.tree.snapshot")
        assertArrayEquals(f.ownAppApks.getValue(ownEntry.paths[0]).readBytes(), File(product, "priv-app/GooglePhone/base.apk").readBytes())
        assertArrayEquals(f.ownAppApks.getValue(ownEntry.paths[1]).readBytes(), File(product, "priv-app/GooglePhone/split_config.arm64_v8a.apk").readBytes())
        assertEquals("elf", File(product, "priv-app/GooglePhone/lib/arm64/libdialer.so").readText())
        val privapp = File(product, "etc/permissions/privapp-permissions-plus.xml").readText()
        assertTrue(privapp.contains("<privapp-permissions package=\"com.google.android.dialer\">\n        <permission name=\"android.permission.MODIFY_PHONE_STATE\"/>\n        <deny-permission name=\"android.permission.WRITE_SECURE_SETTINGS\"/>"), privapp)
        assertTrue(privapp.contains("app.plusos.phone"), "add-on grants stay")
        assertArrayEquals(f.dynamicCodeOverlay!!.readBytes(), File(product, "overlay/PlusDynamicCode/PlusDynamicCode.apk").readBytes())
        assertTrue(File(product, "etc/sysconfig/plus-own-apps.xml").readText().contains("<allow-in-power-save package=\"com.google.android.dialer\"/>"))
        for (p in listOf("/priv-app/GooglePhone/base.apk", "/priv-app/GooglePhone/split_config.arm64_v8a.apk",
                "/overlay/PlusDynamicCode/PlusDynamicCode.apk", "/etc/sysconfig/plus-own-apps.xml")) {
            assertTrue(fake.productToml.contains("path = \"$p\""), p)
        }
    }

    @Test
    fun no_overlay_or_sysconfig_without_an_own_app_that_needs_them() {
        pipeline().run(ota, manifest(), files.copy(dynamicCodeOverlay = fakeApk("overlay.apk", "overlay")), signer, out)
        val product = File(work, "product.tree.snapshot")
        assertFalse(File(product, "overlay/PlusDynamicCode/PlusDynamicCode.apk").exists())
        assertFalse(File(product, "etc/sysconfig/plus-own-apps.xml").exists())
    }

    @Test
    fun an_own_app_whose_files_are_missing_stops_the_build() {
        val f = ownAppFiles().let { it.copy(ownAppApks = it.ownAppApks - ownEntry.paths[1]) }
        assertThrows<PipelineException> { pipeline().run(ota, manifest().copy(ownApps = listOf(ownEntry)), f, signer, out) }
        assertFalse(out.exists())
    }

    @Test
    fun an_own_app_needing_the_overlay_without_one_stops_the_build() {
        val f = ownAppFiles().copy(dynamicCodeOverlay = null)
        assertThrows<PipelineException> { pipeline().run(ota, manifest().copy(ownApps = listOf(ownEntry)), f, signer, out) }
    }

    /** +PLUS learns what its running image contains from this file (UpdateDecision needs it after every update). */
    @Test
    fun manifest_is_written_into_product() {
        val m = manifest()
        pipeline().run(ota, m, files, signer, out)
        val written = File(work, "product.tree.snapshot/etc/plus/build-manifest.json")
        assertEquals(m, BuildManifest.fromJson(written.readText()))
        assertTrue(fake.productToml.contains("path = \"/etc/plus/build-manifest.json\""))
    }

    /** Review I2: a stage killed mid-way (no exception handling ran) leaves partial output; the rerun clears it first. */
    @Test
    fun killed_mid_stage_partial_output_is_cleared_on_rerun() {
        fake.killOn = "pack"
        assertThrows<Kill> { pipeline().run(ota, manifest(), files, signer, out) }
        assertTrue(File(work, "system.new.raw").exists(), "the simulated kill leaves a partial file")
        fake.killOn = null
        fake.outputExistedBefore.clear()
        pipeline().run(ota, manifest(), files, signer, out)
        assertEquals(false, fake.outputExistedBefore["pack"], "FS_PACK must start from a clean slate")
    }

    /** Review I2: inputs are deleted only after the stage marker is written, so a rerun of the stage still finds them. */
    @Test
    fun stage_inputs_survive_until_its_marker_is_written() {
        fake.killOn = "avb-pack"
        assertThrows<Kill> { pipeline().run(ota, manifest(), files, signer, out) }
        assertTrue(File(work, "stage-FS_PACK.done").exists())
        assertFalse(File(work, "system.tree").exists(), "trees are removed once FS_PACK is recorded")
        assertTrue(File(work, "system.new.raw").exists(), "AVB_PACK's input is still there")
    }

    /** Review I2: three failures of the same stage start the build over from scratch. */
    @Test
    fun repeated_failures_wipe_the_work() {
        fake.failOn = "patch"
        repeat(3) { assertThrows<PipelineException> { pipeline().run(ota, manifest(), files, signer, out) } }
        assertFalse(File(work, "stage-VERIFY_INPUT.done").exists())
    }

    /** Review C3: an input that fails verification is not reused: the work is wiped. */
    @Test
    fun verify_input_failure_wipes_the_work() {
        fake.failOn = "verify-input"
        val e = assertThrows<PipelineException> { pipeline().run(ota, manifest(), files, signer, out) }
        assertEquals(Stage.VERIFY_INPUT, e.stage)
        assertFalse(work.exists())
    }

    /** Review I7: library names from an APK can't inject TOML entries or escape the lib directory. */
    @Test
    fun unsafe_native_library_names_are_refused() {
        val apk = File(root, "evil.apk")
        java.util.zip.ZipOutputStream(apk.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libx.so\"\nfile_mode = \"4755")); z.write(1); z.closeEntry()
        }
        val e = assertThrows<PipelineException> { pipeline().run(ota, manifest(), files.copy(installerApk = apk), signer, out) }
        assertEquals(Stage.EDIT, e.stage)
    }

    /** Review I10: an encrypted key file's passphrase reaches avbroot only through the environment. */
    @Test
    fun encrypted_key_passphrase_goes_through_the_environment() {
        val enc = signer.copy(passphrase = "s3cret")
        pipeline().run(ota, manifest(), files, enc, out)
        val i = fake.calls.indexOfFirst { it.getOrNull(2) == "patch" }
        val patch = fake.calls[i].joinToString(" ")
        assertTrue(patch.contains("--pass-avb-env-var PLUS_KEY_PASS --pass-ota-env-var PLUS_KEY_PASS"))
        assertFalse(patch.contains("s3cret"))
        assertEquals("s3cret", fake.envs[i]["PLUS_KEY_PASS"])
    }

    @Test
    fun missing_updater_fails_by_default() {
        fake.systemHasUpdater = false
        val e = assertThrows<PipelineException> { pipeline().run(ota, manifest(), files, signer, out) }
        assertEquals(Stage.EDIT, e.stage)
        assertEquals(PipelineException.Reason.IMAGE_LAYOUT, e.reason)
    }

    /** Test hook for our own source builds (no Updater app): +PLUS is added at the Updater's path instead. */
    @Test
    fun missing_updater_added_when_allowed() {
        fake.systemHasUpdater = false
        Pipeline(work, tools, fake, options = Pipeline.Options(allowMissingUpdater = true)) { }.run(ota, manifest(), files, signer, out)
        assertArrayEquals(files.installerApk.readBytes(), File(work, "system.tree.snapshot/system/priv-app/Updater/Updater.apk").readBytes())
        assertTrue(fake.systemToml.contains("path = \"/system/priv-app/Updater/Updater.apk\""))
        assertTrue(fake.systemToml.contains("path = \"/system/priv-app/Updater\"\n"))
    }

    /** Test hook: verify the input against other keys than GrapheneOS's (our own source builds). */
    @Test
    fun input_keys_override_used_for_verify_input() {
        val avb = File(root, "rung_pkmd.bin").apply { writeText("x") }
        val cert = File(root, "rung.x509.pem").apply { writeText("y") }
        Pipeline(work, tools, fake, options = Pipeline.Options(inputAvbPkmd = avb, inputCert = cert)) { }.run(ota, manifest(), files, signer, out)
        assertEquals("AV ota verify --input $ota --public-key-avb $avb --cert-ota $cert", fake.calls.first().joinToString(" "))
    }

    @Test
    fun resume_skips_done_stages() {
        fake.failOn = "patch"
        assertThrows<PipelineException> { pipeline().run(ota, manifest(), files, signer, out) }
        fake.failOn = null
        fake.calls.clear()
        pipeline().run(ota, manifest(), files, signer, out)
        assertEquals("patch", fake.calls.first()[2])
        assertTrue(out.exists())
    }

    @Test
    fun manifest_change_restarts() {
        fake.failOn = "patch"
        assertThrows<PipelineException> { pipeline().run(ota, manifest(31), files, signer, out) }
        fake.failOn = null
        fake.calls.clear()
        pipeline().run(ota, manifest(32), files, signer, out)
        assertEquals(listOf("AV", "ota", "verify"), fake.calls.first().take(3))
        assertEquals("--input", fake.calls.first()[3])
        assertEquals(ota.toString(), fake.calls.first()[4])
    }

    @Test
    fun failed_stage_cleans_partial() {
        fake.failOn = "pack"
        fake.failStderr = "error: write failed: No space left on device (os error 28)"
        val e = assertThrows<PipelineException> { pipeline().run(ota, manifest(), files, signer, out) }
        assertEquals(Stage.FS_PACK, e.stage)
        assertEquals(PipelineException.Reason.NO_SPACE, e.reason)
        assertFalse(File(work, "system.new.raw").exists())
        assertFalse(File(work, "product.new.raw").exists())
        assertFalse(out.exists())
        assertFalse(File("$out.part").exists())
    }

    @Test
    fun output_never_appears_when_final_verify_fails() {
        fake.failOn = "verify-output"
        val e = assertThrows<PipelineException> { pipeline().run(ota, manifest(), files, signer, out) }
        assertEquals(Stage.VERIFY_OUTPUT, e.stage)
        assertFalse(out.exists())
        assertFalse(File("$out.part").exists())
    }

    /** Simulates the process being killed: not an Exception, so no cleanup code runs. */
    class Kill : Error("killed")

    /** Stands in for avbroot and afsr: records each call and creates the files the real tools would. */
    private inner class FakeTools : ToolRunner {
        val calls = mutableListOf<List<String>>()
        val envs = mutableListOf<Map<String, String>>()
        val outputExistedBefore = mutableMapOf<String, Boolean>()
        var killOn: String? = null
        var failOn: String? = null
        var failStderr = "boom"
        var productToml = ""
        var systemToml = ""
        var systemHasUpdater = true

        override fun run(args: List<String>, env: Map<String, String>, stdin: File?): ToolResult {
            calls += args
            envs += env
            val key = when {
                args[1] == "ota" && args[2] == "verify" && args[4].endsWith(".part") -> "verify-output"
                args[1] == "ota" && args[2] == "verify" -> "verify-input"
                args[0] == "AF" -> args[1]
                args[1] == "avb" && args[2] == "pack" -> "avb-pack"
                else -> args[2]
            }
            arg(args, "-o")?.let { outputExistedBefore.putIfAbsent(key, File(it).exists()) }
            if (key == killOn) {
                arg(args, "-o")?.let { File(it).writeText("partial") }
                throw Kill()
            }
            if (key == failOn) {
                // A failing tool may leave half-written output behind; the pipeline must remove it.
                arg(args, "-o")?.let { File(it).writeText("partial") }
                arg(args, "--output")?.let { File(it).writeText("partial") }
                return ToolResult(1, failStderr)
            }
            when {
                args[0] == "AV" && args[1] == "ota" && args[2] == "extract" ->
                    File(arg(args, "-d")!!).apply { mkdirs() }.let { d ->
                        File(d, "system.img").writeText("s"); File(d, "product.img").writeText("p")
                    }
                args[0] == "AV" && args[1] == "avb" && args[2] == "unpack" -> {
                    File(arg(args, "--output-info")!!).writeText("info"); File(arg(args, "--output-raw")!!).writeText("raw")
                }
                args[0] == "AF" && args[1] == "unpack" -> {
                    val tree = File(arg(args, "--output-tree")!!)
                    val system = tree.name.startsWith("system")
                    if (system) {
                        if (systemHasUpdater) {
                            File(tree, "system/priv-app/Updater").mkdirs()
                            File(tree, "system/priv-app/Updater/Updater.apk").writeText("grapheneos updater")
                        }
                        File(tree, "system/etc/permissions").mkdirs()
                        File(tree, "system/etc/permissions/app.seamlessupdate.client.xml").writeText("old")
                    } else {
                        File(tree, "priv-app").mkdirs(); File(tree, "etc/permissions").mkdirs()
                    }
                    File(arg(args, "--output-metadata")!!).writeText(
                        "[[entries]]\npath = \"/\"\nfile_type = \"Directory\"\nfile_mode = \"755\"\n" +
                            (if (system) "" else "\n[[entries]]\npath = \"/priv-app\"\nfile_type = \"Directory\"\n"),
                    )
                }
                args[0] == "AF" && args[1] == "pack" -> {
                    val tree = File(arg(args, "--input-tree")!!)
                    tree.copyRecursively(File(tree.path + ".snapshot"), overwrite = true)
                    if (tree.name.startsWith("product")) productToml = File(arg(args, "--input-metadata")!!).readText()
                    if (tree.name.startsWith("system")) systemToml = File(arg(args, "--input-metadata")!!).readText()
                    File(arg(args, "-o")!!).writeText("packed")
                }
                args[0] == "AV" && args[1] == "avb" && args[2] == "pack" -> File(arg(args, "-o")!!).writeText("img")
                args[0] == "AV" && args[1] == "ota" && args[2] == "patch" -> File(arg(args, "--output")!!).writeText("signed")
            }
            return ToolResult(0, "")
        }

        private fun arg(args: List<String>, flag: String): String? = args.indexOf(flag).takeIf { it >= 0 }?.let { args[it + 1] }
    }
}

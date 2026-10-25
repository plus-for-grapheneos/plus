// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.cli

import app.plusos.core.AvbKey
import app.plusos.core.BuildManifest
import app.plusos.core.OwnerCert
import app.plusos.core.OwnerKey
import app.plusos.core.pipeline.AddedFiles
import app.plusos.core.pipeline.Pipeline
import app.plusos.core.pipeline.PipelineException
import app.plusos.core.pipeline.ProcessToolRunner
import app.plusos.core.pipeline.SignerConfig
import app.plusos.core.pipeline.Tools
import app.plusos.core.sign.Pkcs1
import app.plusos.core.sign.SignProtocol
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.system.exitProcess

private const val USAGE = """usage: plusbuild build --ota <zip> --manifest <json> --installer <apk> [--installer-xml <image path>=<file>]...
                       [--addon <image path>=<apk>]... --key <PKCS#8 PEM> --out <zip>
                       [--avbroot <path>] [--afsr <path>] [--work <dir>] [--signing-helper <path> --sign-dir <dir>]
                       [--input-avb-key <pkmd> --input-cert <pem>] [--allow-missing-updater true]   (own source builds only)
       plusbuild publish --draft <json> --apks <dir> --key <repo PKCS#8 PEM> --out <site dir> --aapt2 <path> --apksigner <path>
                         [--base-url <where the site is served>]   (prints the catalog link owners enter)
       plusbuild publish-models --models <draft json list> --key <repo PKCS#8 PEM> --out <site dir>
       plusbuild respond --sign-dir <dir> --key <PKCS#8 PEM>   (answers signing-helper requests; test and PC use)
+PLUS for GrapheneOS. Not affiliated with or endorsed by the GrapheneOS project."""

fun main(args: Array<String>) {
    val cmd = args.firstOrNull()
    if (cmd !in setOf("build", "respond", "publish", "publish-models")) { System.err.println(USAGE); exitProcess(2) }
    val opts = parse(args.drop(1))
    fun one(name: String) = opts[name]?.singleOrNull() ?: run { System.err.println("missing --$name\n$USAGE"); exitProcess(2) }
    fun pairs(name: String) = opts[name].orEmpty().associate { it.substringBefore('=') to File(it.substringAfter('=')) }

    if (cmd == "publish-models") {
        val c = app.plusos.core.ai.ModelPublisher.publish(
            app.plusos.core.ai.ModelPublisher.readDraft(File(one("models"))), readPkcs8(File(one("key"))), File(one("out")))
        println("[plusbuild] published model catalog serial ${c.serial} (expires ${c.expires}): " + c.models.joinToString { "${it.id} (${it.size / 1_000_000} MB)" })
        return
    }
    if (cmd == "publish") {
        val aapt2 = one("aapt2")
        val apksigner = one("apksigner")
        val index = app.plusos.core.Publisher { apk -> apkInfo(apk, aapt2, apksigner) }.publish(
            draft = app.plusos.core.Publisher.Draft.fromJson(File(one("draft")).readText()),
            apkDir = File(one("apks")),
            key = readPkcs8(File(one("key"))),
            site = File(one("out")),
            published = java.time.Instant.now().toString(),
        )
        println("[plusbuild] published ${index.repo} serial ${index.serial} (expires ${index.expires}): " + index.apps.joinToString { "${it.packageName} ${it.version}" })
        val key = readPkcs8(File(one("key")))
        println("[plusbuild] catalog key sha256: " + app.plusos.core.CatalogLink.keySha256(app.plusos.core.CatalogLink.publicKeyOf(key).encoded))
        opts["base-url"]?.singleOrNull()?.let { println("[plusbuild] catalog link: " + app.plusos.core.Publisher.link(it, key)) }
        return
    }
    if (cmd == "respond") { respond(File(one("sign-dir")), OwnerKey.fromPrivate(readPkcs8(File(one("key"))))); return }

    val out = File(one("out")).absoluteFile
    val work = File(opts["work"]?.single() ?: (out.path + ".work")).absoluteFile
    val key = OwnerKey.fromPrivate(readPkcs8(File(one("key"))))
    // The certificate and AVB key are always derived from the owner key (ledger pre-flight ruling): the phone checks
    // the running OS's otacerts.zip against OwnerCert.derive.
    work.mkdirs()
    val cert = File(work, "owner.crt").apply { writeText(OwnerCert.toPem(OwnerCert.derive(key))) }
    val pkmd = File(work, "owner_pkmd.bin").apply { writeBytes(AvbKey.encode(key.publicKey)) }

    val pipeline = Pipeline(
        work = File(work, "build"),
        tools = Tools(avbroot = opts["avbroot"]?.single() ?: "avbroot", afsr = opts["afsr"]?.single() ?: "afsr"),
        runner = ProcessToolRunner { println(it) },
        options = Pipeline.Options(
            inputAvbPkmd = opts["input-avb-key"]?.single()?.let { File(it).absoluteFile },
            inputCert = opts["input-cert"]?.single()?.let { File(it).absoluteFile },
            allowMissingUpdater = opts["allow-missing-updater"]?.single() == "true",
        ),
        log = { println("[plusbuild] $it") },
    )
    try {
        pipeline.run(
            ota = File(one("ota")).absoluteFile,
            manifest = BuildManifest.fromJson(File(one("manifest")).readText()),
            files = AddedFiles(File(one("installer")).absoluteFile, pairs("installer-xml"), pairs("addon")),
            signer = opts["signing-helper"]?.single()?.let { helper ->
                val pub = File(work, "owner.pub").apply { writeText(publicPem(key)) }
                SignerConfig.Helper(pub, cert, pkmd, helper, mapOf("PLUS_SIGN_DIR" to File(one("sign-dir")).absolutePath))
            } ?: SignerConfig.KeyFile(File(one("key")).absoluteFile, cert, pkmd),
            out = out,
        )
    } catch (e: PipelineException) {
        System.err.println("plusbuild: ${e.stage} failed (${e.reason}): ${e.message}")
        exitProcess(1)
    }
}

/** Answers signing-helper requests with the owner key, as +PLUS does on the phone through the keystore. */
private fun respond(dir: File, key: OwnerKey) {
    dir.mkdirs()
    val protocol = SignProtocol(dir)
    println("[plusbuild] answering signing requests in $dir")
    while (true) {
        for (r in protocol.pending()) {
            protocol.respond(r, key.signDigestInfo(Pkcs1.digestInfo(r.block, r.algo)))
            println("[plusbuild] signed ${r.id} (${r.algo})")
        }
        Thread.sleep(100)
    }
}

/** Package, versionCode and the single signer's SHA-256, read with the SDK's aapt2 and apksigner. */
private fun apkInfo(apk: File, aapt2: String, apksigner: String): app.plusos.core.Publisher.ApkInfo {
    fun run(vararg cmd: String) = ProcessBuilder(*cmd).redirectErrorStream(true).start().let { p ->
        p.inputStream.bufferedReader().readText().also { check(p.waitFor() == 0) { "${cmd[0]} failed: $it" } }
    }
    val badging = run(aapt2, "dump", "badging", apk.path)
    val pkg = Regex("package: name='([^']+)' versionCode='([0-9]+)'").find(badging) ?: error("no package line in ${apk.name}")
    // apksigner prints "Signer #1 certificate SHA-256 digest: ..." or, for v3-only APKs, "V3.0 Signer: certificate ...".
    val signers = Regex("""(?:Signer #\d+|V\d\.\d Signer:) certificate SHA-256 digest: ([0-9a-f]{64})""")
        .findAll(run(apksigner, "verify", "--print-certs", apk.path)).map { it.groupValues[1] }.toSet()
    check(signers.size == 1) { "${apk.name} must have exactly one signer, found ${signers.size}" }
    return app.plusos.core.Publisher.ApkInfo(pkg.groupValues[1], pkg.groupValues[2].toInt(), signers.single())
}

private fun publicPem(k: OwnerKey): String =
    "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(k.publicKey.encoded) +
        "\n-----END PUBLIC KEY-----\n"

private fun parse(args: List<String>): Map<String, List<String>> {
    val m = linkedMapOf<String, MutableList<String>>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        require(a.startsWith("--") && i + 1 < args.size) { "bad argument '$a'\n$USAGE" }
        m.getOrPut(a.removePrefix("--")) { mutableListOf() } += args[i + 1]
        i += 2
    }
    return m
}

private fun readPkcs8(f: File): RSAPrivateCrtKey {
    val b64 = f.readLines().filter { !it.startsWith("-----") }.joinToString("")
    return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64))) as RSAPrivateCrtKey
}

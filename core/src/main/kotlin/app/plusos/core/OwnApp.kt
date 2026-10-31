// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * "Your own app" add-ons: an app the owner installed (from Play, for example) is copied **unchanged**, still signed by its
 * maker, from this phone into the owner's image as a privileged system app, with the privileged permissions it needs.
 * The app never leaves the phone and is never re-signed; the catalog carries only a recipe (facts about the app).
 *
 * Three layers decide what an app gets, each able only to narrow the one before it:
 * 1. [OwnAppPolicy]: built into +PLUS, reviewed once: which packages may ever be made system apps, their makers'
 *    signing certificates, and the most each may be granted. A compromised catalog can't go beyond it.
 * 2. [OwnAppRecipe]: from the signed catalog: the permissions to grant now, the minimum version, the notice.
 * 3. The owner: turns the recipe on in the store and confirms the grants before the restart (T3).
 */
@Serializable
data class OwnAppRecipe(
    val id: String,
    val name: String,
    @SerialName("package") val packageName: String,
    val signers: List<String>,
    @SerialName("min_version") val minVersion: Long = 0,
    val grant: List<String>,
    /** The app needs GrapheneOS's dynamic-code-loading restriction for system apps lifted (the [DynamicCodeOverlay]). */
    @SerialName("dynamic_code") val dynamicCode: Boolean = false,
    /** Kept out of battery optimisation, as on stock Pixels (sysconfig allow-in-power-save). */
    @SerialName("power_allowlist") val powerAllowlist: Boolean = false,
    val notice: String = "",
    @SerialName("min_installer") val minInstaller: Int = 1,
)

/** What +PLUS will ever do for one package, whatever a recipe says. */
data class OwnAppPolicy(
    val packageName: String,
    /** The image folder: product/priv-app/<dir>/. */
    val dir: String,
    /** SHA-256 of the maker's signing certificate(s). */
    val signers: Set<String>,
    /** The most a recipe may grant: for Google apps, Google's own allowlist on stock Pixels. */
    val maxGrant: Set<String>,
    val dynamicCodeAllowed: Boolean,
) {
    companion object {
        /**
         * Google Phone. Signer: CN=google_dialer, O=Google Inc. (also the digest GrapheneOS lists for it in
         * apk-parser-config). Limit: the 22 privileged permissions the stock Pixel allowlist grants it.
         */
        val GOOGLE_PHONE = OwnAppPolicy(
            packageName = "com.google.android.dialer",
            dir = "GooglePhone",
            signers = setOf("e2d049f3a01192f620b1240615fa8c13badc553c22bc6fddfca45c84d8fc545d"),
            maxGrant = setOf(
                "android.permission.ALLOW_ANY_CODEC_FOR_PLAYBACK",
                "android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS",
                "android.permission.CAPTURE_AUDIO_OUTPUT",
                "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT",
                "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS",
                "android.permission.CONTROL_INCALL_EXPERIENCE",
                "android.permission.GET_ACCOUNTS_PRIVILEGED",
                "android.permission.INTERACT_ACROSS_USERS",
                "android.permission.LOCATION_BYPASS",
                "android.permission.MODIFY_AUDIO_ROUTING",
                "android.permission.MODIFY_PHONE_STATE",
                "android.permission.PERSONAL_CONTEXT_HOST_INSIGHT_SURFACE",
                "android.permission.PERSONAL_CONTEXT_PUBLISH_HINTS",
                "android.permission.PERSONAL_CONTEXT_RECEIVE_INSIGHTS",
                "android.permission.READ_PRIVILEGED_PHONE_STATE",
                "android.permission.READ_SYSTEM_GRAMMATICAL_GENDER",
                "android.permission.SATELLITE_COMMUNICATION",
                "android.permission.STATUS_BAR",
                "android.permission.STOP_APP_SWITCHES",
                "android.permission.WRITE_SECURE_SETTINGS",
                "com.android.voicemail.permission.READ_VOICEMAIL",
                "com.android.voicemail.permission.WRITE_VOICEMAIL",
            ),
            dynamicCodeAllowed = true,
        )

        val ALL: Map<String, OwnAppPolicy> = listOf(GOOGLE_PHONE).associateBy { it.packageName }
    }
}

/**
 * The static overlay (product/overlay) that adds packages to GrapheneOS's dynamic-code-loading allowlists for system
 * apps (framework-res `system_pkgs_allowed_{storage,memory}_dyn_code_loading_by_default`). A system app is otherwise
 * denied loading code from storage, which Google apps need for Google Play services' modules (Stage 1 finding).
 * Built from `overlay-dcl/` (tools/build-dcl-overlay.sh); the lists here must match its arrays.xml (tested).
 */
object DynamicCodeOverlay {
    const val IMAGE_PATH = "product/overlay/PlusDynamicCode/PlusDynamicCode.apk"
    const val PACKAGE = "app.plusos.overlay.dcl"
    val STORAGE: Set<String> = setOf("com.google.android.dialer")

    /** An overlay replaces the whole array, so GrapheneOS's own entries (2026101600) are repeated first. */
    val MEMORY: List<String> = listOf("app.vanadium.browser", "org.chromium.chrome", "com.google.android.dialer")
}

object OwnApp {
    class Refused(message: String) : Exception(message)

    /** What the phone reports about the installed app (PackageManager on the phone). */
    data class Installed(
        val packageName: String,
        val versionCode: Long,
        val signerSha256: String,
        /** base.apk and split APKs, as installed. */
        val apks: List<File>,
        val requestedPermissions: Set<String>,
    )

    /** One own app in an image (build manifest). */
    @Serializable
    data class Entry(
        val id: String,
        val name: String,
        @SerialName("package") val packageName: String,
        val version: Long,
        @SerialName("signer_sha256") val signerSha256: String,
        val paths: List<String>,
        val sha256: List<String> = emptyList(),
        val grants: List<String>,
        val denies: List<String>,
        @SerialName("dynamic_code") val dynamicCode: Boolean = false,
        @SerialName("power_allowlist") val powerAllowlist: Boolean = false,
    )

    data class Plan(val entry: Entry, val files: Map<String, File>)

    private val NAME = Regex("[A-Za-z0-9_.]+")
    private val APK = Regex("[A-Za-z0-9_.-]+\\.apk")

    fun plan(recipe: OwnAppRecipe, app: Installed): Plan {
        val policy = OwnAppPolicy.ALL[recipe.packageName] ?: throw Refused("+PLUS can't make ${recipe.packageName} a system app")
        if (app.packageName != recipe.packageName) throw Refused("the installed app is ${app.packageName}, not ${recipe.packageName}")
        val extraSigners = recipe.signers.map { it.lowercase() }.toSet() - policy.signers
        if (extraSigners.isNotEmpty()) throw Refused("the recipe names a signer +PLUS doesn't accept for ${recipe.name}")
        val signers = recipe.signers.map { it.lowercase() }.toSet()
        if (app.signerSha256.lowercase() !in signers) throw Refused("${recipe.name} on this phone is not signed by its maker")
        if (app.versionCode < recipe.minVersion) {
            throw Refused("${recipe.name} ${app.versionCode} is older than ${recipe.minVersion}; update it from Play first")
        }
        val beyond = recipe.grant.toSet() - policy.maxGrant
        if (beyond.isNotEmpty()) throw Refused("the recipe asks for more than +PLUS allows: ${beyond.sorted().joinToString { it.substringAfterLast('.') }}")
        if (recipe.dynamicCode && (!policy.dynamicCodeAllowed || recipe.packageName !in DynamicCodeOverlay.STORAGE)) {
            throw Refused("+PLUS can't lift the code-loading restriction for ${recipe.name}")
        }
        if (app.apks.isEmpty()) throw Refused("${recipe.name} has no APK files")
        val requested = app.requestedPermissions.filter { it.matches(NAME) }.toSet()
        val grants = recipe.grant.filter { it in requested }.distinct()
        // Every requested permission is granted or explicitly denied: with ro.control_privapp_permissions=enforce, a
        // privileged permission in neither list stops the phone from booting (also after an OS update makes one privileged).
        val denies = (requested - grants.toSet()).sorted()
        val files = linkedMapOf<String, File>()
        for (apk in app.apks) {
            if (!apk.name.matches(APK) || apk.path.contains("..")) throw Refused("unexpected file name ${apk.name}")
            val path = "product/priv-app/${policy.dir}/${apk.name}"
            if (files.put(path, apk) != null) throw Refused("two files named ${apk.name}")
        }
        val entry = Entry(
            id = recipe.id, name = recipe.name, packageName = recipe.packageName, version = app.versionCode,
            signerSha256 = app.signerSha256.lowercase(), paths = files.keys.toList(), grants = grants, denies = denies,
            dynamicCode = recipe.dynamicCode, powerAllowlist = recipe.powerAllowlist,
        )
        return Plan(entry, files)
    }

    /**
     * The recipes the next build uses: the owner's [selected] ids, from the verified [catalog] when it could be read, else
     * from [saved] (the last catalog copy), so an unreachable catalog never drops an own app from the image.
     */
    fun chosen(catalog: List<OwnAppRecipe>?, saved: List<OwnAppRecipe>, selected: Set<String>, installerVersion: Int): List<OwnAppRecipe> =
        (catalog ?: saved).filter { it.id in selected && it.minInstaller <= installerVersion }

    private val RECIPES = kotlinx.serialization.builtins.ListSerializer(OwnAppRecipe.serializer())
    fun encodeRecipes(r: List<OwnAppRecipe>): String = RepoIndex.JSON.encodeToString(RECIPES, r)
    fun decodeRecipes(s: String): List<OwnAppRecipe> = RepoIndex.JSON.decodeFromString(RECIPES, s)

    /** `/product/etc/sysconfig/plus-own-apps.xml`, or null when no own app needs it. */
    fun sysconfigXml(entries: List<Entry>): String? {
        val pkgs = entries.filter { it.powerAllowlist }.map { it.packageName }.sorted()
        if (pkgs.isEmpty()) return null
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<config>\n")
            for (p in pkgs) {
                require(p.matches(NAME)) { "bad package name '$p'" }
                append("    <allow-in-power-save package=\"").append(p).append("\"/>\n")
            }
            append("</config>\n")
        }
    }

    const val SYSCONFIG_XML = "etc/sysconfig/plus-own-apps.xml"
}

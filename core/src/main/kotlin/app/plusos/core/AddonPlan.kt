// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File

/**
 * Which apps the next image contains (spec §4.3, review C4/I5). Every add-on app and +PLUS itself are baked into the
 * image, because GrapheneOS refuses installer updates of system apps. Rules:
 * - an add-on already in the image stays unless the owner removed it explicitly (Android wipes a removed system app's
 *   data, so removal is never a side effect);
 * - with no repository index (offline, outage, verification failure) the image keeps exactly what it has, so a
 *   GrapheneOS release is never held back by the repository;
 * - a failed add-on download keeps the installed copy, or leaves a new add-on out, with a warning.
 */
object AddonPlan {
    const val INSTALLER = "app.seamlessupdate.client"

    sealed interface Source {
        val packageName: String
        val addon: String
        val version: Int
        val imagePath: String
        val privappPermissions: List<String>

        data class Repo(val app: RepoApp) : Source {
            override val packageName get() = app.packageName
            override val addon get() = app.addon
            override val version get() = app.version
            override val imagePath get() = imagePath(app)
            override val privappPermissions get() = if (app.privileged) app.privappPermissions else emptyList()
        }

        data class Image(
            override val packageName: String,
            override val addon: String,
            override val version: Int,
            override val imagePath: String,
            override val privappPermissions: List<String>,
        ) : Source
    }

    data class Plan(val sources: List<Source>)

    data class Fetched(val sources: List<Source>, val files: Map<String, File>, val sha256: Map<String, String>, val warnings: List<String>) {
        /** The manifest entries for what was actually fetched. */
        fun addons(): List<BuildManifest.Addon> = sources.groupBy { it.addon }.map { (id, apps) ->
            BuildManifest.Addon(
                id, apps.maxOf { it.version },
                apps.map { BuildManifest.Apk(it.packageName, sha256.getValue(it.imagePath), it.imagePath, it.version) },
                apps.filter { it.privappPermissions.isNotEmpty() }.associate { it.packageName to it.privappPermissions },
            )
        }
    }

    /** Privileged apps go to /product/priv-app (the generated allowlist covers them), others to /product/app. */
    fun imagePath(app: RepoApp): String {
        require(app.packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "bad package name ${app.packageName}" }
        val dir = if (app.privileged) "priv-app" else "app"
        return "product/$dir/${app.packageName}/${app.packageName}.apk"
    }

    fun plan(index: RepoIndex?, selected: Set<String>, removed: Set<String>, installerVersion: Int, image: BuildManifest?): Plan {
        val inImage = image?.addons.orEmpty().associateBy { it.id }
        val wantedAddons = (selected + inImage.keys) - removed
        val repoApps = index?.let { AddonResolver.resolve(it, wantedAddons, installerVersion).apps }.orEmpty()
            .filter { it.packageName != INSTALLER }.groupBy { it.addon }
        val sources = wantedAddons.sorted().flatMap { id ->
            repoApps[id]?.map { Source.Repo(it) }
                ?: inImage[id]?.let { a -> a.apks.map { Source.Image(it.packageName, id, it.version.takeIf { v -> v > 0 } ?: a.version, it.path, a.privappPermissions[it.packageName].orEmpty()) } }
                ?: emptyList()
        }
        return Plan(sources)
    }

    /**
     * The add-ons an Apply would remove: installed ones ([inImage] holds package names) the owner unticked. +PLUS itself is
     * not an add-on and is never in the list (S6: the store asked "Remove plus?").
     */
    fun removals(index: RepoIndex, inImage: Set<String>, chosen: Set<String>): Set<String> =
        index.apps.filter { it.packageName != INSTALLER && it.packageName in inImage }.map { it.addon }.toSet() - chosen

    /** The newest +PLUS in the catalog when it is newer than the running one (B3: the store shows it as an update). */
    fun installerUpdate(index: RepoIndex, runningVersion: Int): RepoApp? =
        index.apps.filter { it.packageName == INSTALLER }.maxByOrNull { it.version }?.takeIf { it.version > runningVersion }

    /** package → versionCode the next image should contain (for UpdateDecision). */
    fun versions(plan: Plan, installerVersion: Int, index: RepoIndex?): Map<String, Int> {
        val installer = index?.apps?.filter { it.packageName == INSTALLER }?.maxOfOrNull { it.version } ?: 0
        return mapOf(INSTALLER to maxOf(installer, installerVersion)) + plan.sources.associate { it.packageName to it.version }
    }

    /** The newest +PLUS and the selected add-ons' apps, ignoring the image (new phones; the store's view). */
    fun wanted(index: RepoIndex, selected: Set<String>, installerVersion: Int): Map<String, Int> =
        versions(plan(index, selected, emptySet(), installerVersion, null), installerVersion, index)

    /** Downloads repository sources ([download]) and copies image sources ([imageFile] maps an image path to a file). */
    fun fetch(plan: Plan, download: (RepoApp) -> File, imageFile: (String) -> File): Fetched {
        val sources = mutableListOf<Source>()
        val files = linkedMapOf<String, File>()
        val warnings = mutableListOf<String>()
        val byAddon = plan.sources.groupBy { it.addon }
        for ((addon, list) in byAddon) {
            val got = runCatching { list.associateWith { s -> if (s is Source.Repo) download(s.app) else imageFile(s.imagePath) } }
            got.onSuccess { m -> m.forEach { (s, f) -> sources += s; files[s.imagePath] = f } }.onFailure { e ->
                val installed = list.mapNotNull { s -> runCatching { s to imageFile(s.imagePath) }.getOrNull()?.takeIf { it.second.exists() } }
                if (installed.size == list.size && installed.isNotEmpty()) {
                    installed.forEach { (s, f) ->
                        sources += Source.Image(s.packageName, addon, s.version, s.imagePath, s.privappPermissions); files[s.imagePath] = f
                    }
                    warnings += "$addon: could not download the new version (${e.message}); kept the installed version"
                } else {
                    warnings += "$addon: could not download (${e.message}); not added this time"
                }
            }
        }
        val sha = files.mapValues { (_, f) -> Publisher.sha256(f) }
        return Fetched(sources, files, sha, warnings)
    }
}

/**
 * Each package's APK signer, pinned the first time it is seen (spec §8): a repository can never swap an app's signer.
 * [storage] is persisted by the caller (SharedPreferences on the phone).
 */
class SignerPins(private val storage: MutableMap<String, String>) {
    class SignerMismatch(pkg: String) : SecurityException("the signer of $pkg changed; refusing the update")

    fun check(pkg: String, signerSha256: String) {
        val pinned = storage[pkg]
        if (pinned == null) storage[pkg] = signerSha256
        else if (!pinned.equals(signerSha256, ignoreCase = true)) throw SignerMismatch(pkg)
    }
}

/** Review I8: after a failed build, retry in 15 minutes, then 1 hour, then every 4 hours. */
object Backoff {
    fun minutesAfterFailure(failures: Int): Long = when {
        failures <= 1 -> 15L
        failures == 2 -> 60L
        else -> 240L
    }
}

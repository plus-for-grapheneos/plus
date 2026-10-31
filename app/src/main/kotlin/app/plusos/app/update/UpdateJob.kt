// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import app.plusos.app.UpdateEngineClient
import app.plusos.core.AddonNames
import app.plusos.core.AddonPlan
import app.plusos.core.Backoff
import app.plusos.core.PlusStatus
import app.plusos.core.RepoIndex
import app.plusos.core.GrapheneRelease
import app.plusos.core.UpdateCheck
import app.plusos.core.NetworkPolicy
import app.plusos.core.UpdateDecision
import java.net.URL
import kotlin.concurrent.thread

/**
 * The periodic check (every 6 hours, on any network: it only reads small release files) compares the official release
 * and the wanted add-ons with what the running image contains. When an update is needed it queues the automatic build
 * (unmetered network, charging, idle); on a metered network it also shows "Update available" with Update now
 * (+PLUS 13, NetworkPolicy). After a failed build a one-off retry runs on the backoff schedule (15 minutes, 1 hour, then
 * every 4 hours), on an unmetered network.
 */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            val decision = runCatching { decide(this) }.onFailure { Log.w(UpdateEngineClient.TAG, "update check failed", it) }.getOrNull()
            Log.i(UpdateEngineClient.TAG, "update check: $decision")
            if (decision is UpdateDecision.Decision.Build) {
                if (params.jobId == JOB_ID) {
                    scheduleAutoBuild(this)
                    if (NetworkPolicy.onUpdateFound(Network.unmetered(this)) == NetworkPolicy.OnUpdateFound.OFFER_UPDATE_NOW) {
                        UpdateState(this).waitingForWifi = true
                        getSystemService(android.app.NotificationManager::class.java)
                            .notify(BuildService.AVAILABLE_ID, BuildService.availableNote(this))
                    }
                } else {
                    BuildService.start(this, decision.reason, automatic = true)
                }
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 1
        private const val RETRY_JOB_ID = 2
        private const val AUTO_BUILD_JOB_ID = 3

        fun schedule(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build(),
            )
        }

        /** The automatic build: runs when the phone is on an unmetered network, charging and idle. */
        fun scheduleAutoBuild(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(AUTO_BUILD_JOB_ID, ComponentName(ctx, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .setPersisted(true)
                    .build(),
            )
        }

        /** Review I8: a failed build is retried on the backoff schedule, not only at the next 6-hour check. */
        fun scheduleRetry(ctx: Context, failures: Int) {
            ctx.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(RETRY_JOB_ID, ComponentName(ctx, UpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setMinimumLatency(Backoff.minutesAfterFailure(failures) * 60_000L)
                    .setPersisted(true)
                    .build(),
            )
        }

        /** package → versionCode of the apps in the running image. */
        fun imageApps(): Map<String, Int> = UpdateState.imageManifest()?.apps().orEmpty()

        /** The check, also kept as [UpdateState.lastCheck] for the status screen (what was found, or why it failed). */
        fun decide(ctx: Context, manual: Boolean = false): UpdateDecision.Decision = try {
            check(ctx, manual)
        } catch (e: Exception) {
            UpdateState(ctx).lastCheck = PlusStatus.Check(System.currentTimeMillis(), PlusStatus.CheckKind.CHECK_FAILED,
                error = e.message ?: e.javaClass.simpleName)
            throw e
        }

        private fun check(ctx: Context, manual: Boolean): UpdateDecision.Decision {
            val channel = ReleaseChannel(ctx)
            // +PLUS 11: a model without pinned GrapheneOS keys never builds (and never fetches another model's release).
            UpdateDecision.deviceCheck(channel.device, customChannel = !channel.official)?.let { return it }
            val release = channel.parse(URL(channel.metadataUrl).readText())
            val repo = app.plusos.app.store.RepoClient(ctx)
            // Review C4: an unreachable or invalid repository never holds back a GrapheneOS release.
            val index = if (repo.configured) runCatching { repo.fetchIndex() }
                .onFailure { Log.w(UpdateEngineClient.TAG, "repository unavailable; keeping the image's apps", it) }.getOrNull() else null
            val installerVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
            val state = UpdateState(ctx).check(UpdateState.imageManifest(), installerVersion, repo.selected)
            val free = StatFs(ctx.filesDir.path).availableBytes
            val decision = UpdateCheck.decide(Build.TIME / 1000, release, state, index, free, manual)
            UpdateState(ctx).lastCheck = record(decision, release, index, installerVersion, repo.selected, channel.channel, free)
            return decision
        }

        /** In plain words for the status screen: which GrapheneOS, +PLUS and add-on versions are new. */
        private fun record(
            d: UpdateDecision.Decision, release: GrapheneRelease, index: RepoIndex?, installerVersion: Int,
            selected: Set<String>, channel: String, free: Long,
        ): PlusStatus.Check {
            val now = System.currentTimeMillis()
            val os = release.build.takeIf { release.isNewerThan(Build.TIME / 1000) }
            val plus = index?.let { AddonPlan.installerUpdate(it, installerVersion)?.version }
            val image = imageApps()
            val addons = index?.let { AddonPlan.wanted(it, selected, installerVersion) }.orEmpty()
                .filter { (p, v) -> p != AddonPlan.INSTALLER && image[p] != v }
                .map { (p, v) -> "${AddonNames.pkg(p)} $v" }
            return when (d) {
                is UpdateDecision.Decision.Build -> PlusStatus.Check(now, PlusStatus.CheckKind.UPDATE, os, plus, addons)
                is UpdateDecision.Decision.ChannelBehind -> PlusStatus.Check(now, PlusStatus.CheckKind.CHANNEL_BEHIND, d.build, channel = channel)
                is UpdateDecision.Decision.WaitForSpace -> PlusStatus.Check(now, PlusStatus.CheckKind.NEEDS_SPACE, os, plus, addons, freeBytes = d.freeBytes)
                else -> PlusStatus.Check(now, PlusStatus.CheckKind.UP_TO_DATE)
            }
        }
    }
}

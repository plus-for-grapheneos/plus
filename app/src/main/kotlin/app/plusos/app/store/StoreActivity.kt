// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.store

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.plusos.app.update.BuildService
import app.plusos.core.AddonNames
import app.plusos.core.AddonPlan
import app.plusos.core.UpdateDecision
import app.plusos.app.update.UpdateJob
import app.plusos.app.update.UpdateState
import kotlin.concurrent.thread

/**
 * The add-on store (spec §6): each add-on with its version, size and the privileged permissions it gets. Choosing
 * add-ons and pressing "Apply" rebuilds the OS with exactly that selection.
 */
class StoreActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(list) })
        val repo = RepoClient(this)
        if (!repo.configured) {
            list.addView(TextView(this).apply { text = "No add-on catalog: +PLUS keeps GrapheneOS updated and signed with your key, nothing else. To get add-ons, enter a catalog link when you import your key." })
            return
        }
        list.addView(TextView(this).apply { text = "Loading add-ons…" })
        thread {
            val result = runCatching { repo.fetchIndex() }
            runOnUiThread {
                list.removeAllViews()
                val index = result.getOrElse { e -> list.addView(TextView(this).apply { text = "Could not load the add-on list: ${e.message}" }); return@runOnUiThread }
                val inImage = UpdateJob.imageApps()
                val boxes = mutableMapOf<String, CheckBox>()
                val initial = mutableMapOf<String, Boolean>()
                var updates = false
                lateinit var apply: Button
                // S6 UI audit: Apply only when the selection changed or an installed add-on has an update.
                fun refreshApply() {
                    val changed = boxes.any { (k, b) -> b.isChecked != initial[k] }
                    apply.isEnabled = changed || updates
                    apply.text = when {
                        changed -> "Apply (rebuilds the OS, about 5 minutes)"
                        updates -> "Update (rebuilds the OS, about 5 minutes)"
                        else -> "Up to date"
                    }
                }
                for ((addon, apps) in index.apps.filter { it.packageName != AddonPlan.INSTALLER }.groupBy { it.addon }) {
                    val version = apps.maxOf { it.version }
                    val installed = apps.all { inImage[it.packageName] != null }
                    val installedVersion = apps.mapNotNull { inImage[it.packageName] }.maxOrNull()
                    val perms = apps.flatMap { it.privappPermissions }.distinct().map { it.substringAfterLast('.') }
                    // S6 (user): show what is installed against what is available.
                    val versions = when {
                        installedVersion == null -> "available v$version"
                        installedVersion < version -> "installed v$installedVersion, update to v$version on Apply"
                        else -> "installed v$installedVersion (up to date)"
                    }
                    val selected = addon in repo.selected || installed
                    if (selected && installedVersion != null && installedVersion < version) updates = true
                    boxes[addon] = CheckBox(this).apply {
                        text = "${AddonNames.addon(addon)}\n$versions"
                        isChecked = selected
                        setOnCheckedChangeListener { _, _ -> refreshApply() }
                    }.also { list.addView(it) }
                    initial[addon] = selected
                    // The system permissions it gets, on request (a dozen names said nothing at a glance).
                    if (perms.isNotEmpty()) list.addView(TextView(this).apply {
                        val shortText = "Gets ${perms.size} system permissions (show)"
                        text = shortText
                        setTextColor(0xFF3F5BA9.toInt())
                        setPadding(pad * 3, 0, 0, pad / 2)
                        setOnClickListener { text = if (text == shortText) perms.joinToString(", ") else shortText }
                    })
                }
                apply = Button(this)
                list.addView(apply.apply {
                    setOnClickListener {
                        val chosen = boxes.filterValues { it.isChecked }.keys
                        val removing = AddonPlan.removals(index, inImage.keys, chosen)
                        val apply = {
                            repo.selected = chosen
                            val state = UpdateState(this@StoreActivity)
                            state.removed = (state.removed + removing) - chosen
                            BuildService.start(this@StoreActivity, UpdateDecision.BuildReason.ADDON_CHANGE)
                            finish()
                        }
                        // Removing an installed add-on deletes its data with it (Android wipes a removed system app).
                        if (removing.isEmpty()) apply() else android.app.AlertDialog.Builder(this@StoreActivity)
                            .setTitle("Remove ${removing.joinToString(", ") { AddonNames.pkg(it) }}?")
                            .setMessage("Removing an add-on also deletes everything it stored on this phone. This can't be undone.")
                            .setPositiveButton("Remove") { _, _ -> apply() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
                list.addView(TextView(this).apply {
                    text = "\nNot affiliated with or endorsed by the GrapheneOS project."
                })
                refreshApply()
            }
        }
    }
}

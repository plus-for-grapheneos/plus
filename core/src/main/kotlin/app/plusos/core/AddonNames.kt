// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

/** What people call the add-ons and apps (S6 UI audit: screens showed repository ids and package names). */
object AddonNames {
    private val ADDONS = mapOf("callscreen-phone" to "Phone+", "ai-service" to "+PLUS AI")
    private val PACKAGES = mapOf(
        AddonPlan.INSTALLER to "+PLUS",
        "app.plusos.phone" to "Phone+",
        "app.plusos.phone.transcriber" to "Phone+ call transcriber",
        "app.plusos.ai" to "+PLUS AI",
    )

    fun addon(id: String): String = ADDONS[id] ?: id

    fun pkg(packageName: String): String = PACKAGES[packageName] ?: packageName

    /** "+PLUS 8, Phone+ 2900011, …" for the apps in an image. */
    fun image(apps: Map<String, Int>): String = apps.entries.joinToString(", ") { (p, v) -> "${pkg(p)} $v" }
}

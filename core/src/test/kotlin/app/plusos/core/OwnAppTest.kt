// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OwnAppTest {
    private val dialer = OwnAppPolicy.GOOGLE_PHONE
    private val googleSigner = dialer.signers.single()

    private val recipe = OwnAppRecipe(
        id = "google-phone", name = "Google Phone", packageName = dialer.packageName, signers = listOf(googleSigner),
        minVersion = 19664958, grant = listOf(
            "android.permission.CAPTURE_AUDIO_OUTPUT", "android.permission.MODIFY_AUDIO_ROUTING",
            "android.permission.MODIFY_PHONE_STATE",
        ),
        dynamicCode = true, notice = "n",
    )

    private fun installed(
        version: Long = 19664958,
        signer: String = googleSigner,
        requested: Set<String> = setOf(
            "android.permission.CAPTURE_AUDIO_OUTPUT", "android.permission.MODIFY_AUDIO_ROUTING",
            "android.permission.MODIFY_PHONE_STATE", "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.RECORD_AUDIO",
        ),
    ) = OwnApp.Installed(
        packageName = dialer.packageName, versionCode = version, signerSha256 = signer,
        apks = listOf(File("base.apk"), File("split_config.arm64_v8a.apk")), requestedPermissions = requested,
    )

    @Test
    fun plan_puts_the_unchanged_apks_in_priv_app_and_grants_only_the_recipe() {
        val p = OwnApp.plan(recipe, installed())
        assertEquals(
            mapOf(
                "product/priv-app/GooglePhone/base.apk" to File("base.apk"),
                "product/priv-app/GooglePhone/split_config.arm64_v8a.apk" to File("split_config.arm64_v8a.apk"),
            ),
            p.files,
        )
        assertEquals(recipe.grant.toSet(), p.entry.grants.toSet())
    }

    @Test
    fun every_other_requested_permission_is_explicitly_denied_so_enforce_mode_never_boot_loops() {
        val p = OwnApp.plan(recipe, installed())
        assertEquals(setOf("android.permission.WRITE_SECURE_SETTINGS", "android.permission.RECORD_AUDIO"), p.entry.denies.toSet())
    }

    @Test
    fun a_grant_the_app_does_not_request_is_dropped() {
        val p = OwnApp.plan(recipe, installed(requested = setOf("android.permission.MODIFY_PHONE_STATE")))
        assertEquals(listOf("android.permission.MODIFY_PHONE_STATE"), p.entry.grants)
    }

    @Test
    fun a_recipe_can_never_grant_beyond_the_built_in_limit() {
        val greedy = recipe.copy(grant = recipe.grant + "android.permission.INSTALL_PACKAGES")
        val e = assertThrows<OwnApp.Refused> { OwnApp.plan(greedy, installed()) }
        assertTrue("INSTALL_PACKAGES" in e.message!!)
    }

    @Test
    fun a_recipe_for_a_package_plus_does_not_know_is_refused() {
        assertThrows<OwnApp.Refused> { OwnApp.plan(recipe.copy(packageName = "com.example.app"), installed()) }
    }

    @Test
    fun a_recipe_cannot_add_a_signer() {
        assertThrows<OwnApp.Refused> { OwnApp.plan(recipe.copy(signers = listOf("ff".repeat(32))), installed(signer = "ff".repeat(32))) }
    }

    @Test
    fun an_installed_copy_with_another_signer_is_refused() {
        val e = assertThrows<OwnApp.Refused> { OwnApp.plan(recipe, installed(signer = "ab".repeat(32))) }
        assertTrue("signed" in e.message!!)
    }

    @Test
    fun an_installed_copy_older_than_the_recipe_minimum_is_refused() {
        assertThrows<OwnApp.Refused> { OwnApp.plan(recipe, installed(version = 1)) }
    }

    @Test
    fun the_entry_records_version_signer_and_paths_for_the_manifest() {
        val e = OwnApp.plan(recipe, installed()).entry
        assertEquals("google-phone", e.id)
        assertEquals(19664958L, e.version)
        assertEquals(googleSigner, e.signerSha256)
        assertEquals(listOf("product/priv-app/GooglePhone/base.apk", "product/priv-app/GooglePhone/split_config.arm64_v8a.apk"), e.paths)
        assertTrue(e.dynamicCode)
    }

    @Test
    fun odd_apk_names_are_refused() {
        val bad = installed().copy(apks = listOf(File("../evil.apk")))
        assertThrows<OwnApp.Refused> { OwnApp.plan(recipe, bad) }
    }

    @Test
    fun privapp_xml_has_grants_and_denies() {
        val xml = PrivappXml.generate(mapOf("a.b" to listOf("p.ONE")), mapOf("a.b" to listOf("p.TWO"), "c.d" to listOf("p.THREE")))
        assertTrue("<privapp-permissions package=\"a.b\">\n        <permission name=\"p.ONE\"/>\n        <deny-permission name=\"p.TWO\"/>\n    </privapp-permissions>" in xml, xml)
        assertTrue("<privapp-permissions package=\"c.d\">\n        <deny-permission name=\"p.THREE\"/>" in xml, xml)
    }

    @Test
    fun privapp_xml_without_denies_is_unchanged() {
        val grants = mapOf("a.b" to listOf("p.ONE"))
        assertFalse("deny" in PrivappXml.generate(grants))
    }

    @Test
    fun dynamic_code_overlay_lists_exactly_the_packages_plus_was_built_with() {
        assertEquals(setOf("com.google.android.dialer"), DynamicCodeOverlay.STORAGE)
        // GrapheneOS's own memory entries stay (the overlay replaces the whole array).
        assertEquals(listOf("app.vanadium.browser", "org.chromium.chrome", "com.google.android.dialer"), DynamicCodeOverlay.MEMORY)
    }

    @Test
    fun the_overlay_source_matches_the_kotlin_lists() {
        val xml = File("../overlay-dcl/res/values/arrays.xml").readText()
        for (p in DynamicCodeOverlay.MEMORY + DynamicCodeOverlay.STORAGE) assertTrue("<item>$p</item>" in xml, p)
        assertEquals(DynamicCodeOverlay.MEMORY.size + DynamicCodeOverlay.STORAGE.size, Regex("<item>").findAll(xml).count())
    }

    @Test
    fun manifest_round_trips_own_apps_and_old_manifests_still_read() {
        val m = BuildManifest(
            device = "tokay", grapheneos = BuildManifest.Os("1", "a"), ownerKeyFingerprint = "f",
            installer = BuildManifest.Installer(1, "b"), addons = emptyList(),
            ownApps = listOf(OwnApp.plan(recipe, installed()).entry.copy(sha256 = listOf("11", "22"))),
        )
        assertEquals(m, BuildManifest.fromJson(m.toJson()))
        val old = m.copy(ownApps = emptyList()).toJson().replace(Regex(",\\s*\"own_apps\": \\[\\s*]"), "")
        assertFalse("own_apps" in old)
        assertEquals(emptyList<OwnApp.Entry>(), BuildManifest.fromJson(old).ownApps)
    }

    @Test
    fun catalog_recipes_are_optional_in_the_index() {
        val json = RepoIndex(repo = "r", published = "p", serial = 1, apps = emptyList()).toJson()
            .replace(Regex(",\\s*\"recipes\": \\[\\s*]"), "")
        assertEquals(emptyList<OwnAppRecipe>(), RepoIndex.JSON.decodeFromString(RepoIndex.serializer(), json).recipes)
    }

    private fun image(vararg own: OwnApp.Entry) = BuildManifest(
        device = "tokay", grapheneos = BuildManifest.Os("1", "a"), ownerKeyFingerprint = "f",
        installer = BuildManifest.Installer(1, "b"), addons = emptyList(), ownApps = own.toList(),
    )

    @Test
    fun restart_screen_lists_a_new_own_app_with_every_grant() {
        val e = OwnApp.plan(recipe, installed()).entry
        assertEquals(
            listOf("Google Phone 19664958 from this phone, as a system app with 3 system permissions: " +
                "CAPTURE_AUDIO_OUTPUT, MODIFY_AUDIO_ROUTING, MODIFY_PHONE_STATE"),
            ChangeList.between(image(), image(e)),
        )
    }

    @Test
    fun restart_screen_lists_own_app_updates_new_grants_and_removal() {
        val e = OwnApp.plan(recipe, installed()).entry
        val newer = e.copy(version = 19664999, grants = e.grants + "android.permission.READ_PRIVILEGED_PHONE_STATE")
        assertEquals(
            listOf("Google Phone 19664958 → 19664999", "Google Phone gets a new permission: READ_PRIVILEGED_PHONE_STATE"),
            ChangeList.between(image(e), image(newer)),
        )
        assertEquals(listOf("Google Phone is no longer a system app (it stays installed as a normal app)"),
            ChangeList.between(image(e), image()))
    }

    @Test
    fun chosen_recipes_come_from_the_catalog_or_else_from_the_saved_copy() {
        val fromCatalog = recipe.copy(minVersion = 5)
        assertEquals(listOf(fromCatalog), OwnApp.chosen(listOf(fromCatalog), listOf(recipe), setOf("google-phone"), installerVersion = 15))
        assertEquals(listOf(recipe), OwnApp.chosen(null, listOf(recipe), setOf("google-phone"), installerVersion = 15))
        assertEquals(emptyList<OwnAppRecipe>(), OwnApp.chosen(listOf(recipe), listOf(recipe), emptySet(), installerVersion = 15))
        assertEquals(emptyList<OwnAppRecipe>(), OwnApp.chosen(listOf(recipe.copy(minInstaller = 99)), emptyList(), setOf("google-phone"), 15))
    }

    @Test
    fun the_shipped_google_phone_recipe_is_within_the_policy_and_plans() {
        val r = RepoIndex.JSON.decodeFromString(OwnAppRecipe.serializer(), File("../catalog/recipes/google-phone.json").readText())
        assertEquals(dialer.packageName, r.packageName)
        assertTrue(dialer.maxGrant.containsAll(r.grant))
        val p = OwnApp.plan(r, installed(requested = r.grant.toSet() + "android.permission.WRITE_SECURE_SETTINGS"))
        assertEquals(r.grant.size, p.entry.grants.size)
        assertEquals(listOf("android.permission.WRITE_SECURE_SETTINGS"), p.entry.denies)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BuildProgressTest {
    @Test
    fun downloadIsStepOne() {
        assertEquals("[1/5] Downloading GrapheneOS 2026102100: 40%", BuildProgress.download("2026102100", 40))
    }

    @Test
    fun pipelineLogLinesBecomeNumberedSteps() {
        // S6 (user): the notification showed raw stage names ("VERIFY_INPUT ...", "build finished in 281 s ...").
        assertEquals("[2/5] Checking the GrapheneOS download", BuildProgress.fromLog("VERIFY_INPUT ..."))
        assertEquals("[3/5] Adding your add-ons (1 of 6)", BuildProgress.fromLog("EXTRACT ..."))
        assertEquals("[3/5] Adding your add-ons (6 of 6)", BuildProgress.fromLog("AVB_PACK already done"))
        assertEquals("[4/5] Signing the update with your key", BuildProgress.fromLog("PATCH ..."))
        assertEquals("[4/5] Checking the signed update", BuildProgress.fromLog("VERIFY_OUTPUT ..."))
    }

    @Test
    fun otherLogLinesStayInTheLog() {
        assertNull(BuildProgress.fromLog("input: tokay-ota_update-2026102100.zip (1679 MB)"))
        assertNull(BuildProgress.fromLog("VERIFY_OUTPUT OK: /data/ota_package/plus-update.zip"))
        assertNull(BuildProgress.fromLog("build finished in 281 s: /data/ota_package/plus-update.zip (1817 MB)"))
    }

    @Test
    fun installPhasesAreOneStep() {
        // update_engine reports each phase from 0 to 100 %: it looked like installing started over.
        assertEquals("[5/5] Installing: writing 40%", BuildProgress.install(BuildProgress.UE_DOWNLOADING, 0.4f))
        assertEquals("[5/5] Installing: checking 10%", BuildProgress.install(BuildProgress.UE_VERIFYING, 0.1f))
        assertEquals("[5/5] Installing: finishing 95%", BuildProgress.install(BuildProgress.UE_FINALIZING, 0.95f))
    }
}

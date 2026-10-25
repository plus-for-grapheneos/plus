// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.key

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.plusos.core.OwnerCert
import app.plusos.core.OwnerKey
import java.security.MessageDigest
import java.security.Signature
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on the dev phone. Sets and clears a temporary PIN through `locksettings` (UiAutomation runs as shell).
 * The phone must start without a PIN.
 */
@RunWith(AndroidJUnit4::class)
class KeyServiceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = KeyService(ctx)
    private val key = OwnerKey.generate()
    private val runningCert = OwnerCert.derive(key)
    private val di = java.util.HexFormat.of().parseHex("3031300d060960864801650304020105000420") +
        MessageDigest.getInstance("SHA-256").digest("vbmeta test block".toByteArray())

    private fun shell(cmd: String): String {
        val pfd: ParcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    @Before
    fun clean() { keys.delete() }

    @After
    fun noPin() { shell("locksettings clear --old 1234"); keys.delete() }

    @Test
    fun import_requires_lock_screen() {
        assertEquals(KeyService.ImportResult.NO_LOCK_SCREEN, keys.import(key, runningCert))
        assertEquals(KeyService.KeyState.ABSENT, keys.state())
    }

    @Test
    fun wrong_fingerprint_refused() {
        shell("locksettings set-pin 1234")
        val other = OwnerCert.derive(OwnerKey.generate())
        assertEquals(KeyService.ImportResult.FINGERPRINT_MISMATCH, keys.import(key, other))
        assertEquals(KeyService.KeyState.ABSENT, keys.state())
    }

    @Test
    fun sign_equals_software() {
        shell("locksettings set-pin 1234") // setting the PIN counts as a fresh authentication (spike S1b)
        assertEquals(KeyService.ImportResult.OK, keys.import(key, runningCert))
        assertEquals(KeyService.KeyState.READY, keys.state())
        val expected = Signature.getInstance("SHA256withRSA").run {
            initSign(key.privateKey); update("vbmeta test block".toByteArray()); sign()
        }
        assertArrayEquals(expected, keys.sign(di))
    }

    /** Removing the lock screen deletes or invalidates the key (S1b saw it deleted): the owner must import again. */
    @Test
    fun invalidated_after_lock_screen_removed() {
        shell("locksettings set-pin 1234")
        assertEquals(KeyService.ImportResult.OK, keys.import(key, runningCert))
        shell("locksettings clear --old 1234")
        assertTrue(keys.state() in setOf(KeyService.KeyState.ABSENT, KeyService.KeyState.INVALIDATED))
        assertTrue(keys.state().needsImport)
    }
}

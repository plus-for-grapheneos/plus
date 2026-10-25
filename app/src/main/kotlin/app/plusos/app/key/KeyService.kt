// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
package app.plusos.app.key

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import app.plusos.core.OwnerCert
import app.plusos.core.OwnerKey
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * The owner key in the phone's TEE keystore (spec §5.3, Global Constraints). It can only sign PKCS#1 over a
 * DigestInfo, only within 300 s of an unlock, and (S1b ruling) only while the phone is unlocked. It never leaves the TEE.
 */
class KeyService(private val ctx: Context) {
    enum class ImportResult { OK, NO_LOCK_SCREEN, FINGERPRINT_MISMATCH, KEYSTORE_ERROR }

    enum class KeyState(val needsImport: Boolean) { ABSENT(true), READY(false), INVALIDATED(true) }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * How the imported key may be used, chosen by the owner at import (keystore fixes it; changing it means importing
     * again). AUTOMATIC: any time, even locked (updates install by themselves, like stock GrapheneOS). APPROVAL: only
     * within [APPROVAL_WINDOW_S] of an unlock with the lock screen, so each update waits for the owner once.
     */
    enum class Level { AUTOMATIC, APPROVAL }

    fun import(k: OwnerKey, runningOtaCert: X509Certificate, level: Level = Level.APPROVAL): ImportResult {
        if (level == Level.APPROVAL && !ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure) return ImportResult.NO_LOCK_SCREEN
        val cert = OwnerCert.derive(k)
        if (!cert.encoded.contentEquals(runningOtaCert.encoded)) return ImportResult.FINGERPRINT_MISMATCH
        return try {
            val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            if (level == Level.APPROVAL) {
                // One unlock covers a whole build: avbroot's signatures are spread over minutes, and the phone may lock
                // again in between (S6: three unlocks for one build with a 5-minute window plus unlocked-device-required).
                protection.setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(APPROVAL_WINDOW_S, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(false)
            }
            store().setEntry(ALIAS, KeyStore.PrivateKeyEntry(k.privateKey, arrayOf(cert)), protection.build())
            ctx.getSharedPreferences("key", Context.MODE_PRIVATE).edit().putString("level", level.name).apply()
            ImportResult.OK
        } catch (e: Exception) {
            Log.e(TAG, "key import failed", e)
            ImportResult.KEYSTORE_ERROR
        }
    }

    /** PKCS#1 v1.5 over [di]. Throws [UserNotAuthenticatedException] when the phone wasn't unlocked recently. */
    fun sign(di: ByteArray): ByteArray = Signature.getInstance("NONEwithRSA").run {
        initSign(privateKey() ?: throw IllegalStateException("no owner key imported"))
        update(di)
        sign()
    }

    fun state(): KeyState {
        val key = privateKey() ?: return KeyState.ABSENT
        return try {
            Signature.getInstance("NONEwithRSA").initSign(key)
            KeyState.READY
        } catch (e: KeyPermanentlyInvalidatedException) {
            KeyState.INVALIDATED
        } catch (e: UserNotAuthenticatedException) {
            KeyState.READY
        } catch (e: java.security.InvalidKeyException) {
            // A locked device can't tell (keystore answers DEVICE_LOCKED); signing waits for the unlock and reports an
            // invalidated key then. S6: this check stopped every scheduled build on a locked phone.
            if (ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) KeyState.READY else throw e
        }
    }

    fun delete() { store().deleteEntry(ALIAS) }

    /** The level chosen at the last import; keys imported before levels existed behave as APPROVAL. */
    fun level(): Level = runCatching {
        Level.valueOf(ctx.getSharedPreferences("key", Context.MODE_PRIVATE).getString("level", null) ?: "APPROVAL")
    }.getOrDefault(Level.APPROVAL)

    private fun privateKey(): PrivateKey? = store().getKey(ALIAS, null) as PrivateKey?

    companion object {
        const val TAG = "PLUS"
        const val ALIAS = "plus_owner"
        /** APPROVAL: how long one unlock lets the key sign (a build signs over a few minutes). */
        const val APPROVAL_WINDOW_S = 30 * 60

        /** The OTA certificate the running OS trusts: the single entry of /system/etc/security/otacerts.zip. */
        fun runningOtaCert(): X509Certificate = ZipFile(File("/system/etc/security/otacerts.zip")).use { z ->
            val entries = z.entries().toList().filter { !it.isDirectory }
            check(entries.size == 1) { "expected one OTA certificate, found ${entries.size}" }
            z.getInputStream(entries[0]).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
        }
    }
}

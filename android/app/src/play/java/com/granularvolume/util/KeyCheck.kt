package com.granularvolume.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Play flavor: is the paid unlock-key app installed, signed by us?
 *
 * The key app (com.granularvolume.key) is a purchase receipt. Play keeps it
 * installed for the buying account, so possession is the license — no server,
 * no network, no Play Billing, and the main app keeps its zero-INTERNET claim.
 *
 * Signature pinning stops a repackaged fake "key" from unlocking anything: the
 * installed key's signing certificate must hash into [PINNED_CERT_SHA256].
 * The main app's own manifest must carry a <queries> entry for the key package
 * (API 30+ package visibility) or this lookup silently reports not-found.
 */
object KeyCheck {

    private const val TAG = "GranularVolume:KeyCheck"
    private const val KEY_PACKAGE = "com.granularvolume.key"

    /**
     * Accepted signing certificates, SHA-256, lowercase hex, no separators.
     *
     *  1. The upload/local keystore cert (granularvolume-release.jks) — covers
     *     locally signed builds and the internal-track APK before Play re-signs.
     *  2. ⚠ TODO BEFORE LAUNCH (runbook 1.6): after the key app's first upload,
     *     read the "App signing key certificate" SHA-256 from its Play Console
     *     page and ADD it here — Google re-signs new apps, so the cert users
     *     actually receive is Google's, not ours. Launch is blocked on this.
     */
    private val PINNED_CERT_SHA256 = setOf(
        "01cc6025ffce326c32324a3e441355dbdc3934abd2d656bed1b2ec62ff0cced8",
    )

    fun isKeyInstalled(context: Context): Boolean {
        val pm = context.packageManager
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(KEY_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(KEY_PACKAGE, PackageManager.GET_SIGNATURES)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Current certs only. apkContentsSigners covers rotation history;
            // we pin the full accepted set instead, so current is what matters.
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return false

        val matched = signatures.any { sig ->
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
            sha in PINNED_CERT_SHA256
        }
        if (!matched) Log.w(TAG, "Key package present but signer not in pinned set — ignoring")
        return matched
    }
}

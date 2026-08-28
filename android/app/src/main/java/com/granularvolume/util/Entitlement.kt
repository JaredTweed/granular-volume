package com.granularvolume.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Everything that decides whether the full range is open, kept in its OWN prefs file.
 *
 * Why a separate file and not a few more keys in [Prefs]: Android's backup rules work at file
 * granularity, never per key. The trial has to survive an uninstall and reinstall, which means
 * this data must be in the system backup, but the user's dial position, overlay coordinates and
 * general settings must NOT be. Splitting the file is the only way to back up exactly the four
 * values below and nothing else, which is what keeps the privacy policy narrow and true.
 *
 * Contents, and the whole of it:
 *   trial_start_at    when the 7 day trial began, epoch millis
 *   last_seen_at      the latest wall clock we have ever observed, for tamper detection
 *   grandfathered_pro sticky verdict for users who had the app before the gate existed
 *   grandfather_evaluated  so the verdict is taken once and never re-litigated
 */
object Entitlement {

    private const val FILE_NAME = "gv_entitlement"

    private const val KEY_TRIAL_START   = "trial_start_at"
    private const val KEY_LAST_SEEN     = "last_seen_at"
    private const val KEY_GRANDFATHERED = "grandfathered_pro"
    private const val KEY_EVALUATED     = "grandfather_evaluated"

    const val TRIAL_DAYS = 7L
    const val TRIAL_MS = TRIAL_DAYS * 24L * 60L * 60L * 1000L

    /** A backwards clock jump larger than this is treated as tampering, not as drift. */
    private const val CLOCK_SLACK_MS = 24L * 60L * 60L * 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ── grandfathering ──────────────────────────────────────────────────────

    fun isGrandfathered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GRANDFATHERED, false)

    fun setGrandfathered(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_GRANDFATHERED, value) }
    }

    fun wasGrandfatherEvaluated(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EVALUATED, false)

    fun setGrandfatherEvaluated(context: Context) {
        prefs(context).edit { putBoolean(KEY_EVALUATED, true) }
    }

    // ── trial ───────────────────────────────────────────────────────────────

    /**
     * When the trial started, as a dual anchor.
     *
     * The stored timestamp is written the first time we are ever asked. [firstInstallTime] is
     * asked of the package manager on every call. We take the EARLIER of the two, so clearing
     * app data cannot buy a fresh week: the install time is still sitting there, untouched by
     * Clear Data, and it wins.
     */
    private fun trialStart(context: Context): Long {
        val installedAt = try {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        } catch (_: Exception) {
            0L
        }
        val stored = prefs(context).getLong(KEY_TRIAL_START, 0L)
        val anchor = when {
            stored == 0L && installedAt == 0L -> System.currentTimeMillis()
            stored == 0L -> installedAt
            installedAt == 0L -> stored
            else -> minOf(stored, installedAt)
        }
        if (stored != anchor) prefs(context).edit { putLong(KEY_TRIAL_START, anchor) }
        return anchor
    }

    /**
     * Monotonic-ish "now".
     *
     * Winding the device clock backwards is the obvious way to stretch a trial, so we remember
     * the latest time we have ever seen. If the clock has moved back by more than a day we do
     * not trust it and keep using the high-water mark, which freezes the trial rather than
     * extending it. Small backwards moves (a few hours of NTP correction or a timezone edit)
     * pass through untouched.
     */
    private fun trustedNow(context: Context): Long {
        val now = System.currentTimeMillis()
        val lastSeen = prefs(context).getLong(KEY_LAST_SEEN, 0L)
        val trusted = if (lastSeen > 0L && now < lastSeen - CLOCK_SLACK_MS) lastSeen else now
        if (trusted > lastSeen) prefs(context).edit { putLong(KEY_LAST_SEEN, trusted) }
        return trusted
    }

    fun isTrialActive(context: Context): Boolean = millisLeftInTrial(context) > 0L

    fun millisLeftInTrial(context: Context): Long {
        val elapsed = trustedNow(context) - trialStart(context)
        return (TRIAL_MS - elapsed).coerceAtLeast(0L)
    }

    /** Whole days left, rounded up, so the last part-day still reads as "1 day left". */
    fun daysLeftInTrial(context: Context): Int {
        val left = millisLeftInTrial(context)
        if (left <= 0L) return 0
        return ((left + 86_399_999L) / 86_400_000L).toInt()
    }

    /**
     * One-time move of the grandfather verdict out of the old prefs file.
     * Installs that were already grandfathered under 1.5.0-internal keep their status.
     */
    fun migrateFromLegacyPrefsIfNeeded(context: Context, legacyEvaluated: Boolean, legacyGrandfathered: Boolean) {
        if (wasGrandfatherEvaluated(context)) return
        if (!legacyEvaluated) return
        setGrandfathered(context, legacyGrandfathered)
        setGrandfatherEvaluated(context)
    }
}

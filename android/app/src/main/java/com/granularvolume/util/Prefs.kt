package com.granularvolume.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Type-safe SharedPreferences wrapper.
 * All keys are constants — no magic strings outside this class.
 */
object Prefs {

    private const val FILE_NAME = "gv_prefs"

    private const val KEY_ATTENUATION_DB   = "attenuation_db"
    private const val KEY_OVERLAY_X        = "overlay_x"
    private const val KEY_OVERLAY_Y        = "overlay_y"
    private const val KEY_SERVICE_WAS_RUNNING = "service_was_running"
    private const val KEY_COLLAPSED        = "overlay_collapsed"
    private const val KEY_QS_TILE_OFFERED  = "qs_tile_offered"
    private const val KEY_LAUNCH_COUNT     = "launch_count"
    private const val KEY_TILE_ACTIVATIONS = "tile_activations"
    private const val KEY_REVIEW_REQUESTED = "review_flow_requested"
    private const val KEY_LINE_TOOLTIP_SHOWN = "line_tooltip_shown"
    private const val KEY_TERMS_ACCEPTED_VERSION = "terms_accepted_version"

    // ── Pro / full-range gate (1.5.0) ───────────────────────────────
    private const val KEY_GRANDFATHERED_PRO      = "grandfathered_pro"
    private const val KEY_GRANDFATHER_EVALUATED  = "grandfather_evaluated"
    private const val KEY_PREVIEW_STEP_DB        = "preview_step_db"
    private const val KEY_TIPJAR_CARD_SHOWN      = "tipjar_card_shown"
    private const val KEY_UNLOCK_ACKNOWLEDGED    = "unlock_acknowledged"

    /** Current attenuation in dB (0.0 = none, -30.0 = near-silent) */
    const val ATTENUATION_DEFAULT = 0f
    const val ATTENUATION_MIN     = -30f
    const val ATTENUATION_MAX     = 0f

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getAttenuation(context: Context): Float =
        prefs(context).getFloat(KEY_ATTENUATION_DB, ATTENUATION_DEFAULT)

    fun setAttenuation(context: Context, dB: Float) {
        prefs(context).edit { putFloat(KEY_ATTENUATION_DB, dB.coerceIn(ATTENUATION_MIN, ATTENUATION_MAX)) }
    }

    fun getOverlayX(context: Context, default: Int): Int =
        prefs(context).getInt(KEY_OVERLAY_X, default)

    fun getOverlayY(context: Context, default: Int): Int =
        prefs(context).getInt(KEY_OVERLAY_Y, default)

    fun setOverlayPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit {
            putInt(KEY_OVERLAY_X, x)
            putInt(KEY_OVERLAY_Y, y)
        }
    }

    fun setServiceWasRunning(context: Context, running: Boolean) {
        prefs(context).edit { putBoolean(KEY_SERVICE_WAS_RUNNING, running) }
    }

    fun wasServiceRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_WAS_RUNNING, false)

    fun isCollapsed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COLLAPSED, false)

    fun setCollapsed(context: Context, collapsed: Boolean) {
        prefs(context).edit { putBoolean(KEY_COLLAPSED, collapsed) }
    }

    fun wasQsTileOffered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_QS_TILE_OFFERED, false)

    fun setQsTileOffered(context: Context, offered: Boolean) {
        prefs(context).edit { putBoolean(KEY_QS_TILE_OFFERED, offered) }
    }

    /** Counts MainActivity opens where the app was already fully set up (a "return visit"). */
    fun incrementAndGetLaunchCount(context: Context): Int {
        val next = prefs(context).getInt(KEY_LAUNCH_COUNT, 0) + 1
        prefs(context).edit { putInt(KEY_LAUNCH_COUNT, next) }
        return next
    }

    /**
     * Counts Quick Settings tile "turn on" taps. This is the real usage signal for
     * tile-driven users who rarely reopen [com.granularvolume.MainActivity], and is
     * used to decide when to (once ever) offer the in-app review prompt.
     */
    fun incrementAndGetTileActivations(context: Context): Int {
        val next = prefs(context).getInt(KEY_TILE_ACTIVATIONS, 0) + 1
        prefs(context).edit { putInt(KEY_TILE_ACTIVATIONS, next) }
        return next
    }

    fun wasReviewFlowRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REVIEW_REQUESTED, false)

    fun setReviewFlowRequested(context: Context, requested: Boolean) {
        prefs(context).edit { putBoolean(KEY_REVIEW_REQUESTED, requested) }
    }

    /** One-time "drag below the line" tooltip on the first overlay display (full-range spec). */
    fun wasLineTooltipShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LINE_TOOLTIP_SHOWN, false)

    fun setLineTooltipShown(context: Context) {
        prefs(context).edit { putBoolean(KEY_LINE_TOOLTIP_SHOWN, true) }
    }

    /**
     * Version number of the Terms the user actively accepted via the consent gate (clickwrap).
     * 0 = never accepted. Versioned (not boolean) so a future material Terms change can
     * re-prompt — locked design, see the Pro plan's A2b item.
     */
    fun getTermsAcceptedVersion(context: Context): Int =
        prefs(context).getInt(KEY_TERMS_ACCEPTED_VERSION, 0)

    fun setTermsAcceptedVersion(context: Context, version: Int) {
        prefs(context).edit { putInt(KEY_TERMS_ACCEPTED_VERSION, version) }
    }

    // ── Pro / full-range gate (1.5.0) ───────────────────────────────

    /**
     * True iff any trace of pre-gate use exists. Several independent keys are
     * checked because no single one covers every user: tile-driven users never
     * increment the launch count, dial-only users may never touch the tile, and
     * a user who granted overlay but never moved the dial still placed it once.
     * Consulted exactly once, by ProAccess.evaluateGrandfather.
     */
    fun hasAnyPriorUse(context: Context): Boolean {
        val p = prefs(context)
        return p.getInt(KEY_LAUNCH_COUNT, 0) > 0 ||
            p.getInt(KEY_TILE_ACTIVATIONS, 0) > 0 ||
            p.getFloat(KEY_ATTENUATION_DB, ATTENUATION_DEFAULT) != ATTENUATION_DEFAULT ||
            p.contains(KEY_OVERLAY_X) || p.contains(KEY_OVERLAY_Y) ||
            p.getInt(KEY_TERMS_ACCEPTED_VERSION, 0) > 0
    }

    /** Sticky grandfather verdict. Written once; never flips back to false. */
    fun isGrandfathered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GRANDFATHERED_PRO, false)

    fun setGrandfathered(context: Context, grandfathered: Boolean) {
        prefs(context).edit { putBoolean(KEY_GRANDFATHERED_PRO, grandfathered) }
    }

    fun wasGrandfatherEvaluated(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GRANDFATHER_EVALUATED, false)

    fun setGrandfatherEvaluated(context: Context) {
        prefs(context).edit { putBoolean(KEY_GRANDFATHER_EVALUATED, true) }
    }

    /**
     * The locked step the user previewed at the paywall, so a buyer returning
     * from the store lands on exactly the depth they heard. NaN = none.
     */
    fun getPreviewStepDb(context: Context): Float? {
        val v = prefs(context).getFloat(KEY_PREVIEW_STEP_DB, Float.NaN)
        return if (v.isNaN()) null else v
    }

    fun setPreviewStepDb(context: Context, dB: Float) {
        prefs(context).edit { putFloat(KEY_PREVIEW_STEP_DB, dB) }
    }

    fun clearPreviewStepDb(context: Context) {
        prefs(context).edit { remove(KEY_PREVIEW_STEP_DB) }
    }

    /** One-time grandfather tip-jar card in MainActivity: shown once, never again. */
    fun wasTipjarCardShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TIPJAR_CARD_SHOWN, false)

    fun setTipjarCardShown(context: Context) {
        prefs(context).edit { putBoolean(KEY_TIPJAR_CARD_SHOWN, true) }
    }

    /**
     * One-time purchase confirmation. A buyer can return from the store by several paths,
     * and only one of them keeps the paywall alive to say thank you. This makes the
     * acknowledgement independent of the path taken.
     */
    fun wasUnlockAcknowledged(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNLOCK_ACKNOWLEDGED, false)

    fun setUnlockAcknowledged(context: Context) {
        prefs(context).edit { putBoolean(KEY_UNLOCK_ACKNOWLEDGED, true) }
    }
}

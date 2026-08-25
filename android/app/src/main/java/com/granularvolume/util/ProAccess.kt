package com.granularvolume.util

import android.content.Context

/**
 * Single source of truth for "does this device have the full quiet range".
 *
 * Two independent ways in, either one is enough:
 *
 *  1. **Grandfathered** — the device used the app before the gated version arrived.
 *     Decided ONCE, on the first run of the gated version, from prior-use traces in
 *     prefs ([Prefs.hasAnyPriorUse]), then stored sticky. Existing users keep the
 *     full range forever; the promise that made them install is never withdrawn.
 *
 *  2. **Key installed** — the paid unlock-key app is present and its signing
 *     certificate matches the pinned set ([KeyCheck], flavor-split: the F-Droid
 *     flavor is fully unlocked by a stub, keeping that build free and complete).
 *
 * This object lives in src/main and is GPL-published like everything else: the
 * check is possession of the key app, not a secret. Nothing here needs hiding.
 */
object ProAccess {

    /**
     * Runs the one-time grandfather decision. Call early on EVERY entry point
     * (service create, MainActivity create) — whichever runs first decides, the
     * rest are no-ops. Never call after writing new prefs in the same session,
     * or a fresh install could look like prior use.
     */
    fun evaluateGrandfather(context: Context) {
        if (Prefs.wasGrandfatherEvaluated(context)) return
        Prefs.setGrandfathered(context, Prefs.hasAnyPriorUse(context))
        Prefs.setGrandfatherEvaluated(context)
    }

    /**
     * Live answer, cheap enough to call on every gate decision: one prefs read
     * short-circuits before the PackageManager lookup for grandfathered users.
     */
    fun isPro(context: Context): Boolean =
        Prefs.isGrandfathered(context) || KeyCheck.isKeyInstalled(context)
}

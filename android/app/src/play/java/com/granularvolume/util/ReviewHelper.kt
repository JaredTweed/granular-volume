package com.granularvolume.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.granularvolume.ReviewActivity

/**
 * Wraps Google Play's in-app review flow. Asks at most once per install, from
 * whichever natural moment comes first:
 *
 *  1. A return visit to [com.granularvolume.MainActivity] after the app is already
 *     configured (see [maybeRequestReview]), or
 *  2. After the user has actually used the Quick Settings tile several times, which
 *     is the real usage pattern for people who never reopen the main screen
 *     (see [reviewIntentIfDue]).
 *
 * Both paths share the single [Prefs.wasReviewFlowRequested] flag, so the prompt is
 * offered exactly once, ever. Google's own quota then decides whether a card is
 * actually shown; this class only decides whether it is a reasonable moment to ask.
 *
 * Fails silently on any error (no crash, no user-visible effect, no network
 * permission required — the library binds to the Play Store app via a local service
 * connection, it does not open a socket from this process). A missing or unavailable
 * Play Store must never block the app's real function, so [onDone] always fires.
 */
object ReviewHelper {

    private const val TAG = "ReviewHelper"

    /** Return visits to MainActivity required before that path may ask. */
    private const val MIN_LAUNCHES_BEFORE_ASK = 3

    /** QS-tile "turn on" taps required before the tile path may ask. */
    private const val MIN_TILE_ACTIVATIONS_BEFORE_ASK = 4

    /**
     * Whether this is a fair moment to ask, on top of the usage thresholds.
     *
     * Added 2026-09-09. Until then neither path consulted entitlement at all, so the two
     * worst possible moments to ask were both reachable:
     *
     *  - **Locked.** Someone whose trial has run out opens the app to find out why the dial
     *    stopped working, and on the third such visit is asked to rate us. A "rate this app"
     *    card shown to a person who has just lost access is the most reliable way to
     *    manufacture the one-star review this whole design exists to avoid, and the rating
     *    is what Google Play's Explore surface feeds on.
     *  - **The final trial day.** Asking today and locking tomorrow reads as a trick, even
     *    though the warning itself is honest.
     *
     * Everyone else is fair game: a grandfathered user, a buyer, and a trial user with days
     * still ahead are all being asked while the app is doing exactly what they expect.
     */
    private fun isFairMomentToAsk(context: Context): Boolean {
        if (ProAccess.isTrialExpired(context)) return false
        if (ProAccess.isOnTrial(context) && Entitlement.daysLeftInTrial(context) <= 1) return false
        return true
    }

    /**
     * MainActivity path. Requests a review if this looks like a good moment, then
     * invokes [onDone] once it is safe to continue (finish the activity, etc.)
     * whether or not a review was actually shown.
     */
    fun maybeRequestReview(activity: Activity, onDone: () -> Unit) {
        // The count is incremented first and unconditionally: it is also one of the prior-use
        // traces the grandfather decision reads, so it must keep rising even when we choose
        // not to ask.
        val launches = Prefs.incrementAndGetLaunchCount(activity)
        if (Prefs.wasReviewFlowRequested(activity) ||
            launches < MIN_LAUNCHES_BEFORE_ASK ||
            !isFairMomentToAsk(activity)
        ) {
            onDone()
            return
        }
        launchFlow(activity, onDone)
    }

    /**
     * Tile path. Called on every QS-tile "turn on" tap. Counts the activation and,
     * once the threshold is reached and the prompt has not already been offered,
     * returns an [Intent] for the transparent [ReviewActivity] that will host the
     * review card. Returns null when it is not (yet) time to ask, so the caller
     * does nothing and never disturbs the user.
     *
     * The one-shot flag is set only when the flow actually runs (in [launchFlow]),
     * so a race that fails to start the activity simply tries again next time rather
     * than silently burning the single prompt.
     */
    fun reviewIntentIfDue(context: Context): Intent? {
        if (Prefs.wasReviewFlowRequested(context)) return null
        // Same rule as the activity path: count the activation regardless (it is a prior-use
        // trace), but never turn it into a prompt at a moment the person is being locked out.
        val activations = Prefs.incrementAndGetTileActivations(context)
        if (activations < MIN_TILE_ACTIVATIONS_BEFORE_ASK) return null
        if (!isFairMomentToAsk(context)) return null
        return Intent(context, ReviewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Runs the raw review flow with no gating (the caller has already decided it is
     * time), marks it as offered so it never fires again, and calls [onDone] when
     * the flow settles — success or failure.
     */
    fun launchFlow(activity: Activity, onDone: () -> Unit) {
        // Set before launching so a thrown/failed flow still counts as "asked" and
        // never loops. One attempt per install, matching Google's own quota anyway.
        Prefs.setReviewFlowRequested(activity, true)
        try {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (request.isSuccessful) {
                    manager.launchReviewFlow(activity, request.result)
                        .addOnCompleteListener { onDone() }
                } else {
                    Log.d(TAG, "In-app review unavailable, skipping: ${request.exception?.message}")
                    onDone()
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "In-app review threw, skipping: ${t.message}")
            onDone()
        }
    }
}

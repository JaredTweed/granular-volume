package com.granularvolume

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.granularvolume.util.Entitlement
import com.granularvolume.util.KeyCheck
import com.granularvolume.util.ProAccess

/**
 * "Your access": the sheet behind the dial's info button.
 *
 * Why a sheet and not a screen. The dial exists for late-night listening next to someone
 * asleep. A full, bright screen taking over the display at 2am is the opposite of what
 * this app promises, so this slides up over whatever is running and closes on a tap
 * outside. Same component and palette as the paywall sheet, so the two moments a user
 * meets our commercial side look like one product rather than two.
 *
 * It is also the ONLY place several things can be reached at all:
 *  - during the first four days of a trial nothing is locked, so the paywall never opens
 *    and there was otherwise no way for a convinced user to pay us
 *  - a long-time user who dismissed the one-time card had no route back to supporting us
 *  - the legal texts were reachable only from a screen that closes itself once set up
 *
 * Read-only by design: it reports state and offers Play. It never writes entitlement.
 */
class InfoSheetActivity : AppCompatActivity() {

    private var dialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialog = BottomSheetDialog(this).apply {
            setContentView(buildSheet())
            setOnCancelListener { finish() }
            show()
        }
    }

    /** Returning from Play with the key installed: re-render rather than show stale state. */
    override fun onResume() {
        super.onResume()
        dialog?.setContentView(buildSheet())
    }

    override fun onDestroy() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    // -- state ----------------------------------------------------------------

    private enum class State { GRANDFATHERED, TRIAL, UNLOCKED, LOCKED }

    /**
     * Order matters and mirrors [ProAccess.isPro]: a grandfathered device that also owns
     * the key is reported as grandfathered, because that is the promise we made and the
     * one they would be upset to see disappear.
     */
    private fun state(): State = when {
        Entitlement.isGrandfathered(this) -> State.GRANDFATHERED
        KeyCheck.isKeyInstalled(this) -> State.UNLOCKED
        Entitlement.isTrialActive(this) -> State.TRIAL
        else -> State.LOCKED
    }

    // -- sheet ----------------------------------------------------------------

    private fun buildSheet(): View {
        val st = state()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.gv_surface))
            val p = dp(24)
            setPadding(p, p, p, dp(28))
        }

        val headline = when (st) {
            State.GRANDFATHERED -> getString(R.string.gv_info_state_grandfathered)
            State.UNLOCKED -> getString(R.string.gv_info_state_unlocked)
            State.LOCKED -> getString(R.string.gv_info_state_locked)
            State.TRIAL -> {
                val d = Entitlement.daysLeftInTrial(this)
                resources.getQuantityString(R.plurals.gv_trial_days_left, d, d)
            }
        }
        root.addView(text(headline, 19f, bold = true, colorRes = R.color.gv_text_primary))

        val body = when (st) {
            State.GRANDFATHERED -> R.string.gv_info_body_grandfathered
            State.UNLOCKED -> R.string.gv_info_body_unlocked
            State.TRIAL -> R.string.gv_info_body_trial
            State.LOCKED -> R.string.gv_info_body_locked
        }
        root.addView(text(getString(body), 13f, colorRes = R.color.gv_text_secondary).topPad(8))

        // A buyer is offered nothing: they already paid, and a live "buy" button would read
        // as a second charge. Everyone else gets one honest route to Play, worded as
        // support for the people who owe us nothing.
        if (st != State.UNLOCKED) {
            val cta = if (st == State.GRANDFATHERED) R.string.gv_info_cta_support
            else R.string.gv_info_cta_buy
            root.addView(Button(this).apply {
                text = getString(cta)
                isAllCaps = false
                setTextColor(ContextCompat.getColor(context, R.color.gv_on_accent))
                setBackgroundColor(ContextCompat.getColor(context, R.color.gv_accent))
                setOnClickListener { openStore() }
            }.topPad(18, fill = true))
        }

        root.addView(
            text(getString(R.string.gv_info_restore), 12f, colorRes = R.color.gv_text_muted)
                .topPad(if (st == State.UNLOCKED) 18 else 14)
        )

        root.addView(legalRow().topPad(18, fill = true))
        return root
    }

    /** Terms, Privacy and the licences screen, side by side and always reachable. */
    private fun legalRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(link(getString(R.string.gv_info_terms)) { openUrl(URL_TERMS) })
        row.addView(link(getString(R.string.gv_info_privacy)) { openUrl(URL_PRIVACY) })
        row.addView(link(getString(R.string.gv_licenses_title)) {
            startActivity(
                Intent(this, LicensesActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        return row
    }

    private fun link(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(ContextCompat.getColor(context, R.color.gv_accent))
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { action() }
    }

    private fun openStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$KEY_APP_ID")))
        } catch (_: Exception) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$KEY_APP_ID")
                )
            )
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, colorRes: Int): TextView =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(ContextCompat.getColor(context, colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun <T : View> T.topPad(topDp: Int, fill: Boolean = false): T {
        layoutParams = LinearLayout.LayoutParams(
            if (fill) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topDp) }
        return this
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_APP_ID = "com.granularvolume.key"
        const val URL_TERMS = "https://rzuss.github.io/granular-volume-privacy/terms-of-use.html"
        const val URL_PRIVACY = "https://rzuss.github.io/granular-volume-privacy/"
    }
}

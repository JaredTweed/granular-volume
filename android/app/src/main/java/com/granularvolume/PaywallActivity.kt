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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.granularvolume.service.VolumeControlService
import com.granularvolume.util.Prefs
import com.granularvolume.util.ProAccess

/**
 * Full-range upgrade sheet (1.5.0). Launched by the service the moment a locked
 * quiet step is tapped — while the LIVE PREVIEW of that step is already audible,
 * because the silence itself is the honest pitch.
 *
 * Lifecycle contract with the service:
 *  - Dismissed without buying (Not now, tap outside, back, swipe): the service
 *    ramps back to the free floor (ACTION_PREVIEW_END, fired from onDestroy).
 *  - CTA opens the key app's Play listing; this activity stays alive underneath
 *    (no noHistory), so returning from the store lands back HERE. onResume then
 *    re-checks ProAccess: key present -> ACTION_PREVIEW_COMMIT keeps the exact
 *    depth the buyer previewed. That is the anti-"paid and nothing happened"
 *    mechanism.
 *
 * No price is rendered in-app — the store listing shows the local price, so
 * nothing here can go stale.
 */
class PaywallActivity : AppCompatActivity() {

    private var dialog: BottomSheetDialog? = null
    private var committed = false
    private var leavingForStore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialog = BottomSheetDialog(this).apply {
            setContentView(buildSheet())
            setOnCancelListener { finish() }
            show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (leavingForStore) {
            leavingForStore = false
            if (ProAccess.isPro(this)) {
                committed = true
                serviceAction(VolumeControlService.ACTION_PREVIEW_COMMIT)
                Toast.makeText(this, R.string.gv_paywall_unlocked, Toast.LENGTH_LONG).show()
                dialog?.setOnCancelListener(null)
                dialog?.dismiss()
                finish()
            }
        }
    }

    override fun onDestroy() {
        // Every path that does not end in a purchase reverts the preview exactly once.
        if (!committed) serviceAction(VolumeControlService.ACTION_PREVIEW_END)
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun serviceAction(action: String) {
        startService(Intent(this, VolumeControlService::class.java).setAction(action))
    }

    private fun openStore() {
        leavingForStore = true
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$KEY_APP_ID")))
        } catch (_: Exception) {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$KEY_APP_ID"))
            )
        }
    }

    // ── sheet UI, programmatic — matches the app palette, adds no layout file ──

    private fun buildSheet(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.gv_surface))
            val p = dp(24)
            setPadding(p, p, p, dp(28))
        }

        root.addView(text(R.string.gv_paywall_title, 19f, bold = true, colorRes = R.color.gv_text_primary))
        root.addView(text(R.string.gv_paywall_depth, 14f, colorRes = R.color.gv_text_secondary).topPad(6))
        // The preview note is a factual statement ("you are hearing this step right now"), so it
        // may only appear when a preview is actually playing. The quiet-step path sets the
        // preview pref before opening this sheet; the upper-zone and mute paths do not run a
        // preview, and showing the line there would be the sheet's first sentence being false.
        if (Prefs.getPreviewStepDb(this) != null) {
            root.addView(text(R.string.gv_paywall_preview_note, 13f, colorRes = R.color.gv_success).topPad(12))
        }
        root.addView(text(R.string.gv_paywall_body, 13f, colorRes = R.color.gv_text_secondary).topPad(12))
        root.addView(text(R.string.gv_paywall_expectation, 12f, colorRes = R.color.gv_text_muted).topPad(8))

        root.addView(Button(this).apply {
            text = getString(R.string.gv_paywall_cta)
            isAllCaps = false
            setTextColor(ContextCompat.getColor(context, R.color.gv_on_accent))
            setBackgroundColor(ContextCompat.getColor(context, R.color.gv_accent))
            setOnClickListener { openStore() }
        }.topPad(18, fill = true))

        root.addView(text(R.string.gv_paywall_terms, 11f, colorRes = R.color.gv_text_muted).apply {
            gravity = Gravity.CENTER
            setOnClickListener { openUrl(URL_TERMS) }
        }.topPad(8, fill = true))

        root.addView(TextView(this).apply {
            text = getString(R.string.gv_paywall_not_now)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.gv_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { finish() }
        }.topPad(6, fill = true))

        return root
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun text(res: Int, sizeSp: Float, bold: Boolean = false, colorRes: Int): TextView =
        TextView(this).apply {
            text = getString(res)
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
        // Same document the consent gate links to (MainActivity.URL_TERMS).
        const val URL_TERMS = "https://rzuss.github.io/granular-volume-privacy/terms-of-use.html"
    }
}

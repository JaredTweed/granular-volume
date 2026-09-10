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
import com.granularvolume.service.VolumeControlService
import com.granularvolume.util.ProAccess

/**
 * Full-range upgrade sheet (1.5.0). Launched by the coordinator the moment a locked
 * gesture is refused: a quiet step, an upper-zone move, or mute.
 *
 * Nothing is playing behind this sheet. The device is held at 0 dB and stays there;
 * the reader has already spent seven days with the full range and needs no reminder
 * of what it sounds like, only a way to get it back.
 *
 * Lifecycle contract with the service:
 *  - Dismissed without buying (Not now, tap outside, back, swipe): nothing to undo.
 *  - CTA opens the key app's Play listing; this activity stays alive underneath
 *    (no noHistory), so returning from the store lands back HERE. The service has
 *    usually handled the purchase already, from the package broadcast (it unlocks the
 *    session and re-applies the step that was refused); onResume re-checks ProAccess
 *    and sends ACTION_KEY_INSTALLED as the fallback, then closes.
 *
 * No price is rendered in-app: the store listing shows the local price, so nothing
 * here can go stale.
 */
class PaywallActivity : AppCompatActivity() {

    private var dialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialog = BottomSheetDialog(this).apply {
            setContentView(buildSheet())
            setOnCancelListener { finish() }
            show()
        }
    }

    /**
     * Re-checked on EVERY resume, not only on the first return from the store.
     *
     * Until 2026-09-09 this was gated on a one-shot "leavingForStore" flag that was cleared
     * before the check. A buyer who came back while the key was still downloading (the
     * ordinary case on mobile data) consumed the flag, saw a sheet still reading "Your seven
     * days are over", and never got the confirmation or the replay of the refused step. The
     * gate was designed for the moment it failed in. Checking unconditionally costs two pref
     * reads and one PackageManager call per resume, and cannot misfire: this sheet only ever
     * opens on a refused gesture, which requires the range to be locked at that instant.
     */
    override fun onResume() {
        super.onResume()
        if (ProAccess.isPro(this)) {
            // Since 2026-09-10 the service learns of the key from the package broadcast the
            // moment its install completes, and announces the purchase itself (one toast, the
            // dial lights up, the refused step lands). This signal is the fallback door and a
            // no-op when that already happened. No toast here, or a buyer would get two.
            serviceAction(VolumeControlService.ACTION_KEY_INSTALLED)
            dialog?.setOnCancelListener(null)
            dialog?.dismiss()
            finish()
        }
    }

    override fun onDestroy() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun serviceAction(action: String) {
        startService(Intent(this, VolumeControlService::class.java).setAction(action))
    }

    private fun openStore() {
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

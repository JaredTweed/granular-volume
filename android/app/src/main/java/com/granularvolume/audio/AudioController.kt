package com.granularvolume.audio

import android.content.Context
import android.util.Log
import com.granularvolume.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central audio controller. Manages strategy selection and exposes a StateFlow
 * for the current attenuation level so the UI can react reactively.
 *
 * Strategy selection order:
 *   1. DynamicsProcessingStrategy (preferred — clean, flat-spectrum)
 *   2. LoudnessEnhancerStrategy (fallback — OEM-dependent behavior)
 *   3. null (no effect available — notify user)
 */
class AudioController(private val context: Context) {

    /** Deepest step available without Pro: 0 and −5 dB stay free, −10 and below unlock. */
    private val FREE_FLOOR_DB = -5f

    private val tag = "GranularVolume:AudioCtrl"

    private var strategy: AudioEffectStrategy? = null

    // ── Full-range gate (1.5.0) ─────────────────────────────────────
    /**
     * Answers "is the full quiet range unlocked on this device". Wired by the
     * service to ProAccess. Defaults to open on purpose: if a future entry point
     * forgets to wire it, the failure mode is a free full range, never a paying
     * or grandfathered user losing depth.
     */
    var proProvider: () -> Boolean = { true }

    /** Paywall live-preview: while true, gate checks are skipped (2.7). */
    var previewBypass: Boolean = false

    /**
     * Fired when a non-Pro QUIET_STEP request was clamped — the paywall moment.
     * Receives the depth the user asked for. UI-thread hop is the listener's job.
     */
    var onGateHit: ((requestedDb: Float) -> Unit)? = null

    /** Emits current attenuation in dB. UI observes this. */
    private val _attenuationDb = MutableStateFlow(Prefs.getAttenuation(context))
    val attenuationDb: StateFlow<Float> = _attenuationDb.asStateFlow()

    /** True if a working audio effect strategy was found. */
    var isEffectAvailable: Boolean = false
        private set

    /**
     * True while the preferred DynamicsProcessing strategy holds the effect. False means
     * either no strategy at all or the LoudnessEnhancer fallback, whose negative-gain
     * support is OEM-dependent — both states that a caller may want to retry out of.
     */
    val usingPreferredStrategy: Boolean
        get() = strategy is DynamicsProcessingStrategy

    /**
     * Initializes the best available AudioEffect strategy.
     * Call this from Service.onCreate() — never from UI thread.
     * @return true if any strategy initialized successfully
     */
    fun initialize(): Boolean {
        val strategies: List<AudioEffectStrategy> = listOf(
            DynamicsProcessingStrategy(),
            LoudnessEnhancerStrategy()
        )

        for (s in strategies) {
            if (s.initialize()) {
                strategy = s
                isEffectAvailable = true
                Log.i(tag, "Using strategy: ${s::class.simpleName}")
                // Apply persisted attenuation immediately, THROUGH the gate: a stale
                // deep level from a refunded or pre-gate state re-clamps at service start.
                setAttenuation(_attenuationDb.value, GainSource.SYSTEM)
                return true
            }
        }

        Log.e(tag, "No AudioEffect strategy available on this device")
        isEffectAvailable = false
        return false
    }

    /**
     * Where a gain request comes from — the gate treats each origin differently.
     *
     * The one exemption that makes this enum necessary: the full-range curve
     * routes rung remainders through this same method, and a remainder is
     * bounded by the LOCAL hardware step gap, which on coarse OEM volume curves
     * can exceed 5 dB. A blind clamp would corrupt the FREE upper zone on
     * exactly those devices.
     */
    enum class GainSource {
        /** A quiet-zone step the user picked. Gated; the only source that opens the paywall. */
        QUIET_STEP,
        /** Upper-zone curve remainder. NEVER gated: the upper zone is free by design. */
        CURVE_REMAINDER,
        /** Restores and internal writes (boot restore, mute cancel, absorb easing). Gated silently. */
        SYSTEM,
        /** The mute convenience. Gate-exempt by the locked decision: mute is free. */
        MUTE,
    }

    /**
     * Sets attenuation level. Persists to prefs and updates StateFlow.
     * Thread-safe: AudioEffect API is thread-safe internally.
     * @param dB range [Prefs.ATTENUATION_MIN, Prefs.ATTENUATION_MAX]
     * @param source who is asking — decides whether the free-floor gate applies
     */
    fun setAttenuation(dB: Float, source: GainSource = GainSource.SYSTEM) {
        val requested = dB.coerceIn(Prefs.ATTENUATION_MIN, Prefs.ATTENUATION_MAX)
        val clamped = if (gateOpen(source)) requested else {
            val limited = requested.coerceAtLeast(FREE_FLOOR_DB)
            if (limited != requested && source == GainSource.QUIET_STEP) {
                Log.i(tag, "Gate: ${requested}dB requested, held at ${limited}dB")
                onGateHit?.invoke(requested)
            }
            limited
        }
        strategy?.setAttenuation(clamped)
        _attenuationDb.value = clamped
        Prefs.setAttenuation(context, clamped)
        Log.d(tag, "Attenuation set to ${clamped}dB (source=$source)")
    }

    private fun gateOpen(source: GainSource): Boolean =
        previewBypass ||
        source == GainSource.CURVE_REMAINDER ||
        source == GainSource.MUTE ||
        proProvider()

    /**
     * Convenience: mute immediately (max attenuation). Gate-exempt: mute is free.
     */
    fun mute() = setAttenuation(Prefs.ATTENUATION_MIN, GainSource.MUTE)

    /**
     * Convenience: pass-through (no attenuation).
     */
    fun passThrough() = setAttenuation(Prefs.ATTENUATION_MAX)

    /**
     * Tears the effect down and rebuilds it, restoring the current attenuation.
     *
     * Why this exists (in-call fix, 2026-08-16): a session-0 effect chain lives on ONE
     * output thread, chosen by audio policy at effect creation time following the MUSIC
     * strategy. Call audio (cellular downlink, VoIP playout) is routed to a different
     * output on many devices, so the running effect never touches it. Re-creating the
     * effect while the call is active gives policy the chance to attach the chain to the
     * output that is actually carrying sound right now. Field-measured: quiet zone dead
     * in calls on two devices while media attenuation worked on both.
     *
     * Cheap and safe by construction: [initialize] re-applies the persisted attenuation,
     * so the audible state is preserved across the swap; on devices where policy re-picks
     * the same output this is a harmless no-op glitch of a few ms.
     */
    fun reattach() {
        Log.i(tag, "Reattaching audio effect (attenuation=${_attenuationDb.value}dB)")
        strategy?.release()
        strategy = null
        isEffectAvailable = false
        initialize()
    }

    /**
     * Releases the underlying AudioEffect. Must be called in Service.onDestroy().
     * After this call, this instance should not be used.
     */
    fun release() {
        strategy?.release()
        strategy = null
        Log.i(tag, "AudioController released")
    }

}

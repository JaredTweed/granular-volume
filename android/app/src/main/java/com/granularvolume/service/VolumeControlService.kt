package com.granularvolume.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.granularvolume.MainActivity
import com.granularvolume.R
import com.granularvolume.InfoSheetActivity
import com.granularvolume.PaywallActivity
import com.granularvolume.audio.AudioController
import com.granularvolume.util.ProAccess
import com.granularvolume.audio.FullRangeCoordinator
import com.granularvolume.audio.StreamVolumeController
import com.granularvolume.overlay.OverlayManager
import com.granularvolume.util.Entitlement
import com.granularvolume.util.Prefs
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the AudioController and OverlayManager lifecycle.
 *
 * Lifecycle:
 *   onCreate() -> initialize audio + overlay
 *   onDestroy() -> release audio + hide overlay + cancel coroutines
 *
 * This service is START_STICKY — the OS will restart it if killed.
 */
class VolumeControlService : Service() {

    private val tag = "GranularVolume:Service"

    companion object {
        private const val CHANNEL_ID   = "gv_volume_control"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.granularvolume.ACTION_STOP"

        /**
         * The key app just appeared. Sent by the paywall sheet when it returns from the
         * store and finds the key installed, so ownership takes effect in that second
         * rather than at the next service start.
         */
        const val ACTION_KEY_INSTALLED = "com.granularvolume.ACTION_KEY_INSTALLED"

        /**
         * Boot-restore starts carry this so the purchase sheet stays closed. A boot is
         * the MACHINE resuming, not the user opening the control, and a sales sheet
         * over the launcher seconds after power-on is the exact adware gesture this
         * app must never make.
         */
        const val EXTRA_FROM_BOOT = "com.granularvolume.EXTRA_FROM_BOOT"

        // Hidden-but-stable system broadcast + extras (no public constants exist for these).
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("VolumeControlService")
    )

    private lateinit var audioController: AudioController
    private lateinit var streamVolumeController: StreamVolumeController
    private lateinit var coordinator: FullRangeCoordinator

    /** Latched full-range verdict for this session. See [unlockedThisSession]. */
    /**
     * The quiet step a locked user reached for, kept only until the store round trip
     * ends. In memory on purpose: the foreground service outlives the trip, and this
     * must never outlive it, survive a restart, or travel in a backup.
     */
    private var pendingQuietStepDb: Float? = null

    private var sessionUnlocked = false
    private lateinit var overlayManager: OverlayManager

    /**
     * VOLUME_CHANGED_ACTION is undocumented but long-stable and the standard listening
     * mechanism for volume apps — spec accepts it with a real-hardware verification gate.
     * Feeds the coordinator's absorb policy; our own writes are filtered there.
     */
    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (stream < 0) return
            val to = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
            val from = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, to)
            if (to < 0) return
            coordinator.onExternalVolumeChange(stream, from, to)
        }
    }

    /**
     * The volume curve differs per output route — re-read it on every route change (spec).
     * 1.4.6: a route change during a call also moves the voice audio to a different output,
     * so the coordinator must re-place the effect chain there too (onRouteChanged is a
     * no-op outside calls; media effects follow the music output by policy on their own).
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            coordinator.refreshCurve()
            coordinator.onRouteChanged()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            coordinator.refreshCurve()
            coordinator.onRouteChanged()
        }
    }

    // 1.4.2: the AudioPlaybackCallback that re-rendered the pill on playback start/stop is
    // gone WITH its reason: the dial no longer flips between media and ring (it always drives
    // media, matching AOSP's own no-playback default), so playback changes affect nothing the
    // overlay renders.

    /**
     * 1.4.3: in a call the upper zone drives the voice-call stream, so the ladder must
     * re-render when a call starts or ends. Control correctness never depends on this —
     * activeStream() is evaluated live per use — this is display freshness only. The
     * listener API exists from 31; on 28-30 the display catches up on the next volume
     * broadcast or touch, which in practice is the moment the call audio starts.
     */
    private val modeListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            AudioManager.OnModeChangedListener { coordinator.onAudioModeChanged() }
        else null

    /**
     * True only when the user explicitly asked to stop (notification Stop action or
     * overlay dismiss). onDestroy also runs on device shutdown and OS kills, and those
     * must NOT clear the boot-restore flag — otherwise BootReceiver always sees false
     * and the control never comes back after a reboot.
     */
    @Volatile
    private var stopRequestedByUser = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "Service starting")

        createNotificationChannel()
        // API 29+: must pass foregroundServiceType explicitly or the OS throws on some devices.
        // Hardened: never let an FGS-start exception kill the service before the overlay shows.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(0f),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(0f))
            }
        } catch (e: Exception) {
            Log.e(tag, "startForeground failed: ${e.message}", e)
        }

        audioController = AudioController(applicationContext)

        // Full-range gate (1.5.0): decide grandfathering once, then latch the verdict for
        // this session. Must precede initialize(), which routes the boot-restore level
        // through the gate.
        ProAccess.evaluateGrandfather(applicationContext)
        sessionUnlocked = ProAccess.isPro(applicationContext)
        audioController.proProvider = ::unlockedThisSession

        streamVolumeController = StreamVolumeController(applicationContext)
        coordinator = FullRangeCoordinator(applicationContext, audioController, streamVolumeController)
        coordinator.lockedProvider = { !unlockedThisSession() }
        coordinator.onLockedInteraction = { pendingStep ->
            mainHandler.post {
                pendingQuietStepDb = pendingStep
                // Observable refusal: this is now the ONLY way a user gesture reaches the
                // paywall, so the harness asserts on it instead of on the gate's clamp.
                Log.i(tag, "Locked gesture refused (pendingQuietStep=$pendingStep), opening paywall")
                openPaywall()
            }
        }
        coordinator.onQuietUnavailable = {
            mainHandler.post {
                // One short line instead of a bar that would move without the sound moving.
                Log.i(tag, "Quiet step refused: cellular call carries no effect chain")
                Toast.makeText(
                    applicationContext,
                    getString(R.string.gv_quiet_unavailable_in_call),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        // The dial is the only surface a set-up user still sees, so it carries the one
        // route to status, purchase and the legal texts. NEW_TASK because the caller is a
        // service, exactly as with the paywall sheet.
        overlayManager  = OverlayManager(
            context         = applicationContext,
            audioController = audioController,
            coordinator     = coordinator,
            scope           = serviceScope,
            onDismiss       = {
                stopRequestedByUser = true
                stopSelf()
            },
            onInfo          = {
                startActivity(
                    Intent(applicationContext, InfoSheetActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
        // Wired HERE, after construction, never inside a callback. It sat inside the onInfo
        // lambda until 2026-09-09, which compiled and meant it was only ever assigned after
        // the user tapped the info button: the "session already running when the last day
        // begins" warning (see onOpenedByUser) was dead for anyone driving the app from the
        // dial. The harness was green over it because A17 reads the countdown by tapping
        // info, the one action that armed the broken assignment. A21 now covers this path.
        overlayManager.onEngaged = { mainHandler.post { maybeLastDayNudge() } }

        serviceScope.launch(Dispatchers.Default) {
            audioController.initialize()
            // initialize() re-applies the persisted level THROUGH the gate, so a locked
            // device with a stale deep level is now at 0 dB while the coordinator still
            // believes it is in the quiet zone (it read the pre-clamp value when it was
            // constructed). Without this the dial would open showing a step it is not
            // applying, on every single start.
            coordinator.syncZoneToAppliedGain()
            if (!audioController.isEffectAvailable) {
                Log.e(tag, "No audio effect available — service will run without audio attenuation")
            }
            // Read the device's volume curve AFTER the effect is up (update semantics:
            // reads only, writes nothing until the user touches the slider).
            coordinator.refreshCurve()
        }

        // API 34+ requires an explicit export flag on context-registered receivers.
        // NOT_EXPORTED still receives system broadcasts (they come from the system UID).
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            volumeChangeReceiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(deviceCallback, mainHandler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener != null) {
            am.addOnModeChangedListener({ r -> mainHandler.post(r) }, modeListener)
        }

        try {
            overlayManager.show()
        } catch (e: Exception) {
            // Surface the real reason on-device instead of failing silently.
            Log.e(tag, "Failed to show overlay: ${e.message}", e)
            toast("Couldn't show the control: ${e.message}. Check 'Display over other apps'.")
        }
        Prefs.setServiceWasRunning(applicationContext, true)

        // Update notification when attenuation changes
        audioController.attenuationDb
            .onEach { dB -> updateNotification(dB) }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(tag, "Stop action received")
                stopRequestedByUser = true
                stopSelf()
            }
            ACTION_KEY_INSTALLED -> onKeyInstalled()
            // A plain start with a real Intent is a person or the boot receiver turning
            // the control on; a null Intent is only ever the system resurrecting a
            // killed sticky service, which no one asked for and no sheet may answer.
            null -> if (intent != null) {
                onOpenedByUser(fromBoot = intent.getBooleanExtra(EXTRA_FROM_BOOT, false))
            }
        }
        return START_STICKY
    }

    /**
     * Whether the full range is open for THIS session.
     *
     * Latched, and it only ever opens: the snapshot is taken at service start, and after
     * that the only thing that can change the answer is the key arriving, which must take
     * effect at once. A trial that runs out while the control is live is deliberately
     * ignored until the next start. Nothing may get louder on its own while someone is on a
     * call at -30 dB, and finding the app dead the next time you start it is a far kinder
     * failure than the phone shouting mid-sentence.
     */
    private fun unlockedThisSession(): Boolean {
        if (sessionUnlocked) return true
        if (!ProAccess.isPro(applicationContext)) return false
        sessionUnlocked = true
        Log.i(tag, "Full range opened mid-session (key installed)")
        return true
    }

    /**
     * The commercial voice of the app, and ALL of it. Two sentences of policy:
     *
     *  - Expired: every user-originated start answers with the "Your access" sheet,
     *    because starting a control that can no longer do anything deserves an
     *    explanation and the one-tap route to fixing it, every time, uncapped.
     *  - Final trial day: the sheet interrupts ONCE, to warn that tomorrow it locks.
     *    Both here (covers a fresh start that day) and from onEngaged (covers a
     *    session already running when the last day begins).
     *
     * Nothing here ever fires on a timer or a boot. A prompt with no user action
     * behind it is spam, reads as adware in reviews, and risks the Play policy on
     * interruptive monetization; the daily cadence the model needs is carried by
     * whichever comes first that day: a start (sheet) or a locked gesture (paywall).
     */
    private fun onOpenedByUser(fromBoot: Boolean) {
        if (fromBoot) return
        if (ProAccess.isTrialExpired(applicationContext)) { openInfoSheet(); return }
        maybeLastDayNudge()
    }

    /** Once, on the trial's final day: cheapest check first, so the everyday cost is one boolean read. */
    private fun maybeLastDayNudge() {
        if (Prefs.wasLastDayNudgeShown(applicationContext)) return
        if (!ProAccess.isOnTrial(applicationContext)) return
        if (Entitlement.daysLeftInTrial(applicationContext) > 1) return
        Prefs.setLastDayNudgeShown(applicationContext)
        openInfoSheet()
    }

    private fun openInfoSheet() {
        startActivity(
            Intent(applicationContext, InfoSheetActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openPaywall() {
        startActivity(
            Intent(applicationContext, PaywallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * The key has arrived. Opens the latch for this session, then honours the gesture that
     * sent the user to the store in the first place.
     *
     * Why re-apply anything at all: the gate held the gain at 0 dB for the whole locked
     * session, so the purchase by itself changes nothing audible, and "I paid and nothing
     * happened" is the worst possible first second of ownership.
     *
     * Routed through the coordinator rather than straight at the controller, because
     * applyQuiet is what also pins the media stream and sets the zone. Calling the
     * controller alone would apply the gain while the dial still rendered the upper zone.
     *
     * A buyer who arrived from the upper zone or from mute has no pending step: they keep
     * the level they can already see, now unlocked, and the place they left off comes back
     * on the next start, when initialize() re-applies the persisted level through an open
     * gate. That restore needs no bookkeeping here; it falls out of the persistence rule.
     */
    private fun onKeyInstalled() {
        if (!unlockedThisSession()) {
            Log.w(tag, "Key-installed signal received, but ProAccess still reports locked")
            return
        }
        val pending = pendingQuietStepDb
        pendingQuietStepDb = null
        if (pending != null) {
            Log.i(tag, "Key installed: applying the step that was refused (${pending}dB)")
            coordinator.applyQuiet(pending)
        }
        // The shade said "Locked" a second ago. Repaint it now rather than on the next
        // attenuation change, which for a buyer arriving from the info sheet (no pending
        // step) might not come for hours.
        updateNotification(audioController.attenuationDb.value)
    }

    override fun onDestroy() {
        Log.i(tag, "Service stopping (userRequested=$stopRequestedByUser)")
        runCatching { unregisterReceiver(volumeChangeReceiver) }
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.unregisterAudioDeviceCallback(deviceCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener != null) {
                am.removeOnModeChangedListener(modeListener)
            }
        }
        overlayManager.hide()
        audioController.release()
        serviceScope.cancel()
        // Only a user-intended stop clears the boot-restore flag. A system-initiated
        // destroy (device shutdown, OS kill) leaves it set, so BootReceiver restores
        // the control after the next boot.
        if (stopRequestedByUser) {
            Prefs.setServiceWasRunning(applicationContext, false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Show a toast from any thread (service callbacks may run off the main thread). */
    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Volume Control",
            NotificationManager.IMPORTANCE_LOW   // No sound, no popup
        ).apply {
            description = "Granular sub-volume control overlay"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(dB: Float): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VolumeControlService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        // A locked control saying "Pass-through" would be the shade lying about why
        // nothing works. Locked gets the honest line and a tap that opens the sheet:
        // the notification is the one surface the user sees every single day, so it
        // carries the standing, silent version of the daily reminder.
        // Same truth the audio gate uses: once this session is open it stays open until the
        // next start, so a trial that runs out mid-session must not have the shade calling
        // the control "Locked" while the dial still works. Read-only on purpose (no latch
        // mutation, no "opened mid-session" log from a notification repaint).
        val locked = !sessionUnlocked && !ProAccess.isPro(applicationContext)
        val tapTarget = if (locked) InfoSheetActivity::class.java else MainActivity::class.java
        val tapIntent = PendingIntent.getActivity(
            this, 1, Intent(this, tapTarget), PendingIntent.FLAG_IMMUTABLE
        )
        val dbText =
            if (locked) getString(R.string.gv_notif_locked)
            else if (dB == 0f) "Pass-through" else "%.0f dB".format(dB)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume_slider)
            .setContentTitle("Sub-Volume Control")
            .setContentText(dbText)
            .setContentIntent(tapIntent)
            .addAction(R.drawable.ic_close, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(dB: Float) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(dB))
    }
}

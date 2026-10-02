package io.github.christiantwu.longhand.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telecom.TelecomManager

/** What the phone is doing, as far as processing decisions go. */
object CallState {

    /**
     * True while any call is in progress. Heavy processing waits for the call to end; its
     * end triggers the after-call check, which starts it again. False when unknown (no
     * Phone permission), so processing then simply runs as before.
     */
    fun inCall(context: Context): Boolean = try {
        context.getSystemService(TelecomManager::class.java).isInCall
    } catch (e: SecurityException) {
        false
    }

    /**
     * Plugged in, whether or not the battery is filling right now: Pixels hold it at 80% with
     * battery protection, and isCharging reads false then.
     */
    fun onCharger(context: Context): Boolean =
        (context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun prefs(context: Context) = context.getSharedPreferences("call_state", Context.MODE_PRIVATE)

    /**
     * Set when a call connects and cleared when the after-call check runs, so a missed or
     * rejected call (ringing, then idle) doesn't start a check: it can't have a recording.
     */
    fun markConnected(context: Context) = prefs(context).edit().putBoolean(CONNECTED, true).apply()

    fun connectedSinceLastCheck(context: Context): Boolean = prefs(context).getBoolean(CONNECTED, false)

    fun clearConnected(context: Context) = prefs(context).edit().putBoolean(CONNECTED, false).apply()

    /**
     * Set when processing paused because a call came in (even one that only rang), so the
     * call's end starts the after-call check, which picks the work up again.
     */
    fun markPaused(context: Context) = prefs(context).edit().putBoolean(PAUSED, true).apply()

    fun pausedForCall(context: Context): Boolean = prefs(context).getBoolean(PAUSED, false)

    fun clearPaused(context: Context) = prefs(context).edit().putBoolean(PAUSED, false).apply()

    private const val CONNECTED = "connected_since_last_check"
    private const val PAUSED = "paused_for_call"
}

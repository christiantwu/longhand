package io.github.christiantwu.longhand.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import io.github.christiantwu.longhand.TAG

/**
 * Hears every change of the phone's call state; Android delivers this broadcast even when
 * the app isn't running, as long as it holds the Phone (READ_PHONE_STATE) permission.
 * When the phone goes back to idle after a connected call, the folder is checked about a
 * minute later, once the recorder has finished writing the file.
 */
class CallEndedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        // With call log access, Android sends every change twice, once with the other person's
        // number. Only the copy without the number is used: one per change, and no number needed.
        if (intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallState.markConnected(context)
            TelephonyManager.EXTRA_STATE_IDLE -> when {
                CallState.connectedSinceLastCheck(context) -> {
                    Log.i(TAG, "call ended: checking for its recording in ${Work.AFTER_CALL_DELAY_MS / 1000} s")
                    Work.processAfterCall(context)
                }
                // A call that only rang can still have paused processing.
                CallState.pausedForCall(context) -> Work.processAfterCall(context)
            }
        }
    }
}

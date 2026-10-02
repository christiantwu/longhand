package io.github.christiantwu.longhand.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.christiantwu.longhand.TAG
import io.github.christiantwu.longhand.data.Settings
import kotlinx.coroutines.delay

/**
 * Runs as an expedited job right after a call ends, so Android doesn't hold it back for
 * battery batching. It waits until the recording is finished and stable, then scans the
 * folder, which queues transcription. Each new call end replaces this job, restarting the
 * wait; if another call is in progress when the wait is over, the check waits for that
 * call's end instead.
 */
class AfterCallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Settings(applicationContext).current().setupDone) return Result.success()
        delay(Work.AFTER_CALL_DELAY_MS)
        if (CallState.inCall(applicationContext)) {
            Log.i(TAG, "after call: another call is in progress; checking when it ends")
            return Result.success()
        }
        // Cleared before scanning, so a call that connects during the scan still gets its own check.
        CallState.clearConnected(applicationContext)
        CallState.clearPaused(applicationContext)
        FolderScan.run(applicationContext)
        return Result.success()
    }
}

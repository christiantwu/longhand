package io.github.christiantwu.longhand.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.christiantwu.longhand.R
import io.github.christiantwu.longhand.data.Settings
import io.github.christiantwu.longhand.engine.Models
import io.github.christiantwu.longhand.ui.MainActivity
import java.util.concurrent.TimeUnit

object Work {
    const val SCAN_PERIODIC = "scan-periodic"
    const val SCAN_NOW = "scan-now"
    const val TRANSCRIBE = "transcribe"
    const val VOICE_MATCH = "voice-match"
    const val AFTER_CALL = "after-call"

    /**
     * How long after a call ends to look for its recording. Longer than the scan's
     * stability window (ScanDiff.STABLE_AFTER_MS), so the finished file counts as complete.
     */
    const val AFTER_CALL_DELAY_MS = 75_000L

    /** On battery, automatic runs only process calls this recent; older ones wait for the charger. */
    const val RECENT_WINDOW_MS = 24 * 3600_000L

    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_DONE = "done"
    const val NOTIF_PROGRESS = 1
    const val NOTIF_DOWNLOAD = 2
    const val NOTIF_DONE = 3

    /** Checks the folder every 15 minutes (the shortest interval Android allows). */
    fun schedulePeriodicScan(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SCAN_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FolderScanWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SCAN_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FolderScanWorker>().build(),
        )
    }

    /**
     * Starts processing now; TranscribeWorker takes what the battery rules allow, plus the
     * recordings the user asked for. A running job picks up new recordings before it finishes,
     * so it's left alone; a [followUp] (a user request) queues one more run after it, in case it
     * was just finishing.
     *
     * There's deliberately no WorkManager charging constraint: it reads the battery differently
     * from [CallState.onCharger] (battery protection holding at 80% reads as not charging), so
     * the folder scan, which runs every 15 minutes, decides instead whether the phone is plugged in.
     */
    fun enqueueTranscribe(context: Context, followUp: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork(TRANSCRIBE).get()
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        val queued = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
        if (running) {
            if (followUp && !queued) wm.enqueueUniqueWork(TRANSCRIBE, ExistingWorkPolicy.APPEND_OR_REPLACE, request.build())
        } else {
            // Expedited work starts right away instead of waiting for Android's battery batching.
            wm.enqueueUniqueWork(TRANSCRIBE, ExistingWorkPolicy.REPLACE,
                request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        }
    }

    /**
     * Whether a user action that respects "Only while charging" (a correction, queuing a skipped
     * call) may go ahead now. If not, its recordings simply wait for the charger.
     */
    suspend fun mayRunNow(context: Context): Boolean =
        !Settings(context).current().chargingOnly || CallState.onCharger(context)

    /** A call just ended: check the folder once its recording is finished (see AfterCallWorker). */
    fun processAfterCall(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            AFTER_CALL,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AfterCallWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    fun downloadName(set: Models.Set) = "model-download-${set.name.lowercase()}"

    /** Which networks a model download may use. */
    enum class DownloadNetwork { UNMETERED, WIFI, ANY }

    /**
     * Downloads wait for Wi-Fi (an unmetered network), so a dropped connection never carries on
     * over mobile data. The user can choose a metered Wi-Fi ([DownloadNetwork.WIFI], which still
     * never falls back to mobile data) or any network ([DownloadNetwork.ANY]); either replaces the
     * waiting job and resumes from the bytes already downloaded.
     */
    fun downloadModels(context: Context, set: Models.Set, network: DownloadNetwork = DownloadNetwork.UNMETERED) {
        val constraints = Constraints.Builder().apply {
            when (network) {
                DownloadNetwork.UNMETERED -> setRequiredNetworkType(NetworkType.UNMETERED)
                DownloadNetwork.WIFI -> setRequiredNetworkRequest(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build(),
                    NetworkType.CONNECTED,
                )
                DownloadNetwork.ANY -> setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            downloadName(set),
            if (network == DownloadNetwork.UNMETERED) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(ModelDownloadWorker.SET to set.name))
                .setConstraints(constraints)
                .build(),
        )
    }

    /** Re-labels "You" across transcripts after the user confirms their voice. */
    fun matchVoices(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            VOICE_MATCH,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<VoiceMatchWorker>().build(),
        )
    }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Transcription progress", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished transcripts", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun progressNotification(context: Context, title: String, text: String?, progress: Float?): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(context))
            .apply {
                if (progress == null) setProgress(0, 0, true)
                else setProgress(1000, (progress * 1000).toInt(), false)
            }
            .build()

    fun foregroundInfo(id: Int, notification: Notification, longProcessing: Boolean): ForegroundInfo {
        val type = if (longProcessing && Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(id, notification, type)
    }

    /** @param titles one line per finished call, e.g. "Call with Jordan Ellis regarding Harbor Road building materials". */
    fun notifyDone(context: Context, titles: List<String>) {
        if (titles.isEmpty()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val builder = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
        if (titles.size == 1) {
            builder.setContentTitle("Call transcribed").setContentText(titles[0])
                .setStyle(NotificationCompat.BigTextStyle().bigText(titles[0]))
        } else {
            val style = NotificationCompat.InboxStyle()
            titles.take(6).forEach { style.addLine(it) }
            builder.setContentTitle("${titles.size} calls transcribed").setContentText(titles[0]).setStyle(style)
        }
        nm.notify(NOTIF_DONE, builder.build())
    }
}

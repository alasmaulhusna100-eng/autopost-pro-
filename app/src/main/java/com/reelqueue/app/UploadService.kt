package com.reelqueue.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UploadService : Service() {

    companion object {
        const val ACTION_STOP = "com.reelqueue.app.STOP"
        private const val CHANNEL_ID = "upload"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopFlag = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFlag = true
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("প্রস্তুত হচ্ছে…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (job?.isActive != true) {
            stopFlag = false
            job = scope.launch {
                try {
                    runQueue()
                } finally {
                    finishUp()
                }
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 limits dataSync services to ~6h per day; stop gracefully if told to.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopFlag = true
    }

    override fun onDestroy() {
        stopFlag = true
        super.onDestroy()
    }

    private fun runQueue() {
        JobState.running.value = true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reelqueue:upload").apply {
            acquire(6 * 60 * 60 * 1000L)
        }

        JobState.mapAll {
            if (it.status == Status.FAILED || it.status == Status.UPLOADING)
                it.copy(status = Status.PENDING, message = "", progress = 0)
            else it
        }

        val uploader = FbUploader(applicationContext, Prefs.pageId(this), Prefs.token(this))
        val total = JobState.items.value.size
        var cursor = 0L

        for (idx in 0 until total) {
            if (stopFlag) break
            val item = JobState.items.value[idx]
            if (item.status == Status.SCHEDULED) {
                cursor = maxOf(cursor, item.publishAtSec)
                continue
            }

            // Facebook needs the time to be at least 10 minutes ahead.
            val earliest = System.currentTimeMillis() / 1000 + 11 * 60
            var at = item.publishAtSec
            if (at < earliest) {
                at = if (cursor == 0L) earliest
                else maxOf(earliest, cursor + JobState.intervalSec.value)
            }

            JobState.update(idx) {
                it.copy(status = Status.UPLOADING, publishAtSec = at, message = "", progress = 0)
            }
            notify("ভিডিও ${idx + 1}/$total আপলোড হচ্ছে…")

            try {
                uploader.upload(item, at, { stopFlag }) { p ->
                    JobState.update(idx) { it.copy(progress = p) }
                }
                JobState.update(idx) {
                    it.copy(status = Status.SCHEDULED, progress = 100, message = "ফেসবুকে শিডিউল হয়েছে")
                }
                cursor = at
            } catch (e: FbException) {
                JobState.update(idx) {
                    it.copy(status = Status.FAILED, message = e.message ?: "ব্যর্থ")
                }
                if (e.fatal) break
            } catch (e: Exception) {
                JobState.update(idx) {
                    it.copy(status = Status.FAILED, message = e.message ?: "ব্যর্থ")
                }
            }
        }
    }

    private fun finishUp() {
        JobState.running.value = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        val list = JobState.items.value
        val ok = list.count { it.status == Status.SCHEDULED }
        val bad = list.count { it.status == Status.FAILED }
        val left = list.count { it.status == Status.PENDING }
        stopForeground(STOP_FOREGROUND_REMOVE)

        val summary = "শিডিউল হয়েছে: $ok, ব্যর্থ: $bad, বাকি: $left"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            DONE_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("ReelQueue")
                .setContentText(summary)
                .setAutoCancel(true)
                .build()
        )
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ভিডিও আপলোড", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("ReelQueue")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notify(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}

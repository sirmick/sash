package s1m.hwfido2provider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread
import s1m.hwfido2provider.vault.SyncEngine

/**
 * Keeps Syncthing alive.
 *
 * A foreground service and its permanent notification are the honest cost here:
 * Android will kill a background process mid-sync, and there is no way around
 * that. For a sync app it is at least a truthful notification, and constraining
 * sync to charging and WiFi means it is not running most of the time.
 */
class SyncService : Service() {
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (process != null) return START_STICKY

        startForeground(
            NOTIFICATION_ID,
            notification(getString(R.string.latch_sync_running)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        process = try {
            SyncEngine.start(this).also { drainLogs(it) }
        } catch (e: Exception) {
            Log.e(TAG, "sync: failed to start: ${e.message}", e)
            stopSelf()
            null
        }
        return START_STICKY
    }

    override fun onDestroy() {
        SyncEngine.stop(process)
        process = null
        super.onDestroy()
    }

    /**
     * Syncthing's output goes to logcat rather than nowhere. A daemon whose
     * failures are invisible is a daemon that appears to work right up until
     * someone checks whether anything synced.
     */
    private fun drainLogs(p: Process) = thread(isDaemon = true, name = "syncthing-log") {
        runCatching {
            p.inputStream.bufferedReader().forEachLine { Log.i(TAG, "syncthing: $it") }
        }
        Log.w(TAG, "sync: process ended (exit=${runCatching { p.exitValue() }.getOrNull()})")
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.latch_sync_channel), NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.latch_sync_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, VaultActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    companion object {
        private const val TAG = "latch"
        private const val CHANNEL = "latch.sync"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}

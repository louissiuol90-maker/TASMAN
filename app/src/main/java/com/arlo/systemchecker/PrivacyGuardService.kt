package com.arlo.systemchecker

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that polls AppOps every 5 seconds.
 * No audio. Alerts are visual (banner in-app, alert log, silent
 * notification) plus optional vibration from the notification channel.
 *
 * The service does NOT intercept anything in real time — see
 * PrivacyGuard for the honest polling limits.
 */
class PrivacyGuardService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var lastScanMillis: Long = System.currentTimeMillis()
    private val seen = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PrivacyGuard.createNotificationChannel(this)
        startForeground(1, runningNotification())
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val found = PrivacyGuard.scanRecentAccess(this@PrivacyGuardService, lastScanMillis)
                lastScanMillis = now
                for (alert in found) {
                    val key = "${alert.packageName}|${alert.op}|${alert.timeMillis}"
                    if (!seen.add(key)) continue
                    PrivacyAlertStore.add(alert)
                    if (!alert.trusted) {
                        notifyAlert(alert)
                    }
                }
                delay(5_000L)
            }
        }
    }

    private fun runningNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, PrivacyGuard.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("System Checker — Privacy Guard running")
            .setContentText("Watching mic, camera and message-log access.")
            .setContentIntent(pending)
            .setOngoing(true)
            .setSound(null)
            .build()
    }

    private fun notifyAlert(alert: PrivacyAlert) {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, PrivacyGuard.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Privacy Guard: ${alert.label} used ${alert.op}")
            .setContentText(alert.reason)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setSound(null)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(alert.packageName.hashCode() + alert.op.hashCode(), notification)
    }
}

/** In-memory alert log, shared between the service and MainActivity. */
object PrivacyAlertStore {
    private val alerts = mutableListOf<PrivacyAlert>()
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun add(alert: PrivacyAlert) {
        alerts.add(0, alert)
        if (alerts.size > 200) alerts.subList(200, alerts.size).clear()
        listeners.toList().forEach { it() }
    }

    @Synchronized
    fun snapshot(): List<PrivacyAlert> = alerts.toList()

    @Synchronized
    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    @Synchronized
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
}

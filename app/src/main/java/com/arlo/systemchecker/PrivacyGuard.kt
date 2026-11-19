package com.arlo.systemchecker

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Privacy Guard — view-only alerts.
 *
 * What it does:
 *  - Polls AppOpsManager.getPackagesForOps() for recent use of
 *    CAMERA, RECORD_AUDIO, READ_SMS and READ_CALL_LOG by other apps.
 *  - "Message logs" here means SMS and call-log access. This app does
 *    NOT read any message or call log itself, and requests no
 *    READ_SMS / READ_CALL_LOG permission.
 *  - Trusted = (1) system app, or (2) installed by the Play Store
 *    (installer package "com.android.vending").
 *    There is NO Android API flag for "Google-endorsed developer".
 *    Play Store installer is the closest verifiable proxy, and the UI
 *    says so. A sideloaded app, an unverified installer, or a web
 *    page is therefore NOT trusted and triggers an alert.
 *  - Trusted access is logged as allowed, with no alert.
 *  - Non-trusted access creates a PrivacyAlert: red banner in the app,
 *    an entry in the alert log, and a silent notification.
 *    NO audio anywhere: the notification channel has no sound, and
 *    this app contains no MediaPlayer / SoundPool / TextToSpeech.
 *
 * Honest limits — this matters:
 *  - This is polling, not real-time interception. Android does not
 *    let an ordinary app intercept another app's mic/camera use as
 *    it happens. Polling every few seconds (and Android 12+'s own
 *    green mic/camera dot / Privacy Dashboard) is the available
 *    mechanism. An access that starts and stops between polls can
 *    be missed, and AppOps times are coarse-grained.
 *  - getPackagesForOps() requires the GET_APP_OPS_STATS permission,
 *    which Android defines as signature|privileged. An ordinary
 *    sideloaded app may be denied it with a SecurityException.
 *    The code catches that, records it in lastError, and keeps
 *    running without fabricating alerts. On a stock phone, grant
 *    it via ADB if you want this path to work (see README):
 *    `adb shell pm grant com.arlo.systemchecker android.permission.GET_APP_OPS_STATS`
 *    — that grant is not guaranteed on all devices. Without it,
 *    use Android's own Privacy Dashboard as ground truth.
 *  - OpEntry.getOp() returns an int op code, not a string — the
 *    watched ops below are int constants, mapped to labels here.
 */
data class PrivacyAlert(
    val packageName: String,
    val label: String,
    val op: String,              // "Camera", "Microphone", "SMS log", "Call log"
    val timeMillis: Long,
    val trusted: Boolean,
    val reason: String
)

object PrivacyGuard {

    const val CHANNEL_ID = "privacy_guard_alerts"

    /** Int op code -> human label. OpEntry.getOp() is an int. */
    val WATCHED_OPS: Map<Int, String> = mapOf(
        AppOpsManager.OP_CAMERA to "Camera",
        AppOpsManager.OP_RECORD_AUDIO to "Microphone",
        AppOpsManager.OP_READ_SMS to "SMS log",
        AppOpsManager.OP_READ_CALL_LOG to "Call log"
    )

    /** Last AppOps error, if any — shown by MainActivity instead of fake data. */
    @Volatile
    var lastError: String? = null
        private set

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Privacy Guard alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a non-trusted app accesses the mic, camera or message logs."
            setSound(null, null)          // No audio, by design (Louis: no chime/beep).
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 150, 100, 150)
        }
        manager.createNotificationChannel(channel)
    }

    /** Trusted = system app, or installed by the Play Store. See class doc. */
    fun isTrusted(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull() ?: return false
        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem) return true
        val installer = runCatching { pm.getInstallerPackageName(packageName) }.getOrNull()
        return installer == "com.android.vending"
    }

    fun labelFor(context: Context, packageName: String): String {
        val pm = context.packageManager
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrElse { packageName }
    }

    /**
     * Scan AppOps for accesses at or after [sinceMillis].
     * Returns both non-trusted alerts and trusted allowed entries.
     * Callers should de-duplicate by (packageName, op, timeMillis).
     * Returns an empty list (and sets lastError) if AppOps access
     * is denied — it never fabricates an alert.
     */
    @Suppress("DEPRECATION")
    fun scanRecentAccess(
        context: Context,
        sinceMillis: Long
    ): List<PrivacyAlert> {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val results = mutableListOf<PrivacyAlert>()
        try {
            val packageOpsList = appOps.getPackagesForOps(
                WATCHED_OPS.keys.toIntArray()
            ) ?: return emptyList()
            lastError = null
            for (packageOps in packageOpsList) {
                val pkg = packageOps.packageName ?: continue
                if (pkg == context.packageName) continue
                val opEntries: List<AppOpsManager.OpEntry> = packageOps.getOps()
                for (opEntry in opEntries) {
                    val opName = WATCHED_OPS[opEntry.op] ?: continue
                    val lastTime = opEntry.time
                    // Also surface an op that is running right now,
                    // even if its recorded time is slightly old.
                    if (lastTime < sinceMillis && !opEntry.isRunning) continue
                    val trusted = isTrusted(context, pkg)
                    results.add(
                        PrivacyAlert(
                            packageName = pkg,
                            label = labelFor(context, pkg),
                            op = opName,
                            timeMillis = lastTime,
                            trusted = trusted,
                            reason = if (trusted) {
                                "Trusted: system app or Play Store install — logged, no alert."
                            } else {
                                "Non-trusted app accessed $opName. " +
                                    "Not a system app and not installed by the Play Store."
                            }
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            lastError = "AppOps access denied (GET_APP_OPS_STATS is a system permission). " +
                "Privacy Guard cannot read other apps' mic/camera history on this device. " +
                "Use Android Settings > Privacy > Privacy Dashboard as ground truth. " +
                "Detail: ${e.message}"
        }
        return results.sortedByDescending { it.timeMillis }
    }
}

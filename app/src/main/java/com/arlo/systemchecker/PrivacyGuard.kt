package com.arlo.systemchecker

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Privacy Guard — view-only alerts, using only public Android APIs.
 *
 * What it does:
 *  - Polls AppOpsManager.isOpActive() for current use of CAMERA,
 *    RECORD_AUDIO, READ_SMS and READ_CALL_LOG by other apps.
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
 * Honest limits — this matters, and this is why the earlier version
 * did not compile:
 *  - The earlier code used AppOpsManager.OP_CAMERA (int), OP_RECORD_AUDIO,
 *    OP_READ_SMS, OP_READ_CALL_LOG, getPackagesForOps() and OpEntry.
 *    Those are NOT in the public Android SDK. The public SDK only
 *    exposes the string constants OPSTR_CAMERA, OPSTR_RECORD_AUDIO,
 *    OPSTR_READ_SMS and OPSTR_READ_CALL_LOG, and the public methods
 *    checkOpNoThrow() / isOpActive(). Android's own docs say the
 *    tracked history "can only be read by system components", so an
 *    ordinary app cannot read other apps' past mic/camera history.
 *  - This version therefore detects use that is ACTIVE at poll time
 *    (isOpActive, Android 10+), polling every few seconds. An access
 *    that starts and stops between polls can be missed. For past
 *    history, use Android Settings > Privacy > Privacy Dashboard
 *    as ground truth. The app never fabricates an alert.
 *  - isOpActive() needs Android 10 (API 29) or later. On older
 *    versions scanRecentAccess returns an empty list and records
 *    that in lastError.
 */
data class PrivacyAlert(
    val packageName: String,
    val label: String,
    val op: String,
    val timeMillis: Long,
    val trusted: Boolean,
    val reason: String
)

object PrivacyGuard {

    const val CHANNEL_ID = "privacy_guard_alerts"

    /** Public string op -> human label. */
    val WATCHED_OPS: Map<String, String> = mapOf(
        AppOpsManager.OPSTR_CAMERA to "Camera",
        AppOpsManager.OPSTR_RECORD_AUDIO to "Microphone",
        AppOpsManager.OPSTR_READ_SMS to "SMS log",
        AppOpsManager.OPSTR_READ_CALL_LOG to "Call log"
    )

    /** Last Privacy Guard error, if any — shown by MainActivity instead of fake data. */
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
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 150, 100, 150)
        }
        manager.createNotificationChannel(channel)
    }

    /** Trusted = system app, or installed by the Play Store. See class doc. */
    @Suppress("DEPRECATION")
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
     * Scan for mic/camera/SMS/call-log use that is active right now.
     * Returns both non-trusted alerts and trusted allowed entries.
     * Callers should de-duplicate by (packageName, op, timeMillis).
     * Returns an empty list if active-use detection is unavailable —
     * it never fabricates an alert.
     */
    @Suppress("DEPRECATION")
    fun scanRecentAccess(
        context: Context,
        sinceMillis: Long
    ): List<PrivacyAlert> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            lastError = "Privacy Guard active-use detection needs Android 10 or later. " +
                "Use Android Settings > Privacy > Privacy Dashboard as ground truth."
            return emptyList()
        }
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val pm = context.packageManager
        val results = mutableListOf<PrivacyAlert>()
        try {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            lastError = null
            val now = System.currentTimeMillis()
            for (info in installed) {
                val pkg = info.packageName ?: continue
                if (pkg == context.packageName) continue
                for ((opStr, opName) in WATCHED_OPS) {
                    val active = runCatching {
                        appOps.isOpActive(opStr, info.uid, pkg)
                    }.getOrNull() ?: continue
                    if (!active) continue
                    val trusted = isTrusted(context, pkg)
                    results.add(
                        PrivacyAlert(
                            packageName = pkg,
                            label = labelFor(context, pkg),
                            op = opName,
                            timeMillis = now,
                            trusted = trusted,
                            reason = if (trusted) {
                                "Trusted: system app or Play Store install — logged, no alert."
                            } else {
                                "Non-trusted app is using $opName right now. " +
                                    "Not a system app and not installed by the Play Store."
                            }
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            lastError = "Privacy Guard could not check other apps' mic/camera use on this device. " +
                "Use Android Settings > Privacy > Privacy Dashboard as ground truth. " +
                "Detail: ${e.message}"
        }
        return results.sortedByDescending { it.timeMillis }
    }
}

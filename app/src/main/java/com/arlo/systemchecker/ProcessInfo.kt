package com.arlo.systemchecker

/**
 * One row in the process list.
 *
 * HONEST DATA NOTE — read this before trusting a row:
 * Modern Android (10+) does not let an ordinary app enumerate every
 * other app's process, or read per-process CPU / memory for other apps,
 * without system / root privileges. See ProcessRepository for the exact
 * sources used. Fields that Android will not give us are null, and the
 * UI shows "—" for them instead of inventing a number.
 */
data class ProcessInfo(
    val packageName: String,
    val label: String,
    val pid: Int?,                 // Only known for our own process / running services
    val isForeground: Boolean,
    val status: Status,
    val cpuPercent: Float?,        // Only for our own process; null for other apps
    val memoryMb: Long?,           // Only for our own process; null for other apps
    val startTimeMillis: Long?,    // First use / last use from UsageStats, when known
    val lastUsedMillis: Long?,
    val isSystemApp: Boolean,
    val installer: String?,        // e.g. "com.android.vending" = Play Store
    val source: String             // Which Android API this row came from
) {
    enum class Status { RUNNING, IDLE, SLEEPING, UNKNOWN }

    val scopeLabel: String get() = if (isForeground) "Foreground" else "Background"
    val isTrusted: Boolean get() = isSystemApp || installer == "com.android.vending"
}

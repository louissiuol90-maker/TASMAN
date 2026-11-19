package com.arlo.systemchecker

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock

/**
 * Best-effort process enumeration on modern Android — view-only.
 *
 * Sources, in order:
 *  1. ActivityManager.getRunningAppProcesses() — on Android 10+ this
 *     returns mainly our own process and a limited set of others.
 *  2. ActivityManager.getRunningServices() — services the OS reports.
 *  3. UsageStatsManager.queryUsageStats() — apps used in the last
 *     24 h, with lastTimeUsed. Requires PACKAGE_USAGE_STATS, granted
 *     by the user in Settings > Usage access. Without it, this source
 *     is empty and the list is much shorter — the UI says so.
 *
 * What we deliberately do NOT do:
 *  - No /proc/<pid> scanning of other apps, no reading other apps'
 *    memory, no root, no fabricated CPU / memory numbers.
 *  - cpuPercent / memoryMb are filled in ONLY for our own process,
 *    read from our own /proc/<pid>/stat and Debug.MemoryInfo.
 *    For every other row they are null and the UI shows "—".
 */
class ProcessRepository(private val context: Context) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val packageManager: PackageManager = context.packageManager

    // Own-process CPU accounting, for the one process we can measure.
    private var lastCpuTimeMs: Long = 0L
    private var lastWallMs: Long = 0L

    fun hasUsageAccess(): Boolean {
        val usageStats =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usageStats.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now
        )
        return !stats.isNullOrEmpty()
    }

    fun systemMemory(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info
    }

    fun listProcesses(): List<ProcessInfo> {
        val byPackage = linkedMapOf<String, ProcessInfo>()
        val now = System.currentTimeMillis()

        // 1. Running app processes (limited by Android 10+ — see class doc).
        runCatching {
            activityManager.runningAppProcesses?.forEach { running ->
                val pkg = running.processName ?: return@forEach
                val info = appInfoFor(pkg) ?: return@forEach
                val foreground = running.importance <=
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                byPackage[pkg] = buildRow(
                    packageName = pkg,
                    label = labelFor(info),
                    pid = running.pid,
                    isForeground = foreground,
                    status = when (running.importance) {
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ->
                            ProcessInfo.Status.RUNNING
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND ->
                            ProcessInfo.Status.IDLE
                        else -> ProcessInfo.Status.SLEEPING
                    },
                    isSystemApp = info.isSystemApp(),
                    installer = installerFor(pkg),
                    source = "ActivityManager"
                )
            }
        }

        // 2. Running services — these are the background workers the OS reports.
        runCatching {
            @Suppress("DEPRECATION")
            activityManager.getRunningServices(Integer.MAX_VALUE)?.forEach { service ->
                val pkg = service.service.packageName ?: return@forEach
                if (byPackage.containsKey(pkg)) return@forEach
                val info = appInfoFor(pkg) ?: return@forEach
                byPackage[pkg] = buildRow(
                    packageName = pkg,
                    label = labelFor(info),
                    pid = service.pid,
                    isForeground = service.foreground,
                    status = if (service.foreground) ProcessInfo.Status.RUNNING
                        else ProcessInfo.Status.IDLE,
                    isSystemApp = info.isSystemApp(),
                    installer = installerFor(pkg),
                    source = "RunningServices"
                )
            }
        }

        // 3. UsageStats — the widest view available without root:
        //    every app used in the last 24 hours.
        runCatching {
            val usageStats =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usageStats.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 24 * 60 * 60 * 1000L, now
            )
            stats?.forEach { usage ->
                val pkg = usage.packageName
                if (byPackage.containsKey(pkg)) return@forEach
                val info = appInfoFor(pkg) ?: return@forEach
                val recentlyUsed = now - usage.lastTimeUsed < 5 * 60 * 1000L
                byPackage[pkg] = buildRow(
                    packageName = pkg,
                    label = labelFor(info),
                    pid = null,
                    isForeground = recentlyUsed,
                    status = when {
                        recentlyUsed -> ProcessInfo.Status.RUNNING
                        usage.lastTimeUsed > 0 -> ProcessInfo.Status.IDLE
                        else -> ProcessInfo.Status.UNKNOWN
                    },
                    startTimeMillis = usage.firstTimeStamp.takeIf { it > 0 },
                    lastUsedMillis = usage.lastTimeUsed.takeIf { it > 0 },
                    isSystemApp = info.isSystemApp(),
                    installer = installerFor(pkg),
                    source = "UsageStats"
                )
            }
        }

        return byPackage.values
            .sortedWith(
                compareByDescending<ProcessInfo> { it.isForeground }
                    .thenBy { it.label.lowercase() }
            )
    }

    private fun buildRow(
        packageName: String,
        label: String,
        pid: Int?,
        isForeground: Boolean,
        status: ProcessInfo.Status,
        isSystemApp: Boolean,
        installer: String?,
        source: String,
        startTimeMillis: Long? = null,
        lastUsedMillis: Long? = null
    ): ProcessInfo {
        val isOwn = packageName == context.packageName
        val (cpu, mem) = if (isOwn) ownCpuAndMemory() else null to null
        return ProcessInfo(
            packageName = packageName,
            label = label,
            pid = pid,
            isForeground = isForeground,
            status = status,
            cpuPercent = cpu,
            memoryMb = mem,
            startTimeMillis = startTimeMillis,
            lastUsedMillis = lastUsedMillis,
            isSystemApp = isSystemApp,
            installer = installer,
            source = source
        )
    }

    /** Real CPU / memory, but only for our own process. */
    private fun ownCpuAndMemory(): Pair<Float?, Long?> {
        val pid = Process.myPid()
        val cpu = runCatching {
            // /proc/<pid>/stat fields 14 (utime) + 15 (stime), in clock ticks.
            val stat = java.io.File("/proc/$pid/stat").readText()
                .substringAfter(") ").split(" ")
            val ticks = (stat[11].toLong() + stat[12].toLong()) * 10L // ~100 Hz
            val wall = SystemClock.uptimeMillis()
            val cpuPercent = if (lastCpuTimeMs > 0 && wall > lastWallMs) {
                (ticks - lastCpuTimeMs).toFloat() / (wall - lastWallMs) * 100f
            } else null
            lastCpuTimeMs = ticks
            lastWallMs = wall
            cpuPercent?.coerceIn(0f, 100f)
        }.getOrNull()
        val memMb = runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            (info.totalPss / 1024L)
        }.getOrNull()
        return cpu to memMb
    }

    private fun appInfoFor(packageName: String): ApplicationInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
        }.getOrNull()

    private fun labelFor(info: ApplicationInfo): String =
        runCatching { packageManager.getApplicationLabel(info).toString() }
            .getOrElse { info.packageName }

    private fun installerFor(packageName: String): String? =
        runCatching { packageManager.getInstallerPackageName(packageName) }.getOrNull()

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}

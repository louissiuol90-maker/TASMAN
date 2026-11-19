package com.arlo.systemchecker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cell tower logging — view-only, public Android APIs only.
 *
 * What it records, when the serving cell / network type changes:
 *  - timestamp, network type (2G / 3G / 4G / 5G), cell ID (CI/NCI),
 *    TAC (LTE/NR) or LAC (GSM/WCDMA) where the OS reports it,
 *    signal strength in dBm, and the operator name.
 *  - Each change is kept in an in-memory list (shown in MainActivity)
 *    and appended to a plain-text log file in the app's private
 *    storage: filesDir/cell_tower_log.txt — no network upload.
 *
 * Honest limits — read this before trusting the log:
 *  - Android does NOT expose cell-tower credentials, certificates or
 *    any way for an app to verify that a tower is genuine. There is
 *    no public API that proves a tower is authentic, and no app can
 *    get one without system / root access. This log can show a
 *    *change* — e.g. a sudden drop to 2G, or a new cell ID — which is
 *    a clue worth noticing, not proof of a fake tower (a "stingray").
 *    A real tower can also change ID, and a fake tower can mimic 4G/5G.
 *  - Cell identity is only available with location permission
 *    (ACCESS_FINE_LOCATION, Android 10+). Without it, getAllCellInfo()
 *    returns null / empty and this logger records that instead of
 *    inventing a cell ID. READ_PHONE_STATE is used for the network
 *    type / operator name.
 *  - The OS may return no cell info at all (airplane mode, no SIM,
 *    or the radio not reporting). That is recorded as "no cell info",
 *    never as a made-up tower.
 *  - Signal strength (dBm) changes constantly; a new log entry is
 *    only made when the tower identity or network type changes,
 *    so the log stays readable instead of flooding with dBm noise.
 *  - Uses TelephonyCallback (API 31+) with a PhoneStateListener
 *    fallback for older Android, plus a poll from MainActivity's
 *    refresh loop so a missed callback still gets picked up.
 */
data class CellTowerEvent(
    val timeMillis: Long,
    val networkType: String,   // "2G", "3G", "4G", "5G", "Unknown"
    val cellId: String,        // CI / NCI / base-station id, or "—"
    val areaCode: String,      // TAC / LAC, or "—"
    val signalDbm: Int?,       // null when the OS does not report it
    val operator: String
) {
    /** Identity key: a new entry is logged only when this changes. */
    fun identityKey(): String = "$networkType|$cellId|$areaCode|$operator"

    fun displayLine(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
        val dbm = signalDbm?.let { "$it dBm" } ?: "—"
        return "$time — $networkType — cell $cellId — TAC/LAC $areaCode — $dbm — $operator"
    }
}

/** In-memory tower log, shared between the logger and MainActivity. */
object CellTowerStore {
    private val events = mutableListOf<CellTowerEvent>()
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun add(event: CellTowerEvent) {
        events.add(0, event)
        if (events.size > 200) events.subList(200, events.size).clear()
        listeners.toList().forEach { it() }
    }

    @Synchronized
    fun snapshot(): List<CellTowerEvent> = events.toList()

    @Synchronized
    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    @Synchronized
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
}

class CellTowerLogger(private val context: Context) {

    private val telephony: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    @Volatile
    var lastError: String? = null
        private set

    private var lastIdentityKey: String? = null
    private var callback31: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    private var started = false

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /** Register the change callback (API 31+) or the legacy listener. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        val tm = telephony ?: run {
            lastError = "No telephony service on this device."
            return
        }
        // Log the current tower straight away, so the list is not empty.
        checkNow()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                        checkNow()
                    }
                }
                callback31 = cb
                tm.registerTelephonyCallback(context.mainExecutor, cb)
            } else {
                val listener = object : PhoneStateListener() {
                    override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                        checkNow()
                    }
                }
                legacyListener = listener
                tm.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
            }
        } catch (e: SecurityException) {
            lastError = "Cell tower logging needs location permission. " +
                "Grant it to see cell IDs. Detail: ${e.message}"
        } catch (e: Exception) {
            lastError = "Could not start cell tower logging. Detail: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!started) return
        started = false
        val tm = telephony ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (callback31 as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
                callback31 = null
            } else {
                legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
                legacyListener = null
            }
        } catch (_: Exception) {
            // Best effort on teardown; nothing to show the user here.
        }
    }

    /**
     * Take one snapshot and log it if the tower identity changed.
     * Safe to call from MainActivity's refresh loop; never throws.
     */
    @SuppressLint("MissingPermission")
    fun checkNow() {
        val event = snapshotNow() ?: return
        val key = event.identityKey()
        if (key == lastIdentityKey) return
        lastIdentityKey = key
        CellTowerStore.add(event)
        appendToFile(event)
    }

    /** One snapshot of the current serving cell, or null if unavailable. */
    @SuppressLint("MissingPermission")
    fun snapshotNow(): CellTowerEvent? {
        val tm = telephony ?: run {
            lastError = "No telephony service on this device."
            return null
        }
        if (!hasLocationPermission()) {
            lastError = "Cell tower logging needs location permission. " +
                "Grant location access to see cell IDs — Android 10+ hides " +
                "cell identity without it."
            return null
        }
        val infos: List<CellInfo> = try {
            tm.allCellInfo ?: emptyList()
        } catch (e: SecurityException) {
            lastError = "Cell info was blocked by the OS. " +
                "Grant location access to see cell IDs. Detail: ${e.message}"
            return null
        } catch (e: Exception) {
            lastError = "Could not read cell info. Detail: ${e.message}"
            return null
        }
        val registered = infos.firstOrNull { it.isRegistered } ?: infos.firstOrNull()
        if (registered == null) {
            lastError = "No cell info reported by the OS right now " +
                "(airplane mode, no SIM, or the radio is not reporting)."
            return null
        }
        lastError = null
        val (cellId, areaCode) = cellIdentity(registered)
        val dbm = runCatching { registered.cellSignalStrength.dbm }.getOrNull()
        val operator = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "—"
        return CellTowerEvent(
            timeMillis = System.currentTimeMillis(),
            networkType = networkTypeLabel(tm),
            cellId = cellId,
            areaCode = areaCode,
            signalDbm = dbm,
            operator = operator
        )
    }

    private fun cellIdentity(info: CellInfo): Pair<String, String> {
        return when (info) {
            is CellInfoLte -> {
                val id = info.cellIdentity
                "${id.ci}" to "${id.tac}"
            }
            is CellInfoNr -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val id = info.cellIdentity as? android.telephony.CellIdentityNr
                    if (id != null) "${id.nci}" to "${id.tac}" else "—" to "—"
                } else "—" to "—"
            }
            is CellInfoGsm -> {
                val id = info.cellIdentity
                "${id.cid}" to "${id.lac}"
            }
            is CellInfoWcdma -> {
                val id = info.cellIdentity
                "${id.cid}" to "${id.lac}"
            }
            is CellInfoCdma -> {
                val id = info.cellIdentity
                "${id.basestationId}" to "${id.networkId}"
            }
            else -> "—" to "—"
        }
    }

    @Suppress("DEPRECATION")
    private fun networkTypeLabel(tm: TelephonyManager): String {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tm.dataNetworkType
        } else {
            @Suppress("DEPRECATION")
            tm.networkType
        }
        return when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> "Unknown"
        }
    }

    private fun appendToFile(event: CellTowerEvent) {
        try {
            val file = File(context.filesDir, "cell_tower_log.txt")
            file.appendText(event.displayLine() + "\n")
        } catch (_: Exception) {
            // File logging is best-effort; the in-app list is the main log.
        }
    }
}

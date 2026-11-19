package com.arlo.systemchecker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.arlo.systemchecker.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * System Checker — Android only, view-only.
 *
 * - Lists ALL processes the OS will show us (foreground + background),
 *   auto-refreshing every 2 seconds. See ProcessRepository for the
 *   honest Android 10+ limits: this is NOT a complete kernel process
 *   list, and per-process CPU/memory for other apps is "—".
 * - Window mode: a tiny semi-transparent floating mini-bar
 *   ("Checker") drawn over other apps, kept alive by
 *   FloatingBarService. Tapping it opens the full app again.
 *   Picture-in-picture is NOT used — it looked like a video /
 *   YouTube window. Needs "Draw over other apps" permission.
 *   The activity is also resizeableActivity=true, so on tablets,
 *   Samsung DeX / pop-up view and other freeform environments it
 *   can be dragged and resized like a window.
 * - Privacy Guard: alerts for mic/camera/SMS/call-log access by
 *   non-trusted apps. Trusted = system app or Play Store install.
 * - Cell tower log: serving tower changes (network type, cell ID,
 *   TAC/LAC, signal, operator) via CellTowerLogger. It cannot
 *   verify a tower is genuine — Android exposes no such check.
 * - NO audio anywhere. No chime, beep, or spoken line on open.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ProcessRepository
    private lateinit var cellTowerLogger: CellTowerLogger
    private val adapter = ProcessAdapter()
    private var filter: Filter = Filter.ALL
    private var allProcesses: List<ProcessInfo> = emptyList()

    private enum class Filter { ALL, FOREGROUND, BACKGROUND }

    private val alertListener: () -> Unit = { runOnUiThread { renderAlerts() } }
    private val towerListener: () -> Unit = { runOnUiThread { renderTowerLog() } }

    companion object {
        private const val REQUEST_CELL_PERMISSIONS = 1001
        private val CELL_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProcessRepository(this)
        PrivacyGuard.createNotificationChannel(this)

        binding.processList.layoutManager = LinearLayoutManager(this)
        binding.processList.adapter = adapter

        binding.filterAll.setOnClickListener { setFilter(Filter.ALL) }
        binding.filterForeground.setOnClickListener { setFilter(Filter.FOREGROUND) }
        binding.filterBackground.setOnClickListener { setFilter(Filter.BACKGROUND) }

        binding.windowModeButton.setOnClickListener { launchFloatingBar() }
        binding.fullViewButton.setOnClickListener { exitWindowMode() }
        updateFilterButtons()

        binding.grantUsageAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        // Cell tower logging: view-only, public APIs only. Starts
        // alongside Privacy Guard; see CellTowerLogger for limits.
        cellTowerLogger = CellTowerLogger(this)
        cellTowerLogger.start()
        CellTowerStore.addListener(towerListener)
        binding.grantCellAccessButton.setOnClickListener { requestCellPermissions() }
        if (!hasCellPermissions()) requestCellPermissions()

        // Privacy Guard runs in the foreground service so alerts keep
        // coming while the app is in the background / windowed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, PrivacyGuardService::class.java))
        } else {
            @Suppress("DEPRECATION")
            startService(Intent(this, PrivacyGuardService::class.java))
        }

        PrivacyAlertStore.addListener(alertListener)

        lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(2_000L)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateFloatingBarWarning()
    }

    override fun onDestroy() {
        PrivacyAlertStore.removeListener(alertListener)
        CellTowerStore.removeListener(towerListener)
        if (::cellTowerLogger.isInitialized) cellTowerLogger.stop()
        super.onDestroy()
    }

    private fun hasCellPermissions(): Boolean =
        CELL_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestCellPermissions() {
        requestPermissions(CELL_PERMISSIONS, REQUEST_CELL_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CELL_PERMISSIONS) {
            // Re-snapshot now that the user has answered; the logger
            // records "needs location permission" instead of fake data
            // if they declined.
            if (::cellTowerLogger.isInitialized) cellTowerLogger.checkNow()
            renderTowerLog()
        }
    }

    private fun setFilter(newFilter: Filter) {
        filter = newFilter
        updateFilterButtons()
        renderList()
    }

    /** Selected filter button stands out (solid blue); the others stay quiet. */
    private fun updateFilterButtons() {
        val buttons = mapOf(
            binding.filterAll to (filter == Filter.ALL),
            binding.filterForeground to (filter == Filter.FOREGROUND),
            binding.filterBackground to (filter == Filter.BACKGROUND)
        )
        for ((button, selected) in buttons) {
            styleFilterButton(button, selected)
        }
    }

    private fun styleFilterButton(button: Button, selected: Boolean) {
        if (selected) {
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.filter_selected_bg)
            )
            button.setTextColor(ContextCompat.getColor(this, R.color.filter_selected_text))
        } else {
            button.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.filter_unselected_bg)
            )
            button.setTextColor(ContextCompat.getColor(this, R.color.filter_unselected_text))
        }
    }

    private fun updateFloatingBarWarning() {
        binding.floatingBarWarning.visibility =
            if (Settings.canDrawOverlays(this)) View.GONE else View.GONE
        // Only shown when the user actually taps Window mode without
        // the permission — see launchFloatingBar(). Not nagged on open.
    }

    private fun refresh() {
        if (!repository.hasUsageAccess()) {
            binding.usageAccessWarning.visibility = View.VISIBLE
        } else {
            binding.usageAccessWarning.visibility = View.GONE
        }
        // Cell tower: poll as well as the change callback, so a
        // missed callback still gets picked up. Deduplicated by
        // tower identity inside CellTowerLogger.
        if (::cellTowerLogger.isInitialized) cellTowerLogger.checkNow()
        renderTowerLog()
        allProcesses = repository.listProcesses()
        val mem = repository.systemMemory()
        val usedMb = (mem.totalMem - mem.availMem) / (1024 * 1024)
        val totalMb = mem.totalMem / (1024 * 1024)
        binding.summaryText.text =
            "Processes: ${allProcesses.size}  •  " +
                "Foreground: ${allProcesses.count { it.isForeground }}  •  " +
                "Background: ${allProcesses.count { !it.isForeground }}  •  " +
                "System memory: $usedMb / $totalMb MB"
        renderList()
        renderAlerts()
    }

    private fun renderList() {
        val filtered = when (filter) {
            Filter.ALL -> allProcesses
            Filter.FOREGROUND -> allProcesses.filter { it.isForeground }
            Filter.BACKGROUND -> allProcesses.filter { !it.isForeground }
        }
        adapter.submitList(filtered)
        binding.emptyText.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderTowerLog() {
        if (!::cellTowerLogger.isInitialized) return
        val error = cellTowerLogger.lastError
        if (error != null) {
            binding.cellTowerWarning.visibility = View.VISIBLE
            binding.cellTowerWarning.text = error
        } else {
            binding.cellTowerWarning.visibility = View.GONE
        }
        val events = CellTowerStore.snapshot()
        binding.cellTowerLog.text = if (events.isEmpty()) {
            "No cell tower change recorded yet."
        } else {
            events.take(20).joinToString("\n") { it.displayLine() }
        }
    }

    private fun renderAlerts() {
        val alerts = PrivacyAlertStore.snapshot()
        val latest = alerts.firstOrNull { !it.trusted }
        if (latest != null) {
            binding.alertBanner.visibility = View.VISIBLE
            binding.alertBanner.text =
                "Privacy Guard: ${latest.label} used ${latest.op} — non-trusted app. " +
                    "Tap to see the alert log."
        } else {
            binding.alertBanner.visibility = View.GONE
        }
        binding.alertLog.text = if (alerts.isEmpty()) {
            PrivacyGuard.lastError
                ?: "No mic / camera / message-log access recorded yet."
        } else {
            alerts.take(20).joinToString("\n") { alert ->
                val mark = if (alert.trusted) "ALLOWED" else "ALERT"
                "$mark — ${alert.label} (${alert.packageName}) used ${alert.op}. ${alert.reason}"
            }
        }
    }

    /**
     * Window mode — a tiny semi-transparent floating mini-bar, NOT
     * picture-in-picture. PiP looked like a video / YouTube window
     * and Louis did not want that, so it is gone entirely.
     * The bar is drawn over other apps (SYSTEM_ALERT_WINDOW) by
     * FloatingBarService; tapping it opens the full app again.
     * If the overlay permission is not granted, we say so in-app
     * and send the user to Settings — never silently fail.
     */
    private fun launchFloatingBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            binding.floatingBarWarning.visibility = View.VISIBLE
            Toast.makeText(
                this,
                "Allow “Display over other apps” for System Checker, then tap Window mode again.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        binding.floatingBarWarning.visibility = View.GONE
        FloatingBarService.start(this)
        // Send the app to the background so the mini-bar is visible.
        moveTaskToBack(true)
    }

    private fun exitWindowMode() {
        // Full view: hide the floating mini-bar; the full app is
        // already in front when this button is tapped.
        FloatingBarService.stop(this)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onUsageAccessClick(view: View) {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

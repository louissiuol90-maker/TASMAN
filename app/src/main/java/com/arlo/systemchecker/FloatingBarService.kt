package com.arlo.systemchecker

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Floating mini-bar — the replacement for picture-in-picture.
 *
 * PiP is gone: it looked like a video / YouTube window, which Louis
 * did not want. This is a tiny floating pill instead, drawn over
 * other apps with WindowManager TYPE_APPLICATION_OVERLAY.
 *
 * - Semi-transparent dark pill (~60% opaque, alpha 0x99) so you can
 *   see through it a bit, like the accessibility button. The white
 *   "Checker" text on top is fully opaque, so it stays readable
 *   and tappable.
 * - Small and minimal: just the label and a tap target. Drag it
 *   anywhere; a tap (not a drag) opens the full app and hides the bar.
 * - Kept alive by this foreground service (silent notification).
 *   Needs the "Draw over other apps" permission (SYSTEM_ALERT_WINDOW),
 *   granted in Settings — MainActivity explains if it is not granted.
 * - No audio, no monitoring changes: it is only a shortcut back
 *   into the full app.
 */
class FloatingBarService : Service() {

    private var windowManager: WindowManager? = null
    private var barView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PrivacyGuard.createNotificationChannel(this)
        startForeground(NOTIFICATION_ID, runningNotification())
        showBar()
    }

    override fun onDestroy() {
        removeBar()
        super.onDestroy()
    }

    private fun runningNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, PrivacyGuard.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("System Checker — mini-bar active")
            .setContentText("Tap the Checker bar to open the full app.")
            .setContentIntent(pending)
            .setOngoing(true)
            .setSound(null)
            .build()
    }

    private fun showBar() {
        if (barView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(
                this@FloatingBarService, R.drawable.floating_bar_bg
            )
            setPadding(28, 16, 28, 16)
            elevation = 8f
        }
        val label = TextView(this).apply {
            text = "●  Checker"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        bar.addView(label)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 300
        }

        // Drag to move, tap to open. A tap is a touch that barely moves.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        bar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager?.updateViewLayout(bar, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) openFullApp()
                    true
                }
                else -> false
            }
        }

        barView = bar
        wm.addView(bar, params)
    }

    private fun openFullApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        stopSelf()
    }

    private fun removeBar() {
        try {
            barView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
            // View already gone; nothing to clean up.
        }
        barView = null
        windowManager = null
    }

    companion object {
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            val intent = Intent(context, FloatingBarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBarService::class.java))
        }
    }
}

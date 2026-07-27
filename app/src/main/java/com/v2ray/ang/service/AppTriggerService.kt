package com.v2ray.ang.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.MessageUtil

/**
 * Watches the current foreground app and automatically turns the VPN ON while a
 * user-selected "trigger" app is open, turning it back off when the app is left
 * (unless the VPN was already on beforehand). The foreground polling runs on a
 * background thread so it never janks the UI; only the VPN start/stop calls are
 * dispatched back to the main thread. Requires the Usage Access permission.
 */
class AppTriggerService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var startedByTrigger = false   // we turned VPN ON for an "enable" app
    private var pausedByTrigger = false    // we turned VPN OFF for a "disable" app
    private var lastFg: String? = null

    // The core runs in a separate process, so CoreServiceManager.isRunning() is
    // unreliable here. Track the real VPN state via the same broadcasts the UI uses.
    @Volatile private var vpnRunning = false
    private var receiverRegistered = false
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> vpnRunning = true
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> vpnRunning = false
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try { check() } catch (_: Exception) {}
            bgHandler?.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotif())
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, stateReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
            // Ask the core (if running) to report its current state back to us.
            MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        }
        if (bgThread == null) {
            bgThread = HandlerThread("sw-app-watch").also { it.start() }
            bgHandler = Handler(bgThread!!.looper)
        }
        bgHandler?.removeCallbacks(tick)
        bgHandler?.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        bgHandler?.removeCallbacks(tick)
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        if (receiverRegistered) {
            try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun enableApps(): Set<String> =
        MmkvManager.decodeSettingsStringSet(KEY_TRIGGERS) ?: emptySet()

    private fun disableApps(): Set<String> =
        MmkvManager.decodeSettingsStringSet(KEY_TRIGGERS_OFF) ?: emptySet()

    private fun check() {
        val onSet = enableApps()
        val offSet = disableApps()
        if (onSet.isEmpty() && offSet.isEmpty()) return
        val fg = foregroundApp() ?: return
        if (fg == lastFg) return
        lastFg = fg
        if (fg == packageName) return   // our own app is neutral, don't touch VPN

        val paused = MmkvManager.decodeSettingsStringSet(KEY_PAUSED) ?: emptySet()
        val inOn = onSet.contains(fg) && !paused.contains(fg)
        val inOff = offSet.contains(fg) && !paused.contains(fg)

        // "Enable" list: turn the VPN ON while such an app is open, restoring the
        // previous OFF state on exit. If the VPN was already on, leave it untouched.
        if (inOn && !startedByTrigger) {
            if (!vpnRunning) {
                startedByTrigger = true
                vpnRunning = true
                mainHandler.post { CoreServiceManager.startVService(this) }
            }
        } else if (!inOn && startedByTrigger) {
            startedByTrigger = false
            vpnRunning = false
            mainHandler.post { CoreServiceManager.stopVService(this) }
        }

        // "Disable" list: turn the VPN OFF while such an app is open, restoring the
        // previous ON state on exit. If the VPN was already off, leave it untouched.
        if (inOff && !pausedByTrigger) {
            if (vpnRunning) {
                pausedByTrigger = true
                vpnRunning = false
                mainHandler.post { CoreServiceManager.stopVService(this) }
            }
        } else if (!inOff && pausedByTrigger) {
            pausedByTrigger = false
            vpnRunning = true
            mainHandler.post { CoreServiceManager.startVService(this) }
        }
    }

    private fun foregroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val begin = end - 10_000
        val events = usm.queryEvents(begin, end)
        var pkg: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                pkg = e.packageName
            }
        }
        return pkg
    }

    private fun buildNotif(): Notification {
        val chId = "sidewire_trigger"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "Авто-подключение", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        // A no-op tap target: fires a harmless broadcast that no one receives, so
        // tapping the notification does nothing instead of launching the app
        // (some OEM ROMs, e.g. ColorOS, open the app on tap when no intent is set).
        val noopIntent = Intent("sidewire.trigger.noop").setPackage(packageName)
        val noopFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val noopPending = PendingIntent.getBroadcast(this, 0, noopIntent, noopFlags)

        return NotificationCompat.Builder(this, chId)
            .setContentTitle("Sidewire")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setContentIntent(noopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val KEY_TRIGGERS = "sw_trigger_apps"       // apps that turn VPN ON
        const val KEY_TRIGGERS_OFF = "sw_trigger_off"    // apps that turn VPN OFF
        const val KEY_PAUSED = "sw_trigger_paused"       // apps whose rule is paused
        const val ACTION_STOP = "sidewire.trigger.stop"
        private const val NOTIF_ID = 776699
        private const val POLL_MS = 1200L

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, AppTriggerService::class.java)) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            try {
                val i = Intent(ctx, AppTriggerService::class.java)
                i.action = ACTION_STOP
                ctx.startService(i)
            } catch (_: Exception) {}
        }
    }
}

package com.guardpal.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps Byte on guard: every time an app is installed or updated he checks it right away,
 * and every few hours he re-checks all apps (in case something switched on Accessibility later).
 */
class GuardService : Service() {

    companion object {
        @Volatile var running = false
        const val CH_GUARD = "guard"
        const val CH_ALERT = "alerts"
        private const val ONGOING_ID = 1
        private const val PREFS = "guard"

        fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun wanted(ctx: Context) = prefs(ctx).getBoolean("on", false)

        fun start(ctx: Context) {
            prefs(ctx).edit().putBoolean("on", true).apply()
            val i = Intent(ctx, GuardService::class.java)
            try { ctx.startForegroundService(i) } catch (e: Exception) { }
        }

        fun stop(ctx: Context) {
            prefs(ctx).edit().putBoolean("on", false).apply()
            ctx.stopService(Intent(ctx, GuardService::class.java))
        }

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_GUARD, "Byte is on guard", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shows that real-time protection is running"
            })
            nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Threat alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warnings about dangerous apps"
            })
        }
    }

    private fun saveStatus(result: String) {
        prefs(this).edit().putString("lastResult", result).putLong("lastScan", System.currentTimeMillis()).apply()
        try { GuardWidget.refreshAll(this) } catch (e: Exception) {}
    }

    private val handler = Handler(Looper.getMainLooper())
    private fun intervalMs(): Long {
        val hrs = prefs(this).getInt("scanEveryHours", 24)
        return hrs.coerceIn(1, 168) * 60L * 60L * 1000L
    }
    private val periodic = object : Runnable {
        override fun run() {
            Thread { recheckAll() }.start()
            handler.postDelayed(this, intervalMs())
        }
    }

    private val packageWatcher = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart ?: return
            if (i.action == Intent.ACTION_PACKAGE_ADDED && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            Thread { checkNewApp(pkg, i.action == Intent.ACTION_PACKAGE_REPLACED) }.start()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Byte is on guard 🛡️")
            .setContentText("Every new app gets checked the moment it's installed.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ONGOING_ID, n)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageWatcher, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(packageWatcher, f)
        running = true
        handler.postDelayed(periodic, 60_000)  // first check a minute after boot
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(periodic)
        try { unregisterReceiver(packageWatcher) } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun checkNewApp(pkg: String, updated: Boolean) {
        val r = Scanner.scanOneApp(this, pkg) ?: return
        val name = r.optString("name", pkg)
        val why = r.optJSONArray("why")
        val first = if (why != null && why.length() > 0) why.getString(0) else ""
        when (r.optString("level")) {
            "danger" -> { saveStatus("danger"); alert(pkg, "🚨 Dangerous app ${if (updated) "updated" else "installed"}: $name",
                "Byte says: $first. Tap to remove it now.", true) }
            "warn" -> { saveStatus("warn"); alert(pkg, "⚠️ $name needs care", "Byte says: $first.", true) }
            else -> if (!updated) alert(pkg, "✅ Byte checked $name", "No danger signs found.", false)
        }
    }

    /** Re-check every app; only alert about dangers we haven't warned about before. */
    private fun recheckAll() {
        try {
            val res = Scanner.scanAllApps(this)
            val apps = res.getJSONArray("apps")
            val seen = prefs(this).getStringSet("warned", emptySet())!!.toMutableSet()
            for (i in 0 until apps.length()) {
                val a = apps.getJSONObject(i)
                if (a.optString("level") != "danger") continue
                val key = a.getString("pkg") + ":" + a.optInt("score")
                if (key in seen) continue
                seen.add(key)
                val why = a.optJSONArray("why")
                alert(a.getString("pkg"), "🚨 Byte found a dangerous app: ${a.optString("name")}",
                    "Byte says: ${if (why != null && why.length() > 0) why.getString(0) else "danger signs found"}.", true)
            }
            var worst = "safe"
            for (i in 0 until apps.length()) { val lv = apps.getJSONObject(i).optString("level"); if (lv=="danger") { worst="danger"; break } else if (lv=="warn") worst="warn" }
            saveStatus(worst)
            prefs(this).edit().putStringSet("warned", seen).apply()
        } catch (e: Exception) { }
    }

    private fun alert(pkg: String, title: String, text: String, loud: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(this, pkg.hashCode(),
            Intent(this, MainActivity::class.java).putExtra("pkg", pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, if (loud) CH_ALERT else CH_GUARD)
            .setSmallIcon(if (loud) android.R.drawable.stat_notify_error else android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (loud) {
            val uninstall = PendingIntent.getActivity(this, pkg.hashCode() + 1,
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(null, "Uninstall", uninstall).build())
        }
        try { nm.notify(pkg.hashCode(), b.build()) } catch (e: Exception) { }
    }
}

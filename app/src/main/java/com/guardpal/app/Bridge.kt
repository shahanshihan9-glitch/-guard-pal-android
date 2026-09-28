package com.guardpal.app

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.File

/** The doorway between Byte's screen (web page) and the real Android powers. */
class Bridge(private val act: MainActivity, private val web: WebView) {
    private val main = Handler(Looper.getMainLooper())

    private fun reply(cb: String, json: String) {
        main.post { web.evaluateJavascript("window.__nativeCb&&__nativeCb(${JSONObject.quote(cb)},${JSONObject.quote(json)})", null) }
    }

    private fun background(cb: String, work: () -> String) {
        Thread {
            val out = try { work() } catch (e: Throwable) { JSONObject().put("error", e.toString()).toString() }
            reply(cb, out)
        }.start()
    }

    @JavascriptInterface fun isNative(): Boolean = true
    @JavascriptInterface fun version(): String = "1.0"

    @JavascriptInterface fun scanApps(cb: String) = background(cb) { Scanner.scanAllApps(act).toString() }
    @JavascriptInterface fun scanOne(pkg: String, cb: String) = background(cb) { (Scanner.scanOneApp(act, pkg) ?: JSONObject()).toString() }
    @JavascriptInterface fun scanStorage(cb: String) = background(cb) { Scanner.scanStorage(act).toString() }

    @JavascriptInterface fun hasAllFiles(): Boolean = Scanner.hasStorageAccess(act)
    @JavascriptInterface fun askAllFiles() { main.post { act.requestStorageAccess() } }

    @JavascriptInterface fun uninstall(pkg: String) {
        main.post { act.safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
    }
    @JavascriptInterface fun openAppSettings(pkg: String) {
        main.post { act.safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) }
    }
    @JavascriptInterface fun openAccessibility() { main.post { act.safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
    @JavascriptInterface fun openSecurity() { main.post { act.safeStart(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }
    @JavascriptInterface fun openLink(url: String) { main.post { act.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    /** Only ever called after the user taps "Delete" and confirms. */
    @JavascriptInterface fun deleteFile(path: String): Boolean = try {
        val f = File(path); f.exists() && f.isFile && f.delete()
    } catch (e: Exception) { false }

    @JavascriptInterface fun guardOn(): Boolean = GuardService.running || GuardService.wanted(act)
    @JavascriptInterface fun setGuard(on: Boolean) {
        main.post { if (on) { act.askNotifications(); GuardService.start(act) } else GuardService.stop(act) }
    }

    @JavascriptInterface fun setWallpaper() {
        main.post {
            try {
                val i = android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                i.putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    android.content.ComponentName(act, GuardWallpaper::class.java))
                act.safeStart(i)
            } catch (e: Exception) {
                try { act.safeStart(android.content.Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } catch (e2: Exception) {}
            }
        }
    }
    @JavascriptInterface fun addWidget() {
        main.post {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val mgr = act.getSystemService(android.appwidget.AppWidgetManager::class.java)
                    val prov = android.content.ComponentName(act, GuardWidget::class.java)
                    if (mgr != null && mgr.isRequestPinAppWidgetSupported) { mgr.requestPinAppWidget(prov, null, null); return@post }
                }
                android.widget.Toast.makeText(act, "Long-press your home screen, tap Widgets, and add Guard Pal.", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {}
        }
    }
    @JavascriptInterface fun setScanInterval(hours: Int) { GuardService.prefs(act).edit().putInt("scanEveryHours", hours).apply() }
    @JavascriptInterface fun getScanInterval(): Int = GuardService.prefs(act).getInt("scanEveryHours", 24)
    @JavascriptInterface fun saveName(name: String) { GuardService.prefs(act).edit().putString("name", name).apply() }
}

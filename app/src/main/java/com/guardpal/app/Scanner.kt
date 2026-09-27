package com.guardpal.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

object Scanner {

    /** App stores we trust. Apps installed any other way are "sideloaded". */
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending",                // Google Play
        "com.sec.android.app.samsungapps",    // Galaxy Store
        "com.huawei.appmarket",
        "com.amazon.venezia",
        "com.xiaomi.market", "com.xiaomi.mipicks",
        "com.oppo.market", "com.heytap.market",
        "com.vivo.appstore",
        "com.google.android.feedback"         // very old Play installs
    )

    /** Names malware likes to hide behind. */
    private val FAKE_NAME = Regex(
        "(?i)^(system ?update|software ?update|update|updater|android ?(update|security|service|system|services)|" +
            "google ?(service|services|update|play ?services?)|security ?(update|service)|wi-?fi( ?service)?|settings|" +
            "service|services|system|system ?service|battery( ?saver)?|cleaner|flash ?player|chrome ?update|" +
            "whats ?app ?update|device ?(care|security)|smart ?manager)$"
    )

    private const val EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*"

    // ---------------------------------------------------------------- apps

    private fun installerOf(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    } catch (e: Exception) { null }

    private fun labelOf(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: Exception) { pkg }

    private fun secure(ctx: Context, key: String): String =
        try { Settings.Secure.getString(ctx.contentResolver, key) ?: "" } catch (e: Exception) { "" }

    private fun overlayAllowed(ctx: Context, uid: Int, pkg: String): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    private fun activeAdmins(ctx: Context): Set<String> = try {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }

    /** Everything we need about the phone's current state, gathered once per scan. */
    class Ctx(val app: Context) {
        val pm: PackageManager = app.packageManager
        val accessibility = secure(app, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val notifListeners = secure(app, "enabled_notification_listeners")
        val admins = activeAdmins(app)
    }

    /** Returns null for apps we don't judge (preinstalled system apps, ourselves). */
    fun analyze(c: Ctx, pi: PackageInfo): JSONObject? {
        val ai: ApplicationInfo = pi.applicationInfo ?: return null
        val pkg = pi.packageName
        if (pkg == c.app.packageName) return null
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val updatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val inst = installerOf(c.pm, pkg)
        val fromStore = inst != null && inst in TRUSTED_INSTALLERS
        if (isSystem && (!updatedSystem || fromStore || inst == null)) return null

        val label = ai.loadLabel(c.pm).toString()
        val perms = pi.requestedPermissions?.toSet() ?: emptySet()
        val why = JSONArray()
        val flags = JSONArray()
        var score = 0
        fun add(points: Int, text: String, flag: String? = null) {
            score += points; why.put(text); if (flag != null) flags.put(flag)
        }

        if (!fromStore) {
            add(3, if (inst == null) "installed from outside an app store (sideloaded)"
                   else "installed by ${labelOf(c.pm, inst)}, not by an app store")
        }
        val accOn = c.accessibility.contains("$pkg/")
        if (accOn) add(if (fromStore) 2 else 5,
            "has Accessibility switched ON: it can read your screen and tap for you", "accessibility")
        if (pkg in c.admins) add(if (fromStore) 2 else 5,
            "is a Device admin: it can stop you from uninstalling it", "admin")
        if (c.notifListeners.contains("$pkg/")) add(if (fromStore) 1 else 3,
            "can read all your notifications, including bank codes", "notifications")

        val readsSms = "android.permission.READ_SMS" in perms || "android.permission.RECEIVE_SMS" in perms
        if (readsSms) add(if (fromStore) 1 else 3, "can read your SMS messages (bank OTP codes)")
        if ("android.permission.SEND_SMS" in perms && !fromStore) add(2, "can send SMS messages (this can cost you money)")
        if (!fromStore && overlayAllowed(c.app, ai.uid, pkg)) add(2, "can draw on top of other apps (fake login screens)", "overlay")
        if (!fromStore && "android.permission.REQUEST_INSTALL_PACKAGES" in perms) add(2, "can install other apps")
        if (!fromStore && ("android.permission.READ_CALL_LOG" in perms || "android.permission.PROCESS_OUTGOING_CALLS" in perms))
            add(2, "can see your calls")
        if (!fromStore && "android.permission.BIND_DEVICE_ADMIN" in perms && pkg !in c.admins)
            add(1, "asks to become a Device admin")

        val hidden = !isSystem && c.pm.getLaunchIntentForPackage(pkg) == null
        if (hidden && !fromStore) add(3, "has no app icon, so it's hiding from you", "hidden")
        if (!fromStore && FAKE_NAME.matches(label.trim())) add(3, "uses a fake system-sounding name (\"$label\")")

        val level = when { score >= 7 -> "danger"; score >= 4 -> "warn"; else -> "safe" }
        return JSONObject()
            .put("pkg", pkg).put("name", label).put("installer", inst ?: "")
            .put("store", fromStore).put("level", level).put("score", score)
            .put("why", why).put("flags", flags).put("hidden", hidden)
            .put("installed", pi.firstInstallTime)
    }

    @Suppress("DEPRECATION")
    fun infoFor(pm: PackageManager, pkg: String): PackageInfo? =
        try { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    fun scanAllApps(app: Context): JSONObject {
        val c = Ctx(app)
        val all = c.pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val out = JSONArray()
        var checked = 0; var danger = 0; var warn = 0
        for (pi in all) {
            val r = analyze(c, pi) ?: continue
            checked++
            when (r.getString("level")) { "danger" -> danger++; "warn" -> warn++ }
            out.put(r)
        }
        return JSONObject().put("apps", out).put("checked", checked).put("danger", danger).put("warn", warn)
            .put("total", all.size)
    }

    fun scanOneApp(app: Context, pkg: String): JSONObject? {
        val pi = infoFor(app.packageManager, pkg) ?: return null
        return analyze(Ctx(app), pi)
    }

    // ---------------------------------------------------------------- storage

    private val RISKY = mapOf(
        "apk" to "an Android app installer", "xapk" to "an Android app installer", "apks" to "an Android app bundle",
        "exe" to "a Windows program", "scr" to "a Windows screensaver program", "com" to "a Windows program",
        "bat" to "a Windows script", "cmd" to "a Windows script", "vbs" to "a VBScript", "js" to "a script file",
        "jar" to "a Java program", "msi" to "a Windows installer", "ps1" to "a PowerShell script",
        "hta" to "an HTML program", "lnk" to "a Windows shortcut", "sh" to "a shell script", "dex" to "raw Android code"
    )
    private val LOOKS_SAFE = Regex("(?i)^(pdf|docx?|xlsx?|pptx?|txt|jpe?g|png|gif|webp|heic|mp3|mp4|mov|m4a|wav|opus|ogg)$")
    private val DOUBLE_EXT = Regex("(?i)\\.(pdf|docx?|xlsx?|pptx?|txt|jpe?g|png|gif|mp4|mp3)\\s*\\.([a-z0-9]{2,5})$")
    private val TRICK_NAME = Regex("(?i)(update|bank|security|whats ?app|flash|mod|crack|hack|free|premium|unlock|gold|prize|gift|invoice|document)")

    fun hasStorageAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun head(f: File, n: Int): ByteArray = try {
        FileInputStream(f).use { s -> val b = ByteArray(n); val r = s.read(b); if (r <= 0) ByteArray(0) else b.copyOf(r) }
    } catch (e: Exception) { ByteArray(0) }

    private fun kindOf(b: ByteArray): String {
        if (b.size < 4) return ""
        fun u(i: Int) = b[i].toInt() and 0xFF
        return when {
            u(0) == 0x4D && u(1) == 0x5A -> "exe"
            u(0) == 0x7F && u(1) == 0x45 && u(2) == 0x4C && u(3) == 0x46 -> "elf"
            u(0) == 0x64 && u(1) == 0x65 && u(2) == 0x78 && u(3) == 0x0A -> "dex"
            u(0) == 0x50 && u(1) == 0x4B && u(2) == 3 && u(3) == 4 -> "zip"
            else -> ""
        }
    }

    fun checkFile(f: File): JSONObject? {
        val name = f.name
        val ext = name.substringAfterLast('.', "").lowercase()
        val why = JSONArray()
        var level = "safe"
        fun bad(t: String) { level = "danger"; why.put(t) }
        fun warn(t: String) { if (level != "danger") level = "warn"; why.put(t) }

        if (Regex("[\u202A-\u202E\u2066-\u2069]").containsMatchIn(name)) bad("its name uses a hidden character to fake its ending")
        val dbl = DOUBLE_EXT.find(name)
        if (dbl != null && RISKY.containsKey(dbl.groupValues[2].lowercase()))
            bad("it pretends to be a .${dbl.groupValues[1].lowercase()} but is really ${RISKY[dbl.groupValues[2].lowercase()]}")

        val needHead = LOOKS_SAFE.matches(ext) || ext.isEmpty() || RISKY.containsKey(ext)
        val h = if (needHead && f.length() > 0) head(f, 16) else ByteArray(0)
        val kind = kindOf(h)
        if (LOOKS_SAFE.matches(ext) && kind in setOf("exe", "elf", "dex")) bad("it's named like a .$ext file but it's really a program")
        if (LOOKS_SAFE.matches(ext) && kind == "zip" && !ext.startsWith("doc") && !ext.startsWith("xls") && !ext.startsWith("ppt"))
            bad("it's named like a .$ext file but it's really a hidden package (it could be an app)")
        if (ext.isEmpty() && kind in setOf("exe", "elf", "dex")) warn("it's a program with no file ending")

        RISKY[ext]?.let { what ->
            if (level == "safe") warn("this is $what. only install or open it if it came from somewhere you trust")
            if ((ext == "apk" || ext == "xapk") && TRICK_NAME.containsMatchIn(name))
                bad("an app installer with a tricky name (\"$name\"). scam apps pretend to be updates, banks or mods")
        }

        if (f.length() in 1..4096) {
            val all = head(f, 4096)
            if (String(all, Charsets.ISO_8859_1).contains(EICAR)) bad("it contains the standard antivirus test signature (EICAR)")
        }
        if (level == "safe") return null
        return JSONObject().put("name", name).put("path", f.absolutePath).put("size", f.length())
            .put("level", level).put("why", why)
    }

    fun scanStorage(app: Context): JSONObject {
        val root = Environment.getExternalStorageDirectory()
        val out = JSONArray()
        var checked = 0; var danger = 0; var warn = 0
        val max = 60000
        fun walk(dir: File, depth: Int) {
            if (checked >= max || depth > 14) return
            val list = try { dir.listFiles() } catch (e: Exception) { null } ?: return
            for (f in list) {
                if (checked >= max) return
                if (f.isDirectory) {
                    if (f.name.startsWith(".")) continue
                    if (depth == 0 && f.name == "Android") continue
                    walk(f, depth + 1)
                } else {
                    checked++
                    val r = try { checkFile(f) } catch (e: Exception) { null } ?: continue
                    if (r.getString("level") == "danger") danger++ else warn++
                    out.put(r)
                }
            }
        }
        walk(root, 0)
        // WhatsApp, Telegram etc. keep their files in Android/media
        val media = File(root, "Android/media")
        if (media.exists()) walk(media, 1)
        return JSONObject().put("files", out).put("checked", checked).put("danger", danger).put("warn", warn)
            .put("access", hasStorageAccess(app))
    }
}

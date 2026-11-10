package app.bluebox.home.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.widget.Toast
import app.bluebox.home.Prefs
import app.bluebox.home.R
import app.bluebox.home.logic.AppEntry
import app.bluebox.home.logic.CommandKind
import app.bluebox.home.logic.VpnCommand
import app.bluebox.home.logic.VpnControl
import app.bluebox.home.logic.VpnControls

/** Installed-app queries and the few system intents the home screen launches. */
class Apps(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    /** Leanback launchables first; on a box that ships only phone-style apps, fall back to plain LAUNCHER. */
    fun installed(): List<AppEntry> {
        val seen = LinkedHashMap<String, AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg !in seen) seen[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString())
            }
        }
        return seen.values.toList()
    }

    /** A VPN app found on the box: its package, name and what Home can do with it. */
    data class VpnApp(val pkg: String, val label: String, val control: VpnControl)

    /** Installed apps that declare a VPN service (BIND_VPN_SERVICE), with the control Home found for each. */
    @Suppress("DEPRECATION")
    fun vpnApps(): List<VpnApp> = runCatching {
        pm.getInstalledPackages(PackageManager.GET_SERVICES or PackageManager.GET_ACTIVITIES)
            .filter { p -> p.services.orEmpty().any { it.permission == VpnControls.BIND_VPN } }
            .map { p ->
                val exported = p.activities.orEmpty().filter { it.exported }.map { it.name }
                VpnApp(p.packageName, p.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: p.packageName, VpnControls.controlFor(p.packageName, exported))
            }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    /**
     * Switches the chosen VPN app: its own on/off (or toggle) request when it has one, otherwise just opens the app.
     * [vpnUp] says which way. Returns true when a request was sent.
     */
    fun switchVpn(pkg: String, vpnUp: Boolean): Boolean {
        val app = vpnApps().firstOrNull { it.pkg == pkg } ?: return fail(pkg)
        val cmd = effectiveControl(pkg, app.control).command(vpnUp) ?: return launch(pkg)
        val i = Intent().setClassName(pkg, cmd.cls).apply { cmd.action?.let { action = it } }
        return runCatching {
            if (cmd.kind == CommandKind.Broadcast) context.sendBroadcast(i) else context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrElse { launch(pkg) }
    }

    /** The app's own control with the owner's picks from Settings laid over it. */
    fun effectiveControl(pkg: String, base: VpnControl): VpnControl = VpnControls.withOverrides(
        base, Prefs.vpnCmd(context, pkg, "on"), Prefs.vpnCmd(context, pkg, "off"), Prefs.vpnCmd(context, pkg, "toggle"))

    /**
     * Every entry point [pkg] exposes to other apps: exported activities, receivers and services of its manifest that
     * name an intent action (the action is what a launcher shortcut or automation app would call). Android has no API
     * for intent filters of other apps, so the app's AndroidManifest.xml is read directly. Blocking: not for the main thread.
     */
    fun exportedCommands(pkg: String): List<VpnCommand> = runCatching {
        val ns = "http://schemas.android.com/apk/res/android"
        val out = LinkedHashSet<VpnCommand>()
        val x = pm.getResourcesForApplication(pkg).assets.openXmlResourceParser("AndroidManifest.xml")
        try {
            var kind: CommandKind? = null; var cls = ""; var exported: Boolean? = null
            var actions = ArrayList<String>(); var hasFilter = false
            fun flush() {
                val k = kind ?: return
                if (exported ?: hasFilter) {
                    val full = if (cls.startsWith(".")) pkg + cls else if ('.' !in cls) "$pkg.$cls" else cls
                    val acts = actions.filter { it != Intent.ACTION_MAIN }
                    if (acts.isEmpty()) { if (k == CommandKind.Activity) out += VpnCommand(k, full) } else acts.forEach { out += VpnCommand(k, full, it) }
                }
                kind = null
            }
            var ev = x.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) when (x.name) {
                    "activity", "activity-alias", "receiver" -> {
                        flush()
                        kind = if (x.name == "receiver") CommandKind.Broadcast else CommandKind.Activity
                        cls = x.getAttributeValue(ns, "name").orEmpty()
                        exported = x.getAttributeValue(ns, "exported")?.let { it == "true" }
                        actions = ArrayList(); hasFilter = false
                    }
                    "service", "provider" -> flush()
                    "intent-filter" -> hasFilter = true
                    "action" -> if (kind != null) x.getAttributeValue(ns, "name")?.let { actions += it }
                }
                ev = x.next()
            }
            flush()
        } finally { x.close() }
        out.toList()
    }.getOrDefault(emptyList())

    fun icon(pkg: String): Drawable? = runCatching {
        pm.getLeanbackLaunchIntentForPackage(pkg)?.component?.let { pm.getActivityIcon(it) } ?: pm.getApplicationIcon(pkg)
    }.getOrNull()

    fun launch(pkg: String): Boolean {
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return fail(pkg)
        return start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pkg).also { if (it) Prefs.pushRecent(context, pkg) }
    }

    /** Whether the owner granted "usage access" (system-wide recents); without it only apps started from Home are known. */
    fun hasUsageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) ==
            android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Package names, most recently used first: system usage stats when allowed, else apps started from Home. */
    fun recent(): List<String> {
        val usage = runCatching {
            val um = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 14L * 86_400_000L, now)
                .filter { it.lastTimeUsed > 0 }.sortedByDescending { it.lastTimeUsed }.map { it.packageName }.distinct()
        }.getOrDefault(emptyList())
        return if (usage.isNotEmpty()) usage else Prefs.recents(context)
    }

    fun usageAccessSettings(): Boolean =
        start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "usage access")

    /** An intent URI from a Watch Next row; its owner app handles it. */
    fun launchUri(intentUri: String): Boolean = runCatching {
        start(Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), intentUri)
    }.getOrElse { fail(intentUri) }

    fun settings() = system(Settings.ACTION_SETTINGS)
    fun vpnSettings() = system(Settings.ACTION_VPN_SETTINGS)
    fun wifi() = system(Settings.ACTION_WIFI_SETTINGS)
    /** Google TV often has no handler for the stock action: try the TV settings accessory screen, then the main settings. */
    fun bluetooth(): Boolean {
        val tv = "com.android.tv.settings"
        val tries = listOf(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent().setClassName(tv, "$tv.accessories.AddAccessoryActivity"),
            Intent().setClassName(tv, "$tv.accessories.BluetoothAccessoryActivity"),
            Intent().setClassName(tv, "$tv.accessories.AccessoriesActivity"),
            Intent().setClassName(tv, "$tv.connectivity.BluetoothActivity"),
            Intent("com.android.tv.settings.action.ACCESSORIES").setPackage(tv),
            Intent("android.settings.BLUETOOTH_SETTINGS"),
        )
        for (i in tries) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return settings()
    }
    fun screensaver() = system(Settings.ACTION_DREAM_SETTINGS)
    fun accessibility() = system(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun notificationAccess() = system("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    fun developer() = system(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    /** Whether developer options are switched on; readable without privileges. */
    fun developerEnabled(): Boolean =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)

    /** Other HOME apps installed on the box (the stock launcher), without ourselves. */
    fun otherHomes(): List<android.content.ComponentName> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName != context.packageName }

    /**
     * Rollback to the stock launcher: forget our "always" choice and open the stock Home now. If the system keeps us as
     * the default (role-based on newer Android), [homeSettings] is the manual way. Returns false when no other Home exists.
     */
    @Suppress("DEPRECATION")
    fun restoreStockHome(): Boolean {
        val stock = otherHomes().firstOrNull() ?: return fail(context.getString(R.string.no_stock_launcher))
        runCatching { pm.clearPackagePreferredActivities(context.packageName) }
        return start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(stock).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), stock.packageName)
    }

    /** System screen where the default Home app is chosen. */
    fun homeSettings(): Boolean =
        start(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "home settings")

    /** System-wide search (Google TV search / Assistant) covers every app that supports it, not just Blue Box. */
    fun search(): Boolean {
        val i = searchIntent() ?: return fail(context.getString(R.string.search))
        return start(i, "search")
    }

    /** The first system search action that has a handler, as a ready intent targeted at that handler. */
    fun searchIntent(): Intent? {
        for (a in listOf("android.search.action.GLOBAL_SEARCH", Intent.ACTION_ASSIST, Intent.ACTION_SEARCH_LONG_PRESS)) {
            val ri = pm.resolveActivity(Intent(a), PackageManager.MATCH_DEFAULT_ONLY) ?: continue
            val ai = ri.activityInfo ?: continue
            return Intent(a).setClassName(ai.packageName, ai.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return null
    }

    /** Human description of where [search] goes, e.g. "Google · GLOBAL_SEARCH"; null when nothing handles it. */
    fun searchTarget(): String? {
        val i = searchIntent() ?: return null
        val c = i.component ?: return null
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrDefault(c.packageName)
        return "$label (${c.packageName}) · ${i.action?.substringAfterLast('.')}"
    }

    private fun system(action: String): Boolean = start(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), action)

    private fun start(i: Intent, what: String): Boolean = try {
        context.startActivity(i); true
    } catch (e: Exception) { fail(what) }

    private fun fail(what: String): Boolean {
        Toast.makeText(context, context.getString(R.string.no_app, what), Toast.LENGTH_SHORT).show()
        return false
    }
}

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
    fun bluetooth() = system(Settings.ACTION_BLUETOOTH_SETTINGS)
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

    /** Search goes to Blue Box by default (decisions: M6-H1); falls back to the system search. */
    fun search(): Boolean {
        val bb = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("bluebox://search")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (bb.resolveActivity(pm) != null) start(bb, "bluebox://search") else system(Intent.ACTION_ASSIST)
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

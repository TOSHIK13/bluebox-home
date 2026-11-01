package app.bluebox.home.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.widget.Toast
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
        return start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pkg)
    }

    /** An intent URI from a Watch Next row; its owner app handles it. */
    fun launchUri(intentUri: String): Boolean = runCatching {
        start(Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), intentUri)
    }.getOrElse { fail(intentUri) }

    fun settings() = system(Settings.ACTION_SETTINGS)
    fun vpnSettings() = system(Settings.ACTION_VPN_SETTINGS)
    fun wifi() = system(Settings.ACTION_WIFI_SETTINGS)
    fun bluetooth() = system(Settings.ACTION_BLUETOOTH_SETTINGS)

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

package app.bluebox.home.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.bluebox.home.logic.UpdateInfo
import app.bluebox.home.logic.UpdateRules
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the APK of a release, checks it (size, SHA-256, same package, higher versionCode, same signing key)
 * and hands it to PackageInstaller; the system asks the user to confirm. Blocking; call off the UI thread.
 */
object UpdateInstaller {
    const val ACTION = "app.bluebox.home.UPDATE_STATUS"

    /** @return null on success (installer started), otherwise a Russian reason. [progress] gets 0..100. */
    fun install(ctx: Context, info: UpdateInfo, progress: (Int) -> Unit): String? {
        val apk = info.apk ?: return "В релизе нет APK"
        val shaAsset = info.sha ?: return "В релизе нет файла контрольной суммы"
        if (!apk.url.startsWith("https://")) return "Ссылка не HTTPS"
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return "Разрешите Home установку приложений из этого источника и повторите"
        }
        val expected = try { UpdateRules.parseSha256(UpdateSource.text(shaAsset.url)) } catch (e: Exception) { null }
            ?: return "Не удалось прочитать контрольную сумму"
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        try {
            download(apk.url, file, apk.size, progress)
        } catch (e: Exception) {
            file.delete(); return "Не скачалось: ${e.javaClass.simpleName}"
        }
        if (apk.size > 0 && file.length() != apk.size) { file.delete(); return "Размер файла не совпал" }
        if (sha256(file) != expected) { file.delete(); return "Контрольная сумма не совпала: файл повреждён" }
        verifyPackage(ctx, file, UpdateRules.versionCode(info.tag))?.let { file.delete(); return it }
        return try { commit(ctx, file); null } catch (e: Exception) { "Установщик не запустился: ${e.javaClass.simpleName}" }
    }

    private fun download(url: String, to: File, size: Long, progress: (Int) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "BlueBoxHome")
        try {
            c.inputStream.use { ins ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (size > 0) { val p = (done * 100 / size).toInt().coerceIn(0, 100); if (p != last) { last = p; progress(p) } }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Same package, versionCode above the installed one (and equal to the tag's), signed with our key. */
    private fun verifyPackage(ctx: Context, f: File, tagCode: Int?): String? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(f.path, flags) ?: return "Файл не похож на APK"
        if (archive.packageName != ctx.packageName) return "Обновление для другой сборки (${archive.packageName})"
        val installed = pm.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION") val newCode = archive.versionCode
        @Suppress("DEPRECATION") val curCode = installed.versionCode
        if (newCode <= curCode) return "Версия в файле не новее установленной"
        if (tagCode != null && newCode != tagCode) return "Версия в файле не совпадает с тегом релиза"
        val own = pm.getPackageInfo(ctx.packageName, flags)
        if (signers(archive).isEmpty() || signers(archive) != signers(own)) return "Подпись обновления не совпадает с установленной"
        return null
    }

    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun commit(ctx: Context, f: File) {
        val inst = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = inst.createSession(params)
        inst.openSession(id).use { s ->
            s.openWrite("update.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ACTION).setPackage(ctx.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi.intentSender)
        }
    }
}

/** Installer status: asks the user to confirm when the system wants it; on success Home is replaced and restarts by itself. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> android.util.Log.w("BlueBoxHome", "update failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

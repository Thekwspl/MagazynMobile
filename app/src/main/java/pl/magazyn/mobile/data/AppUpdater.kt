package pl.magazyn.mobile.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.magazyn.mobile.BuildConfig

data class GitHubRelease(val version: String, val title: String, val notes: String, val apkUrl: String, val pageUrl: String)

class AppUpdater(private val context: Context) {
    suspend fun latestRelease(repository: String): GitHubRelease = withContext(Dispatchers.IO) {
        val clean = repository.trim().removePrefix("https://github.com/").trim('/')
        require(clean.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Wpisz repozytorium jako użytkownik/nazwa." }
        val connection = (URL("https://api.github.com/repos/$clean/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MagazynMobile/${BuildConfig.VERSION_NAME}")
        }
        val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        if (connection.responseCode !in 200..299) error("GitHub zwrócił błąd ${connection.responseCode}. Sprawdź nazwę repozytorium i czy jest publiczne.")
        val json = JSONObject(body)
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", true) }
            ?: error("Najnowsze wydanie nie zawiera pliku APK.")
        GitHubRelease(
            version = json.getString("tag_name").removePrefix("v"),
            title = json.optString("name").ifBlank { json.getString("tag_name") },
            notes = json.optString("body"),
            apkUrl = apk.getString("browser_download_url"),
            pageUrl = json.getString("html_url"),
        )
    }

    suspend fun download(release: GitHubRelease, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "MagazynMobile-${release.version}.apk")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("User-Agent", "MagazynMobile/${BuildConfig.VERSION_NAME}")
        }
        if (connection.responseCode !in 200..299) error("Nie udało się pobrać APK: błąd ${connection.responseCode}.")
        val total = connection.contentLengthLong
        connection.inputStream.use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024); var copied = 0L
            while (true) {
                val read = input.read(buffer); if (read < 0) break
                output.write(buffer, 0, read); copied += read
                if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
            }
        } }
        require(target.length() > 0) { "Pobrany plik APK jest pusty." }
        require(hasMatchingPackageAndSignature(target)) { "Pobrany APK ma inny identyfikator lub podpis. Aktualizacja została zatrzymana, aby chronić dane." }
        target
    }

    @Suppress("DEPRECATION")
    private fun hasMatchingPackageAndSignature(apk: File): Boolean {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.signingInfo?.apkContentsSigners else archive.signatures
        val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.signingInfo?.apkContentsSigners else installed.signatures
        return !archiveSignatures.isNullOrEmpty() && !installedSignatures.isNullOrEmpty() &&
            archiveSignatures.any { candidate -> installedSignatures.any { it == candidate } }
    }

    fun install(file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }
}

fun isNewerVersion(candidate: String, current: String): Boolean {
    val candidateParts = candidate.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val currentParts = current.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    return (0 until maxOf(candidateParts.size, currentParts.size)).firstNotNullOfOrNull { index ->
        val difference = candidateParts.getOrElse(index) { 0 } - currentParts.getOrElse(index) { 0 }
        difference.takeIf { it != 0 }
    }?.let { it > 0 } ?: false
}

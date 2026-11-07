package dev.tunnelhub.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks GitHub Releases for a newer build and hands the APK to the system installer.
 * Release tags are "build-<N>" where N is the CI run number, which is also the app's versionCode.
 */
class Updater(private val activity: Activity) {

    data class Release(val build: Int, val tag: String, val assetId: Long, val assetName: String, val size: Long)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /** Fetch the latest release; callback on main thread with null if none/newer unavailable. */
    fun checkLatest(onResult: (Release?, String?) -> Unit) {
        io.execute {
            val result = runCatching {
                val conn = open("https://api.github.com/repos/$REPO/releases/latest")
                val code = conn.responseCode
                if (code != 200) error("GitHub returned HTTP $code")
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name")
                val build = tag.removePrefix("build-").toIntOrNull() ?: error("Unexpected tag $tag")
                val assets = json.getJSONArray("assets")
                var asset: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) { asset = a; break }
                }
                val a = asset ?: error("Release has no APK")
                Release(build, tag, a.getLong("id"), a.getString("name"), a.getLong("size"))
            }
            main.post {
                result.fold({ onResult(it, null) }, { onResult(null, it.message ?: "Update check failed") })
            }
        }
    }

    /** Download the APK to the app cache and launch the package installer. */
    fun downloadAndInstall(rel: Release, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
        io.execute {
            val result = runCatching {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "TunnelHub-${rel.build}.apk")
                val conn = open("https://api.github.com/repos/$REPO/releases/assets/${rel.assetId}", octet = true)
                val code = conn.responseCode
                if (code != 200) error("Download failed: HTTP $code")
                val total = if (rel.size > 0) rel.size else conn.contentLengthLong
                conn.inputStream.use { ins ->
                    out.outputStream().use { outs ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var last = -1
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            outs.write(buf, 0, n); read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != last) { last = pct; main.post { onProgress(pct) } }
                            }
                        }
                    }
                }
                out
            }
            main.post {
                result.fold({ install(it) }, { onError(it.message ?: "Download failed") })
            }
        }
    }

    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun open(url: String, octet: Boolean = false): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "TunnelHub/${BuildConfig.VERSION_CODE}")
        conn.setRequestProperty("Accept", if (octet) "application/octet-stream" else "application/vnd.github+json")
        // GitHub redirects asset downloads to blob storage; follow it manually with plain headers.
        if (octet) {
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: error("Redirect without Location")
                val c2 = URL(loc).openConnection() as HttpURLConnection
                c2.connectTimeout = 15_000; c2.readTimeout = 120_000
                c2.setRequestProperty("User-Agent", "TunnelHub/${BuildConfig.VERSION_CODE}")
                return c2
            }
        }
        return conn
    }

    companion object {
        /** GitHub repo whose Releases carry the APK. Change if you fork. */
        const val REPO = "TheRealShadoh/tunnel-hub"
        const val KEY_LAST_CHECK = "update_last_check"
    }
}

package dev.tunnelhub.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** One tab in the app. Configured by the user on first launch (see SetupActivity). */
data class Site(val key: String, val label: String, val url: String, val css: String = "") {
    val host: String get() = Uri.parse(url).host ?: url
}

object Sites {
    private const val PREF = "hub"
    private const val KEY = "sites_json"

    /** Icons cycle through this list in tab order. */
    val icons = intArrayOf(R.drawable.ic_chat, R.drawable.ic_hermes, R.drawable.ic_spark, R.drawable.ic_globe)

    fun load(ctx: Context): List<Site> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Site(o.getString("key"), o.getString("label"), o.getString("url"), o.optString("css", ""))
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, sites: List<Site>) {
        val arr = JSONArray()
        sites.forEach { s ->
            arr.put(JSONObject().put("key", s.key).put("label", s.label).put("url", s.url).put("css", s.css))
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun isConfigured(ctx: Context) = load(ctx).isNotEmpty()

    /** Normalise user input: add https://, strip trailing slash. Returns null if unusable. */
    fun normaliseUrl(input: String): String? {
        var u = input.trim()
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        val parsed = Uri.parse(u)
        if (parsed.host.isNullOrBlank() || !parsed.host!!.contains('.')) return null
        return u.trimEnd('/')
    }

    /**
     * Hosts that must stay inside the WebView so the Cloudflare Access login
     * flow completes and drops its CF_Authorization cookie into the same cookie jar.
     * Anything else http(s) opens in the external browser.
     */
    fun isInternalHost(sites: List<Site>, host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        if (sites.any { h == it.host.lowercase() || h.endsWith("." + parentDomain(it.host)) }) return true
        return h.endsWith("cloudflareaccess.com") ||
            h.endsWith("cloudflare.com") ||
            isIdentityProviderHost(h)
    }

    /** "chat.example.com" -> "example.com" so sibling subdomains stay in-app. */
    private fun parentDomain(host: String): String {
        val parts = host.lowercase().split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase()
    }

    /** Identity providers Cloudflare Access may hand off to; shown in the full-screen login view. */
    fun isIdentityProviderHost(h: String): Boolean {
        return h.endsWith("accounts.google.com") ||
            h.endsWith("google.com") ||
            h.endsWith("gstatic.com") ||
            h.endsWith("github.com") ||
            h.endsWith("githubusercontent.com") ||
            h.endsWith("microsoftonline.com") ||
            h.endsWith("live.com") ||
            h.endsWith("okta.com") ||
            h.endsWith("auth0.com")
    }
}

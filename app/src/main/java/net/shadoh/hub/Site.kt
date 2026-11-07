package net.shadoh.hub

import androidx.annotation.IdRes

/** One tab in the app. Add or remove entries here to change which sites appear. */
data class Site(
    val key: String,
    val label: String,
    val url: String,
    @IdRes val navId: Int,
) {
    val host: String get() = url.removePrefix("https://").substringBefore('/')
}

object Sites {
    val all = listOf(
        Site("chat", "Chat", "https://chat.shadoh.net", R.id.nav_chat),
        Site("hermes", "Hermes", "https://hermes.shadoh.net", R.id.nav_hermes),
        Site("spark", "Spark", "https://spark.shadoh.net", R.id.nav_spark),
    )

    fun byNavId(id: Int) = all.first { it.navId == id }

    /**
     * Hosts that must stay inside the WebView so the Cloudflare Access login
     * flow completes and drops its CF_Authorization cookie into the same cookie jar.
     * Anything else http(s) opens in the external browser.
     */
    fun isInternalHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return h.endsWith("shadoh.net") ||
            h.endsWith("cloudflareaccess.com") ||
            h.endsWith("cloudflare.com") ||
            isIdentityProviderHost(h)
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

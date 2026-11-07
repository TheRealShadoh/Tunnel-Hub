package net.shadoh.hub

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar

private const val VIEWPORT_JS = """
(function(){
  if (document.querySelector('meta[name=viewport]')) return;
  var m = document.createElement('meta');
  m.name = 'viewport';
  m.content = 'width=device-width, initial-scale=1, maximum-scale=5';
  document.head.appendChild(m);
})();
"""

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var container: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var prefs: SharedPreferences

    private val webViews = HashMap<String, WebView>()
    private var current: Site = Sites.all.first()

    /** True while a Cloudflare Access login page is on screen (tabs hidden). */
    private var loginMode = false
    private var firstLoad = true

    private fun isLoginUrl(url: String?): Boolean {
        if (url == null) return false
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return host.endsWith("cloudflareaccess.com") ||
            url.contains("/cdn-cgi/access/") ||
            Sites.isIdentityProviderHost(host)
    }

    private fun setLoginMode(on: Boolean) {
        if (loginMode == on) return
        loginMode = on
        bottomNav.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            container.visibility = View.VISIBLE
            toolbar.title = getString(R.string.sign_in_title)
            toolbar.subtitle = getString(R.string.sign_in_subtitle)
        } else {
            toolbar.title = current.label
            toolbar.subtitle = current.host
        }
        invalidateOptionsMenu()
    }

    // --- file upload (<input type="file">) plumbing ---
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        pendingFileCallback?.onReceiveValue(uris)
        pendingFileCallback = null
    }

    private var pendingPermission: PermissionRequest? = null
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.let { req ->
            if (granted) req.grant(req.resources) else req.deny()
        }
        pendingPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge and apply status-bar / nav-bar / keyboard insets ourselves.
        // Letting the system resize the window (adjustResize + fitsSystemWindows) makes the
        // WebView re-layout to a blank surface when the keyboard opens on newer Android.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        prefs = getSharedPreferences("hub", MODE_PRIVATE)
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        container = findViewById(R.id.webContainer)
        bottomNav = findViewById(R.id.bottomNav)
        setSupportActionBar(toolbar)

        // Persist Cloudflare Access / app session cookies across launches.
        CookieManager.getInstance().setAcceptCookie(true)

        bottomNav.setOnItemSelectedListener { item ->
            showSite(Sites.byNavId(item.itemId))
            true
        }
        bottomNav.setOnItemReselectedListener { item ->
            // Tapping the active tab again scrolls to top / goes home like most apps.
            val wv = webViewFor(Sites.byNavId(item.itemId))
            if (wv.scrollY > 0) wv.scrollTo(0, 0) else wv.loadUrl(current.url)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webViewFor(current)
                when {
                    wv.canGoBack() -> wv.goBack()
                    current != Sites.all.first() -> bottomNav.selectedItemId = Sites.all.first().navId
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        // Deep link (e.g. tapped https://hermes.shadoh.net/... elsewhere) or last-used tab.
        val startSite = siteForIntent(intent)
            ?: Sites.all.firstOrNull { it.key == prefs.getString("lastTab", null) }
            ?: Sites.all.first()
        container.visibility = View.INVISIBLE // revealed once the first page (or login) is ready
        bottomNav.selectedItemId = startSite.navId
        intent?.dataString?.let { url -> if (Sites.isInternalHost(Uri.parse(url).host)) webViewFor(startSite).loadUrl(url) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val site = siteForIntent(intent) ?: return
        bottomNav.selectedItemId = site.navId
        intent.dataString?.let { webViewFor(site).loadUrl(it) }
    }

    private fun siteForIntent(intent: Intent?): Site? {
        val host = intent?.data?.host ?: return null
        return Sites.all.firstOrNull { it.host.equals(host, ignoreCase = true) }
    }

    // ---------------------------------------------------------------- tabs

    private fun showSite(site: Site) {
        current = site
        prefs.edit().putString("lastTab", site.key).apply()
        toolbar.title = site.label
        toolbar.subtitle = site.host
        val target = webViewFor(site)
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            v.visibility = if (v === target) View.VISIBLE else View.GONE
        }
        invalidateOptionsMenu()
    }

    private fun webViewFor(site: Site): WebView =
        webViews.getOrPut(site.key) { createWebView(site).also { container.addView(it); it.loadUrl(site.url) } }

    // ------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(site: Site): WebView {
        val wv = WebView(this)
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.visibility = View.GONE

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Desktop mode is a per-site preference, see applyViewportMode()
        }
        applyViewportMode(wv, isDesktop(site))

        // Follow the phone's dark mode inside pages that honour prefers-color-scheme.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, true)
        }

        // Cloudflare Access sets its auth cookie on the app host after bouncing
        // through *.cloudflareaccess.com — third-party cookies must be on for that.
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return false
                if (scheme == "http" || scheme == "https") {
                    if (Sites.isInternalHost(uri.host)) return false // stay in-app
                    openExternal(uri); return true
                }
                // mailto:, tel:, intent:, etc.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (view === webViewFor(current)) {
                    progress.visibility = View.VISIBLE; progress.progress = 0
                    setLoginMode(isLoginUrl(url))
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view === webViewFor(current)) {
                    progress.visibility = View.GONE
                    setLoginMode(isLoginUrl(url))
                    if (firstLoad && !loginMode) { firstLoad = false; container.visibility = View.VISIBLE }
                }
                CookieManager.getInstance().flush()
                // Pages without a mobile viewport render at 980px; give them one so they reflow.
                if (!isDesktop(site)) view.evaluateJavascript(VIEWPORT_JS, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                progress.visibility = View.GONE
                Snackbar.make(container, getString(R.string.load_error, site.host), Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.retry) { view.reload() }
                    .show()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view === webViewFor(current)) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onShowFileChooser(
                webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, params: FileChooserParams,
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                return runCatching { fileChooser.launch(params.createIntent()); true }
                    .getOrElse { pendingFileCallback = null; false }
            }

            // target="_blank" / window.open: load in the same tab if it's ours, else external.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val temp = WebView(view.context)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        if (Sites.isInternalHost(request.url.host)) view.loadUrl(request.url.toString())
                        else openExternal(request.url)
                        v.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }

            // Mic for voice input (Open WebUI etc.)
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsMic) { request.deny(); return }
                val granted = ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (granted) request.grant(request.resources)
                else { pendingPermission = request; requestMic.launch(android.Manifest.permission.RECORD_AUDIO) }
            }
        }
        return wv
    }

    /**
     * Mobile vs desktop rendering. Also strips the " wv" marker from the UA in both modes —
     * Google's sign-in page refuses embedded WebViews that advertise it, and Cloudflare Access
     * may use Google as an identity provider.
     */
    private fun applyViewportMode(wv: WebView, desktop: Boolean) {
        val base = WebSettings.getDefaultUserAgent(this).replace("; wv", "")
        wv.settings.apply {
            if (desktop) {
                userAgentString = base
                    .replace(Regex("Android [^;]+; [^)]+\\)"), "X11; Linux x86_64)")
                    .replace(" Mobile Safari", " Safari")
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
            } else {
                userAgentString = base
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
            }
        }
    }

    private fun isDesktop(site: Site) = prefs.getBoolean("desktop_${site.key}", false)

    private fun openExternal(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(this, uri.toString(), Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_desktop).isChecked = isDesktop(current)
        menu.findItem(R.id.action_desktop).isVisible = !loginMode
        menu.findItem(R.id.action_home).isVisible = !loginMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val wv = webViewFor(current)
        when (item.itemId) {
            R.id.action_reload -> wv.reload()
            R.id.action_home -> wv.loadUrl(current.url)
            R.id.action_desktop -> {
                val next = !isDesktop(current)
                prefs.edit().putBoolean("desktop_${current.key}", next).apply()
                item.isChecked = next
                applyViewportMode(wv, next)
                wv.reload()
            }
            R.id.action_open_browser -> openExternal(Uri.parse(wv.url ?: current.url))
            R.id.action_sign_out -> {
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                webViews.values.forEach { it.clearCache(true); it.loadUrl(Sites.all.first { s -> webViews[s.key] === it }.url) }
                Toast.makeText(this, R.string.cookies_cleared, Toast.LENGTH_LONG).show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webViews.values.forEach { container.removeView(it); it.destroy() }
        webViews.clear()
        super.onDestroy()
    }
}

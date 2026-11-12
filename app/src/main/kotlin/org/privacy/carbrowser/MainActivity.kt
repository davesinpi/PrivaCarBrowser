package org.privacy.carbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.addCallback
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebStorage
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.privacy.carbrowser.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var web: WebView
    private lateinit var bridge: SpeechRecognitionBridge
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var ext: ExtensionStore

    private val injectJs by lazy { assets.open("inject.js").bufferedReader().use { it.readText() } }
    private val allowedSslHosts = HashSet<String>() // in-memory only, cleared on restart
    private val allowedHttpHosts = HashSet<String>() // same: "Load anyway" lasts for this session only

    // One pending runtime-permission request at a time: Android perm -> callback.
    private var pendingPerm: ((Boolean) -> Unit)? = null
    private val permLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            pendingPerm?.invoke(granted); pendingPerm = null
        }

    private val homeUrl = "https://duckduckgo.com"
    private companion object { const val MAX_SCRIPT = 1_000_000 }

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { open(toUrl(it)) }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // targetSdk 35+ is always edge-to-edge: keep content clear of bars, cutouts and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE)
        ext = ExtensionStore(this)
        binding.menuButton.setOnClickListener { showMenu() }

        web = WebView(this)
        binding.webContainer.addView(web)
        bridge = SpeechRecognitionBridge(this, web)

        configureWebView()
        applyUserAgent()
        hookSpeechBridge()

        // Menu button fades out after a few seconds; any touch on the page brings it back.
        web.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) pokeMenuButton(); false }
        pokeMenuButton()

        // Also wipe at startup: covers sessions that ended by swipe-away/kill (onDestroy never ran).
        val start = intent?.data?.toString() ?: homeUrl
        wipe { web.loadUrl(start) }
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.data?.let { web.loadUrl(it.toString()) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = prefs.getInt("text_zoom", 100)
            // Privacy: never offer to save form data / passwords.
            saveFormData = false
            @Suppress("DEPRECATION")
            savePassword = false
        }

        // Privacy: block third-party cookies; no persistent cross-launch cookie jar
        // when "clear on exit" is on (default).
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        // Privacy: Safe Browsing sends visited URLs to Google. Off by default.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(web.settings, false)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val scheme = req.url.scheme?.lowercase()
                if (scheme == "https" && req.url.path.orEmpty().endsWith(".user.js")) {
                    fetchAndOffer(req.url.toString()); return true
                }
                return when (scheme) {
                    "http" -> if (req.url.host in allowedHttpHosts) false else { confirmHttp(req.url); true } // warn before cleartext
                    "https", "about", "file", "data", "javascript" -> false
                    else -> true                                   // block intent:/tel:/market: etc.
                }
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                v.evaluateJavascript(injectJs, null) // define polyfills before page scripts run
                runUserScripts(v, url, atStart = true)
            }

            override fun onPageFinished(v: WebView, url: String?) = runUserScripts(v, url, atStart = false)

            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                val host = Uri.parse(error.url).host ?: ""
                if (host in allowedSslHosts) { handler.proceed(); return }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Certificate problem")
                    .setMessage("The certificate for $host is not trusted. Continue anyway?")
                    .setPositiveButton("Proceed") { _, _ -> allowedSslHosts.add(host); handler.proceed() }
                    .setNegativeButton("Cancel") { _, _ -> handler.cancel() }
                    .show()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var customCb: CustomViewCallback? = null
            private val bars get() = WindowInsetsControllerCompat(window, binding.root)

            override fun onPermissionRequest(request: PermissionRequest) {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) {
                    val host = Uri.parse(request.origin.toString()).host ?: ""
                    gateSitePermission("microphone", host, Manifest.permission.RECORD_AUDIO) { ok ->
                        if (ok) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        else request.deny()
                    }
                } else request.deny() // deny camera/MIDI/etc. by default
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                val host = Uri.parse(origin).host ?: origin
                gateSitePermission("location", host, Manifest.permission.ACCESS_FINE_LOCATION) { ok ->
                    cb.invoke(origin, ok, false) // never remember across sessions at the OS layer
                }
            }

            override fun onProgressChanged(v: WebView, p: Int) {
                binding.progress.progress = p
                binding.progress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                customView = view; customCb = cb
                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webContainer.visibility = View.GONE
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.visibility = View.GONE
                binding.webContainer.visibility = View.VISIBLE
                bars.show(WindowInsetsCompat.Type.systemBars())
                customCb?.onCustomViewHidden(); customView = null; customCb = null
            }
        }
    }

    private fun hookSpeechBridge() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                web, "_SpeechBridgeChannel", setOf("*")
            ) { _, message, _, _, _ -> bridge.onMessage(message.data ?: "") }
        }
    }

    /** Per-site allow-list dialog, then the Android runtime permission if needed. */
    private fun gateSitePermission(label: String, host: String, androidPerm: String, done: (Boolean) -> Unit) {
        if (host.isBlank()) { done(false); return }
        val key = "allowed_${label}_hosts"
        val allowed = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        fun ensureAndroid() {
            if (ContextCompat.checkSelfPermission(this, androidPerm) == PackageManager.PERMISSION_GRANTED) done(true)
            else { pendingPerm = done; permLauncher.launch(androidPerm) }
        }
        if (host in allowed) { ensureAndroid(); return }
        AlertDialog.Builder(this)
            .setTitle("Allow $label?")
            .setMessage("$host wants to use your $label.")
            .setPositiveButton("Allow") { _, _ ->
                allowed.add(host); prefs.edit().putStringSet(key, allowed).apply(); ensureAndroid()
            }
            .setNegativeButton("Block") { _, _ -> done(false) }
            .show()
    }

    // ---------- Main menu ----------

    private val hideMenuButton = Runnable { binding.menuButton.animate().alpha(0.15f) }
    private fun pokeMenuButton() {
        binding.menuButton.animate().alpha(0.6f)
        binding.menuButton.removeCallbacks(hideMenuButton)
        binding.menuButton.postDelayed(hideMenuButton, 4000)
    }

    private fun showMenu() {
        pokeMenuButton()
        val desktop = prefs.getBoolean("desktop_mode", true)
        val items: List<Pair<String, () -> Unit>> = listOfNotNull(
            "Address / search" to ::showGoTo,
            "Voice search" to ::voiceSearch,
            ("Back" to { web.goBack() }).takeIf { web.canGoBack() },
            ("Forward" to { web.goForward() }).takeIf { web.canGoForward() },
            "Reload" to { web.reload() },
            "Home" to { web.loadUrl(homeUrl) },
            "Bookmarks" to ::showBookmarks,
            "Share page" to ::sharePage,
            "Desktop site: ${if (desktop) "on" else "off"}" to {
                prefs.edit().putBoolean("desktop_mode", !desktop).apply(); applyUserAgent(); web.reload()
            },
            "Text size: ${web.settings.textZoom}%" to ::showTextSize,
            "Extensions" to ::showExtensions,
            "Clear browsing data" to ::confirmClearData,
        )
        AlertDialog.Builder(this).setTitle(web.title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name))
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    /** Typed http:// still gets the cleartext warning; everything else loads directly. */
    private fun open(url: String) {
        if (url.startsWith("http://", ignoreCase = true)) confirmHttp(Uri.parse(url)) else web.loadUrl(url)
    }

    private fun showGoTo() {
        val input = android.widget.EditText(this).apply {
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            hint = "Search or type an address"
            setText(web.url)
            selectAll()
        }
        val d = AlertDialog.Builder(this).setView(input)
            .setPositiveButton("Go") { _, _ -> input.text.toString().takeIf { it.isNotBlank() }?.let { open(toUrl(it)) } }
            .setNegativeButton("Cancel", null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> d.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true }
        // SOFT_INPUT_STATE_VISIBLE is ignored for dialogs on modern Android; request the IME once the dialog has focus.
        d.setOnShowListener {
            input.requestFocus()
            input.post { d.window?.let { WindowCompat.getInsetsController(it, input).show(WindowInsetsCompat.Type.ime()) } }
        }
        d.show()
    }

    private fun voiceSearch() = try {
        voiceLauncher.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // privacy: on-device when available
        )
    } catch (e: android.content.ActivityNotFoundException) {
        toast("No speech recognizer installed")
    }

    private fun applyUserAgent() {
        val base = WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace("Version/4.0 ", "")
        web.settings.userAgentString = if (prefs.getBoolean("desktop_mode", true))
            base.replace(Regex("""\(Linux; Android[^)]*\)"""), "(X11; Linux x86_64)").replace("Mobile ", "")
        else base
    }

    private fun showTextSize() {
        val sizes = intArrayOf(75, 100, 125, 150, 175, 200)
        AlertDialog.Builder(this).setTitle("Text size")
            .setSingleChoiceItems(sizes.map { "$it%" }.toTypedArray(), sizes.indexOf(web.settings.textZoom)) { d, i ->
                web.settings.textZoom = sizes[i]
                prefs.edit().putInt("text_zoom", sizes[i]).apply()
                d.dismiss()
            }.show()
    }

    private fun sharePage() {
        val url = web.url ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
    }

    // Bookmarks are user-chosen data, so they survive the wipe-on-exit.
    private fun bookmarks(): List<Pair<String, String>> {
        val arr = JSONArray(prefs.getString("bookmarks", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.getString("title") to it.getString("url") }
    }

    private fun saveBookmarks(list: List<Pair<String, String>>) = prefs.edit().putString(
        "bookmarks", JSONArray(list.map { JSONObject().put("title", it.first).put("url", it.second) }).toString()
    ).apply()

    private fun showBookmarks() {
        val list = bookmarks()
        val url = web.url
        val b = AlertDialog.Builder(this).setTitle("Bookmarks")
        if (list.isEmpty()) b.setMessage("No bookmarks yet.")
        else b.setItems(list.map { it.first }.toTypedArray()) { _, i -> open(list[i].second) }
        if (url != null && url.startsWith("http") && list.none { it.second == url }) {
            b.setPositiveButton("Add this page") { _, _ ->
                saveBookmarks(list + ((web.title?.takeIf { it.isNotBlank() } ?: url) to url)); toast("Bookmarked")
            }
        }
        if (list.isNotEmpty()) b.setNegativeButton("Remove…") { _, _ ->
            AlertDialog.Builder(this).setTitle("Remove bookmark")
                .setItems(list.map { it.first }.toTypedArray()) { _, i -> saveBookmarks(list.filterIndexed { j, _ -> j != i }) }
                .setNegativeButton("Cancel", null).show()
        }
        b.show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this).setTitle("Clear browsing data?")
            .setMessage("Clears cookies, cache, history, site storage and site permissions.\nBookmarks, extensions and settings are kept.")
            .setPositiveButton("Clear") { _, _ ->
                wipe()
                allowedSslHosts.clear()
                allowedHttpHosts.clear()
                prefs.edit().remove("allowed_microphone_hosts").remove("allowed_location_hosts").apply()
                web.reload()
                toast("Browsing data cleared")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun wipe(then: () -> Unit = {}) {
        CookieManager.getInstance().removeAllCookies { then() }
        web.clearCache(true)
        web.clearHistory()
        WebStorage.getInstance().deleteAllData()
    }

    // ---------- Extensions (userscripts) ----------

    private fun runUserScripts(v: WebView, url: String?, atStart: Boolean) {
        url ?: return
        ext.scripts.filter { it.enabled && it.runAtStart == atStart && it.appliesTo(url) }
            .forEach { v.evaluateJavascript("(function(){try{\n${it.code}\n}catch(e){console.error(e)}})();", null) }
    }

    private fun showExtensions() {
        val list = ext.scripts
        val b = AlertDialog.Builder(this).setTitle("Extensions")
            .setNeutralButton("Add") { _, _ -> showAdd() }
            .setPositiveButton("Done") { _, _ -> web.reload() }
        if (list.isEmpty()) {
            b.setMessage("No extensions installed.\n\nAdd a userscript (.user.js) by pasting its code or an https:// link, or open a .user.js link in the browser.")
        } else {
            b.setMultiChoiceItems(list.map { it.name }.toTypedArray(), list.map { it.enabled }.toBooleanArray()) { _, i, on ->
                ext.setEnabled(i, on)
            }
            b.setNegativeButton("Remove") { _, _ ->
                AlertDialog.Builder(this).setTitle("Remove extension")
                    .setItems(ext.scripts.map { it.name }.toTypedArray()) { _, i -> ext.remove(i); web.reload() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        b.show()
    }

    private fun showAdd() {
        val input = android.widget.EditText(this).apply {
            hint = "Paste userscript code or an https:// link to a .user.js"
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this).setTitle("Add extension").setView(input)
            .setPositiveButton("Next") { _, _ ->
                val text = input.text.toString().trim()
                when {
                    text.isEmpty() -> {}
                    !text.contains('\n') && text.startsWith("https://") -> fetchAndOffer(text)
                    !text.contains('\n') && text.startsWith("http://") -> toast("Only https:// links can be installed")
                    else -> offerInstall(text)
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Download a script over https (system CAs only, size-capped), then ask before installing. */
    private fun fetchAndOffer(url: String) {
        if (!url.startsWith("https://")) { toast("Only https:// links can be installed"); return }
        toast("Downloading…")
        Thread {
            val code = runCatching {
                (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                    connectTimeout = 10_000; readTimeout = 10_000
                    inputStream.bufferedReader().use { r ->
                        val buf = CharArray(MAX_SCRIPT + 1); var n = 0
                        while (n < buf.size) { val k = r.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                        if (n > MAX_SCRIPT) null else String(buf, 0, n)
                    }
                }
            }.getOrNull()
            runOnUiThread { if (code.isNullOrBlank()) toast("Couldn't download script (max 1 MB)") else offerInstall(code) }
        }.start()
    }

    private fun offerInstall(code: String) {
        val s = UserScript.parse(code)
        AlertDialog.Builder(this).setTitle("Install \"${s.name}\"?")
            .setMessage(
                "Runs on:\n" + s.matches.joinToString("\n") { "• " + if (it == "*") "every website" else it } +
                    "\n\nIt can read and change everything on these sites, including what you type. Only install scripts you trust."
            )
            .setPositiveButton("Install") { _, _ -> ext.add(s); web.reload(); toast("Installed ${s.name}") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun confirmHttp(url: Uri) {
        val host = url.host ?: return
        if (host in allowedHttpHosts) { web.loadUrl(url.toString()); return }
        AlertDialog.Builder(this)
            .setTitle("Insecure site")
            .setMessage("$url uses unencrypted HTTP. Load it anyway?")
            .setPositiveButton("Load") { _, _ -> allowedHttpHosts.add(host); web.loadUrl(url.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        bridge.destroy()
        if (isFinishing) wipe() // Privacy: wipe the browsing session on exit (incognito-style)
        (web.parent as? FrameLayout)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}

/** Address-bar input → URL: keeps http(s)/about URLs, adds https:// to bare hosts/IPs, otherwise searches DuckDuckGo. */
fun toUrl(input: String): String {
    val s = input.trim()
    val host = Regex("""^(localhost|[^\s/:]+\.[a-z]{2,}|\d{1,3}(\.\d{1,3}){3})(:\d+)?(/\S*)?$""", RegexOption.IGNORE_CASE)
    return when {
        Regex("^(https?://|about:)", RegexOption.IGNORE_CASE).containsMatchIn(s) -> s
        host.matches(s) -> "https://$s"
        else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(s, "UTF-8")
    }
}

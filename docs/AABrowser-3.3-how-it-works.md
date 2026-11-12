# AABrowser 3.3: how it works

Notes from decompiling `AABrowser-3.3.apk` with jadx 1.5.6 (Nov 2026).

| | |
|---|---|
| Package | `com.kododake.aabrowser` |
| Version | 3.3 (versionCode 10) |
| Min / target SDK | 35 (Android 15) / 37 |
| Language / UI | Kotlin, Jetpack Compose (Material 3), Android `WebView` |
| License / source | GPL-3.0, github.com/kododake/AABrowser |
| Obfuscation | R8-minified (classes merged, names partly kept) |

---

## 1. The core idea

AABrowser is **an ordinary Android app with one full-screen `WebView` that presents itself to the car as a navigation/map app**, so the car's head unit will run it and draw it.

There is **no Android for Cars App Library screen code** in the app. Apps built with that library (`CarAppService` → `Session` → `Screen`) have to use Google's fixed templates (lists, grids, map surfaces), and those can't show arbitrary web content. AABrowser skips the templates: it's a normal `Activity`, and its manifest uses the declarations that car platforms check before they show an app.

The `androidx.car.app` classes in the APK are only the library's manifest pieces plus R8 class merging (`SurfaceContainer$$ExternalSyntheticOutline0`, `Maneuver` constants reused as switch labels). No `CarAppService` exists.

### Manifest declarations that make it a car app

```xml
<!-- Application level -->
<meta-data android:name="distractionOptimized" android:value="true"/>
<meta-data android:name="androidx.car.app.minCarApiLevel" android:value="7"/>
<meta-data android:name="com.google.android.gms.car.application"
           android:resource="@xml/automotive_app_desc"/>   <!-- <uses name="media"/> -->
<uses-permission android:name="androidx.car.app.ACCESS_SURFACE"/>
<uses-permission android:name="androidx.car.app.MAP_TEMPLATES"/>

<!-- MainActivity -->
<intent-filter>
  <action   android:name="android.intent.action.MAIN"/>
  <category android:name="android.intent.category.LAUNCHER"/>
  <category android:name="android.intent.category.CAR_LAUNCHER"/>          <!-- shows in the car launcher -->
  <category android:name="androidx.car.app.category.NAVIGATION"/>          <!-- claims to be navigation -->
  <category android:name="android.intent.category.APP_MAPS"/>              <!-- claims to be a maps app -->
</intent-filter>
<meta-data android:name="distractionOptimized" android:value="true"/>      <!-- allowed while driving -->
<layout android:defaultWidth="800dp" android:defaultHeight="480dp" .../>  <!-- head-unit-sized window -->
```

What each one does:

- **`CAR_LAUNCHER`**: puts the activity in the car's app launcher (Android Automotive OS).
- **`distractionOptimized=true`** (on both the app and the activity): tells Automotive OS the activity is safe to show while moving. Without it the system blocks the activity once the car is in motion. Nothing checks this; the app just declares it.
- **`NAVIGATION` / `APP_MAPS` categories + `MAP_TEMPLATES` / `ACCESS_SURFACE`**: navigation apps get the most screen and the most leeway on car platforms, so the app puts itself in that class.
- **`com.google.android.gms.car.application` → `uses media`**: the descriptor that makes Android Auto (phone projection) see the app at all.
- **`resizeableActivity`, `max_aspect` 4–5, `configChanges`**: the WebView keeps working across the odd sizes, densities and day/night switches of head units, without the activity restarting.

> Note: on **Android Auto phone projection**, Google normally only shows apps that come from the Play Store and use the templates. The app's start page promotes the same developer's **AAAnything** project ("Bring Your Favorite Apps to Android Auto"), which suggests that on projection it relies on a separate unlock/sideload method rather than its manifest alone. The manifest declarations are confirmed from the APK. The exact projection workaround isn't in this APK, so this part is inferred.

---

## 2. Architecture

There's no DI framework and no Application subclass. `MainActivity` builds everything and holds a lazily created `BrowserManagers` container.

```
MainActivity (singleTop, launcher + http/https VIEW handler)
 ├─ ActivityMainBinding: FrameLayouts for the WebView plus several ComposeViews
 │     (menu, tabs, bookmarks, settings, QR, progress, FAB, start page)
 └─ BrowserManagers (lazy)
      ├─ TabManager             : list of BrowserTab {id, WebView}, tab session persistence
      ├─ NavigationManager      : URL/search handling, opening tabs, external URIs
      ├─ BrowserUIManager       : shows and hides the Compose overlays
      ├─ StartPageManager       : quick links, site icons (SiteIconCache), background
      ├─ PermissionManager      : runtime permissions + per-site web permission prompts
      ├─ UmamiTracker           : launch analytics (see section 6)
      └─ callback factories     : MainActivityCallbackFactory / WebBrowserCallbackFactory
                                  connect WebView events to managers through BrowserCallbacks

BrowserTabFactory (code merged into androidx.tracing.Trace by R8) creates each tab:
 WebView + BrowserWebViewClient + WebChromeClient
         + JS interface "Android" (openExternal)
         + WebMessageListener "_SpeechBridgeChannel" (SpeechRecognitionBridge)
```

The UI is a classic View layout hosting Jetpack Compose overlays. The web content is a plain `android.webkit.WebView` per tab. Bookmarks and tab sessions live in SharedPreferences (`data/prefs`).

Other entry points:
- `ShareBookmarkActivity`: transparent activity for `ACTION_SEND text/plain`; saves shared links as bookmarks.
- `SettingsActivity`: internal only.

---

## 3. Making web pages usable in a car

### 3.1 Voice input polyfill (no keyboard while driving)

Android `WebView` doesn't implement the Web Speech API. On every page load, `BrowserWebViewClient` injects a script that defines `window.SpeechRecognition` / `webkitSpeechRecognition`:

```
page JS  ──SR.start()──▶  _SpeechBridgeChannel.postMessage({type:'start', lang})
                                   │  (androidx.webkit WebMessageListener)
                                   ▼
                        SpeechRecognitionBridge (native Android SpeechRecognizer)
                                   │  results / errors / events
                                   ▼
page JS  ◀── evaluateJavascript("window.__sr_event('result', '{a:[...], f:true}')")
```

So sites that support voice search (Google, YouTube, etc.) get a working microphone button. Each use goes through the per-site microphone check (section 4).

### 3.2 DRM downgrade for video

Head units usually can't do **hardware-secure** Widevine. On every site except YouTube, an injected script wraps `navigator.requestMediaKeySystemAccess` and rewrites `HW_SECURE_*` robustness requests to `SW_SECURE_DECODE` (Widevine L3). If that fails it falls back to the original request. It also disguises the patched function as native code (`toString` override). This is what lets DRM streaming sites play at all, usually at lower resolution.

### 3.3 Other car-friendly bits
- **User-agent presets** (Chrome, Edge, Firefox, Samsung, custom) and a **"Request desktop site"** toggle (`desktop_mode`), since many sites serve phone layouts that look wrong on a wide head-unit screen.
- A **floating quick-action button** that auto-hides (optionally always shown) instead of a fixed toolbar, so the page gets the whole screen.
- A **QR code card** for sending the current URL to a phone.
- Fullscreen video container (`fullscreenContainer`) for HTML5 video.

---

## 4. Permissions and security model

| Feature | How it's handled |
|---|---|
| Microphone for websites | Only secure contexts (https / localhost / file) can ask. Per-site dialog, choice saved in `browser_prefs/allowed_microphone_hosts`. Then Android `RECORD_AUDIO` if not yet granted. |
| Location for websites | Same per-site model (`allowed_location_hosts`), then the Android location permission. The app uses location only for web pages. |
| Protected media (DRM ID) | Granted automatically. |
| TLS certificate errors | Dialog per error. A choice to proceed is kept in an in-memory `allowedSslHosts` set (until restart, or cleared in settings). |
| Plain HTTP | Cleartext is allowed (`usesCleartextTraffic=true`), but the app shows a confirmation dialog before loading an http:// page. |
| Trust store | `network_security_config` trusts **system and user-installed** CAs. Handy for debugging, but any user-added CA can intercept traffic. |
| Non-web URL schemes | `shouldOverrideUrlLoading` blocks everything except http/https/about/file/data/javascript, so pages can't fire `intent:`, `tel:` and so on. |
| `Android.openExternal()` JS bridge | Works only from the app's own `file:///android_asset/error.html` and only for http/https URLs. |
| Client certificates | Supported; auto-login choices can be reset in settings. |

---

## 5. Network endpoints in the app's own code

| URL | Purpose |
|---|---|
| `https://stats1.kododake.work/api/send` | Umami analytics (POST, see section 6) |
| `https://www.google.com`, `https://duckduckgo.com` | Search engines / defaults |
| `https://youtube.com` | Default quick link; skipped by the DRM script |
| `github.com/kododake/AABrowser(/releases)` | Update / about links |
| `github.com/kododake/AAAnything/releases/latest` | Promo card |
| `buymeacoffee.com/kododake`, `github.com/sponsors/kododake` | Donations |
| `keepandroidopen.org`, `github.com/woheller69/FreeDroidWarn` | Notice about Google's 2026/27 developer-verification rule (`dialog_Warning`) |

---

## 6. Analytics (UmamiTracker)

- Fires in `MainActivity.onCreate` right after `setContentView`, so on every launch or activity recreation.
- Sends `POST https://stats1.kododake.work/api/send` with User-Agent `Mozilla/5.0 (Android) AABrowser/<version>`:
  ```json
  { "type": "event",
    "payload": { "website": "0429c890-e9a6-4370-8434-a05aaa54249d",
                 "url": "app://v<version>", "hostname": "aabrowser.internal",
                 "name": "app_open", "id": "<user_id>" } }
  ```
- `user_id` is a random `UUID` made on first run and stored for good in `umami_prefs`.
- **No opt-out was found**, and "clear site permissions" doesn't reset the ID. No browsing data is sent.
- To block it: DNS-block `stats1.kododake.work`, or build from source without the tracker.

---

## 7. Summary

AABrowser has no car-specific rendering of its own. It's **a standard WebView browser wrapped in a manifest that tells the car platform "I'm a distraction-optimized navigation/media app."** The car-specific work is in making the web usable there:
- voice input through a native speech bridge
- forcing software DRM so video plays
- user-agent and desktop-mode switching
- a minimal auto-hiding UI

The security handling is careful: per-site prompts, scheme filtering, and a tightly limited JS bridge. The two things to watch are the always-on launch analytics and trusting user-installed CAs.

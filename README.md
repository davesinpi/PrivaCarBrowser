# PrivaCar

A privacy-hardened rebuild of the AABrowser idea: one full-screen `WebView` that
declares itself to the car head unit as a distraction-optimized
navigation/media app, so Android Automotive OS will run and draw it. Keeps the
car-usability tricks, drops the tracking.

## What it does (same as AABrowser)
- Full-screen WebView launched as a car app (`CAR_LAUNCHER` + `NAVIGATION` +
  `APP_MAPS` categories, `distractionOptimized=true`, head-unit-sized `<layout>`).
- **Voice input polyfill** — injects `SpeechRecognition` and bridges it to the
  native `SpeechRecognizer` over a `WebMessageListener`, so sites with a mic
  button work without a keyboard.
- **Widevine downgrade** — rewrites `HW_SECURE_*` to `SW_SECURE_DECODE` (L3) so
  DRM video plays on head units; YouTube excluded; falls back on failure.
- Desktop user-agent, HTML5 fullscreen video, HTTP confirm dialog, per-site
  scheme filtering, SSL-error prompt.

## What's different — more privacy
- **No analytics.** AABrowser POSTs an `app_open` event with a persistent UUID to
  `stats1.kododake.work` on every launch. That whole path is gone — no tracker,
  no stored ID, no network calls the user didn't start.
- **System CAs only.** `network_security_config` trusts the system store, *not*
  user-installed CAs, so a locally added cert can't silently MITM traffic.
- **Third-party cookies blocked** and **Safe Browsing off** (it sends visited
  URLs to Google).
- **Session wiped on exit** — cookies, cache, history and web storage cleared
  when the app is finished (incognito-style default).
- **No form/password saving**; `allowBackup=false`; `DNT`/GPC advertised to pages.
- Default search is **DuckDuckGo**.

## Menu (⋮ button)
The button fades out after 4 s and comes back when you touch the page.
- **Address / search**: type a URL, host or IP, or anything else to search DuckDuckGo
- **Voice search**: hands-free, uses on-device recognition when available
- Back / Forward / Reload / Home, plus a loading bar at the top
- **Bookmarks**: add, open, remove (kept across the wipe-on-exit)
- **Share page** through the Android share sheet
- **Desktop site** on/off (default on, for wide head-unit screens)
- **Text size**, 75–200%, remembered
- **Extensions**
- **Clear browsing data**: cookies, cache, history, storage and site permissions

## Extensions (userscripts)
Tap the **⋮** button (bottom-right) → **Add**, then paste a userscript or an
`https://` link to a `.user.js`. Opening any `.user.js` link in the browser
also offers to install it. Every install asks first and shows which sites the
script runs on. You can switch scripts on/off or remove them from the same menu.

- Supports `@name`, `@match`/`@include` (Chrome match patterns) and
  `@run-at document-start` (default is after the page loads).
- **No `GM_*` APIs**, on purpose: scripts get only the page's own privileges,
  with no cross-origin requests and no hidden storage.
- Downloads over https only, system CAs only, 1 MB cap.
- Chrome/Firefox `.crx`/`.xpi` extensions can't run in Android WebView.

## Build
Open in Android Studio and Run, or from a shell with JDK 17–24 and the Android SDK
(set `sdk.dir` in `local.properties`):

```
./gradlew assembleDebug    # app/build/outputs/apk/debug/
./gradlew assembleRelease  # app/build/outputs/apk/release/
```

**Release signing:** put a `keystore.properties` next to `settings.gradle.kts`
(it's git-ignored) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
Without it the release APK is built unsigned.

Signed APKs are attached to the GitHub Releases. Release signing certificate SHA-256:
`81:da:31:bf:df:0d:8e:80:8e:27:65:99:9f:bd:55:22:b0:b6:de:c9:7f:08:77:ca:a2:6c:53:18:dc:c0:fa:a0`

## Note
Declaring the car-app categories makes head units *run* the app; it does not
make it an officially approved Android Auto app. Phone projection (Android Auto)
still gates non-Play apps separately — that's out of scope here, same as the
original.

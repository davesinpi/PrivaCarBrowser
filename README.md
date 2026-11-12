# PrivaCar

[![Release](https://img.shields.io/github/v/release/davesinpi/PrivaCarBrowser)](../../releases)
[![License](https://img.shields.io/github/license/davesinpi/PrivaCarBrowser)](LICENSE)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Android Automotive](https://img.shields.io/badge/Android_Automotive-head_units-4285F4?logo=android&logoColor=white)

> **Read the [DISCLAIMER](DISCLAIMER.md) before installing or using this app.**
> Do not interact with it while driving.

A privacy-focused web browser for Android Automotive head units: one full-screen
`WebView` that declares itself to the car as a distraction-optimized
navigation/media app, so Android Automotive OS will run and draw it.

## What it does
- Full-screen WebView launched as a car app (`CAR_LAUNCHER` + `NAVIGATION` +
  `APP_MAPS` categories, `distractionOptimized=true`, head-unit-sized `<layout>`).
- **Voice input polyfill:** injects `SpeechRecognition` and bridges it to the
  native `SpeechRecognizer` over a `WebMessageListener`, so sites with a mic
  button work without a keyboard.
- **Widevine robustness downgrade:** asks for `SW_SECURE_DECODE` (L3) instead of
  `HW_SECURE_*` so DRM video can play on head units without hardware-secure
  decoding. YouTube is excluded, and it falls back to the original request on
  failure.
- Desktop user agent, HTML5 fullscreen video, HTTP confirm dialog, per-site
  scheme filtering, SSL-error prompt.

## Privacy
- **No analytics or telemetry.** No tracker, no stored ID, and no network calls
  you didn't start.
- **System CAs only.** `network_security_config` trusts the system store, *not*
  user-installed CAs, so a locally added cert can't silently MITM traffic.
- **Third-party cookies blocked** and **Safe Browsing off** (it sends visited
  URLs to Google).
- **Session wiped on exit and on startup:** cookies, cache, history and web
  storage are cleared when you leave the app, and again when it starts, which
  covers sessions that ended by a swipe-away or a crash.
- **No form/password saving**, `allowBackup=false`, and `DNT`/GPC advertised to pages.
- Default search is **DuckDuckGo**.

## Menu (⋮ button)
The button fades out after 4 seconds and comes back when you touch the page.
- **Address / search**: type a URL, host or IP, or anything else to search DuckDuckGo
- **Voice search**: hands-free, uses on-device recognition when available
- Back / Forward / Reload / Home, plus a loading bar at the top
- **Bookmarks**: add, open, remove (kept across the session wipe)
- **Share page** through the Android share sheet
- **Desktop site** on/off (default on, for wide head-unit screens)
- **Text size** from 75 to 200%, remembered
- **Extensions**
- **Clear browsing data**: cookies, cache, history, storage and site permissions

## Extensions (userscripts)
Tap the **⋮** button (bottom-right), then **Extensions** → **Add**, and paste a
userscript or an `https://` link to a `.user.js`. Opening any `.user.js` link in
the browser also offers to install it. Every install asks first and shows which
sites the script runs on. You can switch scripts on/off or remove them from the
same menu.

- Supports `@name`, `@match`/`@include` (Chrome match patterns) and
  `@run-at document-start` (default is after the page loads).
- **No `GM_*` APIs**, on purpose: scripts get only the page's own privileges,
  with no cross-origin requests and no hidden storage.
- Downloads over https only, system CAs only, 1 MB cap.
- Chrome/Firefox `.crx`/`.xpi` extensions can't run in Android WebView.
- A userscript can read and change everything on the sites it runs on. Only
  install scripts you trust.

## Build
Open in Android Studio and Run, or from a shell with JDK 17 to 24 and the Android
SDK (set `sdk.dir` in `local.properties`):

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
Declaring the car-app categories makes Android Automotive head units *run* the
app. It does not make it an officially approved Android Auto or Android
Automotive app, and it does not change how the car restricts the keyboard while
driving. Phone projection (Android Auto) gates non-Play apps separately, which is
out of scope here. For the phone-projection side, see
[AutoRepack](#related-projects).

## Related projects

**[AutoRepack](https://github.com/davesinpi/AutoRepack)** is the companion
project for **Android Auto** (phone projection). The two cover the two kinds of
car screen:

| | PrivaCar | AutoRepack |
|---|---|---|
| Car platform | Android Automotive OS (built into the head unit) | Android Auto (projected from your phone) |
| Runs on | The head unit | Your phone |
| What it does | A privacy-focused web browser for the car screen | Makes an Android Auto copy of a media or messaging app you already have |
| Language | Kotlin | Java |

They solve different problems and don't depend on each other. AutoRepack copies
only show up in Android Auto if the app is a media, messaging or Car App Library
app, so it is not a way to run PrivaCar over phone projection.

## Credits
Inspired by [AABrowser](https://github.com/kododake/AABrowser). PrivaCar is an independent implementation and shares no code with it.

## License

[MIT](LICENSE). Use of the app is also subject to the [DISCLAIMER](DISCLAIMER.md).

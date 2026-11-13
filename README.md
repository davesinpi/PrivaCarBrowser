# PrivaCar

[![Release](https://img.shields.io/github/v/release/davesinpi/PrivaCarBrowser)](../../releases)
[![License](https://img.shields.io/github/license/davesinpi/PrivaCarBrowser)](LICENSE)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Android Auto](https://img.shields.io/badge/Android_Auto-Android_15%2B_phones-34A853?logo=androidauto&logoColor=white)
![Android Automotive](https://img.shields.io/badge/Android_Automotive-head_units-4285F4?logo=android&logoColor=white)

> **Read the [DISCLAIMER](DISCLAIMER.md) before installing or using this app.**
> Do not interact with it while driving.

A privacy-focused web browser for car screens: one full-screen `WebView` that
declares itself to the car as a distraction-optimized navigation/media app. It
runs on two kinds of car screen:

- **Android Auto**, projected from a phone running **Android 15 or newer**
- **Android Automotive OS**, built into the car's head unit

## Contents
- [Quick start](#quick-start)
- [Features](#features)
- [Privacy](#privacy)
- [Extensions (userscripts)](#extensions-userscripts)
- [Build from source](#build-from-source)
- [Limitations](#limitations)
- [Related projects](#related-projects)

## Quick start

Download `PrivaCar-1.0.apk` from the [latest release](../../releases/latest).
*(Optional)* check it's genuine: the signing certificate's SHA-256 must be
`81:da:31:bf:df:0d:8e:80:8e:27:65:99:9f:bd:55:22:b0:b6:de:c9:7f:08:77:ca:a2:6c:53:18:dc:c0:fa:a0`
(`apksigner verify --print-certs PrivaCar-1.0.apk`).

Then follow the section for your car.

### Android Auto (phone)

**Requirements**
- **A phone running Android 15 or newer.** On Android 14 and older, Android Auto
  does not show apps like PrivaCar, even though it installs (see
  [Limitations](#limitations)). On those phones, use
  [Curbside](https://github.com/davesinpi/Curbside) instead.
- **Android Auto** with **Unknown sources** turned on (steps below).
- A car or head unit that supports Android Auto (USB or wireless).

**Install**
1. Install `PrivaCar-1.0.apk` on the phone (open the file and allow installing
   from that source, or use `adb install PrivaCar-1.0.apk`). Android may show a
   Play Protect warning for a sideloaded app; choose *More details* → *Install
   anyway*.
2. Turn on **Unknown sources** in Android Auto: open Android Auto's settings, tap
   **Version** 10 times to unlock developer mode, then **⋮** → **Developer
   settings** → turn on **Unknown sources**.
3. Connect the phone to the car. **PrivaCar** appears in Android Auto's app
   launcher.

### Android Automotive (built-in head unit)

**Requirements**
- **A head unit running Android Automotive OS 10 (API 29) or newer.**
- **A way to install an APK on it.** Usually that means turning on developer
  options and ADB debugging on the head unit. Many production cars don't allow
  this. Check yours, and your warranty, first.

**Install**
1. With the head unit connected over ADB: `adb install PrivaCar-1.0.apk`
2. Open **PrivaCar** from the car's app grid.

> **Try it without a car:** in Android Studio's Device Manager, create a device
> from an **Automotive** system image (for example "Automotive (1024p landscape)"),
> start it, and `adb install` the APK.

### Both
- **An up-to-date Android System WebView** (it powers the browser and the voice
  bridge).
- **An internet connection.**
- *Optional:* a speech recognition service, such as the Google app, for
  **Voice search**. It's the only way to search while the car is moving, because
  the car disables the keyboard while driving.

### First steps
1. PrivaCar opens on DuckDuckGo. Tap the **⋮** button (bottom-right) to open the
   menu. It fades out after a few seconds; touch the page to bring it back.
2. Choose **Address / search** and type an address (`example.com`) or a search.
   While driving, use **Voice search** instead.
3. Use **Bookmarks** to save pages, and **Text size** to make pages easier to read
   on the car screen.
4. Leave with the car's **Back** button. Cookies, cache and history are wiped
   when you leave and again on the next start. Bookmarks, extensions and
   settings are kept.

## Features
- **Full-screen browser** that the car platform treats as a navigation/media app
  (`CAR_LAUNCHER` + `NAVIGATION` + `APP_MAPS` categories,
  `distractionOptimized=true`, head-unit-sized window).
- **Voice input for websites:** sites with a microphone button (search boxes,
  for example) work through the native speech recognizer.
- **DRM video on head units:** asks streaming sites for software-level Widevine
  decoding (L3) when hardware-secure decoding isn't available. YouTube is
  excluded, and the original request is used if that fails.
- **Desktop or mobile layout** switch, HTML5 fullscreen video, loading bar.
- **Menu (⋮):** Address / search, Voice search, Back / Forward / Reload / Home,
  Bookmarks, Share page, Desktop site on/off, Text size (75 to 200%),
  Extensions, Clear browsing data.
- **Safety prompts:** confirm before plain `http://` pages and certificate errors;
  links to `intent:`, `tel:` and other non-web schemes are blocked.

## Privacy
- **No analytics or telemetry.** No tracker, no stored ID, and no network calls
  you didn't start.
- **System certificates only.** User-installed CAs are not trusted, so a locally
  added certificate can't silently intercept traffic.
- **Third-party cookies blocked** and **Safe Browsing off** (it sends visited
  URLs to Google).
- **Session wiped on exit and on startup:** cookies, cache, history and web
  storage are cleared when you leave, and again when the app starts, which
  covers sessions that ended by a swipe-away or a crash.
- **No form or password saving**, `allowBackup=false`, and Do Not Track / Global
  Privacy Control reported to page scripts (`navigator.doNotTrack`,
  `navigator.globalPrivacyControl`).
- Default search engine is **DuckDuckGo**.

## Extensions (userscripts)
Open the **⋮** menu, then **Extensions** → **Add**, and paste a userscript or an
`https://` link to a `.user.js` file. Opening any `.user.js` link in the browser
also offers to install it. Every install asks first and shows which sites the
script runs on. You can switch scripts on/off or remove them from the same menu.

- Supports `@name`, `@match`/`@include` (Chrome match patterns) and
  `@run-at document-start` (default is after the page loads).
- **No `GM_*` APIs**, on purpose: scripts get only the page's own privileges,
  with no cross-origin requests and no hidden storage.
- Downloads over https only, system certificates only, 1 MB limit.
- Chrome/Firefox `.crx`/`.xpi` extensions can't run in Android WebView.
- A userscript can read and change everything on the sites it runs on. Only
  install scripts you trust.

## Build from source

### Requirements
- **JDK 17 to 24** (Android Studio's bundled JDK works)
- **Android SDK** with **SDK Platform 37** and **Build-Tools 36.0.0**
- Gradle is downloaded automatically by the wrapper (`gradlew`)

Point Gradle at your SDK with a `local.properties` file in the project root
(git-ignored). Use forward slashes, even on Windows:

```
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

### Build and test
```
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/
./gradlew assembleRelease     # app/build/outputs/apk/release/
```

**Release signing:** put a `keystore.properties` next to `settings.gradle.kts`
(git-ignored) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
Without it the release APK is built unsigned.

### Project layout
| Path | What's there |
|---|---|
| `app/src/main/kotlin/.../MainActivity.kt` | Browser, menu, permissions, session wipe |
| `app/src/main/kotlin/.../Extensions.kt` | Userscript parsing, matching and storage |
| `app/src/main/kotlin/.../SpeechRecognitionBridge.kt` | Native speech recognizer bridge |
| `app/src/main/assets/inject.js` | Page scripts: voice polyfill, DRM request, privacy signals |
| `app/src/main/AndroidManifest.xml` | Car-platform declarations |
| `app/src/test/` | Unit tests |

## Limitations
- **Android Auto needs a phone on Android 15 or newer.** On Android 14 and older,
  Android Auto only shows media, messaging and Car App Library apps, so PrivaCar
  installs but never appears in the car. This was tested on an Android 14 phone:
  it did not appear when sideloaded, when marked as a Play Store install, or when
  repacked with [AutoRepack](#related-projects). For Android 14 phones, see
  [Curbside](https://github.com/davesinpi/Curbside), which uses a different route.
- Declaring the car-app categories makes the car *run* the app. It does not make
  it an officially approved Android Auto or Android Automotive app, and the car
  still disables the keyboard while driving.
- Tested on the Android Automotive emulator (Android 15) and an Android phone
  emulator. Android Auto on an Android 15+ phone, voice search, the DRM video
  path and real head units have not been tested yet.
- The home screen's map panel on Android Automotive does not use PrivaCar.

## Related projects

Three independent projects for getting more out of Android Auto. They don't depend on each other.

| | PrivaCar | [Curbside](https://github.com/davesinpi/Curbside) | [AutoRepack](https://github.com/davesinpi/AutoRepack) |
|---|---|---|---|
| What it does | Privacy-focused web browser for the car screen | Web and video browser for the car screen | Makes an Android Auto copy of a media or messaging app you already have |
| Android Auto phones | Android 15 and newer | **Android 14 and older** (and newer) | Any |
| Android Automotive head units | Yes | No | No |
| Language | Kotlin | Kotlin | Java |

Which one? On **Android 15+** or a built-in Android Automotive head unit, use PrivaCar.
On **Android 14 and older**, Android Auto won't show PrivaCar (repacking it doesn't help,
tested), so use Curbside. For a media or messaging app that isn't on Android Auto, use
AutoRepack.

## Credits
Inspired by [AABrowser](https://github.com/kododake/AABrowser). PrivaCar is an independent implementation and shares no code with it.

## License

[MIT](LICENSE). Use of the app is also subject to the [DISCLAIMER](DISCLAIMER.md).

# LightBrowser — Tech Stack & What Was Added

> Minimal WebView browser (< 3 MB APK) inspired by **WibgarBrowser** analysis. 4-tab workspace + userscript engine. This doc summarizes the current stack and everything added on top of the vanilla template so you can give relevant suggestions.

---

## 1. Tech Stack

| Layer | Choice | Why / Notes |
|-------|--------|-------------|
| **Language** | **Kotlin** (`org.jetbrains.kotlin.android`, JVM 17) | Concise, coroutines for shell/IO |
| **Build** | **Gradle 8.7**, **Android Gradle Plugin** (via `com.android.application`), **JDK 17 Temurin** | `compileSdk 34`, `targetSdk 34`, `minSdk 21` (covers 99%+), `namespace com.lightbrowser` |
| **UI Framework** | **Android Views + ViewBinding** (no Compose) — `ConstraintLayout 2.1.4`, `LinearLayout`, `MaterialCardView`, `RecyclerView 1.3.2` | Keeps dex small, no Compose runtime (~1 MB saved) |
| **Design System** | **Material 3** (`com.google.android.material:material:1.11.0`, `Theme.Material3.DayNight.NoActionBar`) + `AppCompat 1.6.1` | Dark `bg_dark #0B0F19`, `surface #121827`, pill buttons `24dp`, cards `20dp`, `bottomNavigationStyle` |
| **Core libs** | `androidx.core:core-ktx 1.12.0` (WindowInsets), `fragment-ktx 1.8.2`, `webkit 1.8.0` (WebViewCompat), `recyclerview 1.3.2`, `constraintlayout 2.1.4` | **No** heavy deps: no ExoPlayer, no Chaquopy, no Room — file-based storage |
| **Async** | **Kotlin Coroutines** (`Dispatchers.IO`/`Main` via `kotlinx.coroutines` from `fragment-ktx`) | `TerminalFragment` shell `Runtime.exec` with 5s timeout, `Music` ID3 off main thread is sync but guarded |
| **Storage** | `SharedPreferences` JSON (`ScriptStorage`, `HistoryStorage`, `BookmarkStorage`, `Prefs` via `AppCtx`) + `java.io.File` sandbox (`filesDir/sandbox`, `filesDir/sandbox/music`) | No DB to keep APK small; `SharedPreferences` `lb_prefs` for `homePage/jsEnabled/desktopMode/adBlock` |
| **Web** | `android.webkit.WebView` (`LAYER_TYPE_SOFTWARE` for WTR flicker fix), `ServiceWorkerController` (API 24+), `CookieManager`, `WebStorage` | Exact replication of Wibgar `fq1:311-730` WebSettings |
| **Media** | `android.media.MediaPlayer` + `MediaMetadataRetriever` (~8 MB RAM, pauses on `onHiddenChanged`) | No ExoPlayer (~15 MB) |
| **File access** | **SAF** (`ActivityResultContracts.OpenDocument` / `CreateDocument` / `OpenDocumentTree` + `DocumentFile`) + `FileProvider` (`@xml/file_paths`) | Scoped-storage compliant, no `MANAGE_EXTERNAL_STORAGE` |
| **Shell** | `Runtime.getRuntime().exec(arrayOf("sh","-c",cmd))` + `BufferedReader` | `ping`/`curl`/`ls`/`cat` in Terminal |
| **Signing** | Persistent `app/lightbrowser.jks` (JKS, `lightbrowser123`) used for both `debug` & `release` (`signingConfigs.create("lightbrowser")`) | Avoids `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, Play Store upload-key ready |
| **Shrinking** | `isMinifyEnabled=true`, `isShrinkResources=true`, `proguard-android-optimize.txt`, `vectorDrawables.useSupportLibrary`, `abiFilters arm64-v8a/armeabi-v7a` | APK ~1.9–2.3 MB release |
| **CI** | **GitHub Actions** `.github/workflows/build.yml` (`ubuntu-latest`, `actions/checkout@v4`, `setup-java@v4`, `./gradlew assembleDebug assembleRelease --stacktrace --info`, `apksigner verify`, artifact upload) | Phone with limited RAM/Internet — no local SDK needed; `gh` CLI for logs |

---

## 2. What Was Added (on top of vanilla 4-placeholder app)

### 2.1 Project Foundation (from `README.md` vanilla)
Vanilla had 4 placeholder tabs (`Browser / Scripts / Downloads / Settings`) with no logic.

### 2.2 Wibgar Forensics → Browser Core (`BrowserFragment.kt:47`, 767 lines)
- **Decompiled `WibgarBrowser` (`Wibgar/` via apktool, `fq1.smali`, `aw1.smali`)**:
  - `ServiceWorkerController.getInstance()` + `serviceWorkerWebSettings.allowFileAccess/allowContentAccess = true` (`fq1:311`) — critical for `Worker(blob:)` + `fetch` in userscripts.
  - Exact `WebSettings` `fq1:595-730`: `domStorageEnabled`, `databaseEnabled`, `allowFileAccess/ContentAccess`, `databasePath`, `setSupportZoom`, `builtInZoomControls`, `useWideViewPort`, `setSupportMultipleWindows`, `mixedContent ALWAYS_ALLOW`, `safeBrowsing`, `LAYER_TYPE_SOFTWARE` (vs Wibgar `HARDWARE` to fix WTR fixed-panel flicker).
  - `BlobDownloader` bridge `addJavascriptInterface("BlobDownloader"/"LightBlobBridge")` + XHR `blob → FileReader.readAsDataURL → onBlobDownload` (`gr1:1402`).
  - `aw1:708` `visibilityState/hidden` override to keep `document.hidden==false` for `wtr-lab.com` background throttling, `aw1:792` delayed inject `350ms` after `onPageFinished`.

### 2.3 Userscript Engine (`data/Script.kt:40`, `ScriptStorage.kt`, `BrowserFragment.kt:365`)
- `UserScript.fromCode(raw)` parses `// ==UserScript== @name @match @include @grant @run-at @description`.
- **Permissive `@match` engine** (`globToRegex` + `globToPrefixRegex` + fuzzy `host+literals-in-order`): handles `https://wtr-lab.com/*/novel/*/chapter-*` deep-slag → all 3 WTR patterns now match (`Strict → Prefix → Trimmed → Fuzzy → Host`).
- Per-script `(function(){try{code}catch(e){console.error}})()` wrapped, `GM_*` polyfill (`GM_setValue/getValue/addStyle/xmlhttpRequest/unsafeWindow`) via `localStorage` only when `grant` contains `GM_*`.
- `onPageStarted(document_start)` + `onPageFinished` delayed `document_end/idle` (350 ms) + `Toast` + `consoleLogs[150]`.
- **Tester dialog** (`showTestDialog()`): current URL, `matched vs all`, `lastInjectInfo` timestamp, `consoleLogs` (60 lines, 420dp ScrollView), `Re-inject / Force inject (ignore @match) / Clear / Copy / Run custom JS / Check WTR panel / Test fetch` — buttons built **before** `dlg.show()` to avoid `IllegalStateException`.

### 2.4 4-Tab Workspace + Navigation (`MainActivity.kt:20`, `activity_main.xml:10`, `menu/bottom_nav_menu.xml`)
- **Bottom nav**: `Browser | Files | Terminal | Music` (`BottomNavigationView`, `surface` opaque, `elevation 3dp`, `labelVisibilityMode labeled`).
- **Fragment management**: `fragments` map `hide/show` retain `WebView` (no `replace` → no reload), `currentId`, `getFrag()`, `switchTab()`/`switchToTab()`/`switchToBrowser(url)`.
- **Bug fixes**: `isStateSaved → commitAllowingStateLoss`, `isNavSyncing` flag to prevent `selectedItemId = id` → listener → double `add` recursion (`IllegalStateException: Fragment already added` before `commit`), `try/catch` around `hide/show/add/commit`, `onBackPressed` WebView `canGoBack()` else return to Browser.
- **Restoration**: `savedInstanceState != null` re-populates `fragments` from `supportFragmentManager.fragments`.

### 2.5 Edge-to-Edge & Window Insets (Bug 1 fix, `MainActivity.kt:48`, `BrowserFragment.kt:259`)
- `WindowCompat.setDecorFitsSystemWindows(window,false)` + `statusBarColor/navigationBarColor = transparent`.
- `MainActivity` `ViewCompat.setOnApplyWindowInsetsListener(binding.root)` → `container.updatePadding(top=statusBars.top, bottom=ime>0?0:navBars)` + `bottomNav.updatePadding(bottom=navBars)` + `requestApplyInsets` — replaces per-fragment `toolbarCard.topMargin = statusBars.top+10dp` hack (doubling & unreliable dispatch). `BrowserFragment.webView` only handles `ime.bottom`.

### 2.6 File Manager (`FileManagerFragment.kt:26`, 338 lines, `fragment_filemanager.xml`)
- Sandboxed to `filesDir/sandbox` + `Downloads` only (`allowed = startsWith(sandbox) || startsWith(downloads)`), `currentDir` guard `::isInitialized`, `navigateUp()` clamped.
- **SAF**: `importLauncher OpenDocument(*/*) → copy to sandbox`, `exportLauncher CreateDocument(*/*)`, `showFileManagerMenu` PopupMenu (`Import/Export/Open Sandbox/Open Downloads`), `tvPath` click/long-press.
- `RecyclerView` (`item_download.xml`) with `tvTitle/tvStatus` (`📁/📄`, `KB`, `MM-dd HH:mm`), `onClick openDir/openFile (FileProvider)`, `onLongClick showFileOptions` (`Open/Share/Export/Rename/Delete/Details`, `Copy to Sandbox` for Downloads).
- `shareFile`, `deleteFile`, `renameFile`, `showDetails`, `openFile` (chooser, `FLAG_GRANT_READ_URI_PERMISSION`), all `try/catch` + `safeToast`, `_b ?: return` guards, `registerForActivityResult` moved to `onCreate()` nullable launcher (avoids `Fragment not associated` crash), `onCreateView` fallback `TextView`.

### 2.7 Terminal (`TerminalFragment.kt:20`, 210 lines, `fragment_terminal.xml`)
- **UI**: `svLogs tvLogs` (`#C8CDF3` mono), `etInput` + `btnSend ▶`, `btnClear/Copy/Scripts`, `tvStatus ● idle`.
- **Commands**: `help/clear/scripts/history/js <code>/sh|shell|exec <cmd>/ping <host>/curl <url>/ls [path]/cat <file>/echo/python stub/ua/cache` — unknown → `sh`.
- **Exec**: `b.etInput.text` → `append("$ cmd")` → `execCmd`; `sh` via `Runtime.exec(arrayOf("sh","-c",cmd))` `Dispatchers.IO` 5s timeout, `withContext(Main)` trimmed 2000 chars, `findBrowser()?.runJs` for `js`/`ua`.
- **Hardening**: `onCreateView` fallback, `_b?.tvLogs ?: return`, `scope = CoroutineScope(Main)` (not lifecycle — guarded), all `try/catch`, `ClipData` copy.

### 2.8 Music Player (`MusicPlayerFragment.kt:27`, 253 lines, `fragment_music.xml`)
- **UI**: `imgArt` (`embeddedPicture` via `MediaMetadataRetriever`), `tvTitle/tvArtist`, `SeekBar` (`thumbTint purple_500`, 500ms `Handler` `updateRunnable`), `tvCurrent/tvDuration`, `btnPrev/Play/Next`, `btnAddFolder/Scan/Stop`, `recycler` (`item_download`) + `empty` + footer `pauses when hidden`.
- **Queue**: `Environment.getExternalStoragePublicDirectory(DOWNLOADS)` + `filesDir/sandbox/music` + `customFolders` (`distinctBy absolutePath`), filter `mp3/m4a/aac/ogg/wav/flac/opus`, `MediaMetadataRetriever` ID3 `TITLE/ARTIST` + art `BitmapFactory`.
- **Playback**: `MediaPlayer` `setDataSource(Uri.fromFile)` `prepareAsync` `onPrepared start` `onCompletion next` `onError toast`, `toggle/prev/next/stop`, `stopPlayer` `stop/release`.
- **SAF**: `folderPicker OpenDocumentTree()` → `takePersistableUriPermission` → `DocumentFile` → copy `.mp3` to `sandbox/music` → `scan()`.
- **Hardening**: `folderPicker` nullable registered in `onCreate`, `_b` guards (`_b?.btnPlay`), `try` around `MediaPlayer` `currentPosition/duration`, `handler.removeCallbacks` in `onDestroyView`, `scan()` `_b ?: return`.

### 2.9 Other Tabs (still placeholder but stable)
- `ScriptsFragment.kt:21` (122 lines): `RecyclerView` `Adapter` (`tvName/tvDesc/tvMatches`, `SwitchMaterial`, `Edit/Delete`), `ScriptStorage` CRUD, `showEditor` parses `UserScript.fromCode`, `Test match` toast.
- `DownloadsFragment.kt:21` (109 lines): `DownloadManager.Query` + `Downloads` folder `listFiles` (30, `MM-dd`), `FileProvider` open, `btnOpenFolder` chooser.
- `SettingsFragment.kt:15` (68 lines): `Prefs.homePage/jsEnabled/desktopMode/adBlock`, `etHome`, `swJs/Desktop/Adblock`, `btnClearCache (CookieManager/WebStorage/cacheDir.deleteRecursively)`, `btnClearHistory` reset.
- `PlaceholderFragment.kt:10` (36 lines): generic `title/desc/icon`.

### 2.10 Data Layer (`data/`)
- `Script.kt:5` (121 lines) `UserScript` data class + `parseMeta/fromCode/globToRegex/matchesUrl`.
- `ScriptStorage.kt:12` (68 lines) `SharedPreferences` `scripts` JSON `all/add/update/delete`.
- `Prefs.kt:8` (31 lines) `AppCtx.init`, `homePage` default `https://www.google.com`, `jsEnabled true`, `desktopMode`, `adBlock`.
- `HistoryStorage.kt:10` (84 lines) `history_v1` `MAX 200`, `add/all/clear`, `BookmarkStorage` `toggle/isBookmarked/all/clear`.
- `DownloadHelper.kt:12` (69 lines) `BlobBridge(@JavascriptInterface onBlobDownload/onBlobData)`, `enqueue` via `DownloadManager`.

### 2.11 UI Polish
- `themes.xml:3` `Theme.LightBrowser` `Material3.DayNight.NoActionBar` `statusBarColor transparent`, `bottomNavigationStyle`, `Widget.LightBrowser.Button.Pill (24dp)`, `ShapeAppearance 20/24dp`.
- `colors.xml:10` `bg_dark #0B0F19`, `surface #121827`, `purple_500 #7C6FFF`.
- `drawable/bg_url_bar.xml`, `bg_btn_go.xml`, `bg_badge.xml`, `activity_main.xml` `ConstraintLayout` `FrameLayout#container` + `BottomNavigationView`.

---

## 3. Current Architecture

```
MainActivity (WindowInsets + fragment hide/show + isNavSyncing)
├─ BrowserFragment (WebView, ServiceWorker, BlobBridge, userscript inject, history/bookmarks)
├─ FileManagerFragment (sandbox SAF, RecyclerView)
├─ TerminalFragment (Coroutines shell + JS bridge)
├─ MusicPlayerFragment (MediaPlayer + SAF import)
├─ Scripts/Downloads/Settings/Placeholder (CRUD)
└─ Data: Prefs/AppCtx/ScriptStorage/HistoryStorage/DownloadHelper
Build: Gradle 8.7 / AGP / JDK17 / GitHub Actions → signed APK (lightbrowser.jks)
```

---

## 4. What to Suggest Next — Prompt for You

Now that you have the stack + added features, relevant suggestions could be:

- **Performance/size**: e.g., “Your `MediaPlayer` + `MediaMetadataRetriever` blocks UI on `setDataSource` — consider `ExoPlayer`? But that’s +15 MB, maybe keep `MediaPlayer` and move `getId3` to `Dispatchers.IO`.”
- **Stability**: e.g., “`TerminalFragment` `scope = CoroutineScope(Main)` leaks — use `viewLifecycleOwner.lifecycleScope`; `Music` `handler` leaks — clear in `onPause`.”
- **Security**: e.g., “`addJavascriptInterface` on API <17 is unsafe, but `minSdk 21` is ok; still restrict to `https` only.”
- **Features**: e.g., “Add `GM_notification`, `GM_download`, `@run-at document_start` pre-inject via `shouldInterceptRequest` HTML injection, or `WebMessage` for `GM_xmlhttpRequest` CORS.”
- **UX**: e.g., “Bottom nav `Files` should request `READ_MEDIA_AUDIO` runtime permission on Android 13+ before `scan()`.”
- **Storage**: e.g., “Migrate `SharedPreferences` JSON to `DataStore` or Room for >200 scripts.”
- **Testing**: e.g., “Add `eslint` for userscript `globToRegex` fuzzy matching edge cases (`*://*.example.com/*`).”

Please suggest based on **minimal APK**, **WTR scraping memory** (keep ~1.9 MB, pause music when hidden), and **Scoped Storage** constraints.

---
*Generated `2026-09-02` from `MainActivity.kt:1`, `BrowserFragment.kt:1`, `app/build.gradle.kts:1`, `.github/workflows/build.yml:1`. Last builds: `c4d1d85 ✓`, `2abd5c7 ✓`, `b5e9dfc ✓`.*

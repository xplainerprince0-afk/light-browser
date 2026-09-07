# LightBrowser — Project Overview

> Minimal browser + sandbox + Alpine terminal + audiobook player, now **100% Jetpack Compose + Material 3 Expressive** (v3.0). Single-activity, no Fragments.

## App purpose
Lightweight everyday browser with a sandboxed file workspace, an Alpine Linux terminal, and an audiobook/music player with background playback. Scoped-storage compliant (SAF), no heavy backend.

## Feature to-do
- [x] Browser: WebView, tabs, history, bookmarks, userscript engine (GM_* polyfill), adblock, desktop UA, blob downloads, share
- [x] Files: sandbox browser, search/sort/grid, breadcrumb, import/export (SAF), new folder/rename/delete/details, storage meter
- [x] Terminal: Alpine bootstrap, sandboxed shell, history, sticky CTRL/ALT, cursor scrub, font size, selectable output
- [x] Player: ExoPlayer + MediaSession background playback, novels/chapters, shuffle/repeat/speed/sleep timer, queue, mini-player morph
- [x] Scripts manager, Downloads, Settings (theme, search engine, data)
- [ ] Screenshot/preview pass on foldable/tablet

## Tech stack
Kotlin 2.3.0, AGP 9.3.2, Gradle 9.5.0, compileSdk/targetSdk 37, minSdk 23, JDK 17.
Compose BOM 2025.09.01 (M3 Expressive), NavigationSuiteScaffold, Coil 2.7.0, Lifecycle ViewModel, coroutines, Media3 ExoPlayer 1.11.0 (verified via official release notes; not in workshop list), DocumentFile SAF.
`SharedPreferences` JSON storage (no DB — keeps app lean). WebView via `AndroidView`.

## Folder structure
```
app/src/main/java/com/lightbrowser/
  MainActivity.kt            single-activity Compose entry (edge-to-edge, drawer+tabs)
  LightBrowserApp.kt         app init (WebView data-dir suffix)
  PlayerService.kt           MediaSessionService for background audio
  data/                      Prefs, AppCtx, Script{,Storage}, HistoryStorage,
                             DownloadHelper, BrowserProfile, AlpineEnv
  ui/theme/                  Theme.kt, Color.kt, Shape.kt
  ui/browser/                BrowserScreen.kt, BrowserViewModel.kt
  ui/files/                  FilesScreen.kt, FilesViewModel.kt
  ui/terminal/               TerminalScreen.kt, TerminalViewModel.kt
  ui/music/                  MusicScreen.kt, MusicViewModel.kt
  ui/scripts/                ScriptsScreen.kt
  ui/downloads/              DownloadsScreen.kt
  ui/settings/               SettingsScreen.kt
```

## Target size
Was ~2 MB (Views). Compose + M3 + Coil + Media3 target **~10–15 MB APK** (user-approved).

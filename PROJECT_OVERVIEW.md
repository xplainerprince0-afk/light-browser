# LightBrowser — Project Overview

> Minimal browser + sandbox + Alpine terminal + audiobook player, now **100% Jetpack Compose + Material 3 Expressive** (v3.0). Single-activity, no Fragments.

## App purpose
Lightweight everyday browser with a sandboxed file workspace, an Alpine Linux terminal, and an audiobook/music player with background playback. Scoped-storage compliant (SAF), no heavy backend.

## Feature to-do
- [x] Browser: TRUE multi-WebView tabs (pool 4, per-tab history), history, bookmarks, userscript engine (GM_* polyfill), adblock (host+path), desktop UA (per-site), blob downloads (escaped bridge), share, reader retry/copy/share, find debounce, agent bridge (token on all endpoints, no Main deadlock)
- [x] Files: sandbox browser (canonical guard + name sanitize), search/sort/grid, breadcrumb, import/export (SAF, to current dir, unique names), new folder/rename/delete/details, storage meter, zip-bomb guard, preview selectable+copy
- [x] Terminal: Alpine bootstrap (HTTP check, arch fix, busybox exec, sh link), sandboxed shell (quote-safe, concurrent stderr drain, busy guard, re-entrancy guard), history, sticky CTRL/ALT (ALT consumed), ESC \u001B, font size, selectable output
- [x] Terminal: Agent bridge panel (server start/stop, recorder, tappable `b` cmds, alias how-to), `b serve on|off`, bottom tabs pinned below keyboard (IME excluded from outer insets)
- [x] Terminal: `b` tab/cookies-set-clear/history/downloads/submit/read/shot-full/hover/select/key/store-named/mkext-script (EXEC+PTY HTTP routes, measured keyboard lift)
- [x] Terminal: downloadable toolbox (`toolbox`, `toolbox-install essentials|agent|all`, remove/update via apk; repos+DNS auto-seed; zero APK cost)
- [x] Terminal: true PTY foundation (vendored Termux terminal-emulator+view Apache-2.0, libtermux.so via NDK 29 in CI, builds green)
- [x] Terminal: PTY tab (TerminalSession→TerminalView, shell or opencode TUI, restart on exit, keys hug keyboard, density-correct font)
- [x] Terminal: opencode via system linker (SELinux W^X workaround) + `opencode-diag` ELF check
- [x] Terminal: keys above keyboard w/ scoped imePadding, follow-scroll waits a frame so $ stays visible
- [x] Terminal: opencode exec self-heal (`opencode-fix`), jail hardened (separator-anchored, no sandbox-root delete)
- [x] Player: ExoPlayer + MediaSession background playback (music attrs, local wakelock, tap-to-open), novels/chapters, shuffle/repeat/speed (0.5..2)/sleep timer, queue, mini-player, error surface, atomic resume
- [x] Scripts manager (name validation), Downloads (skip active, refresh on partial), Settings (homepage normalize+autosave, theme persist, speed clamp)
- [x] Shell: singleTask deep-links, adjustPan keyboard, rememberSaveable tabs/theme, status-bar contrast, offscreen a11y/pointer block, allowBackup=false
- [x] Shell: edge-swipe tab switching replaces all bottom bars (44dp strips both sides, taps/scroll pass through; left-edge long-press opens terminal drawer), order Browser/Terminal/Sandbox/Player
- [x] Browser: cold start always homepage; background tabs can't hijack current URL (home-flash + new-tab-previous-link fixed); keyboard only autofocuses on the active tab
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
Was ~2 MB (Views). v3.0-expressive release APK: **~3.7 MB** (R8 + shrinkResources) — Compose + M3 + Coil + Media3 all fit.

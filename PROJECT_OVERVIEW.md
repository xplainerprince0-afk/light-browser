# Webloom — Project Overview

> Minimal browser + sandbox + Alpine terminal + audiobook player, now **100% Jetpack Compose + Material 3 Expressive** (v4.0). Single-activity, no Fragments.
> Package: `com.rg.webloom` (rebranded from com.lightbrowser).

## App purpose
Lightweight everyday browser with a sandboxed file workspace, an Alpine Linux terminal, and an audiobook/music player with background playback. Scoped-storage compliant (SAF), no heavy backend.

## Feature to-do
- [x] Browser: TRUE multi-WebView tabs (pool 4, per-tab history), history, bookmarks, userscript engine (GM_* polyfill), adblock (host+path), desktop UA (per-site), blob downloads (escaped bridge), share, reader retry/copy/share, find debounce, agent bridge (token on all endpoints, no Main deadlock)
- [x] Files: sandbox browser (canonical guard + name sanitize), search/sort/grid, breadcrumb, import/export (SAF, to current dir, unique names), new folder/rename/delete/details, storage meter, zip-bomb guard, preview selectable+copy
- [x] Terminal: Alpine bootstrap (HTTP check, arch fix, busybox exec, sh link), sandboxed shell (quote-safe, concurrent stderr drain, busy guard, re-entrancy guard), history, sticky CTRL/ALT (ALT consumed), ESC \u001B, font size, selectable output
- [x] Terminal: Agent bridge panel (server start/stop, recorder, tappable `b` cmds, alias how-to), `b serve on|off`, bottom tabs pinned below keyboard (IME excluded from outer insets)
- [x] Terminal: `b` tab/cookies-set-clear/history/downloads/submit/read/shot-full/hover/select/key/store-named/mkext-script (EXEC+PTY HTTP routes, hoisted-IME toolbar lift, opencode size+ELF verified install)
- [x] Terminal: `b` console upgrades — PTY parity (/dom/save/url/title/next/prev/stores/serve/alias/record), `--json`, jailed `> file`, did-you-mean, in-app command studio, toolbox installed-state + space guard, `b metrics` tap-accuracy audit auto-logged to ~/agent_metrics/metrics.log (EXEC+PTY, with WebView on-screen box)
- [x] Terminal: `b` automation — quoted selectors, `wait/links/forms/survey` senses (+routes), `do/run/replay/queue` macro engine with fail-fast + `!` soft steps, recording v3 replayable (tap/swipe/click/fill, auto-URL hops)
- [x] Terminal: `b` page tools — `reload-hard`, `ua [mobile|desktop|<string>|get]`, `viewport`, `zoom [in|out|reset]`, `scroll-top|scroll-bottom`, `find-clear`, `shot-el`, `netlog`, `clear-data`, `tabdup` (EXEC + PTY routes + help + Agent panel)
- [x] Terminal: `b block/unblock/blocks` AI no-go sites enforced at client + entries with ⛔ popup (EXEC-only mutation; PTY `blocks` lists)
- [x] Terminal: PTY copy/paste — long-press selection copies via ClipboardManager, paste reads clipboard into emulator (bracketed-paste aware), drawer Copy works in PTY; keyboard no longer auto-dismisses (focus kept, EXEC editor only grabs focus in EXEC mode)
- [x] Browser: calibrated tap pipeline — visualViewport scale + DPR cross-check, computed-style visibility (fixed elements included), batched MOVE strokes, touch-slop tap fallback, unified getLocationInWindow box
- [x] Terminal: reliable touch v2 — serialized FIFO gesture queue (no interleaved DOWN/UP drops), humanized native touches (±1px jitter, 70–120ms dwell, finger pressure/size, discrete eased swipe MOVEs), single CSS-px contract, `delivered`/`reason` on every tap/swipe (EXEC prints it, /tap//swipe return it, lastTap v2 logs it), `b tap --click` elementFromPoint fallback, new `b box` (center+bounds+safe inset points for AI variation)
- [x] Terminal: raw-touch plumbing fix — FINGER tool type + touchscreen source (Chromium dropped UNKNOWN-tool streams after accepting them), onResume nudge for parked/paused targets, `parked` flag in lastTap; toolbar trimmed (sticky CTRL/ALT covers ^C/^D, arrows clustered ←↑↓→ row 2); `ok/err` single-line result format + AI agent contract in BROWSER_AGENT.md
- [x] Files: fast folder cache (names-only count + dir-mtime probe, LRU 20, 30s usage total, ⋮ toggle, cheap details()) — revisits skip re-stat
- [x] Perf: future.cancel on every blocking-get timeout, socket/reader use-blocks, single serialized metrics.log writer, guarded PTY AndroidView updates, process-stream reader close
- [x] UI: Material 3 Expressive polish — scheme roles replace hardcoded chrome colors, shape tokens, tonal buttons + badges, ListItem/SegmentedButton/RadioButton rows, 48dp touch targets, expressive progress tracks
- [x] App: Webloom rebrand (com.rg.webloom, v4.0), compressed photo launcher icon (1.1MB → ~7KB webp), new webloom.jks signing key, R8 minify+shrinkResources release
- [x] Shell: home-style paths everywhere (`~` prompt, `pwd`/`b save`/`b shot`/recordings print `~/…`, never the app-private absolute); Files hides dotfiles by default (⋮ → Hidden files; shell keeps seeing them)
- [x] Browser: ⋮ → Record taps with floating ● count / ⏸ / ⏹ pill (hidden from screenshots); recorder v3 — clicks/fills/taps/swipes with touch point, scroll, viewport, per-action URL, cover shot, auto-save on stop, reusable JSON
- [x] Terminal: downloadable toolbox (`toolbox`, `toolbox-install essentials|agent|all`, remove/update via apk; repos+DNS auto-seed; zero APK cost)
- [x] Terminal: true PTY foundation (vendored Termux terminal-emulator+view Apache-2.0, libtermux.so via NDK 29 in CI, builds green)
- [x] Terminal: PTY tab (TerminalSession→TerminalView, shell or opencode TUI, restart on exit, keys hug keyboard, density-correct font)
- [x] Terminal: opencode via system linker (SELinux W^X workaround) + `opencode-diag` ELF check
- [x] Terminal: keys above keyboard w/ scoped imePadding, follow-scroll waits a frame so $ stays visible
- [x] Terminal: toolbar fix pass — IME no longer consumed above keys (Row 2 unburied), edge strips exclude bottom 110dp (key-scroll no longer tab-switches), unified sticky (no stuck CTRL, ALT-seqs, CTRL/ALT+Enter no double-submit), saveable ptyMode/follow/sticky/fontScale, scrollable drawer + 48dp close targets, PTY font follows drawer + env refreshes per session + keys disabled after exit
- [x] Terminal: opencode exec self-heal (`opencode-fix`), jail hardened (separator-anchored, no sandbox-root delete)
- [x] Player: ExoPlayer + MediaSession background playback (music attrs, local wakelock, tap-to-open), novels/chapters, shuffle/repeat/speed (0.5..2)/sleep timer, queue, mini-player, error surface, atomic resume
- [x] Scripts manager (name validation), Downloads (skip active, refresh on partial), Settings (homepage normalize+autosave, theme persist, speed clamp)
- [x] Shell: singleTask deep-links, adjustPan keyboard, rememberSaveable tabs/theme, status-bar contrast, offscreen a11y/pointer block, allowBackup=false
- [x] Shell: edge-swipe tab switching replaces all bottom bars (44dp strips both sides, taps/scroll pass through; left-edge long-press opens terminal drawer), order Browser/Terminal/Sandbox/Player
- [x] Shell: pinned bottom tab bar (Scaffold IME-excluded slot — keyboard slides OVER it, never shoves it up); MiniPlayer removed (Player tab + notification own playback); edge-swipe stays opt-in secondary
- [x] Shell: hierarchical Back — find/search close → web go-back → homepage → non-browser tabs unwind (search/selection/folders/hero) then return to Browser → exit arm LAST (hoisted AppTabs state)
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

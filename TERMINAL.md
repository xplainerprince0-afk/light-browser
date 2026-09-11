# LightBrowser Terminal — Handbook

Two terminals in one tab. **EXEC** = quick non-interactive shell (type, Enter,
read output). **PTY** = real Linux terminal (Termux emulator, raw mode,
alt-screen, resize) for full-screen TUIs like `opencode`.

**No top bar:** the terminal is fullscreen with a tiny dot floating top-right
(green = PTY, amber = EXEC). The **left drawer** holds the EXEC/PTY switch at
the very top (Termux style), then (in PTY) the opencode/shell target, then
sessions + `+ New session` (max 8), Agent bridge, Follow output, text size,
Rename/Paste/Copy/Clear/Kill, PTY keyboard. Open it with a **long-press on
the left edge** (taps/pass-through swipes are unaffected); tap the dimmed area
to close. Edge-swipe TAB SWITCHING is a separate opt-in (Settings →
Navigation, default OFF) on a slim middle band — it never fights the system
back gesture or the key rows. Drawer is slim (280dp) by design and scrolls.

**Keys:** two rows, **swipe sideways** for the full set (`ESC TAB / - HOME
END PGUP PGDN |` and `CTRL ALT ← ↑ ↓ → ~ : ;` — arrows clustered row 2).
Interrupt via sticky `CTRL` + letter (`CTRL+C` kills), `CTRL+Enter` while busy
in EXEC, drawer → Kill. They hug the keyboard
(keys end exactly at screenBottom − kbHeight — max(decor inset, live IME)
minus the exact reserved outer pad, measured not guessed). No focus grab at
app launch: the keyboard only opens when the Terminal tab is frontmost.

> Golden path on a fresh install:
> `install-alpine` → `toolbox-install essentials` → `opencode-install` →
> `opencode run "hello"` (EXEC) or PTY → `opencode` button (full TUI).

## EXEC mode command reference

### Setup & status

| Command | What it does |
|---|---|
| `help` | This list, in-app |
| `install-alpine` | Downloads Alpine 3.19 minirootfs into `sandbox/alpine` (~3MB) |
| `alpine-status` | Installed? Root path? |
| `toolbox` / `tools` | Lists all downloadable dev tools + sizes |
| `toolbox-install <name\|essentials\|agent\|opencode\|all>` | Installs via `apk` (needs net). `essentials` ≈ 30MB (`git ssh curl bash jq nano`), `agent` adds `rg fd fzf node(+npm) python`, `opencode` adds `build(build-base) dns wget zip trace` — everything opencode's doctor checks (bun/deno have no Alpine builds: unavailable, not a bug). Unknown names get a did-you-mean; big pulls are space-checked first |
| `toolbox-info <name>` | Description + apk packages + installed ✓/○ per package |
| `toolbox-remove <apk>` | `apk del` a package |
| `toolbox-update` | `apk update && apk upgrade` |
| `opencode-install` | Downloads Hope2333 opencode-termux (~50MB, aarch64 only) to `sandbox/bin/opencode` |
| `opencode-status` | Version + size, or "not installed" |
| `opencode-fix` | Full repair pass: binary x-bit, sidecar libs, tmpdir, linker — says exactly what's wrong and what to run next |
| `opencode-diag` | ELF interpreter, rwx bits, libs, tmpdir, linker presence — run this when exec fails |
| `cache` | App cache size |

### Files (jailed to `sandbox/`, `..` escapes denied)

| Command | What it does |
|---|---|
| `ls [path]` | `ls -la` |
| `cd [dir]` | Bare `cd` = sandbox home |
| `pwd` / `cat <file>` | Print dir / file |
| `mkdir <dir>` | Create (incl. parents) |
| `rm [-r] <file>` | Delete; `-r` for dirs. Sandbox root itself is refused |
| `cp <src> <dst>` / `mv <src> <dst>` | Copy (no overwrite) / move |
| `history` | Last browser history entries (this is browser history, not shell) |
| `scripts` | Installed userscripts ON/OFF |
| `clear` | Wipe transcript |

### Shell & net (raw `sh -c` passthrough)

| Command | What it does |
|---|---|
| `sh <cmd>` / `shell` / `exec` | Run anything (`sh "for f in *.txt; do …; done"`) |
| `run <program> [args]` | Smart launcher: ELF → system linker (beats noexec), `#!` script → its interpreter. Bare names search `$HOME/bin` first, paths stay jailed |
| `<anything else>` | Falls through to the shell too — `ls`, `grep`, `chmod`, `ln -s` all work if the binary exists |
| `apk …` | Raw apk (Alpine must be installed first) |
| `ping [host]` | `ping -c 3` (default 8.8.8.8) |
| `curl <url>` | `curl -I` (headers) |
| `echo <text>` | Print back |
| `ua` / `js` | Nudge: those run from the Browser tab |

### opencode (the AI agent)

| Command | What it does |
|---|---|
| `opencode --help` | Its own help |
| `opencode run "task"` | Non-interactive agent run (up to 300s; redirect to file for full logs: `opencode run "…" > out.txt`) |
| `opencode <anything>` | Passed straight through |

### `b` — drive the Browser tab from here

`b help` prints the list. Highlights: `b open <url>` (localhost + bare
domains OK), `b tabs | tab <n> | new <url> | close [n] | tabdup | home | back |
forward | reload | reload-hard | stop`, `b ua [mobile|desktop|<string>|get] |
viewport | zoom [in|out|reset]`, `b snap` (page refs+text), `b read [max]`
(article text), `b click <ref|name> | fill <ref|name> <val> [--submit] |
submit <form> | key [sel]` (nearest-button tap),
`b hover | select <sel> <val>`, `b store <name> <css> | stores | unstore`,
`b pos <ref|css> | box <ref|css> (coords+bounds+safe points) | tap <x> <y> [--click] | swipe <x1> <y1> <x2> <y2> [ms] | shot-el <ref|css>`,
`b find <text> | find-clear | next | prev`, `b scroll [px] | scroll-to <x> <y> |
scroll-top | scroll-bottom`,
`b js <expr> | text | dom | shot [--full] | console | netlog [n]`,
`b cookies [get [url] | set "k=v" [url] | clear] | clear-data [cookies|cache|history|storage|all] | history [n] | downloads |
save <name>`, `b serve [on|off]` (start/stop the agent HTTP server),
`b record start|stop|pause|resume|save <n>|list` (tap+touch recorder — also
in the browser ⋮ menu, with a floating ●/⏸/⏹ pill that hides itself from
screenshots; Stop auto-saves to `~/agent_recs/`. Captures clicks, fills,
taps and swipes with element, coords, scroll and viewport — replay data),
`b ext | mkext <name>` (your own script commands).

### Your own commands: aliases, scripts, macros

| Where | Format | Args? | Both modes? |
|---|---|---|---|
| `b alias <name> <expansion>` (prefs) | one-liner with `$1…$9`/`$@` | yes | EXEC dispatches; PTY via `/alias` |
| `b mkext <name>` → `~/.b-ext/<name>.sh` | shell script (`B_PORT`/`B_KEY` in PTY) | yes (`$1…`) | yes |
| `b mkcmd <name> ["c1; c2"]` → `~/.b-cmd/<name>.b` | plain b-lines (`#` comments, `!` = skip errors) | no (use alias) | yes — just type `b <name>` |

`b cmds` lists macros, `b run <name>` runs any macro/file, and the Agent
panel manages all three kinds (plus the blocklist below) without typing.

### Blocked sites (AI no-go)

`b block <domain-or-url>` blocks a host **and its subdomains** everywhere
(EXEC-only: PTY `b block/unblock` prints an EXEC-only notice; PTY `b blocks`
still lists):
`b open/new`, link taps, JS navigations, popups, and your own address bar —
every refusal prints `⛔ Blocked by you: <host>`, which is also the popup
the AI sees. `b blocks` lists, `b unblock <domain>` removes. Example: block
`discord.com` and no agent flow can wander into `discord.com/settings`
anymore. Manage it in the Agent panel too.

**Output modifiers:** append `--json` for raw machine output (no PAGE
markers), or `> file` / `>> file` to save into the sandbox instead of
printing (PTY shells already pipe natively: `b snap > page.txt`).

### Web automation (`do` / `run` / `replay` / senses)

Selectors with spaces must be quoted: `b click "div .btn"` (same for
`fill/hover/select/pos/submit/dom/store/key`).

| Command | What it does |
|---|---|
| `b wait <text\|css:sel> [ms]` | Poll until text/element appears (default 10s, max 60s) — use after clicks/navigations so macros don't race the page |
| `b links [n]` / `b forms` | All links / form fields as JSON (feeds `fill`/`click`) |
| `b survey` | One-shot bundle: url, title, viewport, console, tabs, view box, last tap |
| `b do "c1; c2; !c3"` | Chain b-commands (`;` splits, quotes respected); stops on first error unless the step starts with `!` |
| `b run <file>` | Same, from a sandbox file (`#` comments) — your saved macros |
| `b replay <rec>` | Execute a saved recording (auto-opens URLs, 1.2s pacing, `^C` stops) |
| `b queue` | Show pending macro steps |

Example — login flow in one line:
`b do "open example.com/login; wait css:#user; fill #user me; fill #pass x --submit; wait Welcome"`

### Tap-accuracy test (`b metrics`)

Contract: **everything is CSS px** — `b pos`/`b box` return CSS px, `b tap`/`b swipe`
take CSS px, scaling lives inside the app. Taps run through a serialized queue
(150ms gap, so rapid repeats can't interleave and cancel), humanized
(±1px jitter, 70–120ms dwell, finger-like pressure/size), and report delivery.

1. `b box <ref>` (or `b pos`/`b snap` for a ref) → note `x y` + safe points.
2. `b tap <x> <y>` → watch where the page reacts (`--click` adds an
   elementFromPoint click fallback for plain links; raw tap alone is a touch,
   and some pages only navigate on click).
3. `b metrics` → prints screen px vs page CSS geometry, the WebView's
   on-screen box (view x/y are WebView-top relative), and the last touch's
   `css → view` mapping **with `delivered`/`reason`** (false = the gesture
   never landed — retry), and **appends the JSON report to
   `~/agent_metrics/metrics.log`** (300 entries kept, single serialized
   writer, both EXEC and PTY log) so runs stay comparable.
   `b metrics --json` prints the raw report.
4. In PTY: `b metrics` returns the same JSON and logs it too. `/tap` and
   `/swipe` block until delivery and return `{"ok":delivered,"reason":…}` —
   `ok:false` means it never landed.

Mapping rule under test: `view px = css px × scale`, where scale is screen
density at default zoom (`b metrics` shows both). If taps land off by a
constant factor, that scale is the suspect — bring the log.

**`b` inside PTY shells:** PTY is a raw shell, so a `b()` function is
auto-installed into `~/.profile` (`b-setup` refreshes it). It talks to the
same HTTP bridge — but the **server must be running** (start it from the
Agent panel or EXEC `b serve on`) and you need `curl` (`toolbox-install
curl`). Supported there: open/new/tabs/close/tabdup/home/back/forward/reload/reload-hard/stop/
url/title/ua/viewport/zoom/find/next/prev/snap/shot-el/text/read/dom/js/netlog/shot/save/metrics/console/cookies/clear-data/history/
downloads/click/fill/submit/key/hover/select/store/stores/unstore/blocks/alias/mkext/ext/
pos/box/tap/swipe/scroll/scrollto/scroll-top/scroll-bottom/serve/record. EXEC-only:
`b unblock`, `b block` (mutating), `b do/run/replay/queue`, `b unalias`
(in PTY: `b alias remove <name>`).

### Sessions & keys

- Drawer → sessions list (tap = switch, `×` = close), `+ New session` (max 8).
  Sessions keep their own dir, history (200), and transcript.
- Keys: as above. Sticky: tap `CTRL`, then a letter = real control byte —
  works with the soft keyboard too (also `ALT+x` = `ESC x`); sticky `CTRL+C`
  (or CTRL+Enter while busy) kills the running process; drawer → Kill/Clear.
- Drawer: Follow output (auto-scroll), text bigger/smaller, rename, paste
  (works in PTY too), copy (selection in PTY), Agent bridge.
- PTY typing invisible? It was focus theft (Compose buttons stole the View's
  focus) — fixed: keys/buttons hand focus back automatically; keyboard opens
  on tap/`⌨`.

## PTY mode

- **Single target, no toggle:** opencode TUI when installed, plain shell
  otherwise (green dot = PTY either way). Quit the TUI and you get
  Restart / **Shell** / Exec — Shell drops you to a real shell without
  leaving PTY (drawer also has a PTY-target switch).
- Tapping the terminal opens the keyboard; back button hides it again.
- Typing `opencode` in the PTY shell runs an `opencode()` shell function
  (linker path) — deterministic, no toggle dance needed.
- `⌨` (drawer) forces the keyboard; focus is handed back automatically
  after every key/drawer tap (no invisible typing).
- Own key rows: same layout, but arrows/HOME/END send real escape sequences
  and sticky `CTRL`/`ALT` + soft-keyboard letter sends control bytes/`ESC x`
  through the pty (real SIGINT semantics — `CTRL+C` interrupts).
- Typing `opencode` in the PTY shell runs an `opencode()` shell function
  (linker path) — deterministic, no toggle dance needed.
- Session exits → Restart / back-to-EXEC overlay. Font follows density
  (13dip); TUI geometry tracks resizes via `SIGWINCH`.

### Why `b` is a shim in PTY (not built in)

PTY is a raw kernel pty: your keystrokes go straight to `mksh`, our app never
sees the line, so we *cannot* intercept `b …` the way EXEC does (EXEC owns its
text field). Hence `b()` is a shell function in `~/.profile` that calls the
same HTTP bridge (`b-setup` refreshes it; needs server on + `curl`). Same
commands, same results — different road (PTY now covers save/dom/url/title/
next/prev/stores/serve/alias/record too; only `b unalias` stays EXEC-only —
in PTY use `b alias remove <name>`).

## Environment, PATH & linking things (`export`)

Every EXEC command runs as **one fresh `sh -c`**, so:

```sh
export PATH=…          # prefix the app sets for you, highest priority first:
                       #   sandbox/bin : alpine/usr/local/sbin : …/bin :
                       #   alpine/usr/sbin : …/bin : alpine/sbin : alpine/bin :
                       #   /system/bin : /system/xbin
                       # sandbox/bin SHADOWS everything — your shims win.
export HOME=<sandbox> PWD=<cwd> TERM=xterm-256color
export OSTYPE=linux-musl ALPINE_ROOT=<sandbox/alpine> HOSTNAME=alpine
export TMPDIR=<sandbox>/tmp TEMP TMP BUN_TMPDIR (same value — Bun/Rust temp)
export LD_LIBRARY_PATH=<sandbox>/lib/opencode:<sandbox>/lib:<sandbox>/bin
export XDG_CACHE_HOME XDG_CONFIG_HOME XDG_DATA_HOME XDG_STATE_HOME (sandboxed)
export PS1='$ ' ENV=<sandbox>/.profile   # short `$` prompt; mksh sources $ENV
```

| Command | What it does |
|---|---|
| `export NAME=value …` | **Saved persistently** — re-applied to every EXEC command and new PTY sessions. `export` alone lists saved vars. Compound lines (`;`, `&&`) still go to the live shell |
| `unset NAME …` | Forgets saved vars (built-in defaults underneath are untouched) |
| `env` | Lists your saved vars |

Practical rules:

1. **Link a tool:** drop a shim in `$HOME/bin` (first on PATH) —
   `sh "printf '#!/system/bin/sh\nexec node /path/app.js \"$@\"\n' > $HOME/bin/myapp && chmod +x $HOME/bin/myapp"`,
   then `myapp` (or `run myapp`) works everywhere. Or symlink: `ln -s <target> $HOME/bin/name`.
   (`$HOME` = `sandbox/` — always use `$HOME/bin`, never a bare `/bin`.)
2. **Per-command env without export:** `VAR=value cmd args` (e.g.
   `OPENCODE_SERVER_PASSWORD=x opencode serve --port 4096`).
3. **Extend PATH for one command:**
   `sh "export PATH=$HOME/mytools:$PATH && which mytool"`.
   Make it permanent instead: `export PATH=$HOME/mytools:…` (saved;
   `unset PATH` restores the default).
4. **opencode + tools:** after `toolbox-install agent`, `git/node/rg/python`
   are on PATH for both EXEC and `opencode run` (same prefix). Agents acting
   with `cwd=sandbox` stay inside the sandbox.
5. **PTY sessions** start with the same env (saved exports + `$ENV` profile).
   `~/.profile` is auto-created (`PS1='$ '`, `ll`/`la` aliases) — edit it for
   your own prompt/aliases; interactive shells source it.

## Fix-this-issue cheat sheet

| Symptom | Fix |
|---|---|
| `…/bin/opencode: Permission denied` | Android ≥10 SELinux blocks direct exec of app-data files even with `+x`. The app auto-launches via `/system/bin/linker64` (`run` does this for any ELF). If it still fails: `opencode-fix`, then `opencode-diag` and read `interp=`/`libs=`. Flaky launches were the missing `.so` + missing `TMPDIR` — both fixed, but **re-run `opencode-install` once** to heal old copies |
| `library "….so" not found` | Sidecar lib missing — new installs keep `usr/lib/opencode/*.so` into `lib/opencode/` automatically; old installs: re-run `opencode-install`. `LD_LIBRARY_PATH` already covers that dir |
| `mkdir /data/local/tmp…: EACCES` | Fixed: `TMPDIR`/`TEMP`/`TMP`/`BUN_TMPDIR` now point at sandbox `tmp/` (auto-created) |
| `interp=/lib/ld-linux…` (glibc) | That build needs Termux's glibc prefix/proot — standalone run can't work; next step is the proot runner |
| `opencode not installed` | `opencode-install` (needs net, aarch64 only, ~50MB) |
| `Install Alpine first` | `install-alpine`, then retry |
| `apk` fails / offline | `apk` needs net for add/update; installed tools keep working offline. Repos + DNS are auto-seeded (`toolbox-install` does it) |
| Command hangs / long run | EXEC caps at 15s default (5–600s allowed); `opencode` gets 300s, toolbox installs 180s. `^C` / ⋮ → Kill. For more: `cmd > out.txt 2>&1` then `cat out.txt` |
| Output cut with `…truncated` | 8KB capture / 4KB echo cap — redirect to file instead |
| `(busy — Ctrl+C to kill)` | A process is still running; Enter is ignored until it ends or you kill it |
| No stdin / interactive prompts hang | EXEC closes stdin by design — pass flags/args instead (`--yes`, `< file`), or use PTY mode |
| Prompt hidden / keys misplaced | Keys lift by max(decor IME, root IME, keys IME) minus the exact Scaffold outer pad — one signal can't bury them; zero when closed. Transcript follows (`Follow output` in drawer). In PTY, keyboard opens on tap/`⌨`, hides with back |
| opencode `invalid e_shstrndx` | Truncated download — installer now verifies exact byte size + ELF sanity and deletes bad files with a retry message |
| PTY prompt shows full path | Prompt is `sandbox $ ` everywhere (env + managed `~/.profile` block) |
| Bottom tabs vanish while typing | The tab bar is pinned at the physical bottom (keyboard slides over it). Terminal keys hug the keyboard above it |
| `b …` says open the Browser tab first | Tab state lives in the Browser tab — visit it once so `TabBus` wires up |
| `Server is OFF` | `b serve on` or 🤖 → Start server; copy URL+token from the panel |
| TUI garbage in EXEC | Expected — full-screen TUIs need PTY mode, not EXEC |
| `Sandbox root` refused / `Access denied` | Jail working as intended: builtins can't leave `sandbox/` or delete its root |

## Limits (by design)

- EXEC: no stdin, no job control, 15s default timeout, truncated echo. PTY:
  full interactivity, SIGWINCH resizes, real `^C`.
- Builtins (`ls/cd/cat/mkdir/rm/cp/mv`) are jailed to `sandbox/`; raw `sh`
  runs as the app UID with sandbox cwd — agents act inside the sandbox.
- Downloads (Alpine/toolbox/opencode) cost mobile data once, then live on disk.

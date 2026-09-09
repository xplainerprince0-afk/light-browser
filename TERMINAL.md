# LightBrowser Terminal — Handbook

Two terminals in one tab. **EXEC** = quick non-interactive shell (type, Enter,
read output). **PTY** = real Linux terminal (Termux emulator, raw mode,
alt-screen, resize) for full-screen TUIs like `opencode`.

**No top bar:** the terminal is fullscreen with a tiny dot floating top-right
(green = PTY, amber = EXEC). The **left drawer** holds the EXEC/PTY switch at
the very top (Termux style), then (in PTY) the opencode/shell target, then
sessions + `+ New session` (max 8), Agent bridge, Follow output, text size,
Rename/Paste/Copy/Clear/Kill, PTY keyboard. Open it with a **long-press on
the left edge** (taps pass through, middle swipes never trigger it); tap the
dimmed area to close. Drawer is slim (280dp) by design.

**Keys:** two rows, **swipe sideways** for the full set (`ESC TAB / - HOME ↑
END PGUP PGDN |` and `CTRL ALT ^C ^D ← ↓ → ~ : ;`). They hug the keyboard
(keys end exactly at screenBottom − ime — measured, not guessed).

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
| `toolbox-install <name\|essentials\|agent\|opencode\|all>` | Installs via `apk` (needs net). `essentials` ≈ 30MB (`git ssh curl bash jq nano`), `agent` adds `rg fd fzf node(+npm) python`, `opencode` adds `build(build-base) dns wget zip trace` — everything opencode's doctor checks (bun/deno have no Alpine builds: unavailable, not a bug) |
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
domains OK), `b tabs | new <url> | close [n] | home | back | forward |
reload | stop`, `b snap` (page refs+text), `b click <ref> | fill <ref> <val>
[--submit]`, `b pos | tap <x> <y> | swipe <x1> <y1> <x2> <y2> [ms]`,
`b find <text> | next | prev`, `b scroll [px] | scroll-to <x> <y>`,
`b js <expr> | text | dom | shot | console | cookies | save <name>`,
`b serve [on|off]` (start/stop the agent HTTP server),
`b record start|stop|save <n>|list` (tap recorder).

**Make your own:** `b alias deploy 'b open https://example.com'` → `b deploy`
works forever (stored on-device, `$1…$9` + `$@` supported). `b unalias deploy`
removes it. The Agent panel (drawer → Agent bridge) shows all of these
tappable, plus server start/stop and copy-URL/token.

**`b` inside PTY shells:** PTY is a raw shell, so a `b()` function is
auto-installed into `~/.profile` (`b-setup` refreshes it). It talks to the
same HTTP bridge — but the **server must be running** (start it from the
Agent panel or EXEC `b serve on`) and you need `curl` (`toolbox-install
curl`). Supported there: open/new/tabs/close/home/back/forward/reload/stop/
find/snap/text/js/shot/console/cookies/click/fill/pos/tap/swipe/scroll/
scrollto. `record/serve/alias` stay EXEC-only (stateful, no HTTP route).

### Sessions & keys

- Drawer → sessions list (tap = switch, `×` = close), `+ New session` (max 8).
  Sessions keep their own dir, history (200), and transcript.
- Keys: as above. Sticky: tap `CTRL`, then a letter = real control byte —
  works with the soft keyboard too (also `ALT+x` = `ESC x`); `^C` (or
  CTRL+Enter while busy) kills the running process; `^D` clears.
- Drawer: Follow output (auto-scroll), text bigger/smaller, rename, paste
  (works in PTY too), copy-all, clear, kill, Agent bridge.
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
  and `^C/^D` send `0x03/0x04` through the pty (real SIGINT semantics).
- Typing `opencode` in the PTY shell runs an `opencode()` shell function
  (linker path) — deterministic, no toggle dance needed.
- Session exits → Restart / back-to-EXEC overlay. Font follows density
  (13dip); TUI geometry tracks resizes via `SIGWINCH`.

### Why `b` is a shim in PTY (not built in)

PTY is a raw kernel pty: your keystrokes go straight to `mksh`, our app never
sees the line, so we *cannot* intercept `b …` the way EXEC does (EXEC owns its
text field). Hence `b()` is a shell function in `~/.profile` that calls the
same HTTP bridge (`b-setup` refreshes it; needs server on + `curl`). Same
commands, same results — different road.

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
| Prompt hidden / keys misplaced | Content lifts once at the root by keyboard−nav-inset; keys add zero own padding (double-lift = the old gap). Transcript follows (`Follow output` in drawer). In PTY, keyboard opens on tap/`⌨`, hides with back |
| Bottom tabs vanish while typing | Replaced by a slim swipe strip (drag L/R, tap dots) — hidden while typing, returns after. No tall bar anymore |
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

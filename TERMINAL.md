# LightBrowser Terminal — Handbook

Two terminals in one tab. **EXEC** = quick non-interactive shell (type, Enter,
read output). **PTY** = real Linux terminal (Termux emulator, raw mode,
alt-screen, resize) for full-screen TUIs like `opencode`. Toggle with the
**EXEC/PTY** button in the session strip, or ⋮ → `PTY terminal`.

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
| `toolbox-install <name\|essentials\|agent\|all>` | Installs via `apk` (needs net). `essentials` ≈ 30MB (`git ssh curl bash jq nano`), `agent` adds `rg fd fzf node python` |
| `toolbox-remove <apk>` | `apk del` a package |
| `toolbox-update` | `apk update && apk upgrade` |
| `opencode-install` | Downloads Hope2333 opencode-termux (~50MB, aarch64 only) to `sandbox/bin/opencode` |
| `opencode-status` | Version + size, or "not installed" |
| `opencode-fix` | Re-chmods the binary, reports OK/failed |
| `opencode-diag` | ELF interpreter, rwx bits, linker presence — run this when exec fails |
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
removes it. The 🤖 Agent button shows all of these tappable, plus server
start/stop and copy-URL/token.

### Sessions & keys

- `+` = new session, tap name = switch, `×` = close. Sessions keep their own
  dir, history (200), and transcript.
- Keys: `ESC / - HOME ↑ END PGUP`, `CTRL ALT ^C ^D ← ↓ →`. Sticky: tap
  `CTRL`, then a letter = real control byte; `^C` (or CTRL+Enter while busy)
  kills the running process; `^D` clears; `ALT+x` = `ESC x`.
- ⋮ menu: Follow output (auto-scroll), text bigger/smaller, rename, paste,
  copy-all, clear, kill, Agent bridge.

## PTY mode

- **Shell** button = plain `/system/bin/sh` in sandbox. **opencode** button =
  relaunches the session straight into the `opencode` TUI (install it first).
- `⌨` button forces the keyboard (otherwise it opens on tap only — this avoids
  the double-lift gap).
- Own key rows: same layout, but arrows/HOME/END/PGUP send real escape
  sequences and `^C/^D` send `0x03/0x04` through the pty (real SIGINT semantics).
- Session exits → Restart / back-to-EXEC overlay. Font follows density
  (13dip); TUI geometry tracks resizes via `SIGWINCH`.

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
```

Practical rules:

1. **`export` does NOT persist between commands.** Each Enter = new process.
   Chain it: `export FOO=bar && mytool --use $FOO`. (PTY mode *does* keep a
   live shell, so exports persist there for the session.)
2. **Link a tool:** drop a shim in `sandbox/bin` (first on PATH) —
   `sh "printf '#!/system/bin/sh\nexec node /path/app.js \"$@\"\n' > $HOME/bin/myapp && chmod +x $HOME/bin/myapp"`,
   then `myapp` works everywhere. Or symlink: `ln -s <target> $HOME/bin/name`.
3. **Per-command env without export:** `VAR=value cmd args` (e.g.
   `OPENCODE_SERVER_PASSWORD=x opencode serve --port 4096`).
4. **Extend PATH for one command:**
   `sh "export PATH=$HOME/mytools:$PATH && which mytool"`.
5. **opencode + tools:** after `toolbox-install agent`, `git/node/rg/python`
   are on PATH for both EXEC and `opencode run` (same prefix). Agents acting
   with `cwd=sandbox` stay inside the sandbox.

## Fix-this-issue cheat sheet

| Symptom | Fix |
|---|---|
| `…/bin/opencode: Permission denied` | Android ≥10 SELinux blocks direct exec of app-data files even with `+x`. The app auto-launches via `/system/bin/linker64`. If it still fails: `opencode-fix`, then `opencode-diag` and read `interp=` |
| `interp=/lib/ld-linux…` (glibc) | That build needs Termux's glibc prefix/proot — standalone run can't work; next step is the proot runner |
| `opencode not installed` | `opencode-install` (needs net, aarch64 only, ~50MB) |
| `Install Alpine first` | `install-alpine`, then retry |
| `apk` fails / offline | `apk` needs net for add/update; installed tools keep working offline. Repos + DNS are auto-seeded (`toolbox-install` does it) |
| Command hangs / long run | EXEC caps at 15s default (5–600s allowed); `opencode` gets 300s, toolbox installs 180s. `^C` / ⋮ → Kill. For more: `cmd > out.txt 2>&1` then `cat out.txt` |
| Output cut with `…truncated` | 8KB capture / 4KB echo cap — redirect to file instead |
| `(busy — Ctrl+C to kill)` | A process is still running; Enter is ignored until it ends or you kill it |
| No stdin / interactive prompts hang | EXEC closes stdin by design — pass flags/args instead (`--yes`, `< file`), or use PTY mode |
| Prompt hidden / keys misplaced | Keys hug the keyboard via scoped `imePadding`; transcript follows (`Follow output` in ⋮). In PTY, keyboard opens on tap/`⌨` |
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

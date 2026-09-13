# Browser ↔ Terminal Agent Bridge (plan)

Goal: type commands in the Terminal tab to observe and control the Browser tab's
WebView — navigate, read pages, click, fill forms, run JS — like a mini AI agent.

## How agents control browsers (what the ecosystem does)

- **CDP (Chrome DevTools Protocol)** — what Puppeteer/Playwright/Browser-Use use:
  WebSocket JSON-RPC (`Page`, `Runtime`, `DOM`, `Input`). Full power, but it is
  for driving a *separate* browser process. We already own the `WebView` object,
  so CDP buys us nothing in-app. Skip it (keep remote debugging for desktop dev
  only, never in release).
- **Accessibility-tree perception** — modern agents read a compact AX tree
  (role/name/ref) instead of raw HTML (~100 tokens vs ~2000). We can approximate
  this cheaply with one JS eval returning JSON (see `snapshot()` below).
- **JS-injection acting** — one `evaluateJavascript` that runs a self-contained
  script and returns `JSON.stringify(...)`. This maps 1:1 onto
  `WebView.evaluateJavascript` — this is our path.
- Golden rule from production agents: **one eval returning compact structured
  JSON beats many fine-grained calls**; refs beat CSS selectors.

## Recommended design (in-process, no sockets, no new permissions)

```
Terminal types "b open https://…" ──▶ TerminalViewModel.execCmd()
        │  intercept `b …` BEFORE Alpine shell (shell metachars can't smuggle through)
        ▼
BrowserCommandBus: SharedFlow<BrowserCommand(id, op, args, reply: CompletableDeferred)>
        ▼  (collected by BrowserViewModel on Main thread)
BrowserViewModel ── requestLoad(url) / evalJs(expr) on the live WebView
        ▼
BrowserResult(id, ok, body, truncated) ──▶ terminal prints it
```

## `window.LightAgent` JS shim (inject at document-start, fallback onPageFinished)

```js
window.LightAgent = {
  snapshot(max) -> {url, title, interactive:[{ref, role, name, selector}], text},
  getText(n), getDOM(sel), click(ref|selector), fill(ref|selector, value),
  scroll(sel, dx, dy)
};
```

Native keeps a `ref → selector` map per page load (cleared on navigation).

## Command sketch (`b` namespace)

```
b open <url>            # http(s) only — block file://, intent://, custom schemes
b back | fwd | reload | reload-hard | stop
b ua [mobile|desktop|<string>|get] | viewport | zoom [in|out|reset]
b url | title | home
b tabs | tab <n> | new <url> | close [n] | tabdup
b js <expr>             # raw eval, truncated (power-user hatch)
b text [max] | b read [max] | b dom [css] | b snap
 b click <ref|css|name> | b fill <ref|css|name> <value> [--submit] | b submit <form>
 b upload <ref|css> <sandbox-path> | b js-file <path> | b fill-file <ref|css> <path>
b key [sel|name]      # nearest visible button to the field (typed/focused) + tap it
b hover <ref|css|name> | b select <sel|name> <value-or-text>
b store <name> <css> | stores | unstore <name>   # named selectors
 b pos <ref|css> | b box <ref|css> | b tap <x> <y> [--click] | b swipe <x1> <y1> <x2> <y2> [ms] | b shot-el <ref|css>
# contract: pos/box return CSS px, tap/swipe take CSS px; tap/swipe report
# delivered (queued FIFO, humanized); --click adds elementFromPoint fallback
 b circle <cx> <cy> <r> [n] | b scribble <x1> <y1> <x2> <y2> [steps] [--seed N]  # human doodle
 b gesture <"x1,y1 x2,y2 …"> [ms] [--seed N] | b gesture replay <rec> [--seed N]  # freeform/recorded→random
# recorder captures touchmove trails as op=gesture (path + ms + n); replay humanizes
# (translate ±36px, scale 0.92–1.08, rotate ±7°, tempo 0.9–1.15×) so runs never repeat pixels
b scroll [px] | b scroll-to <x> <y> | b scroll-top | b scroll-bottom | b find <text> | find-clear | next | prev
b shot [--full] | b console [n] | b netlog [n] | b cookies [get [url] | set "k=v" [url] | clear]
 b clear-data [cookies|cache|history|storage|all] | b history [n] | downloads | save <name.html|txt>
 b wait <text|css:sel> [ms] | links [n] | forms | survey   # senses
 b do "c1; c2" | run <file> | replay <rec> | queue         # macros (EXEC-only)
 b alias [name expansion] | unalias <name>   # EXEC one-liners
 b mkcmd <name> ["c1; c2"] | cmds             # macro files (~/.b-cmd/), both modes
 b block <domain> | unblock | blocks          # AI no-go sites, EXEC-only (PTY: blocks lists only)
b ext | mkext <name>    # your own SCRIPT commands (~/.b-ext/*.sh, both modes)
 b record start|stop|pause|resume|save <n>|list | b serve on|off
 b metrics [--json]   # screen+page geometry + tap audit (auto-logged)
```
PTY `b()` covers the same via HTTP routes (`/switch /submit /read /hover
/select /store /stores /history /downloads /dom /save /serve /alias /record
/reload-hard /ua /viewport /zoom /netlog /clear-data /tabdup /shot-el /box
/stroke /circle /gesture`
added); `b unalias` and mutating `b block/unblock` stay EXEC-only
(PTY `b blocks` lists; use `b alias remove <name>`).
`b mkext` scaffolds `~/.b-ext/<name>.sh` with
`B_PORT/B_KEY` exported; unknown `b <cmd>` runs the matching script.

Terminal prints page returns wrapped in markers so page text is never confused
with tool output:

```
--- PAGE CONTENT origin=https://example.com ---
…(truncated)…
--- END PAGE CONTENT ---
```

## For AI agents (touch loop contract)

Result lines are machine-parseable: `ok <cmd> … delivered=true` or
`err <cmd> … delivered=false reason=<r>`. Reasons: `no-webview`,
`detached`, `rejected`, `timeout`, `deliver-error`. `b metrics` prints
`ok metrictap … parked=<bool>` — `parked=true` means the Browser tab was
backgrounded (the target auto-resumes, but prefer acting while it is visible).

Reliable interaction loop:
1. `b box "<css>"` → pick the center or any `safe` point (vary per attempt
   using `bounds` — never hammer the identical pixel).
2. Deterministic actions FIRST: `b click` / `b fill` / `b js` (selector-based,
   always land). Raw `b tap` is a real finger touch: some pages only navigate
   on click — append `--click` for links, or verify with a `touchstart`
   listener + `b metrics`.
3. After every `b tap`/`b swipe`, require `delivered=true`. On `false`, wait,
   `b metrics` once, retry with a different safe point — never blind-repeat
   the same coords.
4. Gestures serialize server-side (150ms gap); bursts are safe but slow —
   batch thoughtfully.

## Build order

1. Bus + `b open/url/title/reload/back` + `b js` (timeout + truncate).
2. `snapshot/text/click/fill/dom` + shim injection.
3. `b console` (existing console ring buffer) + `b shot` (canvas capture).
4. Hardening: nav allowlist, confirm before submit on login/pay pages, caps.

## Security notes (don't skip)

- Control flows **native → JS only**. Don't expose new `@JavascriptInterface`
  methods to arbitrary pages (every iframe can call them, no origin check). If
  page→native events are ever needed, use `addWebMessageListener` + `isMainFrame`
  + payload validation.
- **Prompt injection is the top risk:** `b text/dom/snap` returns are *untrusted
  page content*. Delimit it, cap size, keep only the latest snapshot, require
  confirmation for state-changing actions. Page text saying "ignore previous
  instructions…" must never auto-execute.
- All `WebView` calls on Main thread; every eval gets a 10–15 s timeout and a
  50–100 KB truncation.
- Never enable `setWebContentsDebuggingEnabled` in release builds.

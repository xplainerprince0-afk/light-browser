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
b back | fwd | reload | stop
b url | title | home
b tabs | tab <n> | new <url> | close [n]
b js <expr>             # raw eval, truncated (power-user hatch)
b text [max] | b read [max] | b dom [css] | b snap
b click <ref|css|name> | b fill <ref|css|name> <value> [--submit] | b submit <form>
b key [sel|name]      # nearest visible button to the field (typed/focused) + tap it
b hover <ref|css|name> | b select <sel|name> <value-or-text>
b store <name> <css> | stores | unstore <name>   # named selectors
b pos <ref|css> | b tap <x> <y> | b swipe <x1> <y1> <x2> <y2> [ms]
b scroll [px] | b scroll-to <x> <y> | b find <text> | next | prev
b shot [--full] | b console [n] | b cookies [get [url] | set "k=v" [url] | clear]
b history [n] | downloads | save <name.html|txt>
b alias [name expansion] | unalias <name>   # EXEC one-liners
b ext | mkext <name>    # your own SCRIPT commands (~/.b-ext/*.sh, both modes)
b record start|stop|save <n>|list | b serve on|off
```
PTY `b()` covers the same via HTTP routes (`/switch /submit /read /hover
/select /store /history /downloads` added); `record/serve/alias` stay
EXEC-only (stateful). `b mkext` scaffolds `~/.b-ext/<name>.sh` with
`B_PORT/B_KEY` exported; unknown `b <cmd>` runs the matching script.

Terminal prints page returns wrapped in markers so page text is never confused
with tool output:

```
--- PAGE CONTENT origin=https://example.com ---
…(truncated)…
--- END PAGE CONTENT ---
```

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

# LightBrowser `b` Touch-Engine Fix — Verification Report

Date: 2026-09-11
Scope: everything discussed after the fix report was submitted.

---

## 1. The fix report (as sent)

The engine update claimed:

### Tap / swipe reliability
- **No serialization fix** — taps were posted as independent DOWN/MOVE/UP that interleaved (`DOWN,DOWN,UP,UP`) and Chromium cancelled all of them. Now a **FIFO gesture queue with 150ms settle** keeps gestures atomic.
- **addBatch misuse** — swipe folded all MOVEs into a single `ACTION_DOWN` (Chromium ignores it). Now **discrete eased MOVEs with real event times**.
- **Stale/detached WebView** — delayed UPs fired on recycled views. Now **instance-pinned** (view captured at enqueue, delivered to the same view; detached reported if dead).
- **Zero pressure/size + same-point MOVE** — screamed synthetic. Now **pure DOWN→UP, finger-like pressure (0.85–1.0), size jitter, ±1px position jitter, 70–120ms dwell**. Swipe gets ease-in-out velocity.
- **Lying `{"ok":true}`** — `/tap` `/swipe` now **block until delivery** and return `{"ok":delivered,"reason":…}`.

### Contract / diagnostics
- Everything is **CSS px** (`pos`/`box` return it, `tap`/`swipe` take it).
- **`lastTap` v2** logs css → view, scale, density, view size, progress, delivered/reason (no more `devX:1417` confusion).
- New **`b box <ref|css>`** — center + bounds + 5 safe inset points + viewport/scroll/z/dpr (EXEC help, PTY, Agent panel). Plus **`b tap x y --click`** (elementFromPoint fallback for plain links; raw tap stays pure touch).

### Leaks/perf, UI polish (not re-tested here)
- Future cancel on blocking-get timeout, socket/reader `use{}` blocks, serialized metrics.log writer, closed process-stream readers, guarded PTY AndroidView updates.
- M3 Expressive polish: scheme roles, shape tokens, tonal buttons + count badges, ListItem command rows, segmented controls, 48dp targets, etc.

---

## 2. Verification environment

- `b` command defined in `~/.profile` (agent-server shell bridge, token-gated on `127.0.0.1`).
- Server: `curl --get http://127.0.0.1:8089/...` with token.
- Test device metrics: screen 1080×2400, density 2.625, CSS viewport 411×696, dpr 2.625, view w=1080 h=1829.

Test harness (JS-injected into the page):

```js
window.__ok = {c:0, ts:0, te:0, m:0, all:[]};
document.addEventListener('touchstart', e => window.__ok.all.push([Math.round(e.touches[0].clientX), Math.round(e.touches[0].clientY)]));
document.addEventListener('touchend', e => window.__ok.te++);
document.body.insertAdjacentHTML('beforeend', '<div id=btn style="position:fixed;left:200px;top:300px;width:100px;height:60px;background:#0a0;z-index:999"></div>');
var el = document.getElementById('btn');
el.addEventListener('touchstart', () => window.__ok.ts++);
el.addEventListener('click', () => window.__ok.c++);
```

---

## 3. Verification results

### 3.1 Working / confirmed fixed

| Item | Result | Evidence |
|---|---|---|
| `b box` | **Works, accurate** | Injected 100×60 box at CSS (200,300) → `{x:250,y:330,bounds:{left:200,top:300,w:100,h:60},safe:[5 points],vw:411,vh:696,sx:0,sy:0,z:1,dpr:2.625}` — geometry matches exactly. |
| `tap x y --click` | **Works end-to-end** | Fresh `example.com` → `tap 123 160 --click` → navigated to `https://www.iana.org/help/example-domains` (URL + title verified). `--click` fires a JS click via elementFromPoint with **no touch events** (raw tap stays pure). |
| `metrics.lastTap` v2 | **Accurate forensics** | Tap: `{kind:tap,cssX:250,cssY:330,viewX:655,viewY:868,scale:2.625,density:2.625,viewW:1080,viewH:1829,progress:100,delivered:true,reason:"ok",durMs:84}`. Swipe: `{kind:swipe,cssX:200,cssY:400→cssY2:200,ms:400,durMs:400,delivered:true}`. No more `devX:1417` confusion. |
| Response contract | **Blocks until delivery** | `/tap /swipe` return `{"ok":true,"reason":"ok"}`. |
| Human-like dwell | **In range** | Tap `durMs:84` (claimed 70–120ms). Swipe 400ms, eased. |
| FIFO queue | **No interleave visible on server side** | 5-tap burst → no GL corruption, gestures processed atomically server-side. |
| Non-touch paths | **Stable** | `open / js / click / fill / url / title / dom / pos` reliable. |

### 3.2 Still failing — raw `tap` / `swipe` (pure touch)

**Zero events reach the page**, despite the server reporting delivered.

| Test | Requested | Page received |
|---|---|---|
| Single raw tap @ (250,330) button center | `touchstart/touchend/click` | `ts:0, te:0, c:0, all:[]` |
| `--click` variant (no touch) | click only | `c:1` (works) |
| 5-tap FIFO burst @ (250,330) | 5× touchstart | all zero |
| Eased swipe (400ms) | touchmove/scroll | all zero |
| Native scroll test — 4000px page, `swipe 205 500 205 100` | scroll | `scrollY` stayed 0 |

All logged as `delivered:true`, `progress:100`, view coords in-bounds (e.g. (655,868) < 1080×1829).

---

## 4. Diagnosis: why raw touch still fails

Not a human-mimicry problem — dwell, pressure, jitter, ease, FIFO all work. It is a **plumbing problem at layer 1**: the injected MotionEvents never reach the WebView's Chromium input pipeline.

```
b tap → view.dispatchTouchEvent(motion) → WebView.onTouchEvent → Chromium input → page DOM
   (1) target the right view             (2) pipeline consumes        (3) events fire
```

Layers (2) and (3) are fixed and verified. **Layer (1) delivers to the wrong address.**

### Key evidence — offscreen view
`metrics.view` = `{x:-100000, y:-99702, w:1080, h:1829}`.

The touch-target view sits ~100,000px off-screen and ~99,702px above the screen origin. Expected real geometry: `(0, 571, 1080, 1829)` — the 1829px view inside the 2400px screen = 571px of chrome above it. The app resolves the wrong view id, or the WebView was moved offscreen for rendering while the visible browser is mirrored through a different surface (SurfaceView / SurfaceTexture / compositor).

### Confirm which sub-cause — A or B

**A. Wrong view / offscreen placement (most likely)**
Log on the injected view: `getLocationOnScreen(out)`, `getRootWindowInsets().getSystemWindowInsetTop()`, `isAttachedToWindow()`, `getWindowVisibility()`.
- Offscreen/not VISIBLE → wrong view id, or WebView intentionally hidden + mirrored.

**B. Coordinate/size mismatch**
`viewX/Y` maps css→view as `×2.625`, assuming a 1080×1829 view. If the WebView's real layout is 411×696 (1:1 with CSS viewport) and display happens through a scaled SurfaceView/SurfaceTexture, then dispatching at (655,868) is **out of bounds** → dropped or mis-hit.
- Print `view.getWidth()/getHeight()` and compare to the `viewW/viewH` you log.

### Also check once
A `WebView.setOnTouchListener[...]` returning `true` on DOWN swallows every injected gesture before Chromium sees it (drag handles, tab bars, resize gestures).

### Fastest experimental split
1. `view.dispatchTouchEvent(...)` — current path.
2. System level: `Instrumentation.sendPointerSync(down)` / `UiDevice.swipe()` at real screen coords.
- (2) works + (1) fails → view placement/size. Fix the view rect to ≈(0,571,1080,1829).
- Both fail → a touch consumer sits above the WebView.

---

## 5. TL;DR

- **Confirmed working:** `box`, `tap --click`, `lastTap v2`, blocking `reason` contract, human-like dwell/timing, FIFO atomicity, all non-touch commands.
- **Confirmed broken:** raw `tap`/`swipe` still never reach page DOM. Server claims `delivered:true` but the injection targets an offscreen view (`x:-100000,y:-99702`), so Chromium input never runs.
- **One line to fix:** make sure `b tap` dispatch targets the *real, attached, on-screen* WebView and that its logged size matches `getWidth()/getHeight()`.
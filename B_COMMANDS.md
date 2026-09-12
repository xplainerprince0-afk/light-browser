# `b` Command Cheat Sheet (Webloom agent bridge)

Two places to type: **EXEC** (terminal text field — everything works) and **PTY**
(shell `b()` function — needs server on + `curl`). Start the server first:
EXEC `b serve on` or drawer → Agent bridge.

Contract: **all coords are CSS px**. `b pos`/`b box` return them, `b tap`/`b swipe`
take them. Every touch prints `delivered=true` — on `false`, retry, don't repeat blindly.

## Navigation

| Cmd | Example |
|---|---|
| `b open <url>` | `b open example.com` (bare domains + localhost OK) |
| `b new <url>` / `b tabs` / `b tab <n>` / `b close [n]` / `b tabdup` | `b tab 1` |
| `b back` / `b forward` / `b reload` / `b reload-hard` / `b stop` | — |
| `b home` / `b url` / `b title` | — |
| `b ua [mobile\|desktop\|<string>\|get]` / `b viewport` / `b zoom [in\|out\|reset]` | `b ua desktop` |

## Reading the page

| Cmd | Example |
|---|---|
| `b snap` | refs + text (start here, get a `ref`) |
| `b read [max]` / `b text [max]` / `b dom [css]` / `b js <expr>` | `b read 4000` |
| `b links [n]` / `b forms` / `b survey` / `b netlog [n]` / `b console [n]` | `b forms` |
| `b shot [--full]` / `b shot-el <ref\|css>` / `b save <name.html\|txt>` | `b shot` |
| `b find <text>` / `b find-clear` / `b next` / `b prev` | — |
| `b scroll [px]` / `b scroll-to <x> <y>` / `b scroll-top` / `b scroll-bottom` | — |
| `b cookies [get [url] \| set "k=v" [url] \| clear]` / `b clear-data [all]` | — |
| `b history [n]` / `b downloads` / `b metrics [--json]` | — |

## Acting (deterministic first)

Prefer selector actions — they always land. Use raw touch only for gestures.

| Cmd | Example |
|---|---|
| `b click <ref\|css>` | `b click e3` or `b click "#submit"` (quote sels with spaces) |
| `b fill <ref\|css> <val> [--submit]` | `b fill "#user" me --submit` |
| `b submit <form>` / `b key [sel]` (taps nearest button) / `b hover <sel>` / `b select <sel> <val>` | `b key` |
| `b store <name> <css>` / `b stores` / `b unstore <name>` | `b store login "#user"` then `b click login` |

## Touch (raw finger)

```
b pos "<css>"        # center x y + size  (quick)
b box "<css>"        # center + bounds + 5 safe inset points (for variation)
b tap <x> <y> [--click]     # raw touch; --click adds elementFromPoint fallback for links
b swipe <x1> <y1> <x2> <y2> [ms]
```

Optimal tap loop: `b box` → tap a **safe** point (vary each attempt via `bounds`,
never hammer one pixel) → require `delivered=true` → `b metrics` to verify
(`ok metrictap … parked=false`). `parked=true` = Browser tab backgrounded.
Raw tap is a touch, not a click — plain links may need `--click` or `b click`.

## Senses + macros (automation)

```
b wait <text|css:sel> [ms]     # wait for SPA loads AFTER clicks (macros race pages)
b do "open x.com; wait css:#u; fill #u me; fill #p w --submit; wait Welcome"
b run <file|macro>  (! prefix skips errors)   b queue (pending)   b replay <rec>
b record start|stop|pause|resume|save <n>|list  (captures taps/swipes/clicks/fills)
b mkcmd <name> ["c1; c2"] | b cmds      # macro files ~/.b-cmd/ (both modes)
b alias <name> <expansion> ($1…$9, $@) | b unalias <name>
b ext | b mkext <name>                  # shell scripts ~/.b-ext/ (both modes)
b block <domain> | b unblock <domain> | b blocks   # AI no-go sites, EXEC-only
b serve [on|off]     b metrics (auto-logged)     b help (full list)
```

`b do`/`run`/`replay`/`queue` and `b block`/`unblock` are **EXEC-only**.
Modifiers: `--json` (raw output for parsing), `> file` / `>> file` (sandboxed save).

## Automation recipe (login)

```
b do "open example.com/login; wait css:#user; fill #user me; fill #pass w --submit; wait Welcome"
```

Record once, replay forever: `b record start` → tap through the flow in Browser
tab → `b record stop` (auto-saves) → `b replay <name>`.

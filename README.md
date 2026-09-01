# LightBrowser — Minimal 4-Tab WebView Browser

Lightweight Android browser (target < 3 MB APK) with 4 bottom tabs:

1. **Browser** — WebView with address bar, progress, back/forward/refresh, JS & DOM storage enabled, third-party cookies, mixed-content compat. Ready for userscript injection (Violentmonkey-style: `evaluateJavascript` on `onPageFinished`, future `GM_*` polyfill via `addJavascriptInterface`).
2. **Scripts** — placeholder for Violentmonkey-compatible manager (will parse `// ==UserScript==` `@match` `@grant`, `GM_xmlhttpRequest`, `GM_setValue`).
3. **Downloads** — placeholder for blob/media capture.
4. **Settings** — placeholder for UA switcher / adblock.

**Size optimizations:**
- No Compose, no extra SDKs — only `appcompat` + `material` + `fragment-ktx`
- `isMinifyEnabled=true` + `isShrinkResources=true` with `proguard-android-optimize.txt`
- `vectorDrawables.useSupportLibrary`, `abiFilters arm64-v8a/armeabi-v7a` only
- `compileSdk 34`, `minSdk 21`

## Build (via GitHub Actions)
Push to `main` triggers `.github/workflows/build.yml` which builds `app:assembleRelease` on `ubuntu-latest` with Java 17, uploads `app-release.apk` artifact. Watch logs in Actions tab.

Local (if you have SDK later):
```bash
./gradlew assembleRelease
ls app/build/outputs/apk/release/
```

## Project inspired by WibgarBrowser analysis
See `Wibgar/` apktool dump for WebView + `evaluateJavascript` injection pattern.

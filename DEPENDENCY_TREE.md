# LightBrowser — Dependency Tree

Built from `gradle/libs.versions.toml`, verified against `Android/libs.versions.toml.txt` + `compatibility-matrix.txt`.

```
Kotlin 2.3.0 (parent)
└─ Compose compiler plugin 2.3.0 (org.jetbrains.kotlin.plugin.compose)
   └─ Compose BOM 2025.09.01 (androidx.compose:compose-bom-alpha)
      ├─ material3 (expressive APIs, MotionScheme)
      ├─ material-icons-extended (all icons incl. core set)
      ├─ material3-adaptive-navigation-suite (NavigationSuiteScaffold)
      └─ ui-tooling-preview / ui-tooling (debug)

AGP 9.3.2 (parent, requires Gradle 9.5.0+, compileSdk 37, JDK 17)
├─ core-ktx 1.15.0
├─ activity-compose 1.9.3
├─ appcompat 1.7.0
├─ lifecycle 2.10.0 (runtime-compose, viewmodel-compose)
├─ desugar_jdk_libs 2.1.4 (coreLibraryDesugaring, minSdk 23)
├─ coroutines 1.10.1 (android)
└─ coil 2.7.0 (coil-compose)

Media3 1.11.0 ★ NOT in workshop list — verified via developer.android.com
release notes (Aug 2026 stable, needs compileSdk 35+; we use 37).
User explicitly approved ExoPlayer.
├─ media3-exoplayer
└─ media3-session (MediaSessionService + notification)

Kept as literals (pre-existing, scoped-storage/SAF needs):
└─ androidx.documentfile:documentfile:1.0.1

Vendored (Apache-2.0, verbatim from termux/termux-app tag v0.119.0):
termux-app is GPLv3-only, BUT its LICENSE.md explicitly excepts these two
dirs (jackpal Android-Terminal-Emulator lineage). Verified: zero imports of
com.termux.shared anywhere in the vendored sources; empty manifests.
├─ :terminal-emulator (com.termux.terminal: TerminalSession/Emulator + JNI
│   termux.c → libtermux.so via ndkBuild, NDK 29.0.14206865 pinned in CI)
│   └─ androidx.annotation:annotation:1.9.0 ★ user-approved (upstream-pinned)
└─ :terminal-view (com.termux.view: TerminalView/Renderer, api → emulator)
    └─ androidx.annotation:annotation:1.9.0 (same)

Parents drive children: Kotlin → compose plugin → BOM → M3. AGP → Gradle wrapper → compileSdk. Never bump a child without checking its parent floor.

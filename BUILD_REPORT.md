# LightBrowser Build Failure Report

## Summary

| Metric | Value |
|--------|-------|
| **Total Build Attempts** | 7 |
| **Failed Builds** | 6 |
| **Successful Builds** | 1 |
| **Total Time Spent** | ~45 minutes |
| **Final Status** | ✅ SUCCESS |

---

## Build History

| Run ID | Timestamp | Status | Primary Failure Reason |
|--------|-----------|--------|------------------------|
| 33587080480 | 2026-09-02 03:27 | ❌ Failed | Private Android drawable resources in menu XML |
| 33587040394 | 2026-09-02 03:26 | ❌ Failed | Private Android drawable resources in menu XML |
| 33587679417 | 2026-09-02 03:36 | ❌ Failed | Missing custom drawable resources (ic_audio, ic_file) |
| 33587652455 | 2026-09-02 03:36 | ❌ Failed | Missing custom drawable resources |
| 33588160842 | 2026-09-02 03:44 | ❌ Failed | Kotlin compilation errors (imports, data classes, suspend functions) |
| 33589293699 | 2026-09-02 04:02 | ❌ Failed | Kotlin compilation errors (MaterialMenuInflater, compareBy, nameWithoutExtension) |
| **33590895790** | **2026-09-02 04:27** | **✅ Success** | — |

---

## Failure Analysis

### 1. Private Android Drawable Resources (Runs #1, #2)

**Error:**
```
error: resource android:drawable/ic_menu_archive is private.
error: resource android:drawable/ic_menu_copy is private.
```

**Root Cause:** Used Android platform private drawables (`@android:drawable/ic_menu_*`) in custom menu XML files. These are not part of the public API and fail compilation on newer Android SDKs.

**Fix:** Created custom vector drawable icons in `res/drawable/`:
- `ic_folder.xml`, `ic_file.xml`, `ic_audio.xml`, `ic_video.xml`
- `ic_image.xml`, `ic_pdf.xml`, `ic_text.xml`, `ic_archive.xml`
- `ic_apk.xml`, `ic_code.xml`

**Mistake:** Assumed Android system drawables were safe to use in custom menus.

---

### 2. Missing Custom Drawable Resources (Runs #3, #4)

**Error:**
```
error: resource drawable/ic_audio not found.
error: resource drawable/ic_file not found.
```

**Root Cause:** Referenced custom drawables in menu XML before creating the actual drawable files.

**Fix:** Created all 7 missing drawable XML files using `cat` commands.

**Mistake:** Edited menu XML files before verifying all referenced drawables existed.

---

### 3. Kotlin Compilation Errors - Batch 1 (Run #5)

**Errors:**
```
Unresolved reference: menu (R.menu.audiobook_menu)
Unresolved reference: MaterialMenuInflater
Unresolved reference: LinearLayout
Unresolved reference: textColor, textAllCaps, isMinWidth, style
The floating-point literal does not conform to the expected type Int
```

**Root Causes:**
1. `MaterialMenuInflater` requires `com.google.android.material:material` dependency but was imported incorrectly
2. `R.menu` references failed because menu resources had compilation errors
3. `MaterialButton` properties: `isMinWidth` doesn't exist, use `setMinWidth(0)`
4. `cornerRadius = 8f` should be `cornerRadius = 8` (Int)
5. Missing imports: `LinearLayout`, `Gravity`, `ColorStateList`, `Color`

**Fixes Applied:**
- Removed `MaterialMenuInflater` usage, switched to `popup.menuInflater.inflate()`
- Added proper imports for `LinearLayout`, `Gravity`, `ColorStateList`, `Color`
- Changed `isMinWidth = false` → `setMinWidth(0)`
- Changed `cornerRadius = 8f` → `cornerRadius = 8`
- Fixed `addBreadcrumbItem` to use `MaterialButton` with proper property setters

---

### 4. Kotlin Compilation Errors - Batch 2 (Run #6)

**Errors:**
```
Unresolved reference: compareBy
Unresolved reference: it (in comparator)
Unresolved reference: nameWithoutExtension
Unresolved reference: bb (variable scope)
```

**Root Causes:**
1. `compareBy` is in `kotlin.comparisons`, not `java.util.Comparator`
2. `DocumentFile` doesn't have `nameWithoutExtension` extension property
3. Variable `bb` captured in lambda but not in scope

**Fixes Applied:**
- Added `import kotlin.comparisons.compareBy`
- Replaced `file.nameWithoutExtension` with `file.name?.substringBeforeLast(".") ?: file.name ?: "Unknown"`
- Fixed lambda variable capture in `playChapter` by extracting `val bb = _b` before lambda
- Fixed `showNovelSwitcher` dialog lambda parameter name (`it` → `dialog`)

---

### 5. Suspend Function Error (Run #5)

**Error:**
```
Suspension functions can be called only within coroutine body
```

**Root Cause:** `downloadAndExtract` was a suspend function called from non-suspend context.

**Fix:** Converted `downloadAndExtract` to regular function returning `Boolean`, created separate `runShellSync` for synchronous shell execution.

---

## Mistakes Made

| # | Mistake | Impact | Prevention |
|---|---------|--------|------------|
| 1 | Used private Android drawables | 2 failed builds | Use only public SDK resources or custom drawables |
| 2 | Referenced drawables before creating them | 2 failed builds | Create resources first, then reference them |
| 3 | Incorrect `MaterialMenuInflater` usage | Compilation errors | Use standard `PopupMenu.menuInflater` |
| 4 | Wrong property names on MaterialButton | Compilation errors | Check Material 3 API docs for property names |
| 5 | Float literal for Int property | Compilation error | Use correct literal types (`8` not `8f`) |
| 6 | Missing Kotlin stdlib imports | Compilation errors | Import `kotlin.comparisons.compareBy` |
| 7 | Used non-existent `nameWithoutExtension` | Compilation error | Check `DocumentFile` API before using |
| 8 | Variable capture in lambda scope | Compilation error | Extract binding to local val before lambda |
| 9 | Suspend function called from wrong context | Compilation error | Match suspend/non-suspend correctly |
| 10 | Didn't verify build locally before pushing | Wasted CI time | Run `./gradlew assembleDebug` locally first |

---

## Lessons Learned

1. **Validate resources first** - Create all drawables/layout/menu files before referencing them in code
2. **Use public APIs only** - Never use `android:drawable/ic_menu_*` or other private resources
3. **Check Material 3 migration guide** - Property names changed from Material 2 (e.g., `setMinWidth` vs `isMinWidth`)
4. **Kotlin stdlib organization** - `compareBy` is in `kotlin.comparisons`, not `java.util`
4. **Test locally** - Run `./gradlew assembleDebug` before pushing to catch compilation errors early
5. **Incremental commits** - Smaller commits make it easier to bisect failures

---

## Successful Build Configuration

**Final Working State:**
- All 10 custom vector drawables in `res/drawable/`
- 3 menu XML files using custom drawables
- 3 Kotlin fragments with fixed imports and syntax
- Gradle build completes in ~2 minutes on GitHub Actions

**Key Files Modified:**
```
app/src/main/java/com/lightbrowser/ui/FileManagerFragment.kt
app/src/main/java/com/lightbrowser/ui/MusicPlayerFragment.kt
app/src/main/java/com/lightbrowser/ui/TerminalFragment.kt
app/src/main/res/layout/fragment_filemanager.xml
app/src/main/res/layout/fragment_music.xml
app/src/main/res/layout/fragment_terminal.xml
app/src/main/res/layout/item_download.xml
app/src/main/res/menu/audiobook_menu.xml
app/src/main/res/menu/filemanager_menu.xml
app/src/main/res/menu/terminal_menu.xml
app/src/main/res/drawable/ic_*.xml (10 files)
```

---

*Report generated: 2026-09-02*
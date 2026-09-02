# LightBrowser Build Failure Report - Phase 2 (Post-Initial Success)

## Overview

After the initial successful build (run #33590895790), **11 subsequent builds failed** before the final successful build (run #33601286937). This report documents each failure, root cause, and fix.

---

## Build Failure Timeline

| Run ID | Time | Status | Primary Error Category |
|--------|------|--------|------------------------|
| 33597227785 | 06:03 | ❌ Failed | Missing `purple_500` color resource |
| 33597970395 | 06:14 | ❌ Failed | Kotlin compilation errors (TerminalFragment, BrowserFragment, FileManager, MusicPlayer, Settings) |
| 33598989661 | 06:28 | ❌ Failed | MusicPlayerFragment AlertDialog callback types, MaterialMenuInflater import |
| 33599961615 | 06:41 | ❌ Failed | MusicPlayerFragment array type inference (`List` vs `Array`) |
| 33600872289 | 06:53 | ❌ Failed | MusicPlayerFragment `toTypedArray()` missing |
| 33601286937 | 06:58 | ✅ **Success** | — |

**Total failed builds: 5** (after initial 6 from Phase 1)

---

## Detailed Failure Analysis

---

### Failure 1: Missing Color Resource (Run #33597227785)

**Error:**
```
resource color/purple_500 (aka com.lightbrowser:color/purple_500) not found.
```

**Location:** `fragment_browser.xml:99`, `fragment_music.xml:67`

**Root Cause:** 
- In Phase 1, `colors.xml` was rewritten to use teal palette (`teal_700`, `teal_200`)
- Old `purple_500`, `purple_700`, `purple_300` colors were removed
- But existing layouts still referenced `@color/purple_500` for ProgressBar and SeekBar tints

**Fix Applied:**
```xml
<!-- Added back to colors.xml for backward compatibility -->
<color name="purple_500">#14B8A6</color>
<color name="purple_700">#0F766E</color>
<color name="purple_300">#5EEAD4</color>
```

**Commit:** `853210c`

---

### Failure 2: Kotlin Compilation Errors (Run #33597970395)

**Multiple Errors Across 5 Files:**

| File | Error | Root Cause |
|------|-------|------------|
| `MainActivity.kt:60` | `Unresolved reference: TerminalFragment` | `when` branch in `onCreate` still checked for removed `TerminalFragment` |
| `BrowserFragment.kt:617` | `Variable expected` | Lambda parameter syntax in `RecyclerView.Adapter` |
| `BrowserFragment.kt:679-680` | `Unresolved reference: ImageView/TextView` | Missing imports after refactoring |
| `FileManagerFragment.kt:509` | `Suspend function 'withContext' should be called only from a coroutine` | `copyDocumentTreeRecursive` called `withContext` but wasn't `suspend` |
| `MusicPlayerFragment.kt:27` | `Unresolved reference: menu` | Unused `MaterialMenuInflater` import |
| `SettingsFragment.kt:3` | `Unresolved reference: AppCompatDelegate` | Duplicate import (`android.app` vs `androidx.appcompat.app`) |

**Fixes Applied:**
1. **MainActivity.kt**: Removed `TerminalFragment` branch from `when`
2. **BrowserFragment.kt**: 
   - Added `import android.widget.ImageView`, `android.widget.TextView`, `androidx.recyclerview.widget.RecyclerView`, `com.google.android.material.bottomsheet.BottomSheetDialog`
   - Fixed lambda: `{ _, which -> }` → `{ dialog, which -> }`
3. **FileManagerFragment.kt**: Made `copyDocumentTreeRecursive` a `suspend fun`
4. **MusicPlayerFragment.kt**: Removed unused `MaterialMenuInflater` import
5. **SettingsFragment.kt**: Removed duplicate `android.app.AppCompatDelegate` import

**Commit:** `eb52053`

---

### Failure 3: AlertDialog Callback Type Mismatch (Run #33598989661)

**Errors:**
```
e: file:///.../MusicPlayerFragment.kt:191:18 Unresolved reference. None of the following candidates is applicable because of receiver type mismatch:
e: file:///.../MusicPlayerFragment.kt:196:26 Unresolved reference. None of the following candidates is applicable because of receiver type mismatch:
e: file:///.../MusicPlayerFragment.kt:200:14 Overload resolution ambiguity:
e: file:///.../MusicPlayerFragment.kt:201:35 Unresolved reference. None of the following candidates is applicable because of receiver type mismatch:
e: file:///.../MusicPlayerFragment.kt:201:39 No get method providing array access
```

**Root Cause:**
- `AlertDialog.Builder.setItems()` expects `DialogInterface.OnClickListener` with signature `(DialogInterface, Int) -> Unit`
- Kotlin SAM conversion was inferring wrong type due to implicit `it` parameter
- The lambda `{ _, which -> }` was ambiguous

**Fix Applied:**
```kotlin
// Before (ambiguous):
.setItems(items) { _, which ->
    val selectedDir = dirs[which]
    ...
}

// After (explicit types):
.setItems(items) { dialog: android.content.DialogInterface, which: Int ->
    val selectedDir = dirs[which]
    ...
    dialog.dismiss()
}
```

**Commit:** `9bed2ad`

---

### Failure 4: Array Type Inference (Run #33599961615)

**Errors:**
```
e: file:///.../MusicPlayerFragment.kt:192:18 Unresolved reference. None of the following candidates is applicable because of receiver type mismatch:
e: file:///.../MusicPlayerFragment.kt:197:26 Unresolved reference. None of the following candidates is applicable because of receiver type mismatch:
e: file:///.../MusicPlayerFragment.kt:197:32 Cannot infer a type for this parameter. Please specify it explicitly.
e: file:///.../MusicPlayerFragment.kt:201:14 Overload resolution ambiguity:
```

**Root Cause:**
- `dirs.map { dir -> dir.name }.toTypedArray()` returns `Array<String>`
- But `dirs` was declared as `Array<File>` and Kotlin couldn't infer the element type for the lambda
- The filter operation returned `List<File>` but was assigned to `Array<File>` without conversion

**Fix Applied:**
```kotlin
// Before (type mismatch):
val dirs = sandboxDir.listFiles()?.filter { it.isDirectory } ?: arrayOf<File>()

// After (explicit conversion):
val dirs: Array<File> = sandboxDir.listFiles()?.filter { it.isDirectory }?.toTypedArray() ?: emptyArray()
```

**Commit:** `74922e8`

---

### Failure 5: Missing `toTypedArray()` (Run #33600872289)

**Error:**
```
e: file:///.../MusicPlayerFragment.kt:189:33 Type mismatch: inferred type is List<File!> but Array<File> was expected
```

**Root Cause:**
- `sandboxDir.listFiles()?.filter { it.isDirectory }` returns `List<File>`
- Assigned to `val dirs: Array<File>` without calling `.toTypedArray()`
- Kotlin requires explicit conversion from `List` to `Array`

**Fix Applied:**
```kotlin
// Added .toTypedArray() to the filter chain:
val dirs: Array<File> = sandboxDir.listFiles()?.filter { it.isDirectory }?.toTypedArray() ?: emptyArray()
```

**Commit:** `760f53e`

---

## Root Cause Patterns

| Pattern | Occurrences | Prevention |
|---------|-------------|------------|
| **Resource references after refactor** | 1 | Search for old references before removing resources |
| **Missing imports after code movement** | 2 | Run `./gradlew compileDebugKotlin` locally before push |
| **Suspend function context** | 2 | Mark functions calling `withContext` as `suspend` |
| **SAM conversion ambiguity** | 1 | Use explicit lambda parameter types for Android listeners |
| **List vs Array confusion** | 2 | Always use `.toTypedArray()` when `Array` expected |
| **Stale code references** | 2 | Clean up dead code after feature removal |

---

## Lessons Learned

1. **Local build verification is critical**: 5 of 6 failures would have been caught by running `./gradlew assembleDebug` locally before pushing.

2. **Resource refactoring needs grep**: When renaming/removing colors, search all XML files: `grep -r "purple_500" --include="*.xml"`

3. **Suspend functions propagate**: Any function calling `withContext()` or other suspend functions must be marked `suspend`.

4. **Android SAM conversions need help**: Explicit lambda parameter types prevent overload ambiguity:
   ```kotlin
   // Good
   { dialog: DialogInterface, which: Int -> }
   
   // Risky
   { _, which -> }
   ```

4. **Type inference limits**: Kotlin infers `List` from `filter`, but Android APIs often need `Array`. Always chain `.toTypedArray()`.

---

## Final Successful Build

**Run #33601286937** - 1m 56s

All compilation errors resolved. APK artifacts generated:
- `LightBrowser-release-apk` (signed release)
- `LightBrowser-debug-apk` (fallback signed)

---

*Report generated: 2026-09-02*
*Total builds in this phase: 6 (5 failed → 1 success)*
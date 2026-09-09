package com.lightbrowser.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * opencode-termux (Hope2333) installer: the AI coding agent as a Termux
 * pacman-style package, fetched from GitHub releases at runtime (zero APK
 * cost, MIT-style builds — no GPL anywhere near the app).
 *
 * Only aarch64 assets exist upstream, so other ABIs get a clear message.
 * The interactive TUI needs a real PTY (we don't have one yet) — use
 * `opencode run "task"` / `opencode --help` non-interactively.
 */
object OpencodeManager {
    private const val TAG = "OpencodeManager"
    private const val API = "https://api.github.com/repos/Hope2333/opencode-termux/releases/latest"
    private const val PREF = "opencode_v1"
    private const val ASSET_RE = """opencode-compressed-([0-9.]+)-1-aarch64\.pkg\.tar\.gz"""

    data class Release(val version: String, val url: String, val size: Long)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun binDir(ctx: Context): File =
        File(ctx.filesDir, "sandbox/bin").apply { if (!exists()) mkdirs() }

    fun binFile(ctx: Context): File = File(binDir(ctx), "opencode")

    fun isInstalled(ctx: Context): Boolean {
        return try {
            val f = binFile(ctx)
            f.exists() && f.length() > 1_000_000 && f.canExecute()
        } catch (_: Exception) { false }
    }

    fun installedVersion(ctx: Context): String? {
        return try { prefs(ctx).getString("version", null) } catch (_: Exception) { null }
    }

    fun archOk(): Boolean {
        val abi = try { Build.SUPPORTED_ABIS.firstOrNull() ?: "" } catch (_: Exception) { "" }
        return abi.contains("arm64") || abi.contains("aarch64")
    }

    /**
     * Make the installed binary executable. File.setExecutable() alone is not
     * reliable on all devices (fused/odd mounts), so fall back to a real
     * chmod(1) and verify with canExecute().
     */
    fun ensureExecutable(bin: File): Boolean {
        return try {
            if (bin.canExecute()) return true
            try { bin.setExecutable(true) } catch (_: Exception) {}
            if (bin.canExecute()) return true
            try {
                val p = Runtime.getRuntime().exec(arrayOf("chmod", "755", bin.absolutePath))
                try { p.waitFor() } catch (_: Exception) {}
            } catch (_: Exception) {}
            bin.canExecute()
        } catch (_: Exception) { false }
    }

        /**
     * System linker for launching the binary. Since Android 10, SELinux W^X
     * denies untrusted apps direct execve() of app_data_file (our +x bit can
     * read fine while the kernel still says EACCES — same failure Termux hit).
     * Executing via /system/bin/linker64 <elf> only needs READ on our file,
     * which is allowed. Returns null when no linker exists (32-bit fallback).
     */
    fun systemLinker(): File? {
        return listOf("/system/bin/linker64", "/system/bin/linker")
            .map { File(it) }
            .firstOrNull { try { it.exists() } catch (_: Exception) { false } }
    }

    /** Shell fragment that launches [bin]: linker-prefixed when available. */
    fun launchArgv(bin: File): String {
        val q = "'" + bin.absolutePath.replace("'", "'\\''") + "'"
        val link = systemLinker()
        return if (link != null) "${link.absolutePath} $q" else q
    }

    /** PT_INTERP of an ELF (e.g. /lib/ld-linux-… = glibc build needing proot). */
    fun elfInterp(bin: File): String? {
        return try {
            val raf = java.io.RandomAccessFile(bin, "r")
            try {
                val magic = ByteArray(4); raf.readFully(magic)
                if (!(magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                        magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte())
                ) return null
                val is64 = raf.readByte() == 2.toByte()
                val le = raf.readByte() == 1.toByte()
                fun u16(off: Long): Int {
                    raf.seek(off)
                    val b = ByteArray(2); raf.readFully(b)
                    return if (le) (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
                    else ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                }
                fun u32(off: Long): Long {
                    raf.seek(off)
                    val b = ByteArray(4); raf.readFully(b)
                    var v = 0L
                    for (i in 0..3) {
                        val by = b[if (le) i else 3 - i].toLong() and 0xFF
                        v = v or (by shl (8 * i))
                    }
                    return v
                }
                fun u64(off: Long): Long {
                    raf.seek(off)
                    val b = ByteArray(8); raf.readFully(b)
                    var v = 0L
                    for (i in 0..7) {
                        val by = b[if (le) i else 7 - i].toLong() and 0xFF
                        v = v or (by shl (8 * i))
                    }
                    return v
                }
                val (phoff, phentsize, phnum) = if (is64) Triple(u64(0x20), u16(0x36), u16(0x38))
                else Triple(u32(0x1C), u16(0x2A), u16(0x2C))
                for (i in 0 until phnum) {
                    val base = phoff + i * phentsize
                    val type = u32(base)
                    if (type == 3L) { // PT_INTERP
                        val (off, sz) = if (is64) u64(base + 8) to u64(base + 32)
                        else u32(base + 4) to u32(base + 16)
                        raf.seek(off)
                        val s = ByteArray(sz.coerceAtMost(256).toInt()); raf.readFully(s)
                        return String(s).trim('\u0000')
                    }
                }
                null
            } finally { try { raf.close() } catch (_: Exception) {} }
        } catch (_: Exception) { null }
    }

    /** One-shot diagnostic for `opencode-diag`. */
    fun diagnose(ctx: Context): String {
        val app = ctx.applicationContext
        val f = binFile(app)
        if (!f.exists()) return "binary missing — run `opencode-install`."
        val sb = StringBuilder()
        sb.append("size=${f.length() / 1024}KB r=${f.canRead()} w=${f.canWrite()} x=${f.canExecute()}\n")
        val interp = elfInterp(f)
        sb.append("interp=${interp ?: "?"}\n")
        if (interp != null && interp.contains("ld-linux")) {
            sb.append("glibc-linked: needs the glibc prefix/proot — standalone run will fail.\n")
        }
        val link = systemLinker()
        sb.append("linker=${link?.absolutePath ?: "none (direct exec only)"}\n")
        sb.append("archOk=${archOk()}")
        return sb.toString()
    }

    /** Repair path for `opencode-fix`: re-chmod + report. */
    fun fixInstall(ctx: Context): String {
        val app = ctx.applicationContext
        val f = binFile(app)
        if (!f.exists() || f.length() < 1_000_000) return "missing — run `opencode-install` first."
        if (!archOk()) return "opencode-termux ships aarch64 only — this device is not supported."
        return if (ensureExecutable(f)) "OK — ${f.length() / 1024}KB, executable."
        else "chmod failed — reinstall with `opencode-install`."
    }

    private fun verScore(v: String): Long {
        return try {
            val parts = v.split(".").map { it.toLongOrNull() ?: 0L }
            (parts.getOrNull(0) ?: 0L) * 1_000_000L +
                (parts.getOrNull(1) ?: 0L) * 1_000L +
                (parts.getOrNull(2) ?: 0L)
        } catch (_: Exception) { 0L }
    }

    /** Network on the caller's thread. Picks the newest compressed aarch64 asset. */
    fun queryLatest(): Release? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "lightbrowser")
            }
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val root = org.json.JSONObject(body)
            val assets = root.optJSONArray("assets") ?: return null
            var best: Release? = null
            for (i in 0 until assets.length()) {
                try {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name", "")
                    val m = Regex(ASSET_RE).find(name) ?: continue
                    val r = Release(
                        version = m.groupValues[1],
                        url = a.optString("browser_download_url", ""),
                        size = a.optLong("size", 0L)
                    )
                    if (r.url.isBlank()) continue
                    if (best == null || verScore(r.version) > verScore(best.version)) best = r
                } catch (_: Exception) {}
            }
            best
        } catch (e: Exception) {
            Log.w(TAG, "query", e)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Download + install the latest release. Long (50MB); reports progress
     * strings. Returns true on success. Caller thread (IO).
     */
    fun install(ctx: Context, onProgress: (String) -> Unit): Boolean {
        val app = ctx.applicationContext
        if (!archOk()) {
            onProgress("opencode-termux ships aarch64 only — this device is not supported.")
            return false
        }
        return try {
            onProgress("Checking latest opencode-termux release…")
            val rel = queryLatest()
            if (rel == null) {
                onProgress("Could not reach GitHub releases. Check connection and retry.")
                return false
            }
            val cur = installedVersion(app)
            if (cur == rel.version && isInstalled(app)) {
                onProgress("opencode ${rel.version} already installed.")
                return true
            }
            onProgress("Downloading opencode ${rel.version} (~${rel.size / 1_048_576}MB)…")
            val tmp = File(app.filesDir, "sandbox/.oc-tmp").apply { mkdirs() }
            val pkg = File(tmp, "opencode.pkg.tar.gz")
            if (!downloadFile(rel.url, pkg) { done, total ->
                if (total > 0) onProgress("Downloaded ${done * 100 / total}%")
            }) {
                onProgress("Download failed — retry opencode-install.")
                return false
            }
            if (!pkg.exists() || pkg.length() < 10_000_000) {
                onProgress("Download too small — retry opencode-install.")
                try { pkg.delete() } catch (_: Exception) {}
                return false
            }
            onProgress("Extracting…")
            val out = File(tmp, "pkg").apply { mkdirs() }
            AlpineEnv.extractTarGz(pkg, out)
            // Pacman layout: find the real binary (largest file named "opencode").
            val cand = out.walkTopDown()
                .filter { it.isFile && it.name == "opencode" && it.length() > 1_000_000 }
                .maxByOrNull { it.length() }
            if (cand == null) {
                onProgress("Package had no opencode binary — retry later.")
                try { tmp.deleteRecursively() } catch (_: Exception) {}
                return false
            }
            val dest = binFile(app)
            try {
                cand.copyTo(dest, overwrite = true)
            } catch (e: Exception) {
                onProgress("Install failed: ${e.message}")
                return false
            }
            if (!ensureExecutable(dest)) {
                onProgress("Installed but not executable — run `opencode-fix` once.")
                return false
            }
            try { tmp.deleteRecursively() } catch (_: Exception) {}
            return if (isInstalled(app)) {
                try { prefs(app).edit().putString("version", rel.version).apply() } catch (_: Exception) {}
                onProgress("✓ opencode ${rel.version} ready — try `opencode --help` (use `opencode run \"task\"`; the TUI needs a PTY we don't have yet).")
                true
            } else {
                onProgress("Binary won't execute on this device.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "install", e)
            onProgress("Install failed: ${e.message}")
            false
        }
    }

    private fun downloadFile(urlStr: String, dest: File, onProg: (Long, Long) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 300_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "lightbrowser")
            }
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            val total = try { conn.contentLengthLong } catch (_: Exception) { -1L }
            var done = 0L
            var lastPush = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (System.currentTimeMillis() - lastPush > 500) {
                            lastPush = System.currentTimeMillis()
                            onProg(done, total)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "download", e)
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}

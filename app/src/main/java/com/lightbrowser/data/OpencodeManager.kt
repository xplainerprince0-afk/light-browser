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

    /** Repair path for `opencode-fix`: re-chmod + report. Empty string = OK detail. */
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

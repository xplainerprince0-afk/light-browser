package com.lightbrowser.data

import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Lightweight Alpine Linux environment inside the app sandbox.
 * Downloads minirootfs on demand and runs commands with Alpine PATH/tooling.
 */
object AlpineEnv {
    private const val TAG = "AlpineEnv"
    private const val ALPINE_VERSION = "3.19.1"

    fun alpineDir(sandbox: File): File = File(sandbox, "alpine")

    /** Default interactive profile: short `$` prompt (not the full path). */
    private const val DEFAULT_PROFILE =
        "PS1='\$ '\n" +
            "alias ll='ls -la'\n" +
            "alias la='ls -a'\n"

    /**
     * Runtime dirs + files every shell needs: tmp (Bun/Rust honor $TMPDIR;
     * /data/local/tmp is EACCES for apps), lib dir for sidecar .so files,
     * XDG homes, and ~/.profile with the short prompt (mksh sources $ENV).
     */
    fun ensureRuntimeFiles(sandbox: File) {
        try {
            sandbox.mkdirs()
            File(sandbox, "bin").mkdirs()
            File(sandbox, "lib").mkdirs()
            File(sandbox, "tmp").mkdirs()
            File(sandbox, ".cache").mkdirs()
            File(sandbox, ".config").mkdirs()
            File(sandbox, ".local/share").mkdirs()
            File(sandbox, ".local/state").mkdirs()
            val profile = File(sandbox, ".profile")
            if (!profile.exists() || profile.length() == 0L) {
                try { profile.writeText(DEFAULT_PROFILE) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun isInstalled(sandbox: File): Boolean {
        val root = alpineDir(sandbox)
        return File(root, "etc/alpine-release").exists() ||
            File(root, "bin/busybox").exists() ||
            File(root, "usr/bin/busybox").exists()
    }

    private fun archSlug(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.contains("arm64") -> "aarch64"
            abi.startsWith("x86_64") || abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            abi.contains("armeabi") -> "armhf"
            else -> "aarch64"
        }
    }

    fun downloadUrl(): String {
        val arch = archSlug()
        return "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/$arch/alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"
    }

    fun install(sandbox: File, onProgress: (String) -> Unit): Boolean {
        val dest = alpineDir(sandbox)
        dest.mkdirs()
        val tarball = File(dest, "alpine-minirootfs.tar.gz")
        return try {
            // x86 32-bit has no official minirootfs — fail fast with clear message.
            if (archSlug() == "x86") {
                onProgress("x86 32-bit not supported by Alpine minirootfs (use arm64/x86_64 device)")
                return false
            }
            onProgress("Downloading Alpine $ALPINE_VERSION (${archSlug()})…")
            downloadFile(downloadUrl(), tarball, onProgress)
            if (!tarball.exists() || tarball.length() < 1_000_000) {
                onProgress("Download failed (too small / 404?). Check connection and retry.")
                try { tarball.delete() } catch (_: Exception) {}
                return false
            }
            onProgress("Extracting to sandbox/alpine…")
            extractTarGz(tarball, dest)
            tarball.delete()
            // Verify real rootfs (not a 404 HTML page): busybox must exist.
            // Clean partial rootfs on failure so retries aren't poisoned.
            val bb = listOf(File(dest, "bin/busybox"), File(dest, "usr/bin/busybox")).firstOrNull { it.exists() }
            if (bb == null) {
                onProgress("Extract failed (not a valid rootfs) — cleaned, retry download")
                try { dest.deleteRecursively(); dest.mkdirs() } catch (_: Exception) {}
                return false
            }
            try { bb.setExecutable(true) } catch (_: Exception) {}
            // Recreate key symlinks the minimal tar reader skips (sh → busybox).
            try {
                val sh = File(dest, "bin/sh")
                if (!sh.exists()) {
                    try {
                        // Try symlink first, fall back to copy.
                        java.nio.file.Files.createSymbolicLink(sh.toPath(), java.nio.file.Paths.get("busybox"))
                    } catch (_: Exception) {
                        try { bb.copyTo(sh, overwrite = true); sh.setExecutable(true) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            File(dest, "etc/alpine-release").let { rel ->
                if (!rel.exists()) {
                    rel.parentFile?.mkdirs()
                    rel.writeText("$ALPINE_VERSION\n")
                }
            }
            onProgress("Alpine installed at ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            onProgress("Alpine install failed: ${e.message}")
            false
        }
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (String) -> Unit) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.connect()
        val code = try { conn.responseCode } catch (_: Exception) { -1 }
        if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP $code")
        val total = conn.contentLengthLong
        if (total > 0 && total < 1_000_000) throw java.io.IOException("HTTP body too small ($total)")
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(8192)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    done += read
                    if (total > 0 && done % (256 * 1024) < 8192) {
                        onProgress("Downloaded ${done * 100 / total}%")
                    }
                }
            }
        }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** Generic tar.gz extractor (Alpine rootfs, opencode Termux pkgs, …).
     *  Symlinks/hardlinks/pax entries are SKIPPED (bytes still consumed) — only
     *  regular files + dirs materialize. Zip-slip guarded, fail-closed. */
    fun extractTarGz(tarGz: File, destDir: File) {
        GZIPInputStream(BufferedInputStream(tarGz.inputStream())).use { gzip ->
            val buffer = ByteArray(512)
            while (true) {
                val header = ByteArray(512)
                var read = 0
                while (read < 512) {
                    val n = gzip.read(header, read, 512 - read)
                    if (n == -1) break
                    read += n
                }
                if (read < 512) break
                if (header.all { it == 0.toByte() }) break

                val name = String(header, 0, 100).trim('\u0000', ' ')
                if (name.isEmpty()) continue
                val sizeOct = String(header, 124, 12).trim('\u0000', ' ')
                val size = sizeOct.toLongOrNull(8) ?: 0L
                val type = header[156].toInt().toChar()

                val outFile = File(destDir, name)
                // Zip-slip guard: tar entries with ../ or absolute paths must not escape sandbox
                var unsafeEntry = false
                try {
                    val destCanon = destDir.canonicalFile
                    val outCanon = outFile.canonicalFile
                    if (!outCanon.absolutePath.startsWith(destCanon.absolutePath + File.separator) &&
                        outCanon.absolutePath != destCanon.absolutePath
                    ) {
                        unsafeEntry = true
                    }
                } catch (_: Exception) { unsafeEntry = true }
                if (unsafeEntry) {
                    // Consume this entry's bytes so the stream stays aligned, then skip it.
                    var toSkip = size + (512 - (size % 512)) % 512
                    while (toSkip > 0) {
                        val skipped = gzip.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                    continue
                }
                when (type) {
                    '5', '0', '\u0000' -> {
                        if (type == '5' || name.endsWith("/")) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            if (size > 0) {
                                FileOutputStream(outFile).use { fos ->
                                    var remaining = size
                                    val data = ByteArray(8192)
                                    while (remaining > 0) {
                                        val toRead = minOf(remaining, data.size.toLong()).toInt()
                                        val n = gzip.read(data, 0, toRead)
                                        if (n <= 0) break
                                        fos.write(data, 0, n)
                                        remaining -= n
                                    }
                                }
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.createNewFile()
                            }
                        }
                    }
                    else -> {
                        // Symlink/hardlink/pax/etc: skip entry bytes to stay aligned.
                        var toSkip = size
                        val data = ByteArray(8192)
                        while (toSkip > 0) {
                            val toRead = minOf(toSkip, data.size.toLong()).toInt()
                            val n = gzip.read(data, 0, toRead)
                            if (n <= 0) break
                            toSkip -= n
                        }
                    }
                }
                val pad = (512 - (size % 512)) % 512
                if (pad > 0) {
                    var toSkip = pad.toLong()
                    while (toSkip > 0) {
                        val skipped = gzip.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                }
            }
        }
    }

    fun buildEnvironment(sandbox: File, cwd: File): Array<String> {
        val alpine = alpineDir(sandbox)
        val alpinePath = if (isInstalled(sandbox)) {
            listOf(
                "${alpine}/usr/local/sbin",
                "${alpine}/usr/local/bin",
                "${alpine}/usr/sbin",
                "${alpine}/usr/bin",
                "${alpine}/sbin",
                "${alpine}/bin"
            ).joinToString(":")
        } else ""
        val path = if (alpinePath.isNotEmpty()) {
            "${sandbox.absolutePath}/bin:$alpinePath:/system/bin:/system/xbin"
        } else {
            "${sandbox.absolutePath}/bin:/system/bin:/system/xbin:/vendor/bin"
        }
        val sb = sandbox.absolutePath
        val tmp = "$sb/tmp"
        // LD_LIBRARY_PATH: sidecar .so files (e.g. libopencode-crhandler.so)
        // live in sandbox/lib/… — Bionic honors this; DT_RUNPATH/$ORIGIN
        // is unreliable on older APIs, so we export it explicitly.
        val ldPath = "$sb/lib/opencode:$sb/lib:$sb/bin"
        return arrayOf(
            "HOME=$sb",
            "PWD=${cwd.absolutePath}",
            "PATH=$path",
            "LD_LIBRARY_PATH=$ldPath",
            "TMPDIR=$tmp",
            "TEMP=$tmp",
            "TMP=$tmp",
            "BUN_TMPDIR=$tmp",
            "XDG_CACHE_HOME=$sb/.cache",
            "XDG_CONFIG_HOME=$sb/.config",
            "XDG_DATA_HOME=$sb/.local/share",
            "XDG_STATE_HOME=$sb/.local/state",
            // Short `$` prompt for interactive shells (mksh sources $ENV);
            // EXEC sh -c runs ignore both.
            "PS1=\$ ",
            "ENV=$sb/.profile",
            "TERM=xterm-256color",
            "HOSTNAME=alpine",
            "ALPINE_ROOT=${alpine.absolutePath}",
            "OSTYPE=linux-musl"
        )
    }

    fun shellPrefix(sandbox: File): String {
        return if (isInstalled(sandbox)) {
            val a = alpineDir(sandbox).absolutePath
            // sandbox/bin first: user tools (opencode) shadow system ones.
            "export PATH=${sandbox.absolutePath}/bin:$a/usr/local/sbin:$a/usr/local/bin:$a/usr/sbin:$a/usr/bin:$a/sbin:$a/bin:/system/bin:/system/xbin; "
        } else "export PATH=${sandbox.absolutePath}/bin:/system/bin:/system/xbin; "
    }
}

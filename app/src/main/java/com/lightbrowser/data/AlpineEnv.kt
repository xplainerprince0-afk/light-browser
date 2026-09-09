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

    /** Default interactive profile: short `sandbox $` prompt (not the full path). */
    private const val DEFAULT_PROFILE =
        "PS1='sandbox \$ '\n" +
            "alias ll='ls -la'\n" +
            "alias la='ls -a'\n"

    private const val B_MARK = "# >>> LIGHTBROWSER-B (managed — do not edit) >>>"
    private const val B_END = "# <<< LIGHTBROWSER-B <<<"

    /** `b` for real shells (PTY): talks to the agent HTTP bridge. No exec needed. */
    private const val B_FUNCTION =
        "# >>> LIGHTBROWSER-B (managed — do not edit) >>>\n" +
            "b() {\n" +
            "  _b_tok=\"\$(cat \"\$HOME/.agent_token\" 2>/dev/null)\"\n" +
            "  if [ -z \"\$_b_tok\" ]; then echo 'agent server is off — start it (drawer -> Agent bridge, or EXEC: b serve on)'; return 1; fi\n" +
            "  _b_port=\"\${_b_tok%% *}\"; _b_key=\"\${_b_tok#* }\"\n" +
            "  if [ -z \"\$_b_port\" ] || [ \"\$_b_port\" = \"\$_b_key\" ]; then echo 'stale token — restart the server'; return 1; fi\n" +
            "  if ! command -v curl >/dev/null 2>&1; then echo 'need curl — run: toolbox-install curl'; return 1; fi\n" +
            "  _b_get() { _b_p=\"\$1\"; shift; curl -s --get \"http://127.0.0.1:\$_b_port\$_b_p\" --data-urlencode \"token=\$_b_key\" \"\$@\"; echo; }\n" +
            "  _b_c=\"\$1\"; [ \$# -gt 0 ] && shift\n" +
            "  case \"\$_b_c\" in\n" +
            "    ''|help) echo 'b open|new|tabs|tab|close|home|back|forward|reload|stop|find|snap|text|read|js|shot|console|cookies|history|downloads|click|fill|submit|hover|select|store|pos|tap|swipe|scroll|scrollto (server must be on)';;\n" +
            "    status|url|title) _b_get '/status';;\n" +
            "    open|new) [ -z \"\$1\" ] && { echo \"usage: b \$_b_c <url>\"; return 1; }; _b_get \"/\$_b_c\" --data-urlencode \"url=\$1\";;\n" +
            "    tabs|home|back|forward|reload|stop|snap|text|console|downloads) _b_get \"/\$_b_c\";;\n" +
            "    read) _b_get '/read' --data-urlencode \"max=\${1:-6000}\";;\n" +
            "    shot) if [ \"\$1\" = \"--full\" ]; then _b_get '/shot' --data-urlencode \"full=1\"; else _b_get '/shot'; fi;;\n" +
            "    hover) _b_get '/hover' --data-urlencode \"sel=\$1\";;\n" +
            "    select) _b_sel=\"\$1\"; shift; _b_get '/select' --data-urlencode \"sel=\$_b_sel\" --data-urlencode \"value=\$*\";;\n" +
            "    store) _b_sub=\"\$1\"; case \"\$_b_sub\" in list|'') _b_get '/store';; remove|unstore) _b_get '/store' --data-urlencode \"op=remove\" --data-urlencode \"name=\$2\";; *) _b_get '/store' --data-urlencode \"op=set\" --data-urlencode \"name=\$1\" --data-urlencode \"sel=\$2\";; esac;;\n" +
            "    close) _b_get '/close' --data-urlencode \"i=\${1:--1}\";;\n" +
            "    tab) _b_get '/switch' --data-urlencode \"i=\$1\";;\n" +
            "    cookies) _b_get '/cookies' --data-urlencode \"op=\${1:-get}\" --data-urlencode \"value=\$2\" --data-urlencode \"url=\$3\";;\n" +
            "    history) _b_get '/history' --data-urlencode \"n=\${1:-20}\";;\n" +
            "    submit) _b_get '/submit' --data-urlencode \"sel=\$1\";;\n" +
            "    find) _b_get '/find' --data-urlencode \"q=\$*\";;\n" +
            "    js) _b_get '/js' --data-urlencode \"expr=\$*\";;\n" +
            "    click) _b_get '/click' --data-urlencode \"sel=\$1\";;\n" +
            "    fill) _b_sel=\"\$1\"; shift; _b_get '/fill' --data-urlencode \"sel=\$_b_sel\" --data-urlencode \"value=\$*\";;\n" +
            "    pos) _b_get '/pos' --data-urlencode \"sel=\$1\";;\n" +
            "    tap) _b_get '/tap' --data-urlencode \"x=\$1\" --data-urlencode \"y=\$2\";;\n" +
            "    swipe) _b_get '/swipe' --data-urlencode \"x1=\$1\" --data-urlencode \"y1=\$2\" --data-urlencode \"x2=\$3\" --data-urlencode \"y2=\$4\" --data-urlencode \"ms=\${5:-300}\";;\n" +
            "    scroll) _b_get '/scroll' --data-urlencode \"y=\${1:-500}\";;\n" +
            "    scrollto) _b_get '/scrollto' --data-urlencode \"x=\${1:-0}\" --data-urlencode \"y=\${2:-0}\";;\n" +
            "    record|serve|alias|unalias) echo \"use EXEC-mode b \$_b_c (stateful, no HTTP route)\";;\n" +
            "    ext) ls -1 \"\$HOME/.b-ext\" 2>/dev/null || echo '(no extensions — b mkext <name>)';;\n" +
            "    mkext) _b_n=\"\$1\"; case \"\$_b_n\" in ''|*[!a-z0-9_-]*) echo 'usage: b mkext <name>  ([a-z0-9_-])'; return 1;; esac; mkdir -p \"\$HOME/.b-ext\"; _b_f=\"\$HOME/.b-ext/\$_b_n.sh\"; [ -f \"\$_b_f\" ] && { echo \"exists: \$_b_f\"; return 1; }; printf '%s\\n' '#!/bin/sh' '# custom b command — args in \$1..' '# agent server: \$B_PORT / \$B_KEY (server must be on)' '# example: list tabs' 'curl -s --get \"http://127.0.0.1:\$B_PORT/tabs\" --data-urlencode \"token=\$B_KEY\"; echo' > \"\$_b_f\"; chmod +x \"\$_b_f\"; echo \"created \$_b_f — edit it, then run: b \$_b_n\";;\n" +
            "    *) if [ -x \"\$HOME/.b-ext/\$_b_c.sh\" ]; then B_PORT=\"\$_b_port\" B_KEY=\"\$_b_key\" sh \"\$HOME/.b-ext/\$_b_c.sh\" \"\$@\"; else echo \"unknown b subcommand: \$_b_c\"; return 1; fi;;\n" +
            "  esac\n" +
            "}\n" +
            "# opencode via the system linker (direct exec is blocked for app files).\n" +
            "opencode() {\n" +
            "  _o_bin=\"\$HOME/bin/opencode\"\n" +
            "  if [ ! -f \"\$_o_bin\" ]; then echo 'not installed — run: opencode-install'; return 1; fi\n" +
            "  if [ -f /system/bin/linker64 ]; then _o_ld=/system/bin/linker64\n" +
            "  elif [ -f /system/bin/linker ]; then _o_ld=/system/bin/linker\n" +
            "  else echo 'no system linker on this device'; return 1; fi\n" +
            "  \"\$_o_ld\" \"\$_o_bin\" \"\$@\"\n" +
            "}\n" +
            "# Short prompt (overrides any earlier PS1 — full paths eat the line).\n" +
            "PS1='sandbox \$ '\n" +
            "# <<< LIGHTBROWSER-B <<<"

    /**
     * Idempotent: (re)writes the managed `b()` block in ~/.profile,
     * preserving user edits outside the markers.
     */
    fun ensureBFunction(sandbox: File): Boolean {
        return try {
            ensureRuntimeFiles(sandbox)
            val profile = File(sandbox, ".profile")
            val cur = try { profile.readText() } catch (_: Exception) { "" }
            val lines = cur.lines()
            val s = lines.indexOfFirst { it.startsWith("# >>> LIGHTBROWSER-B") }
            val e = lines.indexOfFirst { it.startsWith("# <<< LIGHTBROWSER-B") }
            val kept = when {
                s >= 0 && e > s -> (lines.subList(0, s) + lines.subList(e + 1, lines.size)).joinToString("\n")
                s >= 0 -> lines.subList(0, s).joinToString("\n")
                else -> cur
            }
            profile.writeText(kept.trimEnd() + "\n\n" + B_FUNCTION + "\n")
            true
        } catch (_: Exception) { false }
    }

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
            // Short `sandbox $` prompt for interactive shells (mksh sources $ENV);
            // EXEC sh -c runs ignore both.
            "PS1=sandbox \$ ",
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

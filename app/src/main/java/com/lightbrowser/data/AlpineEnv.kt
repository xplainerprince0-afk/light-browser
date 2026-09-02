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
            abi.contains("armeabi") -> "armhf"
            abi.contains("x86_64") -> "x86_64"
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
            onProgress("Downloading Alpine $ALPINE_VERSION ($archSlug())…")
            downloadFile(downloadUrl(), tarball, onProgress)
            onProgress("Extracting to sandbox/alpine…")
            extractTarGz(tarball, dest)
            tarball.delete()
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
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.connect()
        val total = conn.contentLengthLong
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
    }

    private fun extractTarGz(tarGz: File, destDir: File) {
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
                }
                val pad = (512 - (size % 512)) % 512
                if (pad > 0) gzip.skip(pad)
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
            "$alpinePath:/system/bin:/system/xbin"
        } else {
            "/system/bin:/system/xbin:/vendor/bin"
        }
        return arrayOf(
            "HOME=${sandbox.absolutePath}",
            "PWD=${cwd.absolutePath}",
            "PATH=$path",
            "TERM=xterm-256color",
            "HOSTNAME=alpine",
            "ALPINE_ROOT=${alpine.absolutePath}",
            "OSTYPE=linux-musl"
        )
    }

    fun shellPrefix(sandbox: File): String {
        return if (isInstalled(sandbox)) {
            val a = alpineDir(sandbox).absolutePath
            "export PATH=$a/usr/bin:$a/bin:/system/bin; "
        } else ""
    }
}

package com.rg.webloom.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** One-file backup of every SharedPreferences store (scripts, history, tabs…). */
object Backup {
    private val FILES = listOf(
        "lb_prefs", "scripts_v1", "history_v1", "bookmarks_v1",
        "browser_tabs", "site_prefs_v1", "app_theme",
        "cookie_profiles_v1", "b_store", "b_block", "term_aliases"
    )

    /** Max single file to include in a zip (100 MB) — skips runaway recordings. */
    private const val MAX_ENTRY_BYTES = 100L * 1024 * 1024

    fun export(ctx: Context): JSONObject {
        val root = JSONObject()
        root.put("app", "lightbrowser")
        root.put("v", 1)
        FILES.forEach { name ->
            try {
                val p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                val o = JSONObject()
                p.all.forEach { (k, v) ->
                    when (v) {
                        is String -> o.put(k, v)
                        is Int -> o.put(k, v)
                        is Long -> o.put(k, v)
                        is Float -> o.put(k, v.toDouble())
                        is Boolean -> o.put(k, v)
                        is Set<*> -> o.put(k, org.json.JSONArray(v.filterIsInstance<String>()))
                    }
                }
                root.put(name, o)
            } catch (_: Exception) {}
        }
        return root
    }

    /** Returns number of stores restored. Never wipes before successful parse (rollback-safe). */
    fun import(ctx: Context, root: JSONObject): Int {
        if (root.optString("app", "") != "lightbrowser") return 0
        if (root.optInt("v", 1) > 1) return 0
        // Parse everything into memory first; only apply if at least one store parses.
        val staged = mutableMapOf<String, Map<String, Any>>()
        FILES.forEach { name ->
            try {
                val o = root.optJSONObject(name) ?: return@forEach
                val map = mutableMapOf<String, Any>()
                o.keys().forEach { k ->
                    when (val v = o.get(k)) {
                        is String -> map[k] = v
                        is Int -> map[k] = v
                        is Long -> map[k] = v
                        is Double -> map[k] = v
                        is Boolean -> map[k] = v
                        is org.json.JSONArray -> {
                            val set = (0 until v.length()).mapNotNull {
                                try { v.getString(it) } catch (_: Exception) { null }
                            }.toSet()
                            map[k] = set
                        }
                    }
                }
                staged[name] = map
            } catch (_: Exception) {}
        }
        if (staged.isEmpty()) return 0
        var n = 0
        staged.forEach { (name, map) ->
            try {
                val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                map.forEach { (k, v) ->
                    when (v) {
                        is String -> ed.putString(k, v)
                        is Int -> ed.putInt(k, v)
                        is Long -> ed.putLong(k, v)
                        is Double -> {
                            if (k.startsWith("pl_speed") || k.startsWith("term_font") || k.startsWith("ui_font")) {
                                ed.putFloat(k, v.toFloat().coerceIn(0.25f, 3f))
                            } else if (v % 1.0 == 0.0 && v < Int.MAX_VALUE) {
                                ed.putLong(k, v.toLong())
                            } else ed.putFloat(k, v.toFloat())
                        }
                        is Boolean -> ed.putBoolean(k, v)
                        is Set<*> -> ed.putStringSet(k, v.filterIsInstance<String>().toSet())
                    }
                }
                ed.apply()
                n++
            } catch (_: Exception) {}
        }
        return n
    }

    data class ZipStats(val prefsStores: Int, val files: Long, val bytes: Long, val skipped: Int)

    /**
     * Full backup: prefs JSON + entire filesDir/sandbox tree.
     * Zip-slip guarded, skips `cache/` dirs + oversized files. Fail-soft per entry.
     */
    fun exportZip(ctx: Context, out: java.io.OutputStream): ZipStats {
        var files = 0L; var bytes = 0L; var skipped = 0
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out)).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("prefs.json"))
            val prefsBytes = try { export(ctx).toString().toByteArray(Charsets.UTF_8) } catch (_: Exception) { "{}".toByteArray() }
            z.write(prefsBytes)
            z.closeEntry()
            val sb = try { java.io.File(ctx.filesDir, "sandbox") } catch (_: Exception) { null }
            if (sb != null && sb.isDirectory) {
                val base = try { sb.canonicalPath } catch (_: Exception) { sb.absolutePath }
                val q = ArrayDeque<java.io.File>().also { it.add(sb) }
                while (q.isNotEmpty()) {
                    val f = q.removeFirst()
                    val kids = try { f.listFiles() } catch (_: Exception) { null }
                    if (kids != null) {
                        kids.forEach { k ->
                            try {
                                // Skip CPU-cache noise, never user data.
                                val rel = k.relativeTo(sb).path.replace('\\', '/')
                                if ("/cache/" in "/$rel/" || rel == "cache" || rel.startsWith("cache/")) return@forEach
                                if (k.isDirectory) q.add(k)
                                else {
                                    val len = try { k.length() } catch (_: Exception) { 0L }
                                    if (len > MAX_ENTRY_BYTES) { skipped++; return@forEach }
                                    val canon = try { k.canonicalPath } catch (_: Exception) { "" }
                                    if (!canon.startsWith(base + java.io.File.separator)) { skipped++; return@forEach }
                                    z.putNextEntry(java.util.zip.ZipEntry("sandbox/$rel"))
                                    k.inputStream().use { ins -> ins.copyTo(z, 64 * 1024) }
                                    z.closeEntry()
                                    files++; bytes += len
                                }
                            } catch (_: Exception) { skipped++ }
                        }
                    }
                }
            }
        }
        return ZipStats(0, files, bytes, skipped)
    }

    /**
     * Restore from [exportZip]. Returns (prefsStores, files). Prefs parse first —
     * sandbox files only extract if prefs.json parses (rollback-safe ordering).
     */
    fun importZip(ctx: Context, ins: java.io.InputStream): Pair<Int, Int> {
        var prefsN = 0; var files = 0
        val staged = mutableListOf<Pair<String, ByteArray>>()
        var prefsJson: String? = null
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(ins)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                try {
                    val name = e.name ?: ""
                    if (e.isDirectory) { z.closeEntry(); e = z.nextEntry; continue }
                    if (name == "prefs.json") {
                        prefsJson = z.readBytes().toString(Charsets.UTF_8)
                    } else if (name.startsWith("sandbox/") && !e.isDirectory) {
                        // Cap staged RAM: skip absurd entries (zip bombs).
                        val buf = java.io.ByteArrayOutputStream()
                        val tmp = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val r = z.read(tmp)
                            if (r <= 0) break
                            total += r
                            if (total > MAX_ENTRY_BYTES) break
                            buf.write(tmp, 0, r)
                        }
                        if (total <= MAX_ENTRY_BYTES) staged.add(name to buf.toByteArray())
                    }
                } catch (_: Exception) {}
                try { z.closeEntry() } catch (_: Exception) {}
                e = z.nextEntry
            }
        }
        if (prefsJson != null) {
            try { prefsN = import(ctx, JSONObject(prefsJson!!)) } catch (_: Exception) {}
        }
        if (prefsN == 0 && prefsJson == null) return 0 to 0
        val sb = try { java.io.File(ctx.filesDir, "sandbox").apply { mkdirs() } } catch (_: Exception) { null }
            ?: return prefsN to 0
        val base = try { sb.canonicalPath } catch (_: Exception) { sb.absolutePath }
        staged.forEach { (name, data) ->
            try {
                val rel = name.removePrefix("sandbox/").replace('\\', '/')
                if (rel.isBlank() || rel.contains("..")) return@forEach
                val dest = java.io.File(sb, rel)
                val canon = try { dest.canonicalPath } catch (_: Exception) { "" }
                if (!canon.startsWith(base + java.io.File.separator) && canon != base) return@forEach
                try { dest.parentFile?.mkdirs() } catch (_: Exception) {}
                dest.writeBytes(data)
                files++
            } catch (_: Exception) {}
        }
        return prefsN to files
    }
}

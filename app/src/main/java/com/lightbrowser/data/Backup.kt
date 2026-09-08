package com.lightbrowser.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** One-file backup of every SharedPreferences store (scripts, history, tabs…). */
object Backup {
    private val FILES = listOf(
        "lb_prefs", "scripts_v1", "history_v1", "bookmarks_v1",
        "browser_tabs", "site_prefs_v1", "app_theme"
    )

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
}

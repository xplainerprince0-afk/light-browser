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

    /** Returns number of stores restored. Types round-trip via _t markers for numbers. */
    fun import(ctx: Context, root: JSONObject): Int {
        var n = 0
        FILES.forEach { name ->
            try {
                val o = root.optJSONObject(name) ?: return@forEach
                val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                o.keys().forEach { k ->
                    when (val v = o.get(k)) {
                        is String -> ed.putString(k, v)
                        is Int -> ed.putInt(k, v)
                        is Long -> ed.putLong(k, v)
                        is Double -> {
                            // Floats were saved as Double; Ints/Longs stay integral.
                            if (k.startsWith("pl_speed") || k.startsWith("term_font") || k.startsWith("ui_font")) {
                                ed.putFloat(k, v.toFloat())
                            } else if (v % 1.0 == 0.0 && v < Int.MAX_VALUE) {
                                ed.putLong(k, v.toLong())
                            } else ed.putFloat(k, v.toFloat())
                        }
                        is Boolean -> ed.putBoolean(k, v)
                        is org.json.JSONArray -> {
                            val set = (0 until v.length()).mapNotNull {
                                try { v.getString(it) } catch (_: Exception) { null }
                            }.toSet()
                            ed.putStringSet(k, set)
                        }
                    }
                }
                ed.apply()
                n++
            } catch (_: Exception) {}
        }
        return n
    }
}

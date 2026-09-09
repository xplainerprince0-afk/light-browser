package com.lightbrowser.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val url: String, val title: String, val time: Long = System.currentTimeMillis())

object HistoryStorage {
    private const val PREF = "history_v1"
    private const val KEY = "history"
    private const val MAX = 200

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<HistoryEntry> {
        val s = prefs(ctx).getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(s)
            val out = mutableListOf<HistoryEntry>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url", "")
                    if (url.isBlank()) continue
                    out.add(HistoryEntry(url, o.optString("title", url), o.optLong("time", System.currentTimeMillis())))
                } catch (_: Exception) { }
            }
            out
        } catch (_: Exception) { mutableListOf() }
    }

    fun add(ctx: Context, url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("chrome:") ||
            url.startsWith("lb://") || url.startsWith("data:") || url.startsWith("blob:") ||
            url.startsWith("javascript:")
        ) return
        val list = all(ctx)
        // remove duplicate url
        list.removeAll { it.url == url }
        list.add(0, HistoryEntry(url, title.ifBlank { url }))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(ctx, list)
    }

    private fun save(ctx: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e -> val o = JSONObject(); o.put("url", e.url); o.put("title", e.title); o.put("time", e.time); arr.put(o) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun saveList(ctx: Context, list: List<HistoryEntry>) = save(ctx, list)

    /** Delete entries newer than ts (Chrome-style "last hour" range). Returns removed count. */
    fun deleteNewerThan(ctx: Context, ts: Long): Int {
        val list = all(ctx)
        val kept = list.filter { it.time < ts }
        save(ctx, kept)
        return list.size - kept.size
    }

    fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }
}


data class Bookmark(val url: String, val title: String, val time: Long = System.currentTimeMillis())

object BookmarkStorage {
    private const val PREF = "bookmarks_v1"
    private const val KEY = "bookmarks"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<Bookmark> {
        val s = prefs(ctx).getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(s)
            val out = mutableListOf<Bookmark>()
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url", "")
                    if (url.isBlank()) continue
                    out.add(Bookmark(url, o.optString("title", url), o.optLong("time", System.currentTimeMillis())))
                } catch (_: Exception) { }
            }
            out
        } catch (_: Exception) { mutableListOf() }
    }

    fun isBookmarked(ctx: Context, url: String): Boolean = all(ctx).any { it.url == url }

    fun toggle(ctx: Context, url: String, title: String): Boolean {
        val list = all(ctx)
        val existing = list.find { it.url == url }
        return if (existing != null) {
            list.remove(existing); save(ctx, list); false
        } else {
            list.add(0, Bookmark(url, title)); save(ctx, list); true
        }
    }

    private fun save(ctx: Context, list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { e -> val o = JSONObject(); o.put("url", e.url); o.put("title", e.title); o.put("time", e.time); arr.put(o) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Delete bookmarks older than ts. Returns removed count. */
    fun deleteOlderThan(ctx: Context, ts: Long): Int {
        val list = all(ctx)
        val kept = list.filter { it.time >= ts }
        save(ctx, kept)
        return list.size - kept.size
    }

    fun clear(ctx: Context) { prefs(ctx).edit().remove(KEY).apply() }
}

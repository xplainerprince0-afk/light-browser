package com.lightbrowser.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object ScriptStorage {
    private const val PREF = "scripts_v1"
    private const val KEY = "scripts"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<UserScript> {
        val s = prefs(ctx).getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UserScript(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    code = o.getString("code"),
                    enabled = o.optBoolean("enabled", true),
                    description = o.optString("description", ""),
                    matches = o.optJSONArray("matches")?.let { ja -> (0 until ja.length()).map { ja.getString(it) } } ?: emptyList(),
                    runAt = o.optString("runAt", "document_idle"),
                    grants = o.optJSONArray("grants")?.let { ja -> (0 until ja.length()).map { ja.getString(it) } } ?: emptyList()
                )
            }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveAll(ctx: Context, list: List<UserScript>) {
        val arr = JSONArray()
        list.forEach { sc ->
            val o = JSONObject()
            o.put("id", sc.id)
            o.put("name", sc.name)
            o.put("code", sc.code)
            o.put("enabled", sc.enabled)
            o.put("description", sc.description)
            o.put("matches", JSONArray(sc.matches))
            o.put("runAt", sc.runAt)
            o.put("grants", JSONArray(sc.grants))
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, script: UserScript) {
        val l = all(ctx); l.add(script); saveAll(ctx, l)
    }

    fun update(ctx: Context, script: UserScript) {
        val l = all(ctx); val idx = l.indexOfFirst { it.id == script.id }
        if (idx >= 0) { l[idx] = script; saveAll(ctx, l) }
    }

    fun delete(ctx: Context, id: String) {
        val l = all(ctx).filterNot { it.id == id }; saveAll(ctx, l)
    }

    fun enabledForUrl(ctx: Context, url: String): List<UserScript> {
        return all(ctx).filter { it.enabled && UserScript.matchesUrl(it.matches, url) }
    }
}

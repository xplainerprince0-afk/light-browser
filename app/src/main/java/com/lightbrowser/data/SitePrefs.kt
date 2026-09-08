package com.lightbrowser.data

import android.content.Context
import org.json.JSONObject

/** Per-host overrides. null = follow the global switch. */
data class SiteSetting(val js: Boolean?, val desktop: Boolean?, val adblock: Boolean?)

object SitePrefs {
    private const val PREF = "site_prefs_v1"

    private fun all(ctx: Context): JSONObject {
        return try {
            JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("sites", "{}") ?: "{}")
        } catch (_: Exception) { JSONObject() }
    }

    private fun save(ctx: Context, o: JSONObject) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("sites", o.toString()).apply()
        } catch (_: Exception) {}
    }

    fun hostOf(url: String): String {
        return try {
            android.net.Uri.parse(url).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }
    }

    fun get(ctx: Context, host: String): SiteSetting {
        if (host.isBlank()) return SiteSetting(null, null, null)
        return try {
            val o = all(ctx).optJSONObject(host) ?: return SiteSetting(null, null, null)
            SiteSetting(
                js = if (o.has("js")) o.optBoolean("js") else null,
                desktop = if (o.has("desk")) o.optBoolean("desk") else null,
                adblock = if (o.has("ad")) o.optBoolean("ad") else null
            )
        } catch (_: Exception) { SiteSetting(null, null, null) }
    }

    fun set(ctx: Context, host: String, js: Boolean?, desktop: Boolean?, adblock: Boolean?) {
        if (host.isBlank()) return
        try {
            val all = all(ctx)
            val o = all.optJSONObject(host) ?: JSONObject()
            if (js == null) o.remove("js") else o.put("js", js)
            if (desktop == null) o.remove("desk") else o.put("desk", desktop)
            if (adblock == null) o.remove("ad") else o.put("ad", adblock)
            if (o.length() == 0) all.remove(host) else all.put(host, o)
            save(ctx, all)
        } catch (_: Exception) {}
    }

    fun effectiveJs(ctx: Context, host: String): Boolean = get(ctx, host).js ?: Prefs.jsEnabled
    fun effectiveDesktop(ctx: Context, host: String): Boolean = get(ctx, host).desktop ?: Prefs.desktopMode
    fun effectiveAdblock(ctx: Context, host: String): Boolean = get(ctx, host).adblock ?: Prefs.adBlock
}

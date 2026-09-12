package com.rg.webloom.data

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject

/**
 * Per-site cookie profiles: snapshot + restore logins without full clear.
 * e.g. `b cookies save main` on github.com, then `b cookies load alt`.
 * SharedPreferences-backed (survives restarts). Cookies themselves stay in
 * the WebView CookieManager — we only store the header string per profile.
 *
 * Note: some sites also keep session state in localStorage; restoring
 * cookies alone revives most logins but not all. `b clear-data storage`
 * still logs you out even with profiles saved.
 */
object CookieProfiles {
    private const val PREF = "cookie_profiles_v1"
    private const val KEY = "profiles"

    private fun prefs(): android.content.SharedPreferences? = try {
        AppCtx.ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    } catch (_: Exception) { null }

    fun normalizeHost(input: String): String {
        return try {
            var t = input.trim().lowercase()
            if (t.isEmpty()) return ""
            if ("://" !in t) t = "https://$t"
            (try { android.net.Uri.parse(t).host } catch (_: Exception) { null }
                ?: return "").trim().trimEnd('.').lowercase().take(253)
        } catch (_: Exception) { "" }
    }

    fun validProfile(name: String): Boolean =
        name.matches(Regex("[A-Za-z0-9_\\-]{1,40}"))

    private fun root(): JSONObject = try {
        JSONObject(prefs()?.getString(KEY, null) ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun saveRoot(o: JSONObject) {
        try { prefs()?.edit()?.putString(KEY, o.toString())?.apply() } catch (_: Exception) {}
    }

    fun save(host: String, profile: String, cookies: String): Boolean {
        if (host.isBlank() || !validProfile(profile)) return false
        return try {
            val r = root()
            val h = r.optJSONObject(host) ?: JSONObject()
            h.put(profile, JSONObject().put("cookies", cookies).put("ts", System.currentTimeMillis()))
            r.put(host, h)
            saveRoot(r)
            true
        } catch (_: Exception) { false }
    }

    fun load(host: String, profile: String): String? = try {
        root().optJSONObject(host)?.optJSONObject(profile)?.optString("cookies", null)?.ifBlank { null }
    } catch (_: Exception) { null }

    fun list(host: String): Map<String, Long> = try {
        val h = root().optJSONObject(host) ?: return emptyMap()
        buildMap {
            h.keys().forEach { k ->
                try { put(k, h.optJSONObject(k)?.optLong("ts", 0L) ?: 0L) } catch (_: Exception) {}
            }
        }
    } catch (_: Exception) { emptyMap() }

    fun hosts(): Set<String> = try {
        buildSet { root().keys().forEach { add(it) } }
    } catch (_: Exception) { emptySet() }

    fun delete(host: String, profile: String): Boolean = try {
        val r = root()
        val h = r.optJSONObject(host) ?: return false
        h.remove(profile)
        if (h.length() == 0) r.remove(host) else r.put(host, h)
        saveRoot(r)
        true
    } catch (_: Exception) { false }

    /** Expire every cookie currently set for url (per-host clear workaround — no per-host API). */
    fun expireAll(url: String): Int {
        return try {
            val cm = CookieManager.getInstance()
            val raw = try { cm.getCookie(url) } catch (_: Exception) { null } ?: return 0
            var n = 0
            raw.split(";").forEach { part ->
                val name = part.substringBefore("=").trim()
                if (name.isNotEmpty() && "=" in part) {
                    try {
                        cm.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                        n++
                    } catch (_: Exception) {}
                }
            }
            try { cm.flush() } catch (_: Exception) {}
            n
        } catch (_: Exception) { 0 }
    }

    /** Restore a saved header string onto url. Returns cookies applied. */
    fun applyTo(url: String, header: String): Int {
        return try {
            val cm = CookieManager.getInstance()
            var n = 0
            header.split(";").forEach { part ->
                val p = part.trim()
                if (p.isNotEmpty() && "=" in p) {
                    try { cm.setCookie(url, p); n++ } catch (_: Exception) {}
                }
            }
            try { cm.flush() } catch (_: Exception) {}
            n
        } catch (_: Exception) { 0 }
    }
}

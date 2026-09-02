package com.lightbrowser.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "lb_prefs"
    private fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var homePage: String
        get() = p(AppCtx.ctx).getString("home", "https://www.google.com") ?: "https://www.google.com"
        set(v) { p(AppCtx.ctx).edit().putString("home", v).apply() }

    var jsEnabled: Boolean
        get() = p(AppCtx.ctx).getBoolean("js", true)
        set(v) { p(AppCtx.ctx).edit().putBoolean("js", v).apply() }

    var desktopMode: Boolean
        get() = p(AppCtx.ctx).getBoolean("desktop", false)
        set(v) { p(AppCtx.ctx).edit().putBoolean("desktop", v).apply() }

    var adBlock: Boolean
        get() = p(AppCtx.ctx).getBoolean("adblock", false)
        set(v) { p(AppCtx.ctx).edit().putBoolean("adblock", v).apply() }

    /** Persist cookies & localStorage so logins survive app restarts. */
    var saveSiteData: Boolean
        get() = p(AppCtx.ctx).getBoolean("save_site_data", true)
        set(v) { p(AppCtx.ctx).edit().putBoolean("save_site_data", v).apply() }

    /** Enable HTTP cache for faster revisits. */
    var cacheEnabled: Boolean
        get() = p(AppCtx.ctx).getBoolean("cache_enabled", true)
        set(v) { p(AppCtx.ctx).edit().putBoolean("cache_enabled", v).apply() }

    /** Default search engine key (google/bing/duckduckgo/brave/yahoo) */
    var searchEngine: String
        get() = p(AppCtx.ctx).getString("search_engine", "google") ?: "google"
        set(v) { p(AppCtx.ctx).edit().putString("search_engine", v).apply() }

    /** Build search URL for the given query using the configured search engine */
    fun buildSearchUrl(query: String): String {
        val encoded = android.net.Uri.encode(query)
        return when (searchEngine) {
            "bing"        -> "https://www.bing.com/search?q=$encoded"
            "duckduckgo"  -> "https://duckduckgo.com/?q=$encoded"
            "brave"       -> "https://search.brave.com/search?q=$encoded"
            "yahoo"       -> "https://search.yahoo.com/search?p=$encoded"
            "ecosia"      -> "https://www.ecosia.org/search?q=$encoded"
            else          -> "https://www.google.com/search?q=$encoded"  // default: google
        }
    }
}

object AppCtx {
    lateinit var ctx: Context
    fun init(c: Context) { ctx = c.applicationContext }
}

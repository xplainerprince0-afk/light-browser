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
}

object AppCtx {
    lateinit var ctx: Context
    fun init(c: Context) { ctx = c.applicationContext }
}

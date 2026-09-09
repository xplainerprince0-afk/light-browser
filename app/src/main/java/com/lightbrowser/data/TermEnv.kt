package com.lightbrowser.data

/**
 * Persistent shell variables: EXEC mode runs every command in a fresh
 * `sh -c`, so plain `export FOO=bar` would die with the process. Assignments
 * saved here are re-applied to every EXEC command AND to new PTY sessions.
 * Stored in SharedPreferences (survives restarts); `unset` restores defaults
 * because the built-in env is always emitted underneath.
 */
object TermEnv {
    private const val PREF = "term_env"
    private const val KEY = "vars"
    private val NAME_RE = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    private fun prefs() = try {
        AppCtx.ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
    } catch (_: Exception) { null }

    fun all(): Map<String, String> {
        return try {
            val raw = prefs()?.getString(KEY, null) ?: return emptyMap()
            val o = org.json.JSONObject(raw)
            buildMap {
                o.keys().forEach { k ->
                    try {
                        val v = o.optString(k, "")
                        if (k.matches(NAME_RE)) put(k, v)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    fun validName(name: String): Boolean = name.matches(NAME_RE)

    fun set(name: String, value: String) {
        if (!validName(name)) return
        try {
            val m = all().toMutableMap()
            m[name] = value
            prefs()?.edit()?.putString(KEY, org.json.JSONObject(m as Map<*, *>).toString())?.apply()
        } catch (_: Exception) {}
    }

    fun remove(name: String): Boolean {
        return try {
            val m = all().toMutableMap()
            if (!m.containsKey(name)) return false
            m.remove(name)
            prefs()?.edit()?.putString(KEY, org.json.JSONObject(m as Map<*, *>).toString())?.apply()
            true
        } catch (_: Exception) { false }
    }
}

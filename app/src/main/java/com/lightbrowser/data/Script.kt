package com.lightbrowser.data

import java.util.UUID

data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val enabled: Boolean = true,
    val description: String = "",
    val matches: List<String> = emptyList(), // from @match / @include
    val runAt: String = "document_idle", // document_start / document_end / document_idle
    val grants: List<String> = emptyList()
) {
    companion object {
        fun parseMeta(code: String): Map<String, List<String>> {
            val map = mutableMapOf<String, MutableList<String>>()
            val m = Regex("""//\s*==UserScript==([\s\S]*?)//\s*==/UserScript==""").find(code)
            if (m != null) {
                val block = m.groupValues[1]
                Regex("""//\s*@(\S+)\s+(.*)""").findAll(block).forEach { mr ->
                    val k = mr.groupValues[1].trim()
                    val v = mr.groupValues[2].trim()
                    map.getOrPut(k) { mutableListOf() }.add(v)
                }
            }
            return map
        }

        fun fromCode(raw: String): UserScript {
            val meta = parseMeta(raw)
            val name = meta["name"]?.firstOrNull() ?: "Unnamed"
            val desc = meta["description"]?.firstOrNull() ?: ""
            val matches = (meta["match"] ?: emptyList()) + (meta["include"] ?: emptyList())
            val runAt = meta["run-at"]?.firstOrNull() ?: "document_idle"
            val grants = meta["grant"] ?: emptyList()
            return UserScript(name = name, code = raw, description = desc, matches = matches, runAt = runAt, grants = grants)
        }

        fun globToRegex(glob: String): Regex {
            if (glob == "<all_urls>") return Regex(".*", RegexOption.IGNORE_CASE)
            // Violentmonkey @match glob -> regex. Escape then restore *
            var r = Regex.escape(glob).replace("\\*", ".*")
            // also handle scheme wildcard *:// -> https?://
            if (r.startsWith(".*://")) r = r.replaceFirst(".*://", "(https?|http|https)://")
            return Regex("^$r$", RegexOption.IGNORE_CASE)
        }

        fun matchesUrl(patterns: List<String>, url: String): Boolean {
            if (patterns.isEmpty()) return true
            val u = url.lowercase().trim()
            return patterns.any { p ->
                val pat = p.trim()
                if (pat.isEmpty()) return@any false
                if (pat == "<all_urls>") return@any true
                try {
                    val regex = globToRegex(pat)
                    regex.containsMatchIn(u)
                } catch (_: Exception) {
                    // fallback: simple contains of host
                    u.contains(pat.lowercase())
                }
            }
        }
    }
}

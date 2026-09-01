package com.lightbrowser.data

import java.util.UUID

data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val enabled: Boolean = true,
    val description: String = "",
    val matches: List<String> = emptyList(), // from @match / @include
    val runAt: String = "document_idle" // document_start / document_end / document_idle
) {
    companion object {
        fun parseMeta(code: String): Map<String, List<String>> {
            val map = mutableMapOf<String, MutableList<String>>()
            val m = Regex("""//\s*==UserScript==([\s\S]*?)//\s*==/UserScript==""").find(code)
            if (m != null) {
                val block = m.groupValues[1]
                Regex("""//\s*@(\w+)\s+(.*)""").findAll(block).forEach { mr ->
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
            return UserScript(name = name, code = raw, description = desc, matches = matches, runAt = runAt)
        }

        // very small glob->regex: *://*/*  =>  https?://.*\/.*  ; also domain wildcards
        fun globToRegex(glob: String): Regex {
            // escape regex except *
            var r = Regex.escape(glob).replace("\\*", ".*")
            // handle Violentmonkey specials: <all_urls>
            if (glob == "<all_urls>") r = ".*"
            return Regex("^$r$", RegexOption.IGNORE_CASE)
        }

        fun matchesUrl(patterns: List<String>, url: String): Boolean {
            if (patterns.isEmpty()) return true // inject everywhere if no @match (like Wibgar before)
            val u = url.lowercase()
            return patterns.any { p ->
                try {
                    // exact match or glob
                    if (p.contains("*") || p == "<all_urls>") globToRegex(p).containsMatchIn(u)
                    else u.contains(p.lowercase().trim().removePrefix("*://").removePrefix("https://").removePrefix("http://").substringBefore("/"))
                } catch (_: Exception) { false }
            }
        }
    }
}

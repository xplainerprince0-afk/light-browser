package com.lightbrowser.data

import java.util.UUID

data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val enabled: Boolean = true,
    val description: String = "",
    val matches: List<String> = emptyList(), // from @match / @include
    val runAt: String = "document_idle",
    val grants: List<String> = emptyList()
) {
    companion object {
        fun parseMeta(code: String): Map<String, List<String>> {
            val map = mutableMapOf<String, MutableList<String>>()
            // Support both // ==UserScript== and /* ==UserScript== */ block styles.
            val m = Regex("""==UserScript==([\s\S]*?)==/UserScript==""").find(code)
            if (m != null) {
                val block = m.groupValues[1]
                Regex("""[@*]?\s*@(\S+)\s+(.*)""").findAll(block).forEach { mr ->
                    val k = mr.groupValues[1].trim()
                    val v = mr.groupValues[2].trim().trimEnd('*', '/').trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) map.getOrPut(k) { mutableListOf() }.add(v)
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
            if (glob == "<all_urls>" || glob == "*") return Regex(".*", RegexOption.IGNORE_CASE)
            var r = Regex.escape(glob).replace("\\*", ".*")
            if (r.startsWith(".*://")) r = r.replaceFirst(".*://", "(https?://)")
            return Regex("^$r$", RegexOption.IGNORE_CASE)
        }

        fun globToPrefixRegex(glob: String): Regex {
            if (glob == "<all_urls>" || glob == "*") return Regex(".*", RegexOption.IGNORE_CASE)
            var r = Regex.escape(glob).replace("\\*", ".*")
            if (r.startsWith(".*://")) r = r.replaceFirst(".*://", "(https?://)")
            // remove trailing $ for prefix match, keep ^
            return Regex("^$r", RegexOption.IGNORE_CASE)
        }

        fun matchesUrl(patterns: List<String>, url: String): Boolean {
            if (patterns.isEmpty()) return false
            val full = url.lowercase().trim()
            val u = full.substringBefore("#").substringBefore("?")
            val uHost = try { android.net.Uri.parse(u).host?.lowercase() ?: "" } catch (_: Exception) { "" }
            if (uHost.isEmpty()) return false

            return patterns.any { raw ->
                val pat = raw.trim()
                if (pat.isEmpty()) return@any false
                if (pat == "<all_urls>" || pat == "*") return@any true
                try {
                    // 1. strict full match (Violentmonkey spec) — try with and without query.
                    val strict = globToRegex(pat)
                    if (strict.containsMatchIn(u) || strict.containsMatchIn(full)) return@any true

                    // 2. prefix match – allow pattern as prefix of URL (handles trailing /* case)
                    // e.g., https://wtr-lab.com/*/novel/*/ should match deeper URL with extra segments
                    // Guard: prefix host must equal URL host (prevents example.com matching example.com.evil.com).
                    val pHostEarly = try { android.net.Uri.parse(pat.replace("*", "x")).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                    val hostOkEarly = pHostEarly.isEmpty() || uHost == pHostEarly || (pHostEarly.startsWith("*.") && (uHost == pHostEarly.removePrefix("*.") || uHost.endsWith(pHostEarly.removePrefix("*"))))
                    val prefix = globToPrefixRegex(pat)
                    if (hostOkEarly && prefix.containsMatchIn(u)) {
                        // Extra boundary check for non-wildcard hosts: next char after host must be /, ?, #, :, or end.
                        if (!pHostEarly.contains("*") && pHostEarly.isNotEmpty() && !pat.contains("*")) {
                            val after = u.removePrefix(u.substringBefore(pHostEarly) + pHostEarly)
                            if (after.isEmpty() || after[0] in listOf('/', '?', '#', ':',)) return@any true
                        } else return@any true
                    }

                    // 3. also try prefix without trailing /* and optional slash
                    val trimmed = pat.removeSuffix("/*").removeSuffix("/")
                    if (trimmed != pat) {
                        val trimmedRegex = globToPrefixRegex(trimmed)
                        if (hostOkEarly && trimmedRegex.containsMatchIn(u)) return@any true
                    }

                    // 4. fuzzy host+path check for WTR and similar sites with variable slug depth
                    // If pattern host is wtr-lab.com and url host is wtr-lab.com, and both contain "novel" -> consider match
                    // This makes manager tolerant of consecutive * depth issues
                    val pHost = try { android.net.Uri.parse(pat.replace("*", "x")).host?.lowercase() ?: "" } catch (_: Exception) { "" }
                    val hostMatch = when {
                        pHost.isEmpty() -> false
                        pHost.startsWith("*.") -> uHost.endsWith(pHost.removePrefix("*.")) || uHost == pHost.removePrefix("*.")
                        pHost.contains("*") -> {
                            val hostRegex = Regex.escape(pHost).replace("\\*", ".*")
                            Regex("^$hostRegex$", RegexOption.IGNORE_CASE).containsMatchIn(uHost)
                        }
                        else -> uHost == pHost || uHost.endsWith(".$pHost")
                    }
                    if (!hostMatch) return@any false

                    // extract required path tokens (non-wildcard literals) and ensure they appear in order in URL path only
                    val urlPath = u.substringAfter("://").substringAfter("/").lowercase()
                    val tokens = pat.substringAfter("://").substringAfter("/").split("*", "/").map { it.trim() }.filter { it.isNotEmpty() && it != "/" && !it.contains(":") }
                    // keep only literal tokens that are not just wildcards
                    val literals = tokens.filter { it.length > 1 && !it.contains("*") }.map { it.lowercase() }
                    // for WTR, literals would be ["novel", "chapter"] etc.
                    if (literals.isNotEmpty()) {
                        var lastIdx = -1
                        var allFound = true
                        for (lit in literals) {
                            val idx = urlPath.indexOf(lit, startIndex = lastIdx + 1)
                            if (idx == -1 || idx < lastIdx) { allFound = false; break }
                            lastIdx = idx
                        }
                        if (allFound) return@any true
                    } else {
                        // host-only pattern already matched exactly above
                        return@any true
                    }

                    false
                } catch (_: Exception) {
                    u.contains(pat.lowercase())
                }
            }
        }
    }
}

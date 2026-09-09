package com.lightbrowser.data

import java.io.File

/**
 * Downloadable toolbox for the Alpine sandbox: dev-agent basics fetched
 * at runtime via `apk` (zero APK cost — the manifest below is strings only).
 *
 * Installs live INSIDE sandbox/alpine (managed by apk itself); nothing is
 * copied to sandbox/bin. Requires network for `apk add`; already-installed
 * tools keep working offline.
 */
object ToolboxManager {
    data class Tool(
        val name: String,
        val pkgs: List<String>,
        val desc: String,
        val approxMb: Int
    )

    val TOOLS: List<Tool> = listOf(
        Tool("git", listOf("git"), "version control (diff/commit/push)", 15),
        Tool("ssh", listOf("openssh-client"), "ssh/scp/ssh-keygen for GitHub", 5),
        Tool("curl", listOf("curl", "ca-certificates"), "fetch APIs + releases", 2),
        Tool("bash", listOf("bash"), "agent scripts assume bash", 6),
        Tool("jq", listOf("jq"), "JSON for the agent bridge", 1),
        Tool("nano", listOf("nano"), "tiny editor fallback", 1),
        Tool("rg", listOf("ripgrep"), "fast code search", 5),
        Tool("fd", listOf("fd"), "fast file finder", 4),
        Tool("fzf", listOf("fzf"), "fuzzy finder", 4),
        Tool("tmux", listOf("tmux"), "persistent sessions", 2),
        Tool("node", listOf("nodejs"), "JS tooling + MCP servers", 67),
        Tool("python", listOf("python3", "py3-pip"), "ad-hoc scripts", 50),
        Tool("nvim", listOf("neovim"), "full editor", 15),
        Tool("gh", listOf("github-cli"), "releases + PRs", 12)
    )

    val ESSENTIALS: List<String> = listOf("git", "ssh", "curl", "bash", "jq", "nano")
    val AGENT: List<String> = ESSENTIALS + listOf("rg", "fd", "fzf", "node", "python")

    fun byName(name: String): Tool? = TOOLS.firstOrNull { it.name == name.lowercase() }

    /** Expand `essentials|agent|all|<name> …` to apk package names. Empty = unknown. */
    fun resolve(arg: String): List<String> {
        val wants = arg.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (wants.isEmpty()) return emptyList()
        val names = when {
            wants == listOf("all") -> TOOLS.map { it.name }
            wants == listOf("essentials") -> ESSENTIALS
            wants == listOf("agent") -> AGENT
            else -> wants
        }
        val pkgs = mutableListOf<String>()
        for (n in names) {
            val t = byName(n) ?: return emptyList()
            pkgs += t.pkgs
        }
        return pkgs.distinct()
    }

    fun listText(): String {
        val sb = StringBuilder("Tools (runtime download, apk):\n")
        for (t in TOOLS) sb.append("• ${t.name} (~${t.approxMb}MB) — ${t.desc}\n")
        sb.append("Sets: essentials (~30MB: ${ESSENTIALS.joinToString(" ")})\n")
        sb.append("      agent (~180MB: essentials + rg fd fzf node python)")
        return sb.toString()
    }

    /**
     * Seed files minirootfs lacks on Android: apk repos + DNS. Returns false
     * only on IO failure (caller prints the error).
     */
    fun ensureNetFiles(sandbox: File): Boolean {
        return try {
            val root = AlpineEnv.alpineDir(sandbox)
            val repos = File(root, "etc/apk/repositories")
            if (!repos.exists() || repos.length() == 0L) {
                repos.parentFile?.mkdirs()
                repos.writeText(
                    "https://dl-cdn.alpinelinux.org/alpine/v3.19/main\n" +
                        "https://dl-cdn.alpinelinux.org/alpine/v3.19/community\n"
                )
            }
            val resolv = File(root, "etc/resolv.conf")
            if (!resolv.exists() || resolv.length() == 0L) {
                resolv.parentFile?.mkdirs()
                resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            }
            true
        } catch (_: Exception) { false }
    }
}

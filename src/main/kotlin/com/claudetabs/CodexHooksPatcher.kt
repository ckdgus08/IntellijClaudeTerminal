package com.claudetabs

/** Idempotent edits to Codex's `~/.codex/hooks.json`. */
internal object CodexHooksPatcher {
    val STATUS_EVENTS = listOf(
        "SessionStart",
        "UserPromptSubmit",
        "PermissionRequest",
        "PreToolUse",
        "PostToolUse",
        "Stop",
        "SessionEnd",
    )

    const val POSIX_SCRIPT = "~/.codex/rider-agent-tabs/status-hook.sh"
    const val WINDOWS_SCRIPT = "~\\.codex\\rider-agent-tabs\\status-hook.ps1"

    fun command(event: String) = "bash $POSIX_SCRIPT $event"
    fun commandWindows(event: String) =
        "powershell -NoProfile -ExecutionPolicy Bypass -File \"$WINDOWS_SCRIPT\" $event"

    /** Returns null when no rewrite is needed or the user's JSON cannot be parsed safely. */
    fun patch(hooksText: String?): String? {
        val root = parseRoot(hooksText) ?: return null
        if (root.containsKey("hooks") && root["hooks"] !is MutableMap<*, *>) return null
        @Suppress("UNCHECKED_CAST")
        val hooks = (root["hooks"] as? MutableMap<String, Any?>)
            ?: linkedMapOf<String, Any?>().also { root["hooks"] = it }

        var changed = false
        for (event in STATUS_EVENTS) {
            if (hooks.containsKey(event) && hooks[event] !is MutableList<*>) return null
            @Suppress("UNCHECKED_CAST")
            val groups = (hooks[event] as? MutableList<Any?>)
                ?: mutableListOf<Any?>().also { hooks[event] = it }
            if (containsOurCommand(groups, event)) continue
            groups.add(
                linkedMapOf<String, Any?>(
                    "hooks" to mutableListOf<Any?>(
                        linkedMapOf<String, Any?>(
                            "type" to "command",
                            "command" to command(event),
                            "commandWindows" to commandWindows(event),
                            "timeout" to MiniJson.Num("5"),
                        )
                    )
                )
            )
            changed = true
        }
        return if (changed) MiniJson.write(root) + "\n" else null
    }

    fun unpatch(hooksText: String?): String? {
        if (hooksText.isNullOrBlank()) return null
        val root = parseRoot(hooksText) ?: return null
        @Suppress("UNCHECKED_CAST")
        val hooks = root["hooks"] as? MutableMap<String, Any?> ?: return null
        var changed = false
        for (event in hooks.keys.toList()) {
            @Suppress("UNCHECKED_CAST")
            val groups = hooks[event] as? MutableList<Any?> ?: continue
            val kept = groups.filterNot(::isOurGroup)
            if (kept.size != groups.size) {
                changed = true
                if (kept.isEmpty()) hooks.remove(event) else hooks[event] = kept.toMutableList()
            }
        }
        if (hooks.isEmpty()) root.remove("hooks")
        return if (changed) MiniJson.write(root) + "\n" else null
    }

    private fun parseRoot(text: String?): MutableMap<String, Any?>? = when {
        text.isNullOrBlank() -> linkedMapOf()
        else -> try {
            @Suppress("UNCHECKED_CAST")
            MiniJson.parse(text) as? MutableMap<String, Any?>
        } catch (_: MiniJson.ParseException) {
            null
        }
    }

    private fun containsOurCommand(groups: List<Any?>, event: String): Boolean =
        groups.any { group -> commands(group).any { it.contains("rider-agent-tabs/status-hook") && it.endsWith(event) } }

    private fun isOurGroup(group: Any?): Boolean =
        commands(group).any { it.contains("rider-agent-tabs/status-hook") }

    private fun commands(group: Any?): List<String> {
        val entries = (group as? Map<*, *>)?.get("hooks") as? List<*> ?: return emptyList()
        return entries.flatMap { entry ->
            val map = entry as? Map<*, *> ?: return@flatMap emptyList()
            listOfNotNull(map["command"] as? String, map["commandWindows"] as? String)
        }
    }
}

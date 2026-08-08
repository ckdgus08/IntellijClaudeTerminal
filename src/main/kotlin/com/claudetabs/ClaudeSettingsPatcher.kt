package com.claudetabs

/**
 * Idempotent edits to the user's `~/.claude/settings.json`: register the plugin's hook
 * commands and its Bash permission entries.
 *
 * Operates on the parsed tree ([MiniJson]) rather than on text, so it behaves the same
 * whether the user's file is empty, has no `hooks` key, has `hooks` for unrelated events,
 * or already has some of ours. Everything the plugin didn't touch is preserved verbatim.
 *
 * Pure — the caller does the file IO, which keeps this unit-testable.
 */
internal object ClaudeSettingsPatcher {

    /**
     * Hook events the status indicator subscribes to, and the argument passed to
     * `status-hook.sh`. The event name doubles as the argument so the script needs no
     * mapping table of its own.
     *
     * `SubagentStop` is deliberately absent: a subagent finishing does not mean the turn
     * finished, and treating it as [ClaudeStatus.FINISHED] made tabs flicker to ✓ in the
     * middle of a long agentic run.
     */
    val STATUS_EVENTS = listOf(
        "SessionStart",
        "UserPromptSubmit",
        "Notification",
        "Stop",
        "SessionEnd",
    )

    const val STATUS_HOOK_SCRIPT = "~/.claude/rider-plugin/status-hook.sh"
    const val SESSION_START_SCRIPT = "~/.claude/rider-plugin/session-start-hook.sh"

    fun statusHookCommand(event: String) = "bash $STATUS_HOOK_SCRIPT $event"

    /**
     * Return [settingsText] with every plugin hook and permission present, or null if
     * nothing needed changing (so the caller can skip the write entirely).
     *
     * Throws nothing: on a parse failure the caller gets null and leaves the file alone —
     * a settings.json we can't understand is one we must not rewrite.
     */
    fun patch(settingsText: String?, permissions: List<String>): String? {
        val root: MutableMap<String, Any?> = when {
            settingsText.isNullOrBlank() -> LinkedHashMap()
            else -> try {
                @Suppress("UNCHECKED_CAST")
                MiniJson.parse(settingsText) as? MutableMap<String, Any?> ?: return null
            } catch (_: MiniJson.ParseException) {
                return null
            }
        }

        var changed = false
        if (ensureHooks(root)) changed = true
        if (ensurePermissions(root, permissions)) changed = true
        if (!changed) return null

        return MiniJson.write(root) + "\n"
    }

    /**
     * Ensure `hooks.<Event>` contains our command for each event in [STATUS_EVENTS], plus
     * the legacy session-map hook on `SessionStart`.
     *
     * Claude's shape is `hooks: { <Event>: [ { matcher?, hooks: [ {type, command} ] } ] }`.
     * We append our own group rather than merging into an existing one so the user's
     * matchers and ordering are untouched.
     */
    private fun ensureHooks(root: MutableMap<String, Any?>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val hooks = (root["hooks"] as? MutableMap<String, Any?>)
            ?: LinkedHashMap<String, Any?>().also { root["hooks"] = it }

        var changed = false
        for (event in STATUS_EVENTS) {
            val commands = mutableListOf(statusHookCommand(event))
            // SessionStart also drives the TERM_SESSION_ID → sessionId mapping that the
            // rename path depends on. Keeping both commands under one event keeps the
            // ordering deterministic: the map is written before anything reads it.
            if (event == "SessionStart") commands.add("bash $SESSION_START_SCRIPT")

            for (command in commands) {
                @Suppress("UNCHECKED_CAST")
                val groups = (hooks[event] as? MutableList<Any?>)
                    ?: mutableListOf<Any?>().also { hooks[event] = it }
                if (containsCommand(groups, command)) continue
                groups.add(
                    linkedMapOf<String, Any?>(
                        "hooks" to mutableListOf<Any?>(
                            linkedMapOf<String, Any?>(
                                "type" to "command",
                                "command" to command,
                                "timeout" to MiniJson.Num("5"),
                            )
                        )
                    )
                )
                changed = true
            }
        }
        return changed
    }

    /**
     * True if any hook group under [groups] already runs [command]. Matched on the script
     * path + argument rather than the whole string so a user who edited the `bash` prefix
     * or the timeout doesn't get a duplicate installed on the next IDE start.
     */
    private fun containsCommand(groups: List<Any?>, command: String): Boolean {
        val needle = command.substringAfter("bash ").trim()
        for (group in groups) {
            val entries = (group as? Map<*, *>)?.get("hooks") as? List<*> ?: continue
            for (entry in entries) {
                val existing = (entry as? Map<*, *>)?.get("command") as? String ?: continue
                if (existing.contains(needle)) return true
            }
        }
        return false
    }

    /** Ensure `permissions.allow` contains each entry in [permissions]. */
    private fun ensurePermissions(root: MutableMap<String, Any?>, permissions: List<String>): Boolean {
        if (permissions.isEmpty()) return false

        @Suppress("UNCHECKED_CAST")
        val perms = (root["permissions"] as? MutableMap<String, Any?>)
            ?: LinkedHashMap<String, Any?>().also { root["permissions"] = it }

        @Suppress("UNCHECKED_CAST")
        val allow = (perms["allow"] as? MutableList<Any?>)
            ?: mutableListOf<Any?>().also { perms["allow"] = it }

        var changed = false
        for (entry in permissions) {
            if (allow.any { it == entry }) continue
            allow.add(entry)
            changed = true
        }
        return changed
    }

    /**
     * Remove every plugin-installed hook and permission from [settingsText]. Returns null
     * if nothing changed or the file couldn't be parsed. Used by the uninstall path.
     */
    fun unpatch(settingsText: String?, permissions: List<String>): String? {
        if (settingsText.isNullOrBlank()) return null
        val root: MutableMap<String, Any?> = try {
            @Suppress("UNCHECKED_CAST")
            MiniJson.parse(settingsText) as? MutableMap<String, Any?> ?: return null
        } catch (_: MiniJson.ParseException) {
            return null
        }

        var changed = false

        @Suppress("UNCHECKED_CAST")
        val hooks = root["hooks"] as? MutableMap<String, Any?>
        if (hooks != null) {
            for (event in hooks.keys.toList()) {
                @Suppress("UNCHECKED_CAST")
                val groups = hooks[event] as? MutableList<Any?> ?: continue
                val kept = groups.filterNot { group ->
                    val entries = (group as? Map<*, *>)?.get("hooks") as? List<*> ?: return@filterNot false
                    entries.any { entry ->
                        val cmd = (entry as? Map<*, *>)?.get("command") as? String ?: return@any false
                        cmd.contains("rider-plugin/status-hook.sh") || cmd.contains("rider-plugin/session-start-hook.sh")
                    }
                }
                if (kept.size != groups.size) {
                    changed = true
                    if (kept.isEmpty()) hooks.remove(event) else hooks[event] = kept.toMutableList()
                }
            }
            if (hooks.isEmpty()) root.remove("hooks")
        }

        @Suppress("UNCHECKED_CAST")
        val allow = (root["permissions"] as? MutableMap<String, Any?>)?.get("allow") as? MutableList<Any?>
        if (allow != null && allow.removeAll { it is String && it in permissions }) changed = true

        return if (changed) MiniJson.write(root) + "\n" else null
    }
}

package com.claudetabs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Detects whether a JetBrains-shipped or third-party AI agent host is active in this project,
 * so the plugin can stay out of its way (informational only as of 1.0.4 — no behavior change).
 *
 * The motivation is Rider 2026.1's expanded AI Assistant chat tool window with the agent-selector
 * dropdown labeled "AI Agents" (Junie / Claude Agent / Codex / ACP). That tool window hosts CHAT
 * tabs (not terminal tabs), so it doesn't actually compete with this plugin's surface — but if a
 * future release ever spawns terminal tabs from an AI agent, the detector is here as a forward-
 * compatibility hook. The Registry key [REGISTRY_KEY] lets users disable this deference.
 *
 * Detection is best-effort and silent on failure: if the IntelliJ APIs throw, we treat the host
 * as not present rather than nagging.
 */
internal object AiAgentsDetector {

    private val LOG = Logger.getInstance(AiAgentsDetector::class.java)

    /** Registry key — registered in plugin.xml — flipping to `false` reverts to legacy 1.0.3 behavior. */
    const val REGISTRY_KEY = "rider.claude.tabs.respectAiAgents"

    /**
     * Tool window IDs that signal an AI-agent host is registered in this project. Match is
     * case-insensitive (see [matches]). The set is intentionally generous to cover JetBrains'
     * various AI tool windows across IDE versions and ml-llm patch releases.
     */
    val TOOL_WINDOW_CANDIDATES: Set<String> = setOf(
        "AI Agents",
        "AIAssistant",
        "AI Assistant",
        "JetBrainsAi",
        "JetBrains AI",
        "Junie",
        "ClaudeAgent",
        "Claude Agent",
        "aiAgent",
        "ElectroJunToolWindow",
        "PR AI Assistant",
        "Light Agent",
    )

    @Volatile private var cached: Boolean? = null

    /**
     * Pure-function detection — separated from IntelliJ APIs so it can be unit-tested.
     *
     * The plugin-ID enumeration API (`PluginManagerCore`) varies across IntelliJ build numbers
     * and isn't reliably reachable from this plugin's gradle-intellij-plugin 1.x setup, so we
     * detect AI-host presence through registered tool windows only. This is sufficient in
     * practice: JetBrains AI Assistant (`com.intellij.ml.llm`) registers tool windows like
     * "PR AI Assistant", "Light Agent", "ACP Sandbox", and the main AI Chat window, all of
     * which are caught by [TOOL_WINDOW_CANDIDATES].
     *
     * @param toolWindowIds  the set of tool window IDs registered in the project right now
     * @param registryDefer  whether the Registry override is set to defer (`true` = default = defer)
     * @return `true` if an AI-agent host tool window is registered AND we should defer to it
     */
    fun matches(
        toolWindowIds: Set<String>,
        registryDefer: Boolean,
    ): Boolean {
        if (!registryDefer) return false
        val twNorm = toolWindowIds.mapTo(HashSet()) { it.lowercase() }
        val candNorm = TOOL_WINDOW_CANDIDATES.mapTo(HashSet()) { it.lowercase() }
        return twNorm.any { it in candNorm }
    }

    /**
     * Live detection against this [project]. Memoized — call [invalidate] when a tool window
     * registration may have changed (e.g. on `ToolWindowManagerListener#toolWindowRegistered`).
     */
    fun isActive(project: Project): Boolean {
        cached?.let { return it }
        val result = try {
            val registryDefer = try {
                Registry.`is`(REGISTRY_KEY, true)
            } catch (_: Throwable) {
                true
            }
            if (!registryDefer) {
                false
            } else {
                val twIds = ToolWindowManager.getInstance(project).toolWindowIds.toSet()
                matches(twIds, true)
            }
        } catch (e: Throwable) {
            LOG.debug("[ClaudeTabs] AiAgentsDetector probe failed: ${e.message}")
            false
        }
        cached = result
        return result
    }

    /** Forget the cached detection result — call when tool-window registry may have changed. */
    fun invalidate() {
        cached = null
    }
}

package com.claudetabs

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Reads the small records written by the Codex hook scripts. */
internal class CodexStatusStore(private val codexHome: File) {
    val integrationDir = File(codexHome, "rider-agent-tabs")
    val statusDir = File(integrationDir, "status")
    private val promptCache = ConcurrentHashMap<String, String>()

    data class SessionMetadata(
        val sessionId: String,
        val cwd: String?,
        val prompt: String?,
        val permissionMode: String?,
        val event: String,
        val updatedAt: Long,
        val pid: Long?,
        val termSessionId: String?,
    )

    fun snapshot(
        isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    ): Map<String, ClaudeStatusStore.Reading> = sessions().mapNotNull { (sid, record) ->
        val status = if (record.pid != null && record.pid > 0 && !isAlive(record.pid)) {
            ClaudeStatus.EXITED
        } else {
            StatusResolver.fromHookEvent(record.event) ?: return@mapNotNull null
        }
        sid to ClaudeStatusStore.Reading(status, record.event, null)
    }.toMap()

    /** Latest hook metadata per Codex session, keyed with the collision-safe internal id. */
    fun sessions(): Map<String, SessionMetadata> {
        val files = statusDir.listFiles { f ->
            f.name.endsWith(".json") && !f.name.startsWith("termsess-") && !f.name.startsWith("prompt-")
        } ?: return emptyMap()
        statusDir.listFiles { f -> f.name.startsWith("prompt-") && f.name.endsWith(".json") }
            ?.forEach { file ->
                val record = parseRecord(file)
                val prompt = record?.prompt
                if (record != null && prompt != null) promptCache.putIfAbsent(record.sessionId, prompt)
                // Once read, the raw first-prompt buffer is kept in memory only until the
                // 5s save poll derives and persists the safe compact label.
                runCatching { file.delete() }
            }
        val out = linkedMapOf<String, SessionMetadata>()
        for (file in files) {
            val current = parseRecord(file) ?: continue
            val parsed = if (current.prompt == null) current.copy(prompt = promptCache[current.sessionId]) else current
            val previous = out[parsed.sessionId]
            if (previous == null || parsed.updatedAt >= previous.updatedAt) out[parsed.sessionId] = parsed
        }
        return out
    }

    /** TERM_SESSION_ID to collision-safe Codex session id. */
    fun termSessionMap(
        isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    ): Map<String, String> {
        val files = statusDir.listFiles { f -> f.name.startsWith("termsess-") && f.name.endsWith(".json") }
            ?: return emptyMap()
        val out = linkedMapOf<String, Pair<String, Long>>()
        for (file in files) {
            val record = parseRecord(file) ?: continue
            if (record.event == "SessionEnd") continue
            if (record.pid != null && record.pid > 0 && !isAlive(record.pid)) continue
            val term = record.termSessionId ?: file.nameWithoutExtension.removePrefix("termsess-")
            val previous = out[term]
            if (previous == null || record.updatedAt >= previous.second) {
                out[term] = record.sessionId to record.updatedAt
            }
        }
        return out.mapValues { it.value.first }
    }

    fun liveSessions(
        isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    ): Map<String, SessionMetadata> = sessions().filterValues { record ->
        record.event != "SessionEnd" && record.pid?.let(isAlive) == true
    }

    /** Remove the transient raw first-prompt buffer after Rider has stored a safe tab name. */
    fun discardPrompt(sessionId: String) {
        val external = AgentKind.CODEX.toExternalSessionId(sessionId)
        if (!ClaudeTabsHelpers.isSafeSessionId(external)) return
        promptCache.remove(sessionId)
        runCatching { File(statusDir, "prompt-$external.json").delete() }
    }

    fun prune(liveSessionIds: Set<String>, now: Long = System.currentTimeMillis(), maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000) {
        val files = statusDir.listFiles { f -> f.name.endsWith(".json") } ?: return
        for (file in files) {
            val record = parseRecord(file)
            if (record != null && record.sessionId in liveSessionIds) continue
            if (now - file.lastModified() > maxAgeMs) runCatching { file.delete() }
        }
    }

    private fun parseRecord(file: File): SessionMetadata? {
        val root = try {
            @Suppress("UNCHECKED_CAST")
            MiniJson.parse(file.readText()) as? Map<String, Any?> ?: return null
        } catch (_: Exception) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val payload = root["payload"] as? Map<String, Any?> ?: emptyMap()
        val rawSid = (payload["session_id"] as? String)
            ?: (payload["sessionId"] as? String)
            ?: return null
        if (!ClaudeTabsHelpers.isSafeSessionId(rawSid)) return null
        val event = (root["event"] as? String)
            ?: (payload["hook_event_name"] as? String)
            ?: return null
        val ts = number(root["ts"]) ?: file.lastModified()
        return SessionMetadata(
            sessionId = AgentKind.CODEX.toInternalSessionId(rawSid),
            cwd = (payload["cwd"] as? String)?.takeIf { it.isNotBlank() },
            prompt = (payload["prompt"] as? String)?.takeIf { it.isNotBlank() },
            permissionMode = ((payload["permission_mode"] ?: payload["permissionMode"]) as? String)
                ?.takeIf { it.isNotBlank() },
            event = event,
            updatedAt = ts,
            pid = number(root["pid"]),
            termSessionId = (root["termSessionId"] as? String)?.takeIf { it.isNotBlank() },
        )
    }

    private fun number(value: Any?): Long? = when (value) {
        is MiniJson.Num -> value.raw.toLongOrNull()
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

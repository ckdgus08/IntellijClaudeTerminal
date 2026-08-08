package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Method

/**
 * The value-based identity search the ContentManager attach path uses.
 *
 * It searches by *value* rather than by accessor name on purpose. The identity probe on
 * IntelliJ 2026.1 found `TerminalWidgetBridge.getSession()`, but the shape inside those
 * objects has already moved twice across releases, and hard-coding the next accessor name
 * buys exactly one version. The terminal's id is a UUID and we already hold every UUID the
 * hook has seen, so matching on content survives the next reshuffle.
 *
 * The production method is private and reflective; this exercises the same traversal rules
 * against stand-ins shaped like the real objects.
 */
class IdentifierSearchTest {

    // Stand-ins mirroring TerminalWidgetBridge -> getSession() -> id.
    class SessionId(private val uuid: String) {
        override fun toString() = "TerminalSessionId($uuid)"
    }

    class Session(val id: SessionId) {
        fun getSessionId(): SessionId = id
        override fun toString() = "TerminalSessionImpl@1a2b3c"
    }

    class WidgetBridge(private val session: Session?) {
        fun getSession(): Session? = session
        fun getTtyConnector(): Any? = null
        override fun toString() = "TerminalWidgetBridge@deadbeef"
    }

    /** Mirrors the traversal in ClaudeTabWatcherStartup.findKnownIdentifier. */
    private fun search(root: Any, known: Set<String>, maxNodes: Int = 40): String? {
        if (known.isEmpty()) return null
        val queue = ArrayDeque<Pair<Any, Int>>().apply { add(root to 0) }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            if (!seen.add(node)) continue
            visited++
            val asText = try { node.toString() } catch (_: Exception) { "" }
            if (asText.length <= 512) known.firstOrNull { asText.contains(it) }?.let { return it }
            if (depth >= 2) continue
            for (m: Method in node.javaClass.methods) {
                if (m.parameterCount != 0) continue
                val n = m.name.lowercase()
                if (!(n.startsWith("getsession") || n == "getid" || n == "getsessionid" ||
                        n == "getttyconnector" || n == "getttyconnectoraccessor" || n == "get")
                ) continue
                val child = try { m.isAccessible = true; m.invoke(node) } catch (_: Throwable) { null } ?: continue
                queue.add(child to depth + 1)
            }
        }
        return null
    }

    private val uuid = "70000001-0000-4000-8000-000000000001"

    @Test fun findsTheIdNestedUnderGetSession() {
        val found = search(WidgetBridge(Session(SessionId(uuid))), setOf(uuid))
        assertEquals(uuid, found)
    }

    @Test fun findsAnIdOnTheRootItself() {
        val root = object {
            override fun toString() = "Tab(sessionId=$uuid)"
        }
        assertEquals(uuid, search(root, setOf(uuid)))
    }

    @Test fun returnsNullWhenTheIdIsNotReachable() {
        assertNull(search(WidgetBridge(Session(SessionId("some-other-uuid"))), setOf(uuid)))
        assertNull(search(WidgetBridge(null), setOf(uuid)))
    }

    @Test fun picksTheCandidateThatIsActuallyPresent() {
        val other = "70000003-0000-4000-8000-000000000003"
        assertEquals(uuid, search(WidgetBridge(Session(SessionId(uuid))), setOf(other, uuid)))
    }

    @Test fun noCandidatesMeansNoWork() {
        assertNull(search(WidgetBridge(Session(SessionId(uuid))), emptySet()))
    }

    @Test fun survivesAccessorsThatThrow() {
        val root = object {
            fun getSession(): Any = throw IllegalStateException("disposed")
            override fun toString() = "Widget"
        }
        assertNull(search(root, setOf(uuid)))
    }

    @Test fun survivesAToStringThatThrows() {
        val bad = object {
            override fun toString(): String = throw IllegalStateException("nope")
        }
        assertNull(search(bad, setOf(uuid)))
    }

    @Test fun terminatesOnACyclicGraph() {
        // A widget transitively reaches the whole editor; identity tracking plus the node
        // budget is what stops this becoming an every-poll performance problem.
        class Node {
            var next: Node? = null
            fun getSession(): Node? = next
            override fun toString() = "Node"
        }
        val a = Node(); val b = Node()
        a.next = b; b.next = a
        assertNull(search(a, setOf(uuid)))
    }
}

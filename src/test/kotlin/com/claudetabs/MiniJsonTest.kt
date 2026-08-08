package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [MiniJson] only has to handle what `JSON.stringify` emits — but it has to handle it exactly. */
class MiniJsonTest {

    @Suppress("UNCHECKED_CAST")
    private fun obj(text: String) = MiniJson.parse(text) as Map<String, Any?>

    @Test fun parsesScalars() {
        assertEquals("hi", obj("""{"a":"hi"}""")["a"])
        assertEquals(true, obj("""{"a":true}""")["a"])
        assertEquals(false, obj("""{"a":false}""")["a"])
        assertNull(obj("""{"a":null}""")["a"])
        assertEquals(MiniJson.Num("5"), obj("""{"a":5}""")["a"])
        assertEquals(MiniJson.Num("-1.5e3"), obj("""{"a":-1.5e3}""")["a"])
    }

    @Test fun parsesNesting() {
        val root = obj("""{"a":{"b":[1,{"c":"d"}]}}""")
        @Suppress("UNCHECKED_CAST")
        val a = root["a"] as Map<String, Any?>
        val b = a["b"] as List<Any?>
        assertEquals(2, b.size)
        @Suppress("UNCHECKED_CAST")
        assertEquals("d", (b[1] as Map<String, Any?>)["c"])
    }

    @Test fun parsesEmptyContainersAndWhitespace() {
        assertTrue((obj("""{ "a" : { } , "b" : [ ] }""")["a"] as Map<*, *>).isEmpty())
        assertTrue((obj("""{ "a" : { } , "b" : [ ] }""")["b"] as List<*>).isEmpty())
        assertTrue((MiniJson.parse("  {}  ") as Map<*, *>).isEmpty())
    }

    @Test fun handlesStringEscapes() {
        val v = obj("""{"a":"line\nbreak \"quoted\" back\\slash tab\there é"}""")["a"] as String
        assertEquals("line\nbreak \"quoted\" back\\slash tab\there é", v)
    }

    @Test fun roundTripsNumbersWithoutReformatting() {
        // The whole reason numbers are kept as raw text: turning the user's `"timeout": 5`
        // into `5.0` on every IDE start would be an unwanted diff in their settings file.
        val text = """{"timeout":5,"ratio":0.25,"big":1e10}"""
        val out = MiniJson.write(MiniJson.parse(text))
        assertTrue(out, out.contains(": 5"))
        assertTrue(out, out.contains("0.25"))
        assertTrue(out, out.contains("1e10"))
    }

    @Test fun roundTripsAnActualClaudeSettingsFile() {
        val text = """
            {
              "statusLine": { "type": "command", "command": "bash /Users/x/.claude/statusline-command.sh" },
              "enabledPlugins": { "rust-analyzer-lsp@claude-plugins-official": true },
              "tui": "fullscreen",
              "skipDangerousModePermissionPrompt": true,
              "theme": "dark",
              "preferredNotifChannel": "iterm2_with_bell"
            }
        """.trimIndent()
        val once = MiniJson.write(MiniJson.parse(text))
        val twice = MiniJson.write(MiniJson.parse(once))
        assertEquals("serialisation must be stable", once, twice)
        assertEquals("fullscreen", obj(once)["tui"])
    }

    @Test fun preservesKeyOrder() {
        val text = """{"z":1,"a":2,"m":3}"""
        val keys = obj(MiniJson.write(MiniJson.parse(text))).keys.toList()
        assertEquals(listOf("z", "a", "m"), keys)
    }

    @Test fun writesEscapesBack() {
        val out = MiniJson.write(mapOf("a" to "tab\there\nnew \"q\" \\b"))
        assertEquals("""{
  "a": "tab\there\nnew \"q\" \\b"
}""", out)
        assertEquals("tab\there\nnew \"q\" \\b", obj(out)["a"])
    }

    @Test fun rejectsMalformedInput() {
        for (bad in listOf("{", "{\"a\"}", "{\"a\":}", "[1,]", "{\"a\":1} trailing", "", "{\"a\":\"unterminated")) {
            try {
                MiniJson.parse(bad)
                fail("expected a parse failure for: $bad")
            } catch (_: MiniJson.ParseException) {
                // expected
            } catch (_: IndexOutOfBoundsException) {
                fail("parser must fail with ParseException, not IndexOutOfBounds, for: $bad")
            }
        }
    }
}

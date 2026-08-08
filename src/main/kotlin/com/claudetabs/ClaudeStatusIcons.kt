package com.claudetabs

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Coloured tab icons for [ClaudeStatus].
 *
 * The text glyph carries the state at a glance but everything on the tab strip is the same
 * colour, so telling `⚠` from `✓` still means reading. Colour is the thing the eye picks up
 * without looking, which is the whole point of an at-a-glance indicator.
 *
 * `Content.setIcon` is a plain, stable API — unlike the tab *label* colour, which lives in
 * the tool window's internal `JBTabs` and would mean reflecting into the exact layer that
 * has broken repeatedly on this platform.
 *
 * The glyph stays regardless. Not every tab can be resolved to a `Content` (the ones this
 * plugin spawns are reached through the widget), and a tab that gets no icon still needs to
 * show its state.
 */
internal object ClaudeStatusIcons {

    // Colours are picked for meaning, not decoration, and each is given an explicit dark
    // variant — JBColor's automatic darkening washes out at this size.
    private val WORKING = JBColor(0x3574F0, 0x548AF7)   // blue   — running
    private val WAITING = JBColor(0xE8A33D, 0xF2C55C)   // amber  — needs you
    private val FINISHED = JBColor(0x369650, 0x57965C)  // green  — done
    private val IDLE = JBColor(0x9AA7B0, 0x6F737A)      // grey   — nothing yet
    private val EXITED = JBColor(0xC94F4F, 0xD65C5C)    // red    — gone

    private val cache = java.util.EnumMap<ClaudeStatus, Icon>(ClaudeStatus::class.java)

    /** The icon for [status]. Cached — the status loop asks for these repeatedly. */
    @Synchronized
    fun forStatus(status: ClaudeStatus): Icon = cache.getOrPut(status) {
        when (status) {
            ClaudeStatus.WORKING -> DotIcon(WORKING, filled = true)
            ClaudeStatus.WAITING -> DotIcon(WAITING, filled = true)
            ClaudeStatus.FINISHED -> DotIcon(FINISHED, filled = true)
            // Hollow, so "nothing has happened here" reads as absence rather than as another
            // coloured state competing for attention.
            ClaudeStatus.IDLE -> DotIcon(IDLE, filled = false)
            ClaudeStatus.EXITED -> DotIcon(EXITED, filled = false)
        }
    }

    /**
     * A small dot. Deliberately not an `AllIcons` lookup: those are shaped for actions and
     * change between releases, and this needs one consistent silhouette across five states
     * where only the colour differs.
     */
    private class DotIcon(private val color: JBColor, private val filled: Boolean) : Icon {

        override fun getIconWidth() = JBUI.scale(SIZE)
        override fun getIconHeight() = JBUI.scale(SIZE)

        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            val g2 = (g as? Graphics2D)?.create() as? Graphics2D ?: return
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                val d = JBUI.scale(DOT)
                val off = (JBUI.scale(SIZE) - d) / 2
                if (filled) {
                    g2.fillOval(x + off, y + off, d, d)
                } else {
                    val stroke = JBUI.scale(1).coerceAtLeast(1)
                    g2.stroke = java.awt.BasicStroke(stroke.toFloat())
                    g2.drawOval(x + off, y + off, d - stroke, d - stroke)
                }
            } finally {
                g2.dispose()
            }
        }

        companion object {
            private const val SIZE = 12
            private const val DOT = 8
        }
    }
}

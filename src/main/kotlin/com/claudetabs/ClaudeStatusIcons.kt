package com.claudetabs

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import javax.swing.Icon

/**
 * Tab icons for [ClaudeStatus] — one shape and one colour per state.
 *
 * Colour is what the eye picks up without looking, so it does the fast work. Shape is what
 * makes the state *legible*: five hues of the same dot still have to be learned and compared,
 * and to a red-green colour-blind viewer `✓` and `✕` are the same picture. Encoding the state
 * twice means neither channel has to carry it alone.
 *
 * The silhouettes are chosen to be distinguishable at 12px from their outline alone — solid,
 * hollow, triangle, tick, cross — rather than as five variations on a circle.
 *
 * `Content.setIcon` is a plain, stable API — unlike the tab *label* colour, which lives in the
 * tool window's internal `JBTabs` and would mean reflecting into the exact layer that has
 * broken repeatedly on this platform. It does need [com.intellij.openapi.wm.ToolWindow]'s
 * `SHOW_CONTENT_ICON` on the content, or the icon is stored and never drawn.
 */
internal object ClaudeStatusIcons {

    // Colours are picked for meaning, not decoration, and each is given an explicit dark
    // variant — JBColor's automatic darkening washes out at this size.
    private val WORKING = JBColor(0x3574F0, 0x548AF7)   // blue   — running
    private val WAITING = JBColor(0xE8A33D, 0xF2C55C)   // amber  — needs you
    private val FINISHED = JBColor(0x369650, 0x57965C)  // green  — done
    private val IDLE = JBColor(0x9AA7B0, 0x6F737A)      // grey   — nothing yet
    private val EXITED = JBColor(0xC94F4F, 0xD65C5C)    // red    — gone

    /** What to draw. One per state, and no two share a silhouette. */
    private enum class Shape { DISC, RING, TRIANGLE, TICK, CROSS }

    private val cache = java.util.EnumMap<ClaudeStatus, Icon>(ClaudeStatus::class.java)

    /** The icon for [status]. Cached — the status loop asks for these repeatedly. */
    @Synchronized
    fun forStatus(status: ClaudeStatus): Icon = cache.getOrPut(status) {
        when (status) {
            // Solid: something is happening right now.
            ClaudeStatus.WORKING -> StatusIcon(WORKING, Shape.DISC)
            // The universal "look at me" outline, and the only shape here with a flat base.
            ClaudeStatus.WAITING -> StatusIcon(WAITING, Shape.TRIANGLE)
            ClaudeStatus.FINISHED -> StatusIcon(FINISHED, Shape.TICK)
            // Hollow, so "nothing has happened here" reads as absence rather than as another
            // state competing for attention.
            ClaudeStatus.IDLE -> StatusIcon(IDLE, Shape.RING)
            ClaudeStatus.EXITED -> StatusIcon(EXITED, Shape.CROSS)
        }
    }

    /**
     * A small status mark, drawn rather than looked up in `AllIcons`: those are shaped for
     * actions, carry their own padding, and change between releases — none of which suits a
     * set that has to stay mutually distinguishable at one size.
     */
    private class StatusIcon(private val color: JBColor, private val shape: Shape) : Icon {

        override fun getIconWidth() = JBUI.scale(SIZE)
        override fun getIconHeight() = JBUI.scale(SIZE)

        override fun paintIcon(c: Component?, g: Graphics?, x: Int, y: Int) {
            val g2 = (g as? Graphics2D)?.create() as? Graphics2D ?: return
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = color

                val size = JBUI.scale(SIZE).toFloat()
                val pad = JBUI.scale(PAD).toFloat()
                val left = x + pad
                val top = y + pad
                val span = size - pad * 2
                // Round caps and joins: at this size a mitred tick reads as a smudge.
                g2.stroke = BasicStroke(
                    JBUI.scale(STROKE).toFloat().coerceAtLeast(1.4f),
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                )

                when (shape) {
                    Shape.DISC -> g2.fill(Ellipse2D.Float(left, top, span, span))

                    // Thinner than the other marks on purpose. The hole is the entire
                    // difference between "idle" and "working", and at the shared stroke
                    // width it closed up until the two were nearly the same silhouette —
                    // caught by StatusIconDistinctnessTest at 812 differing pixels.
                    Shape.RING -> {
                        val w = (span * RING_STROKE_RATIO).coerceAtLeast(1.2f)
                        g2.stroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        g2.draw(Ellipse2D.Float(left + w / 2, top + w / 2, span - w, span - w))
                    }

                    // Apex centred, flat base — recognisable as a warning sign even when the
                    // colour is lost. Inset slightly at the top so it optically matches the
                    // height of the round shapes rather than measuring taller.
                    Shape.TRIANGLE -> g2.fill(
                        Path2D.Float().apply {
                            moveTo(left + span / 2, top + span * 0.06f)
                            lineTo(left + span, top + span * 0.94f)
                            lineTo(left, top + span * 0.94f)
                            closePath()
                        }
                    )

                    Shape.TICK -> g2.draw(
                        Path2D.Float().apply {
                            moveTo(left + span * 0.08f, top + span * 0.55f)
                            lineTo(left + span * 0.40f, top + span * 0.86f)
                            lineTo(left + span * 0.94f, top + span * 0.16f)
                        }
                    )

                    Shape.CROSS -> {
                        val i = span * 0.14f
                        g2.draw(
                            Path2D.Float().apply {
                                moveTo(left + i, top + i)
                                lineTo(left + span - i, top + span - i)
                                moveTo(left + span - i, top + i)
                                lineTo(left + i, top + span - i)
                            }
                        )
                    }
                }
            } finally {
                g2.dispose()
            }
        }

        companion object {
            private const val SIZE = 12
            /** Keeps the mark off the tab label's baseline crowding. */
            private const val PAD = 2
            private const val STROKE = 2
            /** Ring stroke as a fraction of its diameter — see [Shape.RING]. */
            private const val RING_STROKE_RATIO = 0.20f
        }
    }
}

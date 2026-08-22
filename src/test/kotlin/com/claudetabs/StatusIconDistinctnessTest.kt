package com.claudetabs

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every state must be tellable apart from its shape alone.
 *
 * Colour does the fast work, but it can't be the only channel: five hues of one dot have to
 * be learned and compared, and to a red-green colour-blind viewer the "done" and "gone"
 * states would be the same picture. So each state gets its own silhouette — disc, triangle,
 * tick, ring, cross — and this pins that, because the easy way to break it is to give two
 * states the same shape and only notice on a tab strip.
 *
 * Rendered in greyscale on purpose: comparing the colours would pass even if two icons were
 * geometrically identical, which is the failure this exists to catch.
 */
class StatusIconDistinctnessTest {

    private val size = 96

    /** The icon's coverage mask, colour discarded. */
    private fun silhouette(status: ClaudeStatus): BooleanArray {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // Scale the 12px icon up so anti-aliasing can't dominate the comparison.
        g.scale(size / 12.0, size / 12.0)
        ClaudeStatusIcons.forStatus(status).paintIcon(null, g, 0, 0)
        g.dispose()
        return BooleanArray(size * size) { i ->
            Color(img.getRGB(i % size, i / size), true).alpha > 128
        }
    }

    private fun differingPixels(a: BooleanArray, b: BooleanArray) = a.indices.count { a[it] != b[it] }

    @Test fun everyStateHasItsOwnShape() {
        val masks = ClaudeStatus.values().associateWith { silhouette(it) }
        val states = ClaudeStatus.values()
        for (i in states.indices) {
            for (j in i + 1 until states.size) {
                val diff = differingPixels(masks[states[i]]!!, masks[states[j]]!!)
                // A tenth of the canvas is a wide margin: the closest pair here (disc vs
                // ring) differs by far more, and anything below it would not read as a
                // different mark at 12px.
                assertTrue(
                    "${states[i]} and ${states[j]} look too alike ($diff pixels differ)",
                    diff > size * size / 10,
                )
            }
        }
    }

    @Test fun everyStateActuallyDrawsSomething() {
        for (status in ClaudeStatus.values()) {
            val painted = silhouette(status).count { it }
            assertTrue("$status drew nothing", painted > 0)
            assertTrue("$status filled the whole canvas", painted < size * size)
        }
    }

    @Test fun backgroundCountAddsAVisibleBadgeWithoutReplacingTheMainIcon() {
        val plain = ClaudeStatusIcons.forStatus(ClaudeStatus.FINISHED)
        val withBackground = ClaudeStatusIcons.forStatus(ClaudeStatus.FINISHED, 3)
        assertTrue(withBackground.iconWidth > plain.iconWidth)
        assertTrue(withBackground.iconHeight >= plain.iconHeight)
    }

    /** Hollow really is hollow — the centre is what separates it from the solid disc. */
    @Test fun idleIsHollowAndWorkingIsSolid() {
        val centre = (size / 2) * size + (size / 2)
        assertTrue("WORKING should be solid at the centre", silhouette(ClaudeStatus.WORKING)[centre])
        assertTrue("IDLE should be hollow at the centre", !silhouette(ClaudeStatus.IDLE)[centre])
    }
}

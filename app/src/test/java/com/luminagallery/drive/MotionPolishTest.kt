package com.luminagallery.drive

import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.PredictiveSeekHandoff
import com.luminagallery.drive.ui.components.returnMorphScrollDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPolishTest {

    @Test
    fun returnMorph_fullyVisibleTile_keepsScrollPosition() {
        val delta = returnMorphScrollDelta(
            itemStart = 300, itemSize = 200, visibleStart = 0, visibleEnd = 1600,
            edgeMarginPx = 24, centre = false
        )
        assertEquals(0f, delta, 0f)
    }

    @Test
    fun returnMorph_tileClippedAtBottom_nudgesJustEnough() {
        val delta = returnMorphScrollDelta(
            itemStart = 1500, itemSize = 200, visibleStart = 0, visibleEnd = 1600,
            edgeMarginPx = 24, centre = false
        )
        assertEquals(124f, delta, 0f)
    }

    @Test
    fun returnMorph_tileClippedAtTop_nudgesUp() {
        val delta = returnMorphScrollDelta(
            itemStart = -80, itemSize = 200, visibleStart = 0, visibleEnd = 1600,
            edgeMarginPx = 24, centre = false
        )
        assertEquals(-104f, delta, 0f)
    }

    @Test
    fun returnMorph_offscreenTile_isCentred() {
        val delta = returnMorphScrollDelta(
            itemStart = 0, itemSize = 200, visibleStart = 0, visibleEnd = 1600,
            edgeMarginPx = 24, centre = true
        )
        assertEquals(-700f, delta, 0f)
    }

    @Test
    fun predictiveHandoff_capturesReleasedGestureProgress() {
        val handoff = PredictiveSeekHandoff()
        handoff.update(0.10f, seeking = true, settled = false)
        handoff.update(0.32f, seeking = true, settled = false)
        assertEquals(-1f, handoff.releaseProgress, 0f)
        handoff.update(0.34f, seeking = false, settled = false)
        assertEquals(0.32f, handoff.releaseProgress, 0.0001f)
        // Stays latched for the rest of the commit animation.
        handoff.update(0.80f, seeking = false, settled = false)
        assertEquals(0.32f, handoff.releaseProgress, 0.0001f)
        handoff.update(0f, seeking = false, settled = true)
        assertEquals(-1f, handoff.releaseProgress, 0f)
    }

    @Test
    fun predictiveHandoff_regularNavigationNeverLatches() {
        val handoff = PredictiveSeekHandoff()
        handoff.update(0.5f, seeking = false, settled = false)
        assertEquals(-1f, handoff.releaseProgress, 0f)
    }

    @Test
    fun gestureResistance_isMonotonicAndBounded() {
        assertEquals(0f, OneUiMotion.gestureResistance(0f), 0f)
        assertEquals(1f, OneUiMotion.gestureResistance(1f), 0.0001f)
        var previous = 0f
        for (i in 1..20) {
            val v = OneUiMotion.gestureResistance(i / 20f)
            assertTrue(v >= previous)
            previous = v
        }
    }
}

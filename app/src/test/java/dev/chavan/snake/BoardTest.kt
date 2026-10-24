package dev.chavan.snake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {
    @Test fun boardFitsWidthAndCentres() {
        // 1000 px wide, 16 columns -> 62 px cells, 992 px board, 4 px either side.
        val b = layoutBoard(0, 100, 1000, 1500, 16)
        assertEquals(62, b.cell)
        assertEquals(4, b.left)
        assertEquals(1500 / 62, b.rows)
        assertTrue(b.top >= 100 && b.top + b.height <= 1600)
    }

    @Test fun boardNeverSpillsOutOfItsArea() {
        for (w in listOf(320, 411, 720, 1080, 1440)) for (h in listOf(400, 900, 2000)) {
            val b = layoutBoard(10, 20, w, h, 16)
            assertTrue(b.left >= 10 && b.left + b.width <= 10 + w)
            if (h >= 5 * b.cell) assertTrue(b.top >= 20 && b.top + b.height <= 20 + h)
        }
    }

    @Test fun shortAreaStillGivesAPlayableBoard() {
        assertEquals(5, layoutBoard(0, 0, 1000, 10, 16).rows)
    }

    @Test fun smallMovementsAreNotSwipes() {
        assertNull(swipeDir(10f, -12f, 40f))
    }

    @Test fun swipeTakesTheLargerAxis() {
        assertEquals(Dir.RIGHT, swipeDir(60f, 20f, 40f))
        assertEquals(Dir.LEFT, swipeDir(-60f, 59f, 40f))
        assertEquals(Dir.DOWN, swipeDir(5f, 45f, 40f))
        assertEquals(Dir.UP, swipeDir(-30f, -80f, 40f))
    }

    @Test fun smallScrollsAddUpToOneTurn() {
        val r = ScrollReader(threshold = 1f)
        assertNull(r.read(0f, 0.4f, 0))
        assertNull(r.read(0f, 0.4f, 10))
        assertEquals(Dir.DOWN, r.read(0f, 0.4f, 20))
    }

    @Test fun oneLongScrollGestureTurnsOnlyOnce() {
        val r = ScrollReader(threshold = 1f)
        assertEquals(Dir.DOWN, r.read(0f, 2f, 0))
        assertNull(r.read(0f, 2f, 30))
        assertNull(r.read(-5f, 0f, 60)) // still the same gesture
    }

    @Test fun aPauseStartsANewGesture() {
        val r = ScrollReader(threshold = 1f, gapMs = 250)
        assertEquals(Dir.DOWN, r.read(0f, 2f, 0))
        assertEquals(Dir.RIGHT, r.read(-2f, 0f, 400))
    }

    @Test fun horizontalScrollIsFlippedToMatchTheFingers() {
        assertEquals(Dir.LEFT, ScrollReader().read(2f, 0f, 0))
        assertEquals(Dir.RIGHT, ScrollReader().read(-2f, 0f, 0))
    }

    // Moving one way, the snake can only turn the two ways across it, so only that part of a
    // swipe matters. A sloppy diagonal still turns the way it was meant.
    @Test fun whileMovingOnlyTheSidewaysPartOfASwipeCounts() {
        assertEquals(Dir.RIGHT, steerDir(30f, 80f, Dir.UP, 14f)) // mostly "down", but down is not a turn
        assertEquals(Dir.LEFT, steerDir(-20f, -90f, Dir.DOWN, 14f))
        assertEquals(Dir.UP, steerDir(70f, -20f, Dir.RIGHT, 14f))
        assertNull(steerDir(10f, 200f, Dir.UP, 14f)) // nothing sideways: no turn
    }

    @Test fun beforeMovingAnyDirectionCounts() {
        assertEquals(Dir.DOWN, steerDir(30f, 80f, null, 14f))
        assertEquals(Dir.LEFT, steerDir(-80f, 30f, null, 14f))
    }

    @Test fun recordedEmulatorTrackpadSwipesTurnTheWayTheyWereMeant() {
        // Real two-finger swipes captured with getevent on the emulator, converted to pixels on a
        // 1080x2424 screen. The emulator stretches vertical movement, so sideways swipes arrive
        // looking diagonal. Each was meant as a sideways turn while the snake moved vertically.
        val dp = 2.625f
        val sideways = listOf(355f to 648f, 330f to 607f, -784f to 167f, 428f to 61f)
        for ((dx, dy) in sideways) {
            val want = if (dx > 0) Dir.RIGHT else Dir.LEFT
            assertEquals("swipe $dx,$dy moving down", want, steerDir(dx, dy, Dir.DOWN, 14f * dp))
            assertEquals("swipe $dx,$dy moving up", want, steerDir(dx, dy, Dir.UP, 14f * dp))
        }
    }
}

package dev.chavan.snake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardTest {
    @Test fun boardIsAlwaysTheFixedSizeAndFillsTheTighterSide() {
        // 1000 x 2000 px: width allows 62 px squares, height allows 83, so width decides.
        val b = fitBoard(0, 100, 1000, 2000, 16, 24, minCell = 20)
        assertEquals(16, b.cols)
        assertEquals(24, b.rows)
        assertEquals(62, b.cell)
        assertEquals(4, b.left) // centred: 1000 - 16 * 62 = 8 px spare
        assertEquals(100 + (2000 - 24 * 62) / 2, b.top)
    }

    @Test fun aWideShortAreaIsDecidedByHeight() {
        val b = fitBoard(0, 0, 2000, 480, 16, 24, minCell = 10)
        assertEquals(20, b.cell)
        assertEquals((2000 - 16 * 20) / 2, b.left)
    }

    @Test fun boardStaysInsideItsAreaWheneverItCan() {
        for (w in listOf(320, 411, 720, 1080, 1440)) for (h in listOf(500, 900, 2000)) {
            val b = fitBoard(10, 20, w, h, 16, 24, minCell = 8)
            assertTrue("$w x $h", b.left >= 10 && b.left + b.width <= 10 + w)
            assertTrue("$w x $h", b.top >= 20 && b.top + b.height <= 20 + h)
        }
    }

    @Test fun aTinyAreaKeepsTheMinimumSquareRatherThanShrinkingToNothing() {
        val b = fitBoard(0, 0, 100, 100, 16, 24, minCell = 8)
        assertEquals(8, b.cell)
        assertEquals(24, b.rows) // still the full board, running past the area
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

    @Test fun anOverflowingBoardStartsAtTheAreasCorner() {
        for ((w, h) in listOf(100 to 100, 1000 to 100, 100 to 1000, 1000 to 0, 1000 to -50)) {
            val b = fitBoard(30, 40, w, h, 16, 24, minCell = 8)
            assertEquals("$w x $h", 8, b.cell)
            if (w < 16 * 8) assertEquals("$w x $h", 30, b.left)
            if (h < 24 * 8) assertEquals("$w x $h", 40, b.top)
        }
    }
}

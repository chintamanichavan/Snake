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
}

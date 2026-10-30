package dev.chavan.snake

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact numbers a replay depends on, worked out by an independent Python copy of the rules.
 * Every other test compares the game with itself, so it would still pass after a change to the
 * food draw or the start. These fail instead.
 *
 * If one fails after a deliberate rule change: bump GHOST_VERSION, so old ghosts are dropped
 * with a reason rather than replayed wrongly, and update the numbers here.
 */
class PinnedRulesTest {
    @Test fun nextIntSequence() {
        val a = Rng(0)
        assertEquals(listOf(87, 378, 39, 374, 333), List(5) { a.nextInt(384) })
        val b = Rng(42)
        assertEquals(listOf(2, 2, 0, 1, 3), List(5) { b.nextInt(7) })
    }

    @Test fun startPosition() {
        assertEquals(listOf(Cell(6, 12), Cell(5, 12), Cell(4, 12)), Game(16, 24, 1).snake.toList())
    }

    @Test fun classicFirstApple() {
        assertEquals(Cell(15, 21), Game(16, 24, 42).food)
        assertEquals(Cell(12, 1), Game(16, 24, -1).food)
    }

    @Test fun dailyApplesForOneDay() {
        val seed = dailySeed(LocalDate.of(2026, 10, 29))
        assertEquals(20755L, seed)
        val g = Game(16, 24, seed, daily = true)
        assertEquals(
            listOf(Cell(4, 6), Cell(8, 1), Cell(12, 2), Cell(8, 20), Cell(10, 1)),
            List(5) { g.dailyCell(it) },
        )
    }
}

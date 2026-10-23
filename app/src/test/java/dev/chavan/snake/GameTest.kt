package dev.chavan.snake

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {
    private fun game(cols: Int = 10, rows: Int = 10) = Game(cols, rows, Random(1))

    @Test fun startsWithThreeCellsMovingRight() {
        val g = game()
        assertEquals(listOf(Cell(4, 5), Cell(3, 5), Cell(2, 5)), g.snake.toList())
        assertEquals(Dir.RIGHT, g.dir)
        assertFalse(g.over)
    }

    @Test fun foodNeverStartsOnTheSnake() {
        repeat(200) { seed ->
            val g = Game(6, 6, Random(seed))
            assertFalse(g.food in g.snake)
        }
    }

    @Test fun stepMovesHeadAndKeepsLength() {
        val g = game()
        g.step()
        assertEquals(Cell(5, 5), g.snake.first())
        assertEquals(3, g.snake.size)
    }

    @Test fun reversingIsIgnored() {
        val g = game()
        g.turn(Dir.LEFT)
        g.step()
        assertEquals(Dir.RIGHT, g.dir)
        assertFalse(g.over)
    }

    @Test fun twoQuickTurnsBothApplyInOrder() {
        val g = game()
        g.turn(Dir.UP)
        g.turn(Dir.LEFT)
        g.step()
        assertEquals(Cell(4, 4), g.snake.first())
        g.step()
        assertEquals(Cell(3, 4), g.snake.first())
    }

    @Test fun quickTurnCannotReverseThroughAQueuedTurn() {
        val g = game()
        g.turn(Dir.UP)
        g.turn(Dir.DOWN) // opposite of the queued UP, so ignored
        g.step()
        g.step()
        assertEquals(Cell(4, 3), g.snake.first())
    }

    @Test fun hittingWallEndsGame() {
        val g = game()
        repeat(5) { g.step() }
        assertFalse(g.over)
        g.step()
        assertTrue(g.over)
        assertFalse(g.won)
    }

    @Test fun eatingGrowsAndScores() {
        val g = game()
        g.food = Cell(6, 5) // two cells ahead of the head
        assertFalse(g.step())
        assertTrue(g.step())
        assertEquals(1, g.score)
        assertEquals(4, g.snake.size)
        assertNotNull(g.food)
        assertFalse(g.food in g.snake)
    }

    @Test fun headMayFollowIntoTheCellTheTailLeaves() {
        // A 2x2 loop: the head moves down into the tail's cell as the tail moves on.
        val g = game()
        g.snake.clear()
        g.snake.addAll(listOf(Cell(3, 4), Cell(4, 4), Cell(4, 5), Cell(3, 5)))
        g.food = Cell(0, 0)
        g.turn(Dir.DOWN)
        g.step()
        assertFalse(g.over)
        assertEquals(Cell(3, 5), g.snake.first())
    }

    @Test fun runningIntoOwnBodyEndsGame() {
        val g = game()
        g.snake.clear()
        // Head at (4,4) moving right, with body wrapped so (5,4) is mid-body.
        g.snake.addAll(listOf(Cell(4, 4), Cell(4, 5), Cell(5, 5), Cell(5, 4), Cell(5, 3)))
        g.step()
        assertTrue(g.over)
    }

    @Test fun fillingTheBoardWins() {
        // The snake covers every cell but (4,0); its head at (3,0) moves right onto the last food.
        val g = Game(5, 5, Random(3))
        g.snake.clear()
        g.snake.addFirst(Cell(3, 0))
        for (y in 0 until 5) for (x in 0 until 5) {
            val c = Cell(x, y)
            if (c != Cell(3, 0) && c != Cell(4, 0)) g.snake.addLast(c)
        }
        g.food = Cell(4, 0)
        assertTrue(g.step())
        assertTrue(g.won)
        assertTrue(g.over)
    }

    @Test fun ticksSpeedUpThenLevelOff() {
        assertEquals(160L, Game.tickMillis(0))
        assertTrue(Game.tickMillis(10) < Game.tickMillis(5))
        assertEquals(70L, Game.tickMillis(1000))
    }
}

package dev.chavan.snake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {
    private fun game(cols: Int = 10, rows: Int = 10) = Game(cols, rows, 1)

    @Test fun startsWithThreeCellsMovingRight() {
        val g = game()
        assertEquals(listOf(Cell(4, 5), Cell(3, 5), Cell(2, 5)), g.snake.toList())
        assertEquals(Dir.RIGHT, g.dir)
        assertFalse(g.over)
    }

    @Test fun foodNeverStartsOnTheSnake() {
        repeat(200) { seed ->
            val g = Game(6, 6, seed.toLong())
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
        val g = Game(5, 5, 3)
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

    @Test fun beforeTheFirstStepAnyDirectionWorks() {
        val g = game()
        g.aim(Dir.LEFT) // would be a reversal once moving
        assertEquals(Dir.LEFT, g.dir)
        assertEquals(Cell(2, 5), g.snake.first()) // turned end to end
        g.step()
        assertFalse(g.over)
        assertEquals(Cell(1, 5), g.snake.first())
    }

    @Test fun onceMovingAimIsAnOrdinaryTurn() {
        val g = game()
        g.step()
        g.aim(Dir.LEFT)
        g.step()
        assertEquals(Dir.RIGHT, g.dir)
    }

    @Test fun resetMakesTheSnakeFreshAgain() {
        val g = game()
        g.step()
        assertFalse(g.fresh)
        g.reset(2)
        assertTrue(g.fresh)
    }

    @Test fun retakeRedoesTheLastStepInTheNewDirection() {
        val g = game()
        g.food = Cell(0, 0)
        g.step() // head (5,5), moving right
        assertTrue(g.retake(Dir.UP))
        assertEquals(Dir.UP, g.dir)
        assertEquals(listOf(Cell(4, 4), Cell(4, 5), Cell(3, 5)), g.snake.toList())
        assertEquals(Cell(2, 5), g.leftTail)
    }

    @Test fun retakeOnlyOncePerStep() {
        val g = game()
        g.food = Cell(0, 0)
        g.step()
        assertTrue(g.retake(Dir.UP))
        assertFalse(g.retake(Dir.LEFT))
    }

    @Test fun retakeRefusesReversalsAndSameDirection() {
        val g = game()
        g.food = Cell(0, 0)
        g.step()
        assertFalse(g.retake(Dir.LEFT))
        assertFalse(g.retake(Dir.RIGHT))
        assertEquals(Cell(5, 5), g.snake.first())
    }

    @Test fun noRetakeAfterEatingOrBeforeMoving() {
        val g = game()
        assertFalse(g.retake(Dir.UP)) // not moved yet
        g.food = Cell(5, 5)
        assertTrue(g.step()) // ate
        assertFalse(g.retake(Dir.UP))
    }

    @Test fun noRetakeWhileATurnIsQueued() {
        val g = game()
        g.food = Cell(0, 0)
        g.step()
        g.turn(Dir.UP)
        assertFalse(g.retake(Dir.DOWN))
    }

    @Test fun retakeCanEatAndCanCrash() {
        val g = game()
        g.food = Cell(4, 4) // above where the head was
        g.step()
        assertTrue(g.retake(Dir.UP))
        assertEquals(1, g.score)

        val h = Game(10, 10, 1)
        h.food = Cell(0, 0)
        repeat(5) { h.step() } // head (9,5) against the right wall
        h.turn(Dir.UP); h.step() // head (9,4), moving up
        assertTrue(h.retake(Dir.RIGHT)) // redo that step going right: into the wall
        assertTrue(h.over)
    }

    @Test fun theSameSeedGivesTheSameGame() {
        val a = Game(10, 10, 77)
        val b = Game(10, 10, 77)
        repeat(30) {
            assertEquals(a.food, b.food)
            a.step(); b.step()
        }
    }

    @Test fun resetWithASeedStartsThatGameAfresh() {
        val fresh = Game(10, 10, 5)
        val reused = Game(10, 10, 99)
        repeat(4) { reused.step() }
        reused.reset(5)
        assertEquals(5L, reused.seed)
        assertEquals(fresh.food, reused.food)
        assertEquals(fresh.snake.toList(), reused.snake.toList())
    }

    @Test fun resetClearsTheRecordingSoPlayAgainStartsANewOne() {
        val g = Game(10, 10, 9)
        repeat(3) { g.step() }
        g.reset(5)
        assertEquals("", g.recording)
        g.step()
        assertEquals("RR", g.recording)
        val r = Replay(Game(10, 10, 5), g.recording)
        r.advance()
        assertEquals(g.snake.toList(), r.game.snake.toList())
    }
}

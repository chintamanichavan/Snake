package dev.chavan.snake

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {
    private val straight = listOf(Cell(5, 2), Cell(4, 2), Cell(3, 2))

    private fun length(line: List<Pos>) =
        line.zipWithNext().sumOf { (a, b) -> (abs(a.x - b.x) + abs(a.y - b.y)).toDouble() }

    @Test fun atTheTickTheLineSitsOnCellCentres() {
        val line = snakeLine(straight, Cell(2, 2), 1f)
        assertEquals(Pos(5f, 2f), line.first())
        assertEquals(Pos(3f, 2f), line.last())
    }

    @Test fun halfwayTheHeadAndTailAreHalfACellBack() {
        val line = snakeLine(straight, Cell(2, 2), 0.5f)
        assertEquals(Pos(4.5f, 2f), line.first())
        assertEquals(Pos(2.5f, 2f), line.last())
    }

    @Test fun lengthStaysTheSameThroughoutAMove() {
        val bent = listOf(Cell(4, 1), Cell(4, 2), Cell(3, 2), Cell(2, 2))
        for (t in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            assertEquals(3.0, length(snakeLine(bent, Cell(1, 2), t)), 1e-6)
        }
    }

    @Test fun lineBendsOnCellCentresNeverDiagonally() {
        val bent = listOf(Cell(4, 1), Cell(4, 2), Cell(3, 2), Cell(2, 2))
        for ((a, b) in snakeLine(bent, Cell(1, 2), 0.3f).zipWithNext()) {
            assert(a.x == b.x || a.y == b.y) { "diagonal segment $a -> $b" }
        }
    }

    @Test fun afterEatingTheTailStaysPut() {
        val line = snakeLine(straight, null, 0.4f)
        assertEquals(Pos(3f, 2f), line.last())
    }

    @Test fun gameReportsTheCellTheTailLeft() {
        val g = Game(10, 10, 1)
        g.food = Cell(0, 0)
        val tail = g.snake.last()
        g.step()
        assertEquals(tail, g.leftTail)
        g.food = g.snake.first() + Dir.RIGHT
        g.step()
        assertEquals(null, g.leftTail)
    }

    @Test fun progressIsClampedToOneTick() {
        assertEquals(0f, progress(100, 200, 160), 0f)
        assertEquals(0.5f, progress(280, 200, 160), 1e-6f)
        assertEquals(1f, progress(999, 200, 160), 0f)
    }
}

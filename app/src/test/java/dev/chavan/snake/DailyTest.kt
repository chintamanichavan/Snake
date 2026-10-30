package dev.chavan.snake

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTest {
    private val day = dailySeed(LocalDate.of(2026, 10, 29))

    private fun daily(seed: Long = day) = Game(16, 24, seed, daily = true)

    @Test fun theSeedIsTheDayNumberSoEveryoneGetsTheSame() {
        assertEquals(LocalDate.of(2026, 10, 29).toEpochDay(), day)
        assertEquals(day + 1, dailySeed(LocalDate.of(2026, 10, 30)))
    }

    @Test fun theKthAppleIsTheSameWhateverTheSnakeDoesWhenItsSquareIsFree() {
        val a = daily()
        val b = daily()
        b.snake.clear() // a different snake somewhere else on the board
        b.snake.addAll(listOf(Cell(10, 3), Cell(10, 4), Cell(10, 5), Cell(11, 5)))
        val empty = daily().also { it.snake.clear() }
        var compared = 0
        for (k in 0 until 200) {
            val want = empty.dailyCell(k)!!
            if (want in a.snake || want in b.snake) continue
            assertEquals("apple $k", want, a.dailyCell(k))
            assertEquals("apple $k", want, b.dailyCell(k))
            compared++
        }
        assertTrue(compared > 150)
    }

    @Test fun differentDaysGetDifferentBoards() {
        val today = daily()
        val tomorrow = daily(day + 1)
        assertNotEquals((0 until 10).map { today.dailyCell(it) }, (0 until 10).map { tomorrow.dailyCell(it) })
    }

    @Test fun aCoveredSquareFallsBackToAFreeOneTheSameWayEveryTime() {
        val g = daily()
        val drawn = daily().also { it.snake.clear() }.dailyCell(3)!!
        g.snake.clear()
        g.snake.addAll(listOf(drawn, Cell(0, 0), Cell(1, 0)))
        val first = g.dailyCell(3)
        assertTrue(first != null && first !in g.snake)
        assertEquals(first, g.dailyCell(3))
    }

    @Test fun aNearlyFullBoardStillFindsTheLastSquareAndAFullOneGivesNone() {
        val g = Game(5, 5, day, daily = true)
        g.snake.clear()
        for (y in 0 until 5) for (x in 0 until 5) if (!(x == 2 && y == 3)) g.snake.addLast(Cell(x, y))
        assertEquals(Cell(2, 3), g.dailyCell(7))
        g.snake.addLast(Cell(2, 3))
        assertNull(g.dailyCell(7))
    }

    @Test fun aDailyGameServesApplesInThatOrder() {
        val g = daily()
        assertEquals(g.dailyCell(0), g.food)
        g.food = g.snake.first() + Dir.RIGHT
        assertTrue(g.step())
        assertEquals(g.dailyCell(1), g.food)
    }

    @Test fun twoDailyGamesStartIdentically() {
        val a = daily()
        val b = daily()
        assertEquals(a.snake.toList(), b.snake.toList())
        assertEquals(a.food, b.food)
        assertFalse(a.over)
    }

    @Test fun aDailyGameReplaysExactly() {
        val g = daily()
        val input = Rng(5)
        while (!g.over) {
            if (input.nextInt(4) == 0) g.turn(Dir.values()[input.nextInt(4)])
            g.step()
        }
        val r = Replay(daily(), g.recording)
        while (r.advance()) {}
        assertEquals(g.snake.toList(), r.game.snake.toList())
        assertEquals(g.score, r.game.score)
    }

    @Test fun todayIsTheUtcDateWhateverThePhonesTimeZone() {
        // 23:30 on 29 Oct in Chicago is already 30 Oct in UTC, so the daily board has moved on.
        val chicagoLateEvening = java.time.ZonedDateTime.of(2026, 10, 29, 23, 30, 0, 0, java.time.ZoneId.of("America/Chicago"))
        val clock = java.time.Clock.fixed(chicagoLateEvening.toInstant(), java.time.ZoneId.of("America/Chicago"))
        assertEquals(LocalDate.of(2026, 10, 30), utcToday(clock))
    }
}

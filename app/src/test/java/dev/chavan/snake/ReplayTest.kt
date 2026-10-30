package dev.chavan.snake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {
    private data class Snap(val snake: List<Cell>, val food: Cell?, val score: Int, val over: Boolean)

    private fun snap(g: Game) = Snap(g.snake.toList(), g.food, g.score, g.over)

    /**
     * Plays one game through the real controls with random input, keeping a snapshot per step.
     * A retake redoes the last step, so it replaces that step's snapshot rather than adding one.
     */
    private fun playRandomly(seed: Long, input: Rng): Pair<Game, List<Snap>> {
        val g = Game(12, 12, seed)
        val snaps = ArrayList<Snap>()
        val dirs = Dir.values()
        if (input.nextInt(2) == 0) g.aim(dirs[input.nextInt(4)])
        var guard = 0
        while (!g.over && guard++ < 2_000) {
            when (input.nextInt(10)) {
                0, 1 -> g.turn(dirs[input.nextInt(4)])
                2 -> if (snaps.isNotEmpty() && g.retake(dirs[input.nextInt(4)])) snaps[snaps.size - 1] = snap(g)
                else -> { g.step(); snaps.add(snap(g)) }
            }
        }
        return g to snaps
    }

    @Test fun replayMatchesTheOriginalAtEveryStep() {
        val input = Rng(2026)
        var reversedStarts = 0
        repeat(300) { n ->
            val (g, snaps) = playRandomly(1_000L + n, input)
            assertTrue(g.over)
            if (g.recording.first() == 'L') reversedStarts++
            val r = Replay(Game(12, 12, g.seed), g.recording)
            for ((i, want) in snaps.withIndex()) {
                assertTrue("game $n step $i: replay ran out", r.advance())
                assertEquals("game $n step $i", want, snap(r.game))
            }
            assertFalse("game $n: replay has steps left over", r.advance())
            assertTrue(r.done)
        }
        assertTrue("no game started reversed, so that path went untested", reversedStarts > 0)
    }

    @Test fun recordingStartsWithFacingThenOneLetterPerStep() {
        val g = Game(10, 10, 1)
        g.food = Cell(0, 0)
        g.step(); g.turn(Dir.UP); g.step()
        assertEquals("RRU", g.recording)
    }

    @Test fun aReversedStartIsRecordedAndReplayed() {
        val g = Game(10, 10, 1)
        g.food = Cell(0, 0)
        g.aim(Dir.LEFT)
        g.turn(Dir.UP) // reversed, then turned before moving
        g.step()
        assertEquals("LU", g.recording)
        val r = Replay(Game(10, 10, 1).also { it.food = Cell(0, 0) }, g.recording)
        r.advance()
        assertEquals(g.snake.toList(), r.game.snake.toList())
    }

    @Test fun aRetakeReplacesTheLastLetter() {
        val g = Game(10, 10, 1)
        g.food = Cell(0, 0)
        g.step()
        assertEquals("RR", g.recording)
        assertTrue(g.retake(Dir.UP))
        assertEquals("RU", g.recording)
    }

    @Test fun aRetakeThatEatsReplaysTheSame() {
        val g = Game(10, 10, 3)
        g.food = Cell(4, 4) // above where the head was before the step
        g.step()
        assertTrue(g.retake(Dir.UP))
        assertEquals(1, g.score)
        val r = Replay(Game(10, 10, 3).also { it.food = Cell(4, 4) }, g.recording)
        r.advance()
        assertEquals(g.snake.toList(), r.game.snake.toList())
        assertEquals(g.food, r.game.food)
    }

    @Test fun theCrashStepIsRecordedSoTheReplayEndsThere() {
        val g = Game(10, 10, 1)
        g.food = Cell(0, 0)
        while (!g.over) g.step()
        val r = Replay(Game(10, 10, 1).also { it.food = Cell(0, 0) }, g.recording)
        while (r.advance()) {}
        assertTrue(r.game.over)
        assertEquals(g.snake.toList(), r.game.snake.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRecordingWithAStrangeLetterIsRejected() {
        Replay(Game(10, 10, 1), "RRX")
    }

    @Test fun replayMatchesInDailyModeTooAndReallyExercisesRetakes() {
        val input = Rng(77)
        val seed = dailySeed(java.time.LocalDate.of(2026, 10, 29))
        var retakes = 0
        var eatingRetakes = 0
        repeat(150) { n ->
            val g = Game(16, 24, seed, daily = true)
            val snaps = ArrayList<Snap>()
            val dirs = Dir.values()
            if (input.nextInt(2) == 0) g.aim(dirs[input.nextInt(4)])
            var guard = 0
            while (!g.over && guard++ < 3_000) {
                when (input.nextInt(10)) {
                    0, 1 -> g.turn(dirs[input.nextInt(4)])
                    2, 3 -> {
                        val score = g.score
                        if (snaps.isNotEmpty() && g.retake(dirs[input.nextInt(4)])) {
                            snaps[snaps.size - 1] = snap(g)
                            retakes++
                            if (g.score > score) eatingRetakes++
                        }
                    }
                    else -> { g.step(); snaps.add(snap(g)) }
                }
            }
            val r = Replay(Game(16, 24, seed, daily = true), g.recording)
            for ((i, want) in snaps.withIndex()) {
                assertTrue(r.advance())
                assertEquals("daily game $n step $i", want, snap(r.game))
            }
            assertFalse(r.advance())
        }
        assertTrue("no retakes happened", retakes > 0)
        assertTrue("no retake ate an apple", eatingRetakes > 0)
    }

    @Test fun extremeSeedsReplayToo() {
        for (seed in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE, 0L)) {
            val g = Game(12, 12, seed)
            val input = Rng(seed)
            while (!g.over) {
                if (input.nextInt(3) == 0) g.turn(Dir.values()[input.nextInt(4)])
                g.step()
            }
            val r = Replay(Game(12, 12, seed), g.recording)
            while (r.advance()) {}
            assertEquals("seed $seed", g.snake.toList(), r.game.snake.toList())
            assertEquals("seed $seed", g.score, r.game.score)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRecordingMustStartFacingLeftOrRight() {
        Replay(Game(10, 10, 1), "URR") // the snake only ever starts facing right, or left if reversed
    }

    @Test(expected = IllegalArgumentException::class)
    fun aReplayNeedsAFreshGame() {
        val g = Game(10, 10, 1)
        g.step()
        Replay(g, "RR")
    }
}

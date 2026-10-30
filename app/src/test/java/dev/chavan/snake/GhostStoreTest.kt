package dev.chavan.snake

import java.io.File
import java.io.IOException
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GhostStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val today = LocalDate.of(2026, 10, 29)

    /** A real finished game, played with random turns. */
    private fun finished(seed: Long, daily: Boolean = false): Game {
        val g = Game(16, 24, seed, daily)
        val input = Rng(seed)
        while (!g.over) {
            if (input.nextInt(3) == 0) g.turn(Dir.values()[input.nextInt(4)])
            g.step()
        }
        return g
    }

    private fun dir(): File = temp.newFolder()

    private fun reasonFor(text: String): String = try {
        decodeGhost(text)
        fail("accepted: $text")
        ""
    } catch (e: IllegalArgumentException) {
        e.message!!
    }

    // Worked out by hand on the 29 Oct 2026 board (apples at (4,6), (8,1), (12,2), ...): from (6,12)
    // up 6, left 2 to eat at (4,6), up 5, right 4 to eat at (8,1), then right 8 into the wall.
    // If this stops loading, the rules changed: bump GHOST_VERSION.
    private val knownDaily = "snake-ghost 1\nmode daily\nboard 16 24\nseed 20755\nscore 2\nsteps 25\n" +
        "moves R" + "U".repeat(6) + "LL" + "U".repeat(5) + "R".repeat(4) + "R".repeat(8) + "\n"

    // Seed 42's first apple is at (15,21), off row 12, so straight right hits the wall at step 10.
    private val knownClassic = "snake-ghost 1\nmode classic\nboard 16 24\nseed 42\nscore 0\nsteps 10\nmoves " + "R".repeat(11) + "\n"

    @Test fun ghostsWrittenByHandStillReplay() {
        assertNull(decodeGhost(knownDaily).verify())
        assertNull(decodeGhost(knownClassic).verify())
        val d = dir()
        File(d, "daily-2026-10-29.ghost").writeText(knownDaily)
        File(d, "best.ghost").writeText(knownClassic)
        val store = GhostStore(d)
        assertEquals(2, store.load(daily = true, today, 16, 24).ghost!!.score)
        assertEquals(0, store.load(daily = false, today, 16, 24).ghost!!.score)
    }

    @Test fun aGhostSurvivesTheRoundTripThroughText() {
        for (seed in listOf(11L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val r = GhostRecord.of(finished(seed))
            assertEquals(r, decodeGhost(r.encode()))
            assertNull(r.verify())
        }
    }

    @Test fun theTextIsPlainAndReadable() {
        val text = GhostRecord(daily = true, cols = 16, rows = 24, seed = 7, score = 2, moves = "RRU").encode()
        assertEquals("snake-ghost 1\nmode daily\nboard 16 24\nseed 7\nscore 2\nsteps 2\nmoves RRU\n", text)
    }

    @Test fun damagedFilesAreRejectedForTheRightReason() {
        val good = knownClassic
        val cases = listOf( // a list, not a map: several cases share a reason
            "empty" to "",
            "not a ghost" to good.replace("snake-ghost", "something"),
            "version" to good.replace("snake-ghost 1", "snake-ghost 99"),
            "mode" to good.replace("mode classic", "mode sometimes"),
            "not a number" to good.replace("seed 42", "seed lots"),
            "direction" to good.replace("moves R", "moves X"),
            "missing" to good.lines().take(3).joinToString("\n"),
            "board" to good.replace("board 16 24", "board 16"),
            "board" to good.replace("board 16 24", "board 4294967312 24"), // would wrap to 16 as an Int
            "board" to good.replace("board 16 24", "board 100000 100000"),
            "score" to good.replace("score 0", "score -3"),
            "steps" to good.replace("steps 10", "steps 9"),
            "twice" to good + "moves RRR\n",
            "unknown" to good + "colour green\n",
            "too long" to good.replace(Regex("moves R+"), "moves R" + "R".repeat(MAX_MOVES)),
        )
        for ((why, text) in cases) {
            val reason = reasonFor(text)
            assertTrue("expected '$why' in: $reason", reason.contains(why))
        }
    }

    @Test fun aGhostThatNoLongerReplaysIsCaughtWithTheReason() {
        val r = GhostRecord.of(finished(13))
        assertTrue(r.copy(score = r.score + 1).verify()!!.contains("scored"))
        assertTrue(r.copy(moves = r.moves.dropLast(3)).verify()!!.contains("crash"))
        assertTrue(r.copy(moves = r.moves + "RRR").verify()!!.contains("steps"))
    }

    @Test fun savedGhostsLoadBackForTheirMode() {
        val store = GhostStore(dir())
        val classic = GhostRecord.of(finished(14))
        val daily = GhostRecord.of(finished(dailySeed(today), daily = true))
        store.save(classic)
        store.save(daily)
        assertEquals(classic, store.load(daily = false, today, 16, 24).ghost)
        assertEquals(daily, store.load(daily = true, today, 16, 24).ghost)
        assertNull(store.load(daily = true, today.plusDays(1), 16, 24).ghost) // a new day starts empty
    }

    @Test fun aDailyGhostIsFiledUnderTheDayItWasPlayedEvenIfSavedAfterMidnight() {
        val d = dir()
        val store = GhostStore(d)
        store.save(GhostRecord.of(finished(dailySeed(today), daily = true)))
        assertTrue(File(d, "daily-2026-10-29.ghost").exists())
        assertFalse(File(d, "daily-2026-10-30.ghost").exists())
    }

    @Test fun aMissingGhostIsNotAProblem() {
        val none = GhostStore(dir()).load(daily = false, today, 16, 24)
        assertNull(none.ghost)
        assertNull(none.problem)
        assertFalse(none.unreadable)
    }

    @Test fun anInvalidGhostIsSetAsideSoTheNextGameCanReplaceIt() {
        val d = dir()
        File(d, "best.ghost").writeText("rubbish")
        val bad = GhostStore(d).load(daily = false, today, 16, 24)
        assertNull(bad.ghost)
        assertTrue(bad.problem!!.contains("not a ghost"))
        assertFalse(bad.unreadable)
        assertFalse(File(d, "best.ghost").exists())
        assertEquals("rubbish", File(d, "best.ghost.bad").readText()) // kept for a look later
    }

    @Test fun anUnreadableGhostIsReportedAndMustNotBeOverwritten() {
        val d = dir()
        File(d, "best.ghost").apply { mkdir(); File(this, "x").writeText("a folder where the file goes") }
        val r = GhostStore(d).load(daily = false, today, 16, 24)
        assertNull(r.ghost)
        assertTrue(r.problem!!.contains("could not read"))
        assertTrue(r.unreadable)
    }

    @Test fun anOversizedFileIsRejectedWithoutReadingIt() {
        val d = dir()
        File(d, "best.ghost").writeText("x".repeat(MAX_FILE_BYTES + 1))
        val r = GhostStore(d).load(daily = false, today, 16, 24)
        assertNull(r.ghost)
        assertTrue(r.problem!!.contains("too large"))
    }

    @Test fun ghostsThatDoNotFitThisGameAreRejectedWithTheReason() {
        val d = dir()
        val store = GhostStore(d)
        store.save(GhostRecord.of(finished(15)))
        assertTrue(store.load(daily = false, today, 20, 30).problem!!.contains("board"))

        File(d, "daily-2026-10-29.ghost").writeText(GhostRecord.of(finished(16)).encode())
        assertTrue(store.load(daily = true, today, 16, 24).problem!!.contains("mode"))

        File(d, "daily-2026-10-29.ghost").writeText(GhostRecord.of(finished(dailySeed(today.minusDays(1)), daily = true)).encode())
        assertTrue(store.load(daily = true, today, 16, 24).problem!!.contains("seed"))
    }

    @Test fun aFailedSaveThrowsLeavesTheOldGhostAndNoTempFile() {
        val d = dir()
        val store = GhostStore(d)
        val old = GhostRecord.of(finished(16))
        store.save(old)
        File(d, "best.ghost.tmp").apply { mkdir(); File(this, "x").writeText("blocks the temp file") }
        try {
            store.save(GhostRecord.of(finished(17)))
            fail("save should have failed")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(old, store.load(daily = false, today, 16, 24).ghost)
    }

    @Test fun aTempFileLeftByAFailedWriteIsRemoved() {
        val d = dir()
        val store = GhostStore(d)
        val target = File(d, "best.ghost")
        target.mkdir() // the rename onto a non-empty folder fails after the temp file is written
        File(target, "x").writeText("in the way")
        try {
            store.save(GhostRecord.of(finished(18)))
            fail("save should have failed")
        } catch (e: IOException) {
            // expected
        }
        assertFalse(File(d, "best.ghost.tmp").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aGameTooLongToStoreIsRefused() {
        GhostStore(dir()).save(GhostRecord(false, 16, 24, 1, 0, "R" + "R".repeat(MAX_MOVES)))
    }

    @Test fun onlyTodaysDailyGhostIsKept() {
        val d = dir()
        val store = GhostStore(d)
        store.save(GhostRecord.of(finished(18)))
        for (day in listOf(today.minusDays(2), today.minusDays(1), today, today.plusDays(1))) {
            store.save(GhostRecord.of(finished(dailySeed(day), daily = true)))
        }
        File(d, "daily-2026-10-27.ghost.tmp").writeText("left by a crash")
        File(d, "daily-2026-10-27.ghost.bad").writeText("set aside")
        File(d, "notes.txt").writeText("not ours")
        assertEquals(emptyList<String>(), store.removeOldDailies(today))
        assertEquals(listOf("best.ghost", "daily-2026-10-29.ghost", "notes.txt"), d.list()!!.sorted())
    }

    @Test fun dailyFilesThatCannotBeDeletedAreReported() {
        val d = dir()
        File(d, "daily-2026-01-01.ghost").apply { mkdir(); File(this, "x").writeText("not empty") }
        assertEquals(listOf("daily-2026-01-01.ghost"), GhostStore(d).removeOldDailies(today))
    }

    @Test(expected = IOException::class)
    fun aFolderThatCannotBeListedIsAnError() {
        GhostStore(File(dir(), "missing")).removeOldDailies(today)
    }

    @Test fun aNewGameReplacesTheGhostOnlyIfItScoresHigherOrThereIsNone() {
        assertTrue(GhostStore.beats(score = 0, ghost = null))
        val r = GhostRecord.of(finished(19))
        assertFalse(GhostStore.beats(r.score, r))
        assertTrue(GhostStore.beats(r.score + 1, r))
    }

    /**
     * The longest game possible: a bot following a loop through every square never crashes and
     * fills the whole board. Row 0 runs right, the rows below snake left and right through
     * columns 1-15, and column 0 leads back up. The start (row 12, heading right) is on the loop.
     */
    private fun fullBoardGame(): Game {
        val g = Game(16, 24, 3)
        fun next(c: Cell): Cell = when {
            c.x == 0 -> if (c.y > 0) Cell(0, c.y - 1) else Cell(1, 0)
            c.y == 0 -> if (c.x < 15) Cell(c.x + 1, 0) else Cell(15, 1)
            c.y % 2 == 1 -> if (c.x > 1) Cell(c.x - 1, c.y) else if (c.y == 23) Cell(0, 23) else Cell(1, c.y + 1)
            else -> if (c.x < 15) Cell(c.x + 1, c.y) else Cell(15, c.y + 1)
        }
        while (!g.over) {
            val h = g.snake.first()
            val n = next(h)
            g.turn(Dir.values().first { h + it == n })
            g.step()
        }
        return g
    }

    @Test fun theLongestPossibleGameIsWonStoredAndReplayed() {
        val g = fullBoardGame()
        assertTrue(g.won)
        assertEquals(16 * 24 - 3, g.score)
        assertTrue("${g.recording.length} moves", g.recording.length <= MAX_MOVES)
        val r = GhostRecord.of(g)
        val store = GhostStore(dir())
        store.save(r)
        assertEquals(r, store.load(daily = false, today, 16, 24).ghost)
    }
}

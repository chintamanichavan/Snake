package dev.chavan.snake

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The whole ghost and daily flow through the real activity and real files. */
@RunWith(AndroidJUnit4::class)
class GhostFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val ghosts = File(context.filesDir, "ghosts")
    private val day = LocalDate.of(2026, 10, 29)
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var view: SnakeView
    private var now = 10_000L

    @Before fun clean() {
        context.getSharedPreferences("snake", 0).edit().clear().commit()
        ghosts.deleteRecursively()
    }

    @After fun close() {
        scenario?.close()
    }

    /**
     * Opens the app as a player would and waits for its first layout, which makes the first game.
     * The test then takes over the clock and the sound.
     */
    private fun launchAsIs() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        inst.waitForIdleSync() // the first layout, and with it the first game, has happened
        scenario!!.onActivity { a ->
            view = a.view
            view.autoTick = false
            view.clock = { now }
            view.feedback = Silent
        }
    }

    /**
     * Opens the app, then starts a fresh game from fixed seeds (100, 101, ...), so the bot plays
     * the same games every run. Waiting for the first layout first means that layout's own game
     * cannot use up a seed and shift which game is played.
     */
    private fun launch() {
        launchAsIs()
        onUi {
            var next = 100L
            view.seeds = { next++ }
            view.newGame()
        }
        assertEquals(100L, view.game!!.seed)
    }

    private fun onUi(block: () -> Unit) = inst.runOnMainSync(block)

    private fun touch(action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, x, y, 0)
        onUi { view.dispatchTouchEvent(e) }
        e.recycle()
    }

    private fun tap(x: Float = view.width / 2f, y: Float = view.height / 2f) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_UP, x, y)
    }

    private fun swipeRight() {
        val x = view.width / 2f
        val y = view.height / 2f
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_MOVE, x + 200f, y)
        touch(MotionEvent.ACTION_UP, x + 200f, y)
    }

    /**
     * Plays a real game: steers to the actual apples until it has eaten `eat`, then runs into the
     * nearest wall. Nothing is placed by hand, so the recording replays exactly, as a player's would.
     */
    private fun playToTheWall(eat: Int) {
        swipeRight()
        while (view.state == SnakeView.State.RUNNING) {
            val g = view.game!!
            val h = g.snake.first()
            val f = g.food!!
            val want = if (g.score < eat) {
                when {
                    f.x > h.x -> Dir.RIGHT
                    f.x < h.x -> Dir.LEFT
                    f.y > h.y -> Dir.DOWN
                    else -> Dir.UP
                }
            } else if (h + g.heading == f) {
                if (g.heading.dx != 0) Dir.UP else Dir.LEFT // step round an apple in the way
            } else {
                g.heading // keep going: the wall comes soon enough
            }
            onUi {
                if (want.isOpposite(g.heading)) g.turn(if (f.y >= h.y) Dir.DOWN else Dir.UP) else g.turn(want)
                view.tick()
            }
        }
        assertEquals("the bot should have eaten $eat apples", eat, view.game!!.score)
        now += SnakeView.OVERLAY_DELAY_MS + 1
    }

    @Test fun aFinishedGameBecomesTheNextGamesGhostAndSurvivesARestart() {
        launch()
        assertNull(view.ghostGame)
        playToTheWall(eat = 2)
        val played = view.game!!.seed
        assertTrue(File(ghosts, "best.ghost").isFile)
        tap() // play again
        assertNotNull(view.ghostGame)
        assertEquals(played, view.ghostGame!!.seed)
        assertEquals(2, view.best)

        launch() // the app is closed and opened again
        assertEquals(played, view.ghostGame!!.seed)
        assertEquals(2, view.best)
    }

    @Test fun aLowerScoreDoesNotReplaceTheGhost() {
        launch()
        playToTheWall(eat = 2)
        val best = view.game!!.seed
        tap()
        playToTheWall(eat = 1)
        tap()
        assertEquals(best, view.ghostGame!!.seed)
    }

    @Test fun dailyAndClassicKeepSeparateGhosts() {
        launch()
        onUi { view.today = { day } }
        playToTheWall(eat = 1)
        val classicSeed = view.game!!.seed
        tap()
        onUi { view.chooseMode(daily = true) }
        assertNull(view.ghostGame) // no daily ghost yet today
        assertEquals(0, view.best)
        playToTheWall(eat = 2)
        assertTrue(File(ghosts, "daily-$day.ghost").isFile)
        tap()
        assertEquals(dailySeed(day), view.ghostGame!!.seed)
        onUi { view.chooseMode(daily = false) }
        assertEquals(classicSeed, view.ghostGame!!.seed)
        assertEquals(1, view.best)
    }

    @Test fun anOldBestScoreCarriesOverAndTheFirstGameStillLeavesAGhost() {
        context.getSharedPreferences("snake", 0).edit().putInt(MainActivity.BEST, 7).commit()
        launch()
        assertEquals(7, view.best)
        playToTheWall(eat = 1)
        tap()
        assertNotNull(view.ghostGame) // even though 1 < 7, there was no ghost yet
        assertEquals(7, view.best)
    }

    @Test fun anUnreadableGhostIsNeverOverwrittenAndTheGameStillRuns() {
        File(ghosts, "best.ghost").apply { mkdirs(); File(this, "x").writeText("in the way") }
        launch()
        assertNull(view.ghostGame)
        playToTheWall(eat = 1)
        tap()
        assertTrue(File(ghosts, "best.ghost").isDirectory) // untouched
        assertEquals(SnakeView.State.READY, view.state)
    }

    @Test fun oldDailyGhostsAreClearedAtLaunch() {
        ghosts.mkdirs()
        File(ghosts, "daily-2020-01-01.ghost").writeText("old")
        launch()
        assertTrue(!File(ghosts, "daily-2020-01-01.ghost").exists())
    }

    /** Steers with real swipes, like a player: a sideways swipe to `want`, or round if that is backwards. */
    private fun swipeTo(want: Dir) {
        val g = view.game!!
        val d = if (g.fresh || !want.isOpposite(g.heading)) want else if (g.heading.dx != 0) Dir.DOWN else Dir.RIGHT
        val x = view.width / 2f
        val y = view.height / 2f
        val px = 60f * view.resources.displayMetrics.density
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_MOVE, x + d.dx * px, y + d.dy * px)
        touch(MotionEvent.ACTION_UP, x + d.dx * px, y + d.dy * px)
    }

    @Test fun aGamePlayedOnlyWithSwipesIncludingRetakesReplaysAfterARelaunch() {
        launch()
        swipeTo(Dir.LEFT) // the first swipe turns the snake end to end
        assertEquals(Dir.LEFT, view.game!!.dir)
        var retakes = 0
        var n = 0
        while (view.state == SnakeView.State.RUNNING && n++ < 2_000) {
            onUi { view.tick() }
            if (view.state != SnakeView.State.RUNNING) break
            val g = view.game!!
            val h = g.snake.first()
            val f = g.food!!
            val want = when {
                g.score >= 2 -> null // two apples, then on into a wall
                f.x > h.x -> Dir.RIGHT
                f.x < h.x -> Dir.LEFT
                f.y > h.y -> Dir.DOWN
                else -> Dir.UP
            }
            if (want != null && want != g.heading) {
                // Every other turn is swiped straight after a step, inside the retake window, so
                // it redoes that step; the rest arrive mid-step and wait for the next one.
                val early = n % 2 == 0
                if (!early) now += Game.tickMillis(g.score) / 2
                val before = g.snake.first()
                swipeTo(want)
                if (early && view.game!!.snake.first() != before) retakes++
            }
        }
        assertEquals(SnakeView.State.OVER, view.state)
        assertTrue("no swipe became a retake", retakes > 0)
        val seed = view.game!!.seed
        val score = view.game!!.score

        launchAsIs() // closed and opened again: the ghost comes from the file
        assertFalse(File(ghosts, "best.ghost.bad").exists())
        assertEquals(seed, view.ghostGame!!.seed)
        assertEquals(score, view.best)
    }

    @Test fun theFirstGameAfterOpeningTheAppHasItsGhostAndBest() {
        launch()
        playToTheWall(eat = 1)
        val seed = view.game!!.seed
        launchAsIs() // no extra new game: this is the one made by the first layout
        assertEquals(seed, view.ghostGame!!.seed)
        assertEquals(1, view.best)
    }

    @Test fun aDailyGameAcrossMidnightIsKeptUnderItsOwnDay() {
        val next = day.plusDays(1)
        launch()
        onUi { view.today = { day }; view.chooseMode(daily = true) }
        playToTheWall(eat = 2)
        tap()
        assertEquals(dailySeed(day), view.ghostGame!!.seed) // racing yesterday's best...
        swipeRight()
        onUi { view.today = { next } } // ...when midnight passes mid-game
        while (view.state == SnakeView.State.RUNNING) onUi { view.tick() }
        now += SnakeView.OVERLAY_DELAY_MS + 1
        tap()
        assertEquals(2, decodeGhost(File(ghosts, "daily-$day.ghost").readText()).score) // not overwritten
        assertFalse(File(ghosts, "daily-$next.ghost").exists()) // a game from day D is never filed under D+1
        assertEquals(dailySeed(next), view.game!!.seed) // the new game is on the new day's board
        assertNull(view.ghostGame)
        assertEquals(0, view.best)
    }

    @Test fun aDailyBoardLeftOnTheStartScreenOvernightIsRefreshedWhenPlayStarts() {
        launch()
        onUi { view.today = { day }; view.chooseMode(daily = true) }
        assertEquals(dailySeed(day), view.game!!.seed)
        onUi { view.today = { day.plusDays(1) } }
        swipeRight()
        assertEquals(dailySeed(day.plusDays(1)), view.game!!.seed)
        assertEquals(SnakeView.State.RUNNING, view.state)
    }

    @Test fun anEmptyFolderInTheGhostsPlaceIsNeverReplaced() {
        File(ghosts, "best.ghost").mkdirs() // reads fail, but a rename could replace it
        launch()
        playToTheWall(eat = 1)
        assertTrue(view.notice!!.contains("not saved"))
        tap()
        assertTrue(File(ghosts, "best.ghost").isDirectory)
        assertFalse(File(ghosts, "best.ghost.tmp").exists())
    }

    @Test fun whenGhostsCannotBeSavedTheGameCarriesOnAndKeepsTheBestScore() {
        ghosts.parentFile!!.mkdirs()
        ghosts.writeText("a file where the ghosts folder should be")
        launch()
        playToTheWall(eat = 1)
        assertTrue(view.notice!!.contains("not saved"))
        tap()
        assertEquals(SnakeView.State.READY, view.state)
        assertNull(view.ghostGame)
        assertEquals(1, view.best) // the best score is kept apart from the ghost file
        assertTrue(ghosts.isFile)
    }

    @Test fun aGhostSetAsideDoesNotLowerTheBestScore() {
        launch()
        playToTheWall(eat = 2)
        File(ghosts, "best.ghost").writeText("damaged")
        launchAsIs()
        assertNull(view.ghostGame)
        assertEquals(2, view.best)
        assertTrue(File(ghosts, "best.ghost.bad").exists())
    }

    @Test fun launchKeepsTodaysDailyGhostAndClearsLeftovers() {
        val today = utcToday(java.time.Clock.systemUTC())
        ghosts.mkdirs()
        val g = Game(SnakeView.COLS, SnakeView.ROWS, dailySeed(today), daily = true)
        while (!g.over) g.step()
        GhostStore(ghosts).save(GhostRecord.of(g))
        File(ghosts, "daily-2020-01-01.ghost.tmp").writeText("left by a crash")
        File(ghosts, "daily-2020-01-01.ghost.bad").writeText("set aside")
        launchAsIs()
        assertTrue(File(ghosts, "daily-$today.ghost").isFile)
        assertFalse(File(ghosts, "daily-2020-01-01.ghost.tmp").exists())
        assertFalse(File(ghosts, "daily-2020-01-01.ghost.bad").exists())
        onUi { view.chooseMode(daily = true) }
        assertEquals(dailySeed(today), view.ghostGame!!.seed)
    }

    @Test fun theChosenModeSurvivesTheScreenBeingRebuilt() {
        launch()
        onUi { view.chooseMode(daily = true) }
        scenario!!.recreate()
        inst.waitForIdleSync()
        scenario!!.onActivity { view = it.view }
        assertTrue(view.daily)
        assertTrue(view.game!!.daily)
    }

    @Test fun smallerRules() {
        launch()
        playToTheWall(eat = 0)
        tap()
        assertNotNull(view.ghostGame) // a first game scoring 0 still becomes the ghost
        assertEquals(0, view.best)
        val ghostSeed = view.ghostGame!!.seed
        playToTheWall(eat = 0)
        tap()
        assertEquals(ghostSeed, view.ghostGame!!.seed) // a tie does not replace it

        val seed = view.game!!.seed
        var calls = 0
        onUi { view.onNewGame = { _, _ -> calls++ }; view.chooseMode(daily = false) }
        assertEquals(0, calls) // choosing the mode already chosen starts nothing
        assertEquals(seed, view.game!!.seed)
    }

    @Test fun beatingTheDailyGhostRaisesTheDailyBest() {
        launch()
        onUi { view.today = { day }; view.chooseMode(daily = true) }
        playToTheWall(eat = 1)
        tap()
        playToTheWall(eat = 2)
        tap()
        assertEquals(2, view.best)
        assertEquals(2, decodeGhost(File(ghosts, "daily-$day.ghost").readText()).score)
    }

    private object Silent : Feedback {
        override fun eat(score: Int) {}
        override fun crash() {}
        override fun newBest() {}
    }
}

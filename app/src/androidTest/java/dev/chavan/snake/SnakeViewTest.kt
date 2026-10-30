package dev.chavan.snake

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real activity on a device or emulator and drives it with real touch events. */
@RunWith(AndroidJUnit4::class)
class SnakeViewTest {
    // Launched by hand in setUp, after the ghost files are cleared: a rule would launch first,
    // and a ghost left by an earlier test would be loaded and drawn over the board.
    private lateinit var scenario: ActivityScenario<MainActivity>

    private lateinit var view: SnakeView

    /** Records sound and vibration events instead of making them. */
    private class Recorder : Feedback {
        val events = ArrayList<String>()
        override fun eat(score: Int) { events.add("eat $score") }
        override fun crash() { events.add("crash") }
        override fun newBest() { events.add("newBest") }
    }
    private val recorder = Recorder()

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("snake", 0).edit().clear().commit()
        java.io.File(context.filesDir, "ghosts").deleteRecursively()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            view = it.view
            view.autoTick = false
            view.feedback = recorder
        }
    }

    @After fun tearDown() = scenario.close()

    private fun onUi(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun touch(action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, x, y, 0)
        onUi { view.dispatchTouchEvent(e) }
        e.recycle()
    }

    private fun swipe(dx: Float, dy: Float) {
        val x = view.width / 2f
        val y = view.height / 2f
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_MOVE, x + dx, y + dy)
        touch(MotionEvent.ACTION_UP, x + dx, y + dy)
    }

    private fun tap() = swipe(0f, 0f)

    @Test fun boardFillsScreenBelowTheScore() {
        val l = view.layout!!
        assertEquals(SnakeView.COLS, l.cols)
        assertEquals(SnakeView.ROWS, l.rows) // the same board on every phone
        assertTrue(l.left >= 0 && l.left + l.width <= view.width)
        assertTrue(l.top > view.insetTop && l.top + l.height <= view.height - view.insetBottom)
    }

    @Test fun swipeStartsTheGameAndTurns() {
        assertEquals(SnakeView.State.READY, view.state)
        swipe(0f, -200f)
        assertEquals(SnakeView.State.RUNNING, view.state)
        val head = view.game!!.snake.first()
        onUi { view.tick() }
        assertEquals(Dir.UP, view.game!!.dir)
        assertEquals(Cell(head.x, head.y - 1), view.game!!.snake.first())
    }

    @Test fun tapPausesAndResumes() {
        swipe(200f, 0f)
        tap()
        assertEquals(SnakeView.State.PAUSED, view.state)
        val before = view.game!!.snake.first()
        onUi { view.tick() }
        assertEquals(before, view.game!!.snake.first()) // paused: no movement
        tap()
        assertEquals(SnakeView.State.RUNNING, view.state)
    }

    @Test fun crashingEndsGameSavesBestAndTapRestarts() {
        var now = 10_000L
        onUi { view.clock = { now } }
        swipe(200f, 0f)
        view.game!!.food = Cell(view.game!!.snake.first().x + 1, view.game!!.snake.first().y)
        onUi { view.tick() } // eat once
        view.game!!.food = Cell(0, 0) // off the snake's row, so it cannot eat again by chance
        onUi { repeat(SnakeView.COLS) { view.tick() } } // run into the right wall
        assertEquals(SnakeView.State.OVER, view.state)
        assertEquals(listOf("eat 1", "crash", "newBest"), recorder.events)
        assertTrue(view.newBest)
        assertEquals(view.game!!.snake.size, view.particles.list.count { it.size == 0.38f }) // one piece per segment, besides the sparks
        assertEquals(1, view.best)
        val ghosts = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ghosts")
        // The game is kept as the ghost. Read the file without replaying it: this test placed the
        // apple by hand, which no replay can reproduce (GhostFlowTest plays real games for that).
        assertEquals(1, decodeGhost(java.io.File(ghosts, "best.ghost").readText()).score)

        tap() // too soon: the game-over card is not up yet
        assertEquals(SnakeView.State.OVER, view.state)

        now += SnakeView.OVERLAY_DELAY_MS
        tap()
        assertEquals(SnakeView.State.READY, view.state)
        assertEquals(0, view.game!!.score)
        assertTrue(view.particles.list.isEmpty())
    }

    @Test fun drawsSnakeHeadAndFoodWhereTheGameSaysTheyAre() {
        val later = SystemClock.uptimeMillis() + 60_000 // long after every animation has settled
        onUi { view.clock = { later } }
        swipe(200f, 0f) // start, so no overlay dims the board
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        onUi { view.draw(Canvas(bmp)) }
        val l = view.layout!!
        fun centre(c: Cell) = bmp.getPixel(l.left + c.x * l.cell + l.cell / 2, l.top + c.y * l.cell + l.cell / 2)
        // The head's centre sits between its eyes, so sample just behind it.
        val head = view.game!!.snake.first()
        val behind = bmp.getPixel(l.left + head.x * l.cell + l.cell / 4, l.top + head.y * l.cell + l.cell / 2)
        assertEquals(SnakeView.HEAD, behind)
        assertEquals(SnakeView.FOOD, centre(view.game!!.food!!))
    }

    @Test fun snakeSlidesSmoothlyBetweenCells() {
        var now = 10_000L
        onUi { view.clock = { now } }
        swipe(200f, 0f)
        view.game!!.food = Cell(0, 0) // keep food out of the way
        onUi { view.tick() }
        val l = view.layout!!
        val head = view.game!!.snake.first()
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        fun at(fx: Float) = bmp.getPixel((l.left + (head.x + fx) * l.cell).toInt(), l.top + head.y * l.cell + l.cell / 2)

        now += Game.tickMillis(0) / 2 // halfway through the step
        onUi { view.draw(Canvas(bmp)) }
        assertTrue(at(0.5f) != SnakeView.HEAD) // the head has not reached its new cell's centre yet
        assertEquals(SnakeView.HEAD, at(-0.1f)) // it is half a cell back

        now += Game.tickMillis(0) // the step has finished
        onUi { view.draw(Canvas(bmp)) }
        assertEquals(SnakeView.HEAD, at(0.5f))
    }

    @Test fun eatingPlaysFeedbackAndSpraysSparks() {
        swipe(200f, 0f)
        val g = view.game!!
        g.food = g.snake.first() + Dir.RIGHT
        onUi { view.tick() }
        assertEquals(listOf("eat 1"), recorder.events)
        assertTrue(view.particles.alive)
    }

    @Test fun newFoodPopsIn() {
        var now = 10_000L
        onUi { view.clock = { now } }
        swipe(200f, 0f)
        val g = view.game!!
        g.food = g.snake.first() + Dir.RIGHT
        onUi { view.tick() } // eats; new food appears now
        val f = g.food!!
        val l = view.layout!!
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        fun foodEdge() = bmp.getPixel(l.left + f.x * l.cell + l.cell / 2 + (l.cell * 0.3f).toInt(), l.top + f.y * l.cell + l.cell / 2)
        onUi { view.draw(Canvas(bmp)) }
        assertTrue(foodEdge() != SnakeView.FOOD) // just appeared: still tiny
        now += 2_000
        onUi { view.draw(Canvas(bmp)) }
        assertEquals(SnakeView.FOOD, foodEdge()) // fully grown
    }

    @Test fun everySwipeDirectionStartsTheGame() {
        for (d in Dir.values()) {
            onUi { view.game!!.reset(1) }
            scenario.recreate()
            scenario.onActivity { view = it.view; view.autoTick = false; view.feedback = recorder }
            swipe(d.dx * 200f, d.dy * 200f)
            assertEquals(SnakeView.State.RUNNING, view.state)
            onUi { view.tick() }
            assertEquals("swipe $d", d, view.game!!.dir)
        }
    }

    @Test fun boardMiddleIsShieldedFromTheBackGesture() {
        val r = view.systemGestureExclusionRects
        assertEquals(1, r.size)
        assertEquals(0, r[0].left)
        assertEquals(view.width, r[0].right)
    }

    @Test fun aGameThatDoesNotBeatTheBestIsNotCelebrated() {
        onUi { view.best = 5 }
        swipe(200f, 0f)
        view.game!!.food = Cell(0, 0)
        onUi { repeat(SnakeView.COLS) { view.tick() } }
        assertEquals(SnakeView.State.OVER, view.state)
        assertEquals(listOf("crash"), recorder.events)
        assertFalse(view.newBest)
        assertEquals(5, view.best)
    }

    @Test fun scoreFlashesWhenItGoesUp() {
        var now = 10_000L
        onUi { view.clock = { now } }
        swipe(200f, 0f)
        view.game!!.food = view.game!!.snake.first() + Dir.RIGHT
        onUi { view.tick() }
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val top = view.insetTop
        val hud = view.layout!!.top - top
        fun hudHasHeadColour(): Boolean {
            for (y in top until top + hud step 2) for (x in 0 until view.width / 3 step 2) {
                val p = bmp.getPixel(x, y)
                if (android.graphics.Color.green(p) > 180 && android.graphics.Color.red(p) < 160) return true
            }
            return false
        }
        now += 110 // middle of the swell
        onUi { view.draw(Canvas(bmp)) }
        assertTrue(hudHasHeadColour())
        now += 1_000 // settled back to plain text
        onUi { view.draw(Canvas(bmp)) }
        assertFalse(hudHasHeadColour())
    }

    private fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        onUi {
            view.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
            view.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0))
        }
    }

    @Test fun arrowKeysSteerAndSpacePauses() {
        assertTrue(view.hasFocus())
        key(android.view.KeyEvent.KEYCODE_DPAD_LEFT) // starts the game heading left
        assertEquals(SnakeView.State.RUNNING, view.state)
        assertEquals(Dir.LEFT, view.game!!.dir)
        key(android.view.KeyEvent.KEYCODE_DPAD_UP)
        onUi { view.tick() }
        assertEquals(Dir.UP, view.game!!.dir)
        key(android.view.KeyEvent.KEYCODE_SPACE)
        assertEquals(SnakeView.State.PAUSED, view.state)
    }

    @Test fun trackpadScrollSteers() {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        fun scrollBy(h: Float, v: Float) {
            val c = MotionEvent.PointerCoords().apply {
                x = view.width / 2f; y = view.height / 2f
                setAxisValue(MotionEvent.AXIS_HSCROLL, h)
                setAxisValue(MotionEvent.AXIS_VSCROLL, v)
            }
            val t = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, props, arrayOf(c), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_MOUSE, 0)
            onUi { view.dispatchGenericMotionEvent(e) }
            e.recycle()
        }
        scrollBy(0f, 2f)
        assertEquals(SnakeView.State.RUNNING, view.state)
        onUi { view.tick() }
        assertEquals(Dir.DOWN, view.game!!.dir)
    }

    @Test fun aShortQuickSwipeTurnsInsteadOfPausing() {
        swipe(0f, -200f) // start heading up
        onUi { view.tick() }
        val short = 10f * view.resources.displayMetrics.density // well under the mid-drag threshold
        swipe(short, 0f)
        assertEquals(SnakeView.State.RUNNING, view.state)
        onUi { view.tick() }
        assertEquals(Dir.RIGHT, view.game!!.dir)
    }

    @Test fun aStillTouchIsStillATap() {
        swipe(0f, -200f)
        swipe(2f, 1f) // a finger never lands perfectly still
        assertEquals(SnakeView.State.PAUSED, view.state)
    }

    @Test fun aSwipeJustAfterAStepTurnsAtOnce() {
        var now = 10_000L
        onUi { view.clock = { now } }
        swipe(200f, 0f)
        view.game!!.food = Cell(0, 0)
        onUi { view.tick() }
        val head = view.game!!.snake.first()
        now += Game.tickMillis(0) / 10 // the head has barely left its cell
        swipe(0f, -200f)
        assertEquals(Dir.UP, view.game!!.dir) // turned without waiting for the next tick
        assertEquals(Cell(head.x - 1, head.y - 1), view.game!!.snake.first())
    }

    @Test fun aSwipeLateInAStepWaitsForTheNextOne() {
        var now = 10_000L
        onUi { view.clock = { now } }
        swipe(200f, 0f)
        view.game!!.food = Cell(0, 0)
        onUi { view.tick() }
        val head = view.game!!.snake.first()
        now += Game.tickMillis(0) / 2
        swipe(0f, -200f)
        assertEquals(Dir.RIGHT, view.game!!.dir)
        assertEquals(head, view.game!!.snake.first())
        onUi { view.tick() }
        assertEquals(Dir.UP, view.game!!.dir)
    }

    @Test fun aLightDragTurnsBeforeTheFingerLifts() {
        swipe(0f, -200f) // start heading up
        onUi { view.tick() }
        val light = 16f * view.resources.displayMetrics.density
        val x = view.width / 2f
        val y = view.height / 2f
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_MOVE, x + light, y) // finger still down
        onUi { view.tick() }
        assertEquals(Dir.RIGHT, view.game!!.dir)
        touch(MotionEvent.ACTION_UP, x + light, y)
    }

    @Test fun aSloppyDiagonalSwipeTurnsTheWayItWasMeantAndOnlyOnce() {
        swipe(0f, -200f) // heading up
        onUi { view.tick() }
        val x = view.width / 2f
        val y = view.height / 2f
        // Like a recorded emulator trackpad swipe: mostly down, partly right, in several moves.
        touch(MotionEvent.ACTION_DOWN, x, y)
        for (i in 1..6) touch(MotionEvent.ACTION_MOVE, x + 60f * i, y + 110f * i)
        touch(MotionEvent.ACTION_UP, x + 360f, y + 660f)
        onUi { view.tick() }
        assertEquals(Dir.RIGHT, view.game!!.dir)
        onUi { view.tick() }
        assertEquals(Dir.RIGHT, view.game!!.dir) // no second turn from the rest of the drag
    }

    @Test fun aResizedWindowKeepsTheSameGameAndBoardOnlyRescaled() {
        swipe(200f, 0f)
        repeat(3) { onUi { view.tick() } }
        val game = view.game
        val snake = game!!.snake.toList()
        val cell = view.layout!!.cell
        onUi { view.layout(0, 0, view.width / 2, view.height / 2) } // e.g. split screen
        assertTrue(game === view.game)
        assertEquals(snake, view.game!!.snake.toList())
        assertEquals(SnakeView.COLS, view.layout!!.cols)
        assertEquals(SnakeView.ROWS, view.layout!!.rows)
        assertTrue(view.layout!!.cell < cell)
    }

    @Test fun playAgainGetsANewSeedAndAnEmptyRecording() {
        var now = 10_000L
        onUi { view.clock = { now } }
        swipe(200f, 0f)
        view.game!!.food = Cell(0, 0)
        onUi { repeat(SnakeView.COLS) { view.tick() } }
        assertEquals(SnakeView.State.OVER, view.state)
        val seed = view.game!!.seed
        now += SnakeView.OVERLAY_DELAY_MS
        tap()
        assertEquals(SnakeView.State.READY, view.state)
        assertTrue(view.game!!.seed != seed)
        assertEquals("", view.game!!.recording)
    }

    /** A ghost that went up from the start and kept going until it hit the top wall. */
    private fun ghostGoingUp(): GhostRecord {
        val g = Game(SnakeView.COLS, SnakeView.ROWS, 5)
        g.turn(Dir.UP)
        while (!g.over) g.step()
        return GhostRecord.of(g)
    }

    private fun startWithGhost(ghost: GhostRecord?) {
        onUi { view.onNewGame = { _, _ -> }; view.ghost = ghost; view.newGame() }
    }

    @Test fun theGhostStepsWithYouAndNotOnARetake() {
        var now = 10_000L
        onUi { view.clock = { now } }
        startWithGhost(ghostGoingUp())
        val start = view.ghostGame!!.snake.first()
        swipe(0f, 200f) // you go down; the ghost goes up
        view.game!!.food = Cell(0, 0)
        onUi { view.tick() }
        assertEquals(Cell(start.x, start.y - 1), view.ghostGame!!.snake.first())
        now += 5 // just after the step: a swipe now redoes your step
        swipe(200f, 0f)
        assertEquals(Dir.RIGHT, view.game!!.dir)
        assertEquals(Cell(start.x, start.y - 1), view.ghostGame!!.snake.first()) // ghost did not move again
        onUi { view.tick() }
        assertEquals(Cell(start.x, start.y - 2), view.ghostGame!!.snake.first())
    }

    @Test fun theGhostIsDrawnWhereItsGameSaysAndNowhereElse() {
        var now = SystemClock.uptimeMillis() + 60_000
        onUi { view.clock = { now } }
        startWithGhost(ghostGoingUp())
        swipe(0f, 200f)
        view.game!!.food = Cell(0, 0)
        repeat(4) { onUi { view.tick() } }
        now += 1_000 // let the slide into the new cells finish
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        onUi { view.draw(Canvas(bmp)) }
        val l = view.layout!!
        fun at(c: Cell) = bmp.getPixel(l.left + c.x * l.cell + l.cell / 2, l.top + c.y * l.cell + l.cell / 2)
        val ghostHead = view.ghostGame!!.snake.first()
        val empty = Cell(14, 3)
        assertTrue(empty !in view.ghostGame!!.snake && empty !in view.game!!.snake)
        assertTrue("ghost cell should be lighter than the board", android.graphics.Color.red(at(ghostHead)) > android.graphics.Color.red(at(empty)) + 20)
        assertTrue(ghostHead !in view.game!!.snake)
    }

    @Test fun withoutAGhostNothingExtraIsDrawn() {
        startWithGhost(null)
        assertEquals(null, view.ghostGame)
        swipe(0f, 200f)
        onUi { view.tick() } // no crash with no ghost
    }

    @Test fun theScoreBarShowsTheGhostOnlyWhenThereIsOne() {
        fun middleOfScoreBarHasText(): Boolean {
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            onUi { view.draw(Canvas(bmp)) }
            val top = view.insetTop
            for (y in top until view.layout!!.top step 2) for (x in view.width * 2 / 5 until view.width * 3 / 5 step 2) {
                if (android.graphics.Color.red(bmp.getPixel(x, y)) > 120) return true
            }
            return false
        }
        startWithGhost(null)
        assertFalse(middleOfScoreBarHasText())
        startWithGhost(ghostGoingUp())
        assertTrue(middleOfScoreBarHasText())
    }

    private fun tapAt(x: Float, y: Float) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_UP, x, y)
    }

    @Test fun theStartScreenSwitchesBetweenClassicAndDaily() {
        val day = java.time.LocalDate.of(2026, 10, 29)
        val modes = ArrayList<Boolean>()
        onUi { view.today = { day }; view.onNewGame = { d, _ -> modes.add(d) } }
        assertFalse(view.daily)
        val d = view.chipBounds(daily = true)
        tapAt(d.centerX(), d.centerY())
        assertTrue(view.daily)
        assertTrue(view.game!!.daily)
        assertEquals(dailySeed(day), view.game!!.seed)
        assertEquals(SnakeView.State.READY, view.state)
        val c = view.chipBounds(daily = false)
        tapAt(c.centerX(), c.centerY())
        assertFalse(view.daily)
        assertFalse(view.game!!.daily)
        assertEquals(listOf(true, false), modes)
    }

    @Test fun twoDailyGamesOnOneDayStartTheSame() {
        onUi { view.today = { java.time.LocalDate.of(2026, 10, 29) } }
        val d = view.chipBounds(daily = true)
        tapAt(d.centerX(), d.centerY())
        val food = view.game!!.food
        onUi { view.newGame() }
        assertEquals(food, view.game!!.food)
        assertEquals(Cell(4, 6), food) // the day's first apple, pinned in PinnedRulesTest
    }

    @Test fun theModeCannotChangeMidGame() {
        swipe(200f, 0f)
        val d = view.chipBounds(daily = true)
        tapAt(d.centerX(), d.centerY()) // a tap while playing pauses, as before
        assertFalse(view.daily)
        assertEquals(SnakeView.State.PAUSED, view.state)
    }

    @Test fun tabSwitchesModeFromTheKeyboard() {
        key(android.view.KeyEvent.KEYCODE_TAB)
        assertTrue(view.daily)
        key(android.view.KeyEvent.KEYCODE_TAB)
        assertFalse(view.daily)
    }

    @Test fun theModeButtonsDoNotCoverTheSnake() {
        val l = view.layout!!
        val snakeTop = l.top + view.game!!.snake.first().y * l.cell
        assertTrue(view.chipBounds(daily = true).bottom < snakeTop)
        assertTrue(view.chipBounds(daily = false).bottom < snakeTop)
    }
}

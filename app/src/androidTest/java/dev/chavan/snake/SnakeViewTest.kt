package dev.chavan.snake

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real activity on a device or emulator and drives it with real touch events. */
@RunWith(AndroidJUnit4::class)
class SnakeViewTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var view: SnakeView

    /** Records sound and vibration events instead of making them. */
    private class Recorder : Feedback {
        val events = ArrayList<String>()
        override fun eat(score: Int) { events.add("eat $score") }
        override fun crash() { events.add("crash") }
    }
    private val recorder = Recorder()

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("snake", 0).edit().clear().commit()
        rule.scenario.onActivity {
            view = it.view
            view.autoTick = false
            view.feedback = recorder
        }
    }

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
        assertTrue(l.rows >= SnakeView.COLS) // a portrait phone gives a tall board
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
        assertEquals(listOf("eat 1", "crash"), recorder.events)
        assertEquals(view.game!!.snake.size, view.particles.list.count { it.size == 0.38f }) // one piece per segment, besides the sparks
        assertEquals(1, view.best)
        val saved = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("snake", 0).getInt(MainActivity.BEST, 0)
        assertEquals(1, saved)

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
}

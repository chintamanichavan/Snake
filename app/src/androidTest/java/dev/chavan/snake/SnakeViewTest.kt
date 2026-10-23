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

    @Before fun setUp() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("snake", 0).edit().clear().commit()
        rule.scenario.onActivity {
            view = it.view
            view.autoTick = false
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
        swipe(200f, 0f)
        view.game!!.food = Cell(view.game!!.snake.first().x + 1, view.game!!.snake.first().y)
        onUi { repeat(SnakeView.COLS) { view.tick() } } // eat once, then run into the right wall
        assertEquals(SnakeView.State.OVER, view.state)
        assertEquals(1, view.best)
        val saved = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("snake", 0).getInt(MainActivity.BEST, 0)
        assertEquals(1, saved)
        tap()
        assertEquals(SnakeView.State.READY, view.state)
        assertEquals(0, view.game!!.score)
    }

    @Test fun drawsSnakeHeadAndFoodWhereTheGameSaysTheyAre() {
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
}

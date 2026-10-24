package dev.chavan.snake

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays in real time and measures how the game feels: the delay from a swipe to the snake
 * actually turning, and how many frames show the snake standing still. Results go to logcat
 * under the tag SnakeMeasure.
 */
@RunWith(AndroidJUnit4::class)
class FeelMeasureTest {
    @get:Rule val rule = ActivityScenarioRule(MainActivity::class.java)

    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var view: SnakeView

    private fun swipe(dx: Float, dy: Float) {
        val x = view.width / 2f
        val y = view.height / 2f
        val t = SystemClock.uptimeMillis()
        val events = listOf(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0),
            MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0),
            MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_UP, x + dx, y + dy, 0),
        )
        inst.runOnMainSync { events.forEach { view.dispatchTouchEvent(it) } }
        events.forEach { it.recycle() }
    }

    /** A turn that keeps the snake heading back towards the middle of the board. */
    private fun turnTowardsMiddle(): Dir {
        val g = view.game!!
        val h = g.snake.first()
        return if (g.dir.dx != 0) {
            if (h.y < g.rows / 2) Dir.DOWN else Dir.UP
        } else {
            if (h.x < g.cols / 2) Dir.RIGHT else Dir.LEFT
        }
    }

    @Test fun measureTurnDelayAndStalls() {
        rule.scenario.onActivity { view = it.view; view.feedback = Silent }
        val random = Random(7)
        val delays = ArrayList<Long>()
        swipe(0f, -200f)
        SystemClock.sleep(400)
        repeat(20) {
            SystemClock.sleep(150L + random.nextLong(250))
            if (view.state != SnakeView.State.RUNNING) return@repeat
            val turned = CountDownLatch(1)
            var at = 0L
            inst.runOnMainSync { view.onTurned = { t -> at = t; turned.countDown() } }
            val d = turnTowardsMiddle()
            val px = 60f * view.resources.displayMetrics.density
            val swipedAt = SystemClock.uptimeMillis()
            swipe(d.dx * px, d.dy * px)
            if (turned.await(1, TimeUnit.SECONDS)) delays.add(at - swipedAt)
        }
        delays.sort()
        val frames = view.runningFrames
        val stalls = view.stalledFrames
        Log.i("SnakeMeasure", "turns=${delays.size} delay ms: min=${delays.first()} median=${delays[delays.size / 2]} max=${delays.last()} mean=${delays.average().toInt()}")
        Log.i("SnakeMeasure", "frames=$frames stalled=$stalls (${"%.1f".format(100.0 * stalls / frames)}%)")
    }

    private object Silent : Feedback {
        override fun eat(score: Int) {}
        override fun crash() {}
        override fun newBest() {}
    }
}

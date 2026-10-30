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

    /** The longest game possible: a bot looping through every square until the board is full. */
    private fun fullBoardGhost(): GhostRecord {
        val g = Game(SnakeView.COLS, SnakeView.ROWS, 3)
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
        return GhostRecord.of(g)
    }

    @Test fun measureGhostLoadAndSave() {
        val dir = java.io.File(inst.targetContext.cacheDir, "measure-ghosts").apply { deleteRecursively(); mkdirs() }
        val store = GhostStore(dir)
        val r = fullBoardGhost()
        val saves = List(5) { val t = System.nanoTime(); store.save(r); (System.nanoTime() - t) / 1_000_000.0 }
        val loads = List(5) {
            val t = System.nanoTime()
            check(store.load(false, java.time.LocalDate.now(), SnakeView.COLS, SnakeView.ROWS).ghost != null)
            (System.nanoTime() - t) / 1_000_000.0
        }
        Log.i("SnakeMeasure", "longest ghost ${r.moves.length} moves: save ms ${saves.map { "%.1f".format(it) }} load+verify ms ${loads.map { "%.1f".format(it) }}")
        dir.deleteRecursively()
    }

    /**
     * Plays 8 seconds and counts frames that missed their deadline (FrameMetrics' own deadline,
     * so it holds for 60 Hz and 120 Hz screens alike). Run with and without a ghost to compare.
     */
    private fun framesMissed(withGhost: Boolean): String {
        rule.scenario.onActivity { view = it.view; view.feedback = Silent; view.autoTick = true }
        val missed = java.util.concurrent.atomic.AtomicInteger()
        val total = java.util.concurrent.atomic.AtomicInteger()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, m, _ ->
            total.incrementAndGet()
            if (m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) > m.getMetric(android.view.FrameMetrics.DEADLINE)) missed.incrementAndGet()
        }
        val ghost = if (withGhost) fullBoardGhost() else null
        rule.scenario.onActivity { a ->
            view.onNewGame = { _, _ -> }
            view.ghost = ghost
            view.newGame()
            a.window.addOnFrameMetricsAvailableListener(listener, handler)
        }
        swipe(0f, -200f)
        val random = Random(3)
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end && view.state == SnakeView.State.RUNNING) {
            SystemClock.sleep(200L + random.nextLong(300))
            if (view.state == SnakeView.State.RUNNING) {
                val d = turnTowardsMiddle()
                val px = 60f * view.resources.displayMetrics.density
                swipe(d.dx * px, d.dy * px)
            }
        }
        rule.scenario.onActivity { a -> a.window.removeOnFrameMetricsAvailableListener(listener) }
        val n = total.get()
        return "frames=$n missed deadline=${missed.get()} (${"%.2f".format(100.0 * missed.get() / n)}%)"
    }

    @Test fun measureFramesWithAndWithoutAGhost() {
        Log.i("SnakeMeasure", "no ghost: ${framesMissed(false)}")
        Log.i("SnakeMeasure", "with ghost: ${framesMissed(true)}")
    }

    private object Silent : Feedback {
        override fun eat(score: Int) {}
        override fun crash() {}
        override fun newBest() {}
    }
}

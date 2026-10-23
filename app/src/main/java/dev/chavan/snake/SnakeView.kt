package dev.chavan.snake

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** Draws the game, turns swipes into moves, and runs the tick clock. */
class SnakeView(context: Context) : View(context) {
    enum class State { READY, RUNNING, PAUSED, OVER }

    var game: Game? = null
        private set
    var layout: BoardLayout? = null
        private set
    var state = State.READY
        private set
    var best = 0

    /** Called when a game ends, with its final score. */
    var onGameOver: (Int) -> Unit = {}

    /** Tests turn this off and call tick() themselves so nothing depends on timing. */
    var autoTick = true

    /** Space kept clear of the status bar, gesture bar and camera cutout. */
    var insetTop = 0
    var insetBottom = 0

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val hudHeight = dp(72f)
    private val margin = dp(16f)

    private val board = Paint().apply { color = BOARD }
    private val checker = Paint().apply { color = CHECKER }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val food = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FOOD }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BACKGROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = dp(12f)
        letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textSize = dp(28f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textSize = dp(26f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
    }
    private val scrim = Paint().apply { color = Color.argb(170, 12, 17, 15) }
    private val rect = RectF()
    private val boardShape = Path()

    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private val swipeDistance = dp(24f)

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (state == State.RUNNING && autoTick) postDelayed(this, Game.tickMillis(game!!.score))
        }
    }

    init {
        setBackgroundColor(BACKGROUND)
        contentDescription = "Snake game board"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    fun relayout() {
        if (width == 0 || height == 0) return
        val top = insetTop + hudHeight
        val l = layoutBoard(
            margin.toInt(), top.toInt(),
            (width - 2 * margin).toInt(), (height - top - insetBottom - margin).toInt(), COLS,
        )
        // The board size only changes if the window does; keep the game going if it still fits.
        if (game?.rows != l.rows) {
            game = Game(l.cols, l.rows)
            state = State.READY
        }
        layout = l
        invalidate()
    }

    /** One step of the game. Public so tests can drive the clock. */
    fun tick() {
        val g = game ?: return
        if (state != State.RUNNING) return
        g.step()
        if (g.over) {
            state = State.OVER
            if (g.score > best) best = g.score
            onGameOver(g.score)
        }
        invalidate()
    }

    fun pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED
            removeCallbacks(ticker)
            invalidate()
        }
    }

    private fun start() {
        state = State.RUNNING
        removeCallbacks(ticker)
        if (autoTick) postDelayed(ticker, Game.tickMillis(game!!.score))
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val g = game ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; swiped = false
            }
            MotionEvent.ACTION_MOVE -> {
                // Measure from where the last swipe registered, so one long drag can make several turns.
                val d = swipeDir(e.x - downX, e.y - downY, swipeDistance)
                if (d != null) {
                    swiped = true
                    downX = e.x; downY = e.y
                    when (state) {
                        State.READY, State.PAUSED -> { g.turn(d); start() }
                        State.RUNNING -> g.turn(d)
                        State.OVER -> {}
                    }
                }
            }
            MotionEvent.ACTION_UP -> if (!swiped) {
                when (state) {
                    State.OVER -> { g.reset(); state = State.READY; invalidate() }
                    State.PAUSED -> start()
                    State.RUNNING -> pause()
                    State.READY -> {}
                }
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val g = game ?: return
        val l = layout ?: return
        drawHud(canvas, g)

        val c = l.cell.toFloat()
        val r = c * 0.18f
        rect.set(l.left.toFloat(), l.top.toFloat(), (l.left + l.width).toFloat(), (l.top + l.height).toFloat())
        canvas.drawRoundRect(rect, dp(10f), dp(10f), board)
        canvas.save()
        boardShape.reset()
        boardShape.addRoundRect(rect, dp(10f), dp(10f), Path.Direction.CW)
        canvas.clipPath(boardShape)
        for (y in 0 until l.rows) for (x in 0 until l.cols) if ((x + y) % 2 == 1) {
            val px = l.left + x * c
            val py = l.top + y * c
            canvas.drawRect(px, py, px + c, py + c, checker)
        }
        canvas.restore()

        g.food?.let {
            canvas.drawCircle(l.left + (it.x + 0.5f) * c, l.top + (it.y + 0.5f) * c, c * 0.36f, food)
        }

        // Fade from bright head to a deeper tail so direction reads at a glance.
        val n = g.snake.size
        val inset = c * 0.08f
        for (i in n - 1 downTo 0) {
            val s = g.snake[i]
            body.color = blend(HEAD, TAIL, if (n > 1) i / (n - 1f) else 0f)
            val px = l.left + s.x * c
            val py = l.top + s.y * c
            rect.set(px + inset, py + inset, px + c - inset, py + c - inset)
            canvas.drawRoundRect(rect, r, r, body)
        }
        drawEyes(canvas, l, g)

        when (state) {
            State.READY -> overlay(canvas, l, "Snake", "Swipe to start")
            State.PAUSED -> overlay(canvas, l, "Paused", "Tap or swipe to continue")
            State.OVER -> overlay(canvas, l, if (g.won) "You filled the board" else "Game over", "Score ${g.score}  ·  Tap to play again")
            State.RUNNING -> {}
        }
    }

    private fun drawHud(canvas: Canvas, g: Game) {
        val baseline = insetTop + hudHeight - dp(18f)
        val left = margin + dp(4f)
        canvas.drawText("SCORE", left, baseline - dp(32f), label)
        canvas.drawText(g.score.toString(), left, baseline, number)
        val bestText = maxOf(best, g.score).toString()
        val right = width - margin - dp(4f)
        label.textAlign = Paint.Align.RIGHT
        number.textAlign = Paint.Align.RIGHT
        canvas.drawText("BEST", right, baseline - dp(32f), label)
        canvas.drawText(bestText, right, baseline, number)
        label.textAlign = Paint.Align.LEFT
        number.textAlign = Paint.Align.LEFT
    }

    private fun drawEyes(canvas: Canvas, l: BoardLayout, g: Game) {
        val h = g.snake.first()
        val c = l.cell.toFloat()
        val cx = l.left + (h.x + 0.5f) * c
        val cy = l.top + (h.y + 0.5f) * c
        val ahead = c * 0.14f
        val apart = c * 0.18f
        val d = g.dir
        // Two eyes either side of the line of travel, nudged forward.
        for (side in listOf(-1, 1)) {
            val ex = cx + d.dx * ahead + -d.dy * apart * side
            val ey = cy + d.dy * ahead + d.dx * apart * side
            canvas.drawCircle(ex, ey, c * 0.07f, eye)
        }
    }

    private fun overlay(canvas: Canvas, l: BoardLayout, heading: String, sub: String) {
        rect.set(l.left.toFloat(), l.top.toFloat(), (l.left + l.width).toFloat(), (l.top + l.height).toFloat())
        canvas.drawRoundRect(rect, dp(10f), dp(10f), scrim)
        // Text sits in the upper third: the snake starts on the middle row and must stay visible.
        val cx = rect.centerX()
        val cy = rect.top + rect.height() / 3
        canvas.drawText(heading, cx, cy - dp(6f), title)
        canvas.drawText(sub, cx, cy + dp(24f), hint)
    }

    companion object {
        const val COLS = 16

        val BACKGROUND = Color.rgb(12, 17, 15)
        val BOARD = Color.rgb(22, 31, 27)
        val CHECKER = Color.rgb(26, 36, 31)
        val HEAD = Color.rgb(124, 224, 140)
        val TAIL = Color.rgb(38, 128, 80)
        val FOOD = Color.rgb(255, 112, 92)
        val TEXT = Color.rgb(236, 242, 238)
        val MUTED = Color.rgb(132, 150, 140)

        fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )
    }
}

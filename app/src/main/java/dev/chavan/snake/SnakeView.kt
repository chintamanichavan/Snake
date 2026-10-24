package dev.chavan.snake

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
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

    /** Time source for animation. Tests replace it to freeze or advance time. */
    var clock: () -> Long = { SystemClock.uptimeMillis() }

    /** When the last step happened and how long it is meant to last, for sliding between cells. */
    private var stepAt = 0L
    private var stepMillis = 1L

    /** Sound and vibration. Tests swap in a recorder. */
    var feedback: Feedback = PhoneFeedback(this)

    val particles = Particles()
    private var foodAt = 0L
    private var crashAt = 0L
    private var frameAt = 0L

    /** Space kept clear of the status bar, gesture bar and camera cutout. */
    var insetTop = 0
    var insetBottom = 0

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val hudHeight = dp(72f)
    private val margin = dp(16f)

    private val board = Paint().apply { color = BOARD }
    private val checker = Paint().apply { color = CHECKER }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val food = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FOOD }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG)
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
            foodAt = clock()
        }
        layout = l
        invalidate()
    }

    /** One step of the game. Public so tests can drive the clock. */
    fun tick() {
        val g = game ?: return
        if (state != State.RUNNING) return
        stepMillis = Game.tickMillis(g.score)
        val eaten = g.food
        val ate = g.step()
        stepAt = clock()
        if (ate && eaten != null) {
            particles.burst(eaten.x.toFloat(), eaten.y.toFloat(), 14, 3.2f, FOOD)
            particles.burst(eaten.x.toFloat(), eaten.y.toFloat(), 6, 2f, HEAD, size = 0.09f)
            foodAt = stepAt
            feedback.eat(g.score)
        }
        if (g.over) {
            state = State.OVER
            crashAt = stepAt
            if (g.won) {
                // A full board: confetti from every segment instead of a crash.
                g.snake.forEachIndexed { i, c -> if (i % 3 == 0) particles.burst(c.x.toFloat(), c.y.toFloat(), 5, 3f, segmentColor(i, g.snake.size)) }
                feedback.eat(g.score)
            } else {
                // The hit is half a cell ahead of the head, where the wall or body was.
                val h = g.snake.first()
                val ix = h.x + g.dir.dx * 0.5f
                val iy = h.y + g.dir.dy * 0.5f
                particles.shatter(g.snake, ix, iy) { segmentColor(it, g.snake.size) }
                particles.burst(ix, iy, 16, 6f, TEXT, size = 0.07f, lifetime = 0.35f)
                feedback.crash()
            }
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
                    // Ignore taps until the card shows, so a panicked swipe can't skip past it.
                    State.OVER -> if (clock() - crashAt >= OVERLAY_DELAY_MS) {
                        g.reset()
                        particles.clear()
                        foodAt = clock()
                        state = State.READY
                        invalidate()
                    }
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
        val now = clock()
        drawHud(canvas, g)

        val crashed = state == State.OVER && !g.won
        val shake = if (crashed) shakeOffset(now - crashAt, dp(9f)) else Pos(0f, 0f)
        canvas.save()
        canvas.translate(shake.x, shake.y)

        val c = l.cell.toFloat()
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

        val dt = ((now - frameAt).coerceIn(0L, 50L)) / 1000f
        frameAt = now
        particles.update(dt)

        // New food pops in, then breathes gently so the eye finds it.
        g.food?.let {
            val pop = popScale(now - foodAt, FOOD_POP_MS)
            val breathe = 1f + 0.05f * kotlin.math.sin(now / 260f)
            canvas.drawCircle(px(l, it.x.toFloat()), py(l, it.y.toFloat()), c * 0.36f * pop * breathe, food)
        }

        // Between steps the snake slides, so redraw every frame while it moves or anything is animating.
        val t = if (state == State.RUNNING) progress(now, stepAt, stepMillis) else 1f
        val settling = state == State.OVER && now - crashAt < OVERLAY_DELAY_MS + OVERLAY_FADE_MS
        if (state == State.RUNNING || particles.alive || now - foodAt < FOOD_POP_MS || settling) postInvalidateOnAnimation()
        val line = snakeLine(g.snake, g.leftTail, t)
        if (!crashed) drawSnake(canvas, l, g, line)

        for (p in particles.list) {
            spark.color = p.color
            spark.alpha = (255 * p.fade).toInt()
            canvas.drawCircle(px(l, p.x), py(l, p.y), c * p.size * (0.4f + 0.6f * p.fade), spark)
        }
        canvas.restore()

        val shown = if (state == State.OVER) ((now - crashAt - OVERLAY_DELAY_MS) / OVERLAY_FADE_MS.toFloat()).coerceIn(0f, 1f) else 1f
        when (state) {
            State.READY -> overlay(canvas, l, "Snake", "Swipe to start", 1f)
            State.PAUSED -> overlay(canvas, l, "Paused", "Tap or swipe to continue", 1f)
            State.OVER -> if (shown > 0f) overlay(canvas, l, if (g.won) "You filled the board" else "Game over", "Score ${g.score}  ·  Tap to play again", shown)
            State.RUNNING -> {}
        }
    }

    private fun drawSnake(canvas: Canvas, l: BoardLayout, g: Game, line: List<Pos>) {
        val c = l.cell.toFloat()

        // One rounded tube, drawn tail first so the head sits on top. Each piece fades from a
        // bright head to a deeper tail so direction reads at a glance.
        body.strokeWidth = c * 0.76f
        val n = line.size
        for (i in n - 2 downTo 0) {
            body.color = segmentColor(i, n - 1)
            val a = line[i + 1]
            val b = line[i]
            canvas.drawLine(px(l, a.x), py(l, a.y), px(l, b.x), py(l, b.y), body)
        }
        if (n == 1) {
            body.color = HEAD
            canvas.drawPoint(px(l, line[0].x), py(l, line[0].y), body)
        }
        drawEyes(canvas, l, g, line[0])
    }

    /** Segment i of n, bright at the head and deeper towards the tail. */
    private fun segmentColor(i: Int, n: Int) = blend(HEAD, TAIL, if (n > 1) i / (n - 1f) else 0f)

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

    /** Screen position of the centre of a point given in cell units. */
    private fun px(l: BoardLayout, x: Float) = l.left + (x + 0.5f) * l.cell
    private fun py(l: BoardLayout, y: Float) = l.top + (y + 0.5f) * l.cell

    private fun drawEyes(canvas: Canvas, l: BoardLayout, g: Game, head: Pos) {
        val c = l.cell.toFloat()
        val cx = px(l, head.x)
        val cy = py(l, head.y)
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

    private fun overlay(canvas: Canvas, l: BoardLayout, heading: String, sub: String, opacity: Float) {
        rect.set(l.left.toFloat(), l.top.toFloat(), (l.left + l.width).toFloat(), (l.top + l.height).toFloat())
        scrim.alpha = (170 * opacity).toInt()
        title.alpha = (255 * opacity).toInt()
        hint.alpha = (255 * opacity).toInt()
        canvas.drawRoundRect(rect, dp(10f), dp(10f), scrim)
        // Text sits in the upper third: the snake starts on the middle row and must stay visible.
        val cx = rect.centerX()
        val cy = rect.top + rect.height() / 3
        canvas.drawText(heading, cx, cy - dp(6f), title)
        canvas.drawText(sub, cx, cy + dp(24f), hint)
    }

    companion object {
        const val COLS = 16
        const val FOOD_POP_MS = 280L
        const val OVERLAY_DELAY_MS = 650L
        const val OVERLAY_FADE_MS = 250L

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

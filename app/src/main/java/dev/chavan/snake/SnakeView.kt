package dev.chavan.snake

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate

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

    /** Frames drawn while running, and how many of them showed the snake standing still. */
    var runningFrames = 0
        private set
    var stalledFrames = 0
        private set

    /** Called with the clock time whenever a step sends the snake in a new direction. For measuring. */
    var onTurned: (Long) -> Unit = {}

    /** Sound and vibration. Tests swap in a recorder. */
    var feedback: Feedback = PhoneFeedback(this)

    val particles = Particles()
    private var foodAt = 0L
    private var crashAt = 0L
    private var scoreAt = -10_000L

    /** True when the game that just ended beat the previous best. */
    var newBest = false
        private set
    private var confettiDone = false
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
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.5f) }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val ghostPath = Path()
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GHOST
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
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
    // How far a finger must travel mid-drag to turn. Low enough that a light flick counts, but
    // still well above the touch slop so a resting finger never turns by accident.
    private val swipeDistance = dp(14f)
    // Android's own "this finger moved on purpose" distance; anything less is a tap.
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (state == State.RUNNING && autoTick) postDelayed(this, Game.tickMillis(game!!.score))
        }
    }

    init {
        setBackgroundColor(BACKGROUND)
        contentDescription = "Snake game board"
        // Take keyboard focus so arrow keys work on the emulator, Chromebooks and tablets with keyboards.
        isFocusable = true
        isFocusableInTouchMode = true
        // Android would otherwise grey over the whole board to show it has focus.
        defaultFocusHighlightEnabled = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    fun relayout() {
        if (width == 0 || height == 0) return
        val top = insetTop + hudHeight
        val l = fitBoard(
            margin.toInt(), top.toInt(),
            (width - 2 * margin).toInt(), (height - top - insetBottom - margin).toInt(), COLS, ROWS, dp(8f).toInt(),
        )
        // The board is a fixed size, so the game is made once; a resized window only rescales it.
        if (game == null) newGame()
        layout = l
        // Swipes from the screen's side edges would otherwise be taken as Android's back gesture.
        // Android honours at most 200dp of each edge, so cover the middle of the board.
        val band = minOf(l.height, (200 * density).toInt())
        val mid = l.top + l.height / 2
        systemGestureExclusionRects = listOf(android.graphics.Rect(0, mid - band / 2, width, mid + band / 2))
        invalidate()
    }

    /** Starts a fresh game, with a new seed and the current ghost from its beginning. */
    fun newGame() {
        removeCallbacks(ticker)
        onNewGame(daily)
        // A daily game takes the date it starts on and keeps it, even if it runs past midnight.
        game = Game(COLS, ROWS, if (daily) dailySeed(today()) else newSeed(), daily)
        startGhost()
        particles.clear()
        newBest = false
        foodAt = clock()
        state = State.READY
        invalidate()
    }

    /** Daily challenge (the same board for everyone today) rather than classic. Chosen on the start screen. */
    var daily = false
        private set

    /** Today's date for the daily board. Tests replace it. */
    var today: () -> LocalDate = { utcToday(java.time.Clock.systemUTC()) }

    /** Called with the mode just before each new game, so the activity can set that mode's ghost and best. */
    var onNewGame: (daily: Boolean) -> Unit = {}

    /** Switches mode on the start screen only; a game under way keeps its mode. */
    fun chooseMode(daily: Boolean) {
        if (state != State.READY || daily == this.daily) return
        this.daily = daily
        newGame()
    }

    /** Where the Classic and Daily buttons sit on the start screen, below its text. */
    fun chipBounds(daily: Boolean): RectF {
        val l = layout ?: return RectF()
        val w = dp(132f)
        val h = dp(44f)
        val gap = dp(12f)
        val cx = l.left + l.width / 2f
        val top = l.top + l.height * TEXT_AT + dp(48f)
        val left = if (daily) cx + gap / 2 else cx - gap / 2 - w
        return RectF(left, top, left + w, top + h)
    }

    /**
     * The best game so far, replayed alongside the next game started. Set by the activity after
     * it has loaded and checked the file.
     */
    var ghost: GhostRecord? = null
    private var ghostRun: Replay? = null

    /** The ghost's game as it stands, or null with no ghost. */
    val ghostGame: Game? get() = ghostRun?.game

    private fun startGhost() {
        ghostRun = ghost?.replay()
    }

    /** One step of the game. Public so tests can drive the clock. */
    fun tick() {
        val g = game ?: return
        if (state != State.RUNNING) return
        stepMillis = Game.tickMillis(g.score)
        val eaten = g.food
        val before = g.dir
        val ate = g.step()
        // Lockstep: one ghost step per real step. A retake redoes a step, so it does not get one.
        ghostRun?.advance()
        stepAt = clock()
        afterStep(g, before, ate, eaten, stepAt)
    }

    /** Effects of a step, whether a normal one or a re-taken one: turning, eating, crashing, winning. */
    private fun afterStep(g: Game, before: Dir, ate: Boolean, eaten: Cell?, at: Long) {
        if (g.dir != before) onTurned(at)
        if (ate && eaten != null) {
            particles.burst(eaten.x.toFloat(), eaten.y.toFloat(), 14, 3.2f, FOOD)
            particles.burst(eaten.x.toFloat(), eaten.y.toFloat(), 6, 2f, HEAD, size = 0.09f)
            foodAt = at
            scoreAt = at
            feedback.eat(g.score)
        }
        if (g.over) {
            state = State.OVER
            crashAt = at
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
            newBest = g.score > best
            confettiDone = false
            if (newBest) {
                best = g.score
                feedback.newBest()
            }
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
                downAt = e.eventTime
            }
            // One turn per swipe, made as soon as the swipe is clear. Letting a long drag turn again
            // made sloppy diagonal swipes turn twice.
            MotionEvent.ACTION_MOVE -> if (!swiped) {
                val d = steerDir(e.x - downX, e.y - downY, heading(), swipeDistance)
                if (d != null) {
                    swiped = true
                    logInput(e, "turn $d mid-swipe")
                    steer(d)
                }
            }
            // A drag too short to turn mid-way still turns when the finger lifts. Only a touch that
            // barely moved is a tap; otherwise a quick flick would pause the game.
            MotionEvent.ACTION_UP -> if (!swiped) {
                val dx = e.x - downX
                val dy = e.y - downY
                if (swipeDir(dx, dy, touchSlop) == null && state == State.READY &&
                    (chipBounds(true).contains(e.x, e.y) || chipBounds(false).contains(e.x, e.y))
                ) {
                    logInput(e, "mode button")
                    chooseMode(chipBounds(true).contains(e.x, e.y))
                } else if (swipeDir(dx, dy, touchSlop) == null) {
                    logInput(e, "tap")
                    press()
                    performClick()
                } else {
                    val d = steerDir(dx, dy, heading(), touchSlop)
                    logInput(e, if (d == null) "ignored: not across heading ${heading()}" else "turn $d on lift")
                    d?.let { steer(it) }
                }
            } else {
                logInput(e, "lift after turn")
            }
        }
        return true
    }

    private var downAt = 0L

    /**
     * One line per input decision under the tag SnakeInput, so a swipe that feels wrong can be
     * traced from `adb logcat -s SnakeInput`: where it came from, how far it moved, what it did.
     */
    private fun logInput(e: MotionEvent, what: String) {
        val dpx = (e.x - downX) / density
        val dpy = (e.y - downY) / density
        android.util.Log.d(
            "SnakeInput",
            "%s dx=%.0fdp dy=%.0fdp %dms src=0x%x class=%d state=%s heading=%s".format(
                what, dpx, dpy, e.eventTime - downAt, e.source, e.classification, state, game?.heading,
            ),
        )
    }

    /** Two-finger trackpad swipes and mouse wheels arrive as scrolling, not touches. */
    private val scroll = ScrollReader()

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val d = scroll.read(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL), e.eventTime)
            if (d != null) {
                android.util.Log.d("SnakeInput", "scroll turn $d state=$state")
                steer(d)
            }
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        val d = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Dir.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Dir.RIGHT
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { press(); return true }
            KeyEvent.KEYCODE_TAB -> { chooseMode(!daily); return true }
            else -> return super.onKeyDown(keyCode, e)
        }
        steer(d)
        return true
    }

    /** A fresh seed for each classic game; only its value needs recording, not where it came from. */
    private fun newSeed() = kotlin.random.Random.nextLong()

    /** The way turns are measured against, or null before the game starts when any way goes. */
    private fun heading(): Dir? = if (state == State.READY) null else game?.heading

    /** A swipe, scroll or arrow key: start, resume or turn. Every input method comes through here. */
    fun steer(d: Dir) {
        val g = game ?: return
        when (state) {
            State.READY -> { g.aim(d); start() }
            State.PAUSED -> { g.turn(d); start() }
            State.RUNNING -> {
                // A swipe just after a step redoes that step in the new direction instead of
                // waiting a whole tick. The slide timing is kept, so the head turns the corner in motion.
                val eaten = g.food
                val before = g.dir
                val score = g.score
                if (progress(clock(), stepAt, stepMillis) < RETAKE_WINDOW && g.retake(d)) {
                    afterStep(g, before, g.score > score, eaten, clock())
                } else {
                    g.turn(d)
                }
            }
            State.OVER -> {}
        }
    }

    /** A tap or the space bar: pause, resume, or play again once the game-over card is up. */
    fun press() {
        val g = game ?: return
        when (state) {
            // Ignore presses until the card shows, so a panicked swipe can't skip past it.
            State.OVER -> if (clock() - crashAt >= OVERLAY_DELAY_MS) newGame()
            State.PAUSED -> start()
            State.RUNNING -> pause()
            State.READY -> {}
        }
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
        if (state == State.RUNNING && !g.fresh) {
            runningFrames++
            if (t >= 1f) stalledFrames++
        }
        val settling = state == State.OVER && now - crashAt < OVERLAY_DELAY_MS + OVERLAY_FADE_MS
        if (state == State.RUNNING || particles.alive || now - foodAt < FOOD_POP_MS || settling) postInvalidateOnAnimation()
        val line = snakeLine(g.snake, g.leftTail, t)
        ghostGame?.let { drawGhost(canvas, l, it, t) }
        if (!crashed) drawSnake(canvas, l, g, line)

        for (p in particles.list) {
            spark.color = p.color
            spark.alpha = (255 * p.fade).toInt()
            canvas.drawCircle(px(l, p.x), py(l, p.y), c * p.size * (0.4f + 0.6f * p.fade), spark)
        }
        canvas.restore()

        val shown = if (state == State.OVER) ((now - crashAt - OVERLAY_DELAY_MS) / OVERLAY_FADE_MS.toFloat()).coerceIn(0f, 1f) else 1f
        if (state == State.OVER && newBest && shown > 0f && !confettiDone) {
            confettiDone = true
            celebrate(l)
            postInvalidateOnAnimation()
        }
        when (state) {
            State.READY -> {
                overlay(canvas, l, if (daily) "Daily challenge" else "Snake", "Swipe to start", 1f)
                drawChip(canvas, false, "Classic")
                drawChip(canvas, true, "Daily · " + today().format(java.time.format.DateTimeFormatter.ofPattern("d MMM")))
            }
            State.PAUSED -> overlay(canvas, l, "Paused", "Tap or swipe to continue", 1f)
            State.OVER -> if (shown > 0f) {
                val heading = when {
                    g.won -> "You filled the board"
                    newBest -> "New best!"
                    else -> "Game over"
                }
                overlay(canvas, l, heading, "Score ${g.score}  ·  Tap to play again", shown)
            }
            State.RUNNING -> {}
        }
    }

    /**
     * The ghost as one pale, see-through tube under your snake. It is a single path so the
     * translucent pieces don't overlap into darker spots at the joints. Once its game is over it
     * stays where it crashed, fainter.
     */
    private fun drawGhost(canvas: Canvas, l: BoardLayout, gg: Game, t: Float) {
        val line = snakeLine(gg.snake, gg.leftTail, if (gg.over) 1f else t)
        ghostPath.reset()
        line.forEachIndexed { i, p ->
            if (i == 0) ghostPath.moveTo(px(l, p.x), py(l, p.y)) else ghostPath.lineTo(px(l, p.x), py(l, p.y))
        }
        if (line.size == 1) ghostPath.lineTo(px(l, line[0].x) + 0.01f, py(l, line[0].y))
        ghostPaint.strokeWidth = l.cell * 0.76f
        ghostPaint.alpha = if (gg.over) 40 else 85
        canvas.drawPath(ghostPath, ghostPaint)
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

    /** Confetti in the game's colours, falling from across the top of the board. */
    private fun celebrate(l: BoardLayout) {
        val colors = intArrayOf(HEAD, FOOD, TEXT, TAIL)
        for (i in 0 until 7) {
            val x = (i + 0.5f) * l.cols / 7f - 0.5f
            particles.burst(x, l.rows * 0.25f, 9, 4f, colors[i % colors.size], size = 0.11f, lifetime = 1.1f)
        }
    }

    private fun drawHud(canvas: Canvas, g: Game) {
        val now = clock()
        val baseline = insetTop + hudHeight - dp(18f)
        val left = margin + dp(4f)
        canvas.drawText("SCORE", left, baseline - dp(32f), label)
        // The score swells briefly and flashes the snake's colour each time it goes up.
        val bump = bumpScale(now - scoreAt)
        canvas.save()
        canvas.scale(bump, bump, left, baseline)
        number.color = blend(TEXT, HEAD, (bump - 1f) / 0.3f)
        canvas.drawText(g.score.toString(), left, baseline, number)
        canvas.restore()
        number.color = TEXT

        // The ghost's score in the middle, so you can see at a glance who is ahead.
        ghostGame?.let {
            label.textAlign = Paint.Align.CENTER
            number.textAlign = Paint.Align.CENTER
            number.color = GHOST
            canvas.drawText("GHOST", width / 2f, baseline - dp(32f), label)
            canvas.drawText(it.score.toString(), width / 2f, baseline, number)
            label.textAlign = Paint.Align.LEFT
            number.textAlign = Paint.Align.LEFT
            number.color = TEXT
        }

        // Once this game passes the old best, BEST becomes NEW BEST and follows the score.
        val beating = g.score > best || newBest
        val bestText = maxOf(best, g.score).toString()
        val right = width - margin - dp(4f)
        label.textAlign = Paint.Align.RIGHT
        number.textAlign = Paint.Align.RIGHT
        if (beating) label.color = HEAD
        canvas.drawText(if (beating) "NEW BEST" else "BEST", right, baseline - dp(32f), label)
        canvas.save()
        if (g.score > best) canvas.scale(bump, bump, right, baseline)
        if (beating) number.color = HEAD
        canvas.drawText(bestText, right, baseline, number)
        canvas.restore()
        label.color = MUTED
        number.color = TEXT
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

    /** A start-screen mode button: filled when chosen, outlined when not. */
    private fun drawChip(canvas: Canvas, forDaily: Boolean, text: String) {
        val b = chipBounds(forDaily)
        val chosen = forDaily == daily
        chipPaint.style = if (chosen) Paint.Style.FILL else Paint.Style.STROKE
        chipPaint.color = if (chosen) HEAD else MUTED
        canvas.drawRoundRect(b, b.height() / 2, b.height() / 2, chipPaint)
        chipText.color = if (chosen) BACKGROUND else TEXT
        canvas.drawText(text, b.centerX(), b.centerY() + chipText.textSize / 3, chipText)
    }

    private fun overlay(canvas: Canvas, l: BoardLayout, heading: String, sub: String, opacity: Float) {
        rect.set(l.left.toFloat(), l.top.toFloat(), (l.left + l.width).toFloat(), (l.top + l.height).toFloat())
        scrim.alpha = (170 * opacity).toInt()
        title.alpha = (255 * opacity).toInt()
        hint.alpha = (255 * opacity).toInt()
        canvas.drawRoundRect(rect, dp(10f), dp(10f), scrim)
        // Text (and the mode buttons below it) sit in the upper quarter: the snake starts on the
        // middle row and must stay visible.
        val cx = rect.centerX()
        val cy = rect.top + rect.height() * TEXT_AT
        canvas.drawText(heading, cx, cy - dp(6f), title)
        canvas.drawText(sub, cx, cy + dp(24f), hint)
    }

    companion object {
        // Fixed for every phone, so classic boards, daily boards and ghosts all line up.
        const val COLS = 16
        const val ROWS = 24
        const val FOOD_POP_MS = 280L
        private const val TEXT_AT = 0.22f
        /** How early in a step a swipe may redo it. Later than this the head would visibly jump. */
        const val RETAKE_WINDOW = 0.25f
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
        val GHOST = Color.rgb(214, 226, 255)

        fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )
    }
}

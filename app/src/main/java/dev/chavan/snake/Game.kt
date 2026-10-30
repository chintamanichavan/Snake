package dev.chavan.snake

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

enum class Dir(val dx: Int, val dy: Int, val letter: Char) {
    UP(0, -1, 'U'), DOWN(0, 1, 'D'), LEFT(-1, 0, 'L'), RIGHT(1, 0, 'R');

    fun isOpposite(other: Dir) = dx == -other.dx && dy == -other.dy

    companion object {
        fun of(letter: Char): Dir = values().firstOrNull { it.letter == letter }
            ?: throw IllegalArgumentException("not a direction: '$letter'")
    }
}

data class Cell(val x: Int, val y: Int) {
    operator fun plus(d: Dir) = Cell(x + d.dx, y + d.dy)
}

/**
 * The seed for a day's board: the day number, in UTC so that everyone everywhere is on the same
 * board at the same moment. A game keeps the date it started on, even across midnight.
 */
fun dailySeed(date: LocalDate): Long = date.toEpochDay()

/** Today's date for the daily board: the UTC date, whatever time zone the phone is in. */
fun utcToday(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))

/** The rules of Snake on a cols x rows board with solid walls. No Android code, so it is unit-tested directly. */
class Game(val cols: Int, val rows: Int, seed: Long, val daily: Boolean = false) {
    /** This game's seed. With the moves made, it is all that is needed to replay the game exactly. */
    var seed = seed
        private set
    private var rng = Rng(seed)

    /** Head first. */
    val snake = ArrayDeque<Cell>()
    var dir = Dir.RIGHT
        private set
    var food: Cell? = null
        internal set // tests place food directly
    var score = 0
        private set
    var over = false
        private set
    var won = false
        private set

    /** The cell the tail moved off on the last step, or null if the snake grew. Drawing slides the tail out of it. */
    var leftTail: Cell? = null
        private set

    // Turns made between ticks are queued, so two fast swipes (up, then left) both happen
    // instead of the second overwriting the first. Each is checked against the one before it,
    // which stops a quick pair of turns from reversing the snake into itself.
    private val turns = ArrayDeque<Dir>()

    // The game so far as plain text: the facing at the start (R, or L if aim() turned the snake
    // end to end), then the direction of every step, the crashing one included. With the seed,
    // that is enough to replay the game exactly.
    private val moves = StringBuilder()
    val recording: String get() = moves.toString()

    init {
        require(cols >= 5 && rows >= 5) { "board too small: ${cols}x$rows" }
        reset(seed)
    }

    /** Starts a new game. Each game gets its own seed, set before the first apple is drawn. */
    fun reset(seed: Long) {
        this.seed = seed
        rng = Rng(seed)
        snake.clear()
        val y = rows / 2
        for (i in 0 until 3) snake.addLast(Cell(cols / 4 + 2 - i, y))
        dir = Dir.RIGHT
        leftTail = null
        retakeable = false
        fresh = true
        moves.setLength(0)
        turns.clear()
        score = 0
        over = false
        won = false
        food = nextFood()
    }

    /** True until the first step, while the snake can still be pointed any way, including backwards. */
    var fresh = true
        private set

    /**
     * Sets the starting direction. Before the first step a "reverse" is allowed: the snake is turned
     * end to end, so every swipe can start the game. Once moving, this is an ordinary turn.
     */
    fun aim(d: Dir) {
        if (!fresh) return turn(d)
        if (d.isOpposite(dir)) {
            snake.reverse()
            dir = d
        } else {
            turn(d)
        }
    }

    /** True while the last step was a plain move that retake() may redo. */
    private var retakeable = false

    /**
     * Redoes the last step in direction d, as if the turn had arrived in time. Swipes made just
     * after a step would otherwise wait a whole tick. Only for a plain move (no eating, no crash),
     * once per step, with no other turn waiting. Returns whether it happened.
     */
    fun retake(d: Dir): Boolean {
        val tail = leftTail
        if (!retakeable || tail == null || turns.isNotEmpty() || d == dir || d.isOpposite(dir)) return false
        snake.removeFirst()
        snake.addLast(tail)
        moves.setLength(moves.length - 1) // the redone step replaces the one it undoes
        dir = d
        step()
        retakeable = false
        return true
    }

    /** The way the snake will be heading once queued turns are done; new turns are relative to this. */
    val heading get() = turns.lastOrNull() ?: dir

    fun turn(d: Dir) {
        if (over || turns.size >= 2) return
        val last = turns.lastOrNull() ?: dir
        if (d == last || d.isOpposite(last)) return
        turns.addLast(d)
    }

    /** Replay only: before the first step, face the way a recording started. */
    internal fun face(d: Dir) {
        if (fresh && d.isOpposite(dir)) {
            snake.reverse()
            dir = d
        }
    }

    /** Replay only: take one step in a recorded direction, which was already checked when it was played. */
    internal fun force(d: Dir) {
        turns.clear()
        turns.addLast(d)
        step()
    }

    /** Advances one tick. Returns true if the snake ate. */
    fun step(): Boolean {
        if (over) return false
        if (fresh) moves.append(dir.letter)
        fresh = false
        retakeable = false
        turns.removeFirstOrNull()?.let { dir = it }
        moves.append(dir.letter)
        val next = snake.first() + dir
        val eats = next == food
        if (next.x !in 0 until cols || next.y !in 0 until rows) {
            over = true
            return false
        }
        // The tail moves away this tick unless the snake grows, so stepping onto it is safe.
        val body = if (eats) snake else snake.subList(0, snake.size - 1)
        if (next in body) {
            over = true
            return false
        }
        snake.addFirst(next)
        if (eats) {
            score++
            food = nextFood()
            if (food == null) {
                won = true
                over = true
            }
            leftTail = null
        } else {
            leftTail = snake.removeLast()
            retakeable = true
        }
        return eats
    }

    /** The next apple: in daily mode the score-th apple of the day, otherwise any free square. */
    private fun nextFood(): Cell? = if (daily) dailyCell(score) else randomEmptyCell()

    /**
     * Apple k of a daily board. It is drawn from all squares by a stream of its own, seeded by the
     * day and k, so every player's k-th apple lands on the same square whenever that square is
     * free, however their snakes differ. If the snake covers it, draw again up to 8 times, then
     * take the next free square in reading order: deterministic, and it always ends.
     */
    internal fun dailyCell(k: Int): Cell? {
        val total = cols * rows
        val taken = snake.toHashSet()
        if (taken.size >= total) return null
        val r = Rng(seed xor (k.toLong() * -0x61c8864680b583ebL))
        var i = 0
        repeat(8) {
            i = r.nextInt(total)
            val c = Cell(i % cols, i / cols)
            if (c !in taken) return c
        }
        repeat(total) {
            i = (i + 1) % total
            val c = Cell(i % cols, i / cols)
            if (c !in taken) return c
        }
        return null
    }

    private fun randomEmptyCell(): Cell? {
        val taken = snake.toHashSet()
        val free = cols * rows - taken.size
        if (free == 0) return null
        var n = rng.nextInt(free)
        for (y in 0 until rows) for (x in 0 until cols) {
            val c = Cell(x, y)
            if (c !in taken && n-- == 0) return c
        }
        return null
    }

    companion object {
        /** Milliseconds per tick: starts relaxed, speeds up as the snake grows, then levels off. */
        fun tickMillis(score: Int) = maxOf(70L, 160L - score * 4L)
    }
}

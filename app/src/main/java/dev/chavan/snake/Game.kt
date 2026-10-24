package dev.chavan.snake

import kotlin.random.Random

enum class Dir(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    fun isOpposite(other: Dir) = dx == -other.dx && dy == -other.dy
}

data class Cell(val x: Int, val y: Int) {
    operator fun plus(d: Dir) = Cell(x + d.dx, y + d.dy)
}

/** The rules of Snake on a cols x rows board with solid walls. No Android code, so it is unit-tested directly. */
class Game(val cols: Int, val rows: Int, private val random: Random = Random.Default) {
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

    init {
        require(cols >= 5 && rows >= 5) { "board too small: ${cols}x$rows" }
        reset()
    }

    fun reset() {
        snake.clear()
        val y = rows / 2
        for (i in 0 until 3) snake.addLast(Cell(cols / 4 + 2 - i, y))
        dir = Dir.RIGHT
        leftTail = null
        turns.clear()
        score = 0
        over = false
        won = false
        food = randomEmptyCell()
    }

    fun turn(d: Dir) {
        if (over || turns.size >= 2) return
        val last = turns.lastOrNull() ?: dir
        if (d == last || d.isOpposite(last)) return
        turns.addLast(d)
    }

    /** Advances one tick. Returns true if the snake ate. */
    fun step(): Boolean {
        if (over) return false
        turns.removeFirstOrNull()?.let { dir = it }
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
            food = randomEmptyCell()
            if (food == null) {
                won = true
                over = true
            }
            leftTail = null
        } else {
            leftTail = snake.removeLast()
        }
        return eats
    }

    private fun randomEmptyCell(): Cell? {
        val taken = snake.toHashSet()
        val free = cols * rows - taken.size
        if (free == 0) return null
        var n = random.nextInt(free)
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

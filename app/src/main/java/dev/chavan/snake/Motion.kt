package dev.chavan.snake

/** A point in cell units: (2.5, 3) is halfway between the centres of cells (2,3) and (3,3). */
data class Pos(val x: Float, val y: Float)

fun lerp(a: Cell, b: Cell, t: Float) = Pos(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/**
 * The snake's centre line part-way through a move, head first. t = 0 is where it was before the
 * last step, t = 1 where it is now. The head slides into its new cell, the tail slides out of the
 * cell it left, and every point in between is a cell centre, so the body bends exactly at the
 * corners instead of cutting across them. The line is always (length - 1) cells long.
 */
fun snakeLine(snake: List<Cell>, leftTail: Cell?, t: Float): List<Pos> {
    if (snake.size == 1) return listOf(lerp(leftTail ?: snake[0], snake[0], t))
    val line = ArrayList<Pos>(snake.size + 1)
    line.add(lerp(snake[1], snake[0], t))
    for (i in 1 until snake.size) line.add(lerp(snake[i], snake[i], 0f))
    line.add(lerp(snake.last(), leftTail ?: snake.last(), 1 - t))
    return line
}

/** How far through the current tick we are, from 0 to 1. */
fun progress(now: Long, stepAt: Long, tickMillis: Long): Float =
    ((now - stepAt).toFloat() / tickMillis).coerceIn(0f, 1f)

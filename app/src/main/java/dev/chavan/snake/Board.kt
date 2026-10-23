package dev.chavan.snake

import kotlin.math.abs

/** Where the board sits on screen, in pixels. Cells are square and whole pixels so the grid stays crisp. */
data class BoardLayout(val cols: Int, val rows: Int, val cell: Int, val left: Int, val top: Int) {
    val width get() = cols * cell
    val height get() = rows * cell
}

/**
 * Fits a board with a fixed number of columns into the given area, taking as many rows as fit,
 * and centres it. The column count is fixed so the game feels the same on every phone width.
 */
fun layoutBoard(areaLeft: Int, areaTop: Int, areaWidth: Int, areaHeight: Int, cols: Int): BoardLayout {
    val cell = maxOf(1, areaWidth / cols)
    val rows = maxOf(5, areaHeight / cell)
    val left = areaLeft + (areaWidth - cols * cell) / 2
    val top = areaTop + maxOf(0, (areaHeight - rows * cell) / 2)
    return BoardLayout(cols, rows, cell, left, top)
}

/** Reads a finger movement as a direction once it has travelled minDistance along its main axis. */
fun swipeDir(dx: Float, dy: Float, minDistance: Float): Dir? {
    if (abs(dx) < minDistance && abs(dy) < minDistance) return null
    return if (abs(dx) > abs(dy)) {
        if (dx > 0) Dir.RIGHT else Dir.LEFT
    } else {
        if (dy > 0) Dir.DOWN else Dir.UP
    }
}

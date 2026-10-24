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

/**
 * Turns trackpad and mouse-wheel scrolling into swipes. Scroll arrives in many small pieces, so it
 * is added up until it is big enough to count; a pause of gapMs starts a fresh gesture.
 * Android reports scroll as the way the content should move, the opposite of the fingers for a
 * horizontal swipe, so h is flipped to give the direction the fingers went (with natural scrolling).
 */
class ScrollReader(private val threshold: Float = 1f, private val gapMs: Long = 250L) {
    private var h = 0f
    private var v = 0f
    private var lastAt = Long.MIN_VALUE
    private var fired = false

    fun read(hScroll: Float, vScroll: Float, at: Long): Dir? {
        if (lastAt == Long.MIN_VALUE || at - lastAt > gapMs) { h = 0f; v = 0f; fired = false }
        lastAt = at
        if (fired) return null // one turn per gesture, however long it runs on
        h -= hScroll
        v += vScroll
        val d = swipeDir(h, v, threshold) ?: return null
        fired = true
        return d
    }
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

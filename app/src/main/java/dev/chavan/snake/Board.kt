package dev.chavan.snake

import kotlin.math.abs

/** Where the board sits on screen, in pixels. Cells are square and whole pixels so the grid stays crisp. */
data class BoardLayout(val cols: Int, val rows: Int, val cell: Int, val left: Int, val top: Int) {
    val width get() = cols * cell
    val height get() = rows * cell
}

/**
 * Fits a board of exactly cols x rows into the area, with square cells as large as both sides
 * allow, centred. The size never changes with the screen, so a game (or a ghost of one) is the same
 * on every phone. Cells never go below minCell: on a window too small for that, the board starts
 * at the area's corner and runs past it rather than becoming unreadable.
 */
fun fitBoard(areaLeft: Int, areaTop: Int, areaWidth: Int, areaHeight: Int, cols: Int, rows: Int, minCell: Int): BoardLayout {
    val cell = maxOf(minCell, minOf(areaWidth / cols, areaHeight / rows))
    val left = areaLeft + maxOf(0, (areaWidth - cols * cell) / 2)
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

/**
 * Reads a finger movement as a turn for a snake heading the given way. Only the part of the
 * movement across the heading counts, since those are the only turns possible: moving up, a swipe
 * that is mostly "down" but a little right means right. Before the snake moves (heading null)
 * every direction is allowed, so the larger part wins.
 */
fun steerDir(dx: Float, dy: Float, heading: Dir?, minDistance: Float): Dir? = when {
    heading == null -> swipeDir(dx, dy, minDistance)
    heading.dx != 0 -> if (abs(dy) < minDistance) null else if (dy > 0) Dir.DOWN else Dir.UP
    else -> if (abs(dx) < minDistance) null else if (dx > 0) Dir.RIGHT else Dir.LEFT
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

package dev.chavan.snake

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {
    @Test fun burstMakesSparksThatSpreadAndFadeAway() {
        val p = Particles(Random(2))
        p.burst(5f, 5f, 12, 3f, 0)
        assertEquals(12, p.list.size)
        p.update(0.1f)
        assertTrue(p.list.all { it.x != 5f || it.y != 5f })
        assertTrue(p.list.all { it.fade < 1f })
        repeat(10) { p.update(0.1f) }
        assertFalse(p.alive)
    }

    @Test fun sparksSlowDown() {
        val p = Particles(Random(2))
        p.burst(0f, 0f, 1, 3f, 0)
        val s = p.list[0]
        val v0 = abs(s.vx) + abs(s.vy)
        p.update(0.1f)
        assertTrue(abs(s.vx) + abs(s.vy) < v0)
    }

    @Test fun popStartsAtZeroOvershootsAndSettlesAtOne() {
        assertEquals(0f, popScale(0, 300), 1e-4f)
        assertTrue((1..299).any { popScale(it.toLong(), 300) > 1.05f })
        assertEquals(1f, popScale(300, 300), 1e-4f)
        assertEquals(1f, popScale(5_000, 300), 1e-4f)
    }

    @Test fun toneHasTheRightLengthAndNoClicks() {
        val s = tone(660f, 990f, 70, 44_100)
        assertEquals(44_100 * 70 / 1000, s.size)
        // Starts and ends silent, so the speaker does not click.
        assertTrue(abs(s.first().toInt()) < 200)
        assertTrue(abs(s.last().toInt()) < 200)
        assertTrue(s.maxOf { abs(it.toInt()) } in 5_000..Short.MAX_VALUE.toInt())
    }

    @Test fun eatPitchClimbsThenWraps() {
        assertEquals(660f, eatPitch(1), 0.01f)
        assertTrue(eatPitch(2) > eatPitch(1))
        assertEquals(1320f, eatPitch(8), 0.1f)
        assertEquals(eatPitch(1), eatPitch(9), 0.01f)
    }

    @Test fun shakeStaysWithinAmplitudeAndStops() {
        for (ms in 0L until SHAKE_MS) {
            val o = shakeOffset(ms, 10f)
            assertTrue(abs(o.x) <= 10f && abs(o.y) <= 10f)
        }
        assertEquals(Pos(0f, 0f), shakeOffset(SHAKE_MS, 10f))
        assertEquals(Pos(0f, 0f), shakeOffset(-5, 10f))
        // It dies down: late wobble is smaller than early wobble.
        val early = (0L..60L).maxOf { abs(shakeOffset(it, 10f).x) }
        val late = (350L..449L).maxOf { abs(shakeOffset(it, 10f).x) }
        assertTrue(late < early / 3)
    }

    @Test fun shatterMakesOnePiecePerCellFlyingAwayFromTheHead() {
        val p = Particles(Random(4))
        val snake = listOf(Cell(5, 5), Cell(4, 5), Cell(3, 5), Cell(2, 5))
        p.shatter(snake, 5.5f, 5f) { it * 10 } // hit a wall just right of the head
        assertEquals(4, p.list.size)
        assertEquals(listOf(0, 10, 20, 30), p.list.map { it.color })
        assertEquals(snake.map { Pos(it.x.toFloat(), it.y.toFloat()) }, p.list.map { Pos(it.x, it.y) })
        // Everything lies left of the impact, so on average the pieces go left, the head fastest.
        assertTrue(p.list.sumOf { it.vx.toDouble() } < 0)
        assertTrue(p.list[0].vx < p.list[3].vx)
    }

    @Test fun bumpSwellsThenReturnsToNormal() {
        assertEquals(1f, bumpScale(0), 1e-6f)
        assertEquals(1.3f, bumpScale(110), 1e-3f)
        assertEquals(1f, bumpScale(220), 0f)
        assertEquals(1f, bumpScale(-50), 0f)
    }
}

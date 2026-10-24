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
}

package dev.chavan.snake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** A spark. Position and speed are in cell units so the effect looks the same on every screen. */
class Particle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    val color: Int, val size: Float, val lifetime: Float,
) {
    var age = 0f
    val fade get() = (1f - age / lifetime).coerceIn(0f, 1f)
}

/** Short-lived sparks for eating and crashing. No Android code, so it is unit-tested directly. */
class Particles(private val random: Random = Random.Default) {
    val list = ArrayList<Particle>()
    val alive get() = list.isNotEmpty()

    /** Sprays count sparks outward from (x, y) in every direction. */
    fun burst(x: Float, y: Float, count: Int, speed: Float, color: Int, size: Float = 0.12f, lifetime: Float = 0.45f) {
        repeat(count) { i ->
            // Evenly spread angles with a little jitter look fuller than purely random ones.
            val a = (i + random.nextFloat() * 0.6f) / count * 2 * PI.toFloat()
            val v = speed * (0.5f + random.nextFloat())
            list.add(Particle(x, y, cos(a) * v, sin(a) * v, color, size * (0.7f + random.nextFloat() * 0.6f), lifetime))
        }
    }

    fun add(p: Particle) = list.add(p)

    /**
     * Breaks the snake into one piece per cell. Pieces fly away from the point of impact (ix, iy),
     * the ones nearest it fastest, so the crash reads as a hit at the front. color(i) gives segment i's colour.
     */
    fun shatter(snake: List<Cell>, ix: Float, iy: Float, cellRadius: Float = 0.38f, lifetime: Float = 0.9f, color: (Int) -> Int) {
        snake.forEachIndexed { i, c ->
            var dx = c.x - ix
            var dy = c.y - iy
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d > 0f) { dx /= d; dy /= d }
            val push = 10f / (1f + 0.2f * i)
            val jitter = { (random.nextFloat() - 0.5f) * 3f }
            list.add(Particle(c.x.toFloat(), c.y.toFloat(), dx * push + jitter(), dy * push + jitter(), color(i), cellRadius, lifetime))
        }
    }

    /** Moves every spark on by dt seconds, slowing it down, and drops the ones that have faded out. */
    fun update(dt: Float) {
        val drag = exp(-4f * dt)
        val it = list.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.lifetime) { it.remove(); continue }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= drag
            p.vy *= drag
        }
    }

    fun clear() = list.clear()
}

/** How far to nudge the board ageMs after a crash: a quick shake that dies out by SHAKE_MS. */
fun shakeOffset(ageMs: Long, amplitude: Float): Pos {
    if (ageMs < 0 || ageMs >= SHAKE_MS) return Pos(0f, 0f)
    val a = amplitude * exp(-ageMs / 110f)
    return Pos(a * sin(ageMs / 14f), a * cos(ageMs / 19f))
}

const val SHAKE_MS = 450L

/** A quick swell and settle for a number that just changed: 1 -> 1.3 -> 1 over durationMs. */
fun bumpScale(ageMs: Long, durationMs: Long = 220L): Float {
    if (ageMs < 0 || ageMs >= durationMs) return 1f
    return 1f + 0.3f * sin(PI.toFloat() * ageMs / durationMs)
}

/** Grows from 0 to 1 over durationMs, overshooting a little before settling, like something popping in. */
fun popScale(ageMs: Long, durationMs: Long): Float {
    val t = (ageMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val s = 1.7f
    val u = t - 1f
    return 1f + (s + 1f) * u * u * u + s * u * u
}

/**
 * A short tone as 16-bit mono samples: a sine that glides from startHz to endHz and dies away.
 * A soft attack and a decay to silence stop the speaker clicking at either end.
 */
fun tone(startHz: Float, endHz: Float, ms: Int, sampleRate: Int = 44_100, volume: Float = 0.35f): ShortArray {
    val n = sampleRate * ms / 1000
    val out = ShortArray(n)
    var phase = 0.0
    val attack = sampleRate * 0.004
    for (i in 0 until n) {
        val t = i.toDouble() / n
        val hz = startHz + (endHz - startHz) * t
        phase += 2 * PI * hz / sampleRate
        val envelope = minOf(1.0, i / attack) * (1 - t) * (1 - t)
        out[i] = (sin(phase) * envelope * volume * Short.MAX_VALUE).toInt().toShort()
    }
    return out
}

/** Eating blips climb a major scale as the score grows, then start again an octave lower. */
fun eatPitch(score: Int): Float {
    val steps = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
    val semis = steps[(score - 1).coerceAtLeast(0) % steps.size]
    return 660f * Math.pow(2.0, semis / 12.0).toFloat()
}

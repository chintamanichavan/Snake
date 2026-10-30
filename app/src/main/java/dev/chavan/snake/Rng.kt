package dev.chavan.snake

/**
 * SplitMix64 (Steele, Lea and Flood, 2014). Written out here rather than using kotlin.random,
 * which only promises the same sequence within one Kotlin version: daily boards and saved ghosts
 * must come out the same on every build and every phone.
 */
class Rng(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9e3779b97f4a7c15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xbf58476d1ce4e5b9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94d049bb133111eb
        return z xor (z ushr 31)
    }

    /** A number in 0 until bound. Draws above the last whole multiple of bound are redrawn, so no value is favoured. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive: $bound" }
        val b = bound.toLong()
        val limit = Long.MAX_VALUE - Long.MAX_VALUE % b
        while (true) {
            val x = nextLong() ushr 1
            if (x < limit) return (x % b).toInt()
        }
    }
}

package dev.chavan.snake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RngTest {
    // Expected values come from an independent Python SplitMix64; seed 0 matches the published
    // reference output 0xe220a8397b1dcdaf. If these change, every saved ghost and daily board breaks.
    @Test fun matchesTheReferenceSequence() {
        val zero = Rng(0)
        assertEquals(-2152535657050944081L, zero.nextLong())
        assertEquals(7960286522194355700L, zero.nextLong())
        assertEquals(487617019471545679L, zero.nextLong())
        val answer = Rng(42)
        assertEquals(-4767286540954276203L, answer.nextLong())
        assertEquals(2949826092126892291L, answer.nextLong())
        assertEquals(5139283748462763858L, answer.nextLong())
    }

    @Test fun nextIntStaysInRangeAndCoversIt() {
        val r = Rng(9)
        val seen = IntArray(7)
        repeat(7_000) {
            val n = r.nextInt(7)
            assertTrue(n in 0 until 7)
            seen[n]++
        }
        assertTrue(seen.all { it in 850..1150 }) // each value about 1000 times
    }

    @Test(expected = IllegalArgumentException::class)
    fun nextIntRejectsAnEmptyRange() {
        Rng(1).nextInt(0)
    }

    @Test fun nextIntAtTheEdgesOfItsRange() {
        val r = Rng(3)
        repeat(20) { assertEquals(0, r.nextInt(1)) }
        repeat(20) { assertTrue(r.nextInt(Int.MAX_VALUE) in 0 until Int.MAX_VALUE) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun nextIntRejectsANegativeRange() {
        Rng(1).nextInt(-1)
    }
}

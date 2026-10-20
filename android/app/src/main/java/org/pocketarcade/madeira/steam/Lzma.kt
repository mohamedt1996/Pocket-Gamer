// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// LZMA1 decoder for Steam's "VZ" chunk container, written from the LZMA
// specification (Igor Pavlov's LZMA SDK, public domain). Decodes into a flat
// buffer of known size, so the whole output is the dictionary.

package org.pocketarcade.madeira.steam

class LzmaException(message: String) : Exception(message)

object Lzma {
    private const val NUM_STATES = 12
    private const val POS_BITS_MAX = 4
    private const val START_POS_MODEL = 4
    private const val END_POS_MODEL = 14
    private const val FULL_DISTANCES = 128
    private const val ALIGN_BITS = 4
    private const val LEN_TO_POS_STATES = 4
    private const val MATCH_MIN = 2

    /**
     * Decodes a raw LZMA1 stream with its 5 property bytes into exactly [size] bytes
     * (an end marker before that ends the output early).
     */
    fun decode(props: ByteArray, input: ByteArray, inOffset: Int, inLength: Int, size: Int): ByteArray {
        if (props.size < 5) throw LzmaException("properties")
        var d = props[0].toInt() and 0xFF
        if (d >= 9 * 5 * 5) throw LzmaException("properties")
        val lc = d % 9; d /= 9
        val lp = d % 5
        val pb = d / 5
        val out = ByteArray(size)
        val n = Decoder(lc, lp, pb, input, inOffset, inOffset + inLength).run(out)
        return if (n == size) out else out.copyOf(n)
    }

    private class Decoder(val lc: Int, val lp: Int, pb: Int, val src: ByteArray, var pos: Int, val end: Int) {
        val posStateMask = (1 shl pb) - 1
        val literalPosMask = (1 shl lp) - 1
        var range = -1
        var code = 0

        val isMatch = probs(NUM_STATES shl POS_BITS_MAX)
        val isRep = probs(NUM_STATES)
        val isRepG0 = probs(NUM_STATES)
        val isRepG1 = probs(NUM_STATES)
        val isRepG2 = probs(NUM_STATES)
        val isRep0Long = probs(NUM_STATES shl POS_BITS_MAX)
        val posSlot = Array(LEN_TO_POS_STATES) { probs(1 shl 6) }
        val posDecoders = probs(FULL_DISTANCES - END_POS_MODEL)
        val posAlign = probs(1 shl ALIGN_BITS)
        val lenDecoder = LenDecoder()
        val repLenDecoder = LenDecoder()
        val literals = Array(1 shl (lc + lp)) { probs(0x300) }

        fun probs(n: Int) = ShortArray(n) { 1024 }

        fun readByte(): Int {
            if (pos >= end) throw LzmaException("truncated input")
            return src[pos++].toInt() and 0xFF
        }

        fun init() {
            code = 0; range = -1
            repeat(5) { code = (code shl 8) or readByte() }
        }

        fun bit(p: ShortArray, i: Int): Int {
            val prob = p[i].toInt()
            val bound = (range ushr 11) * prob
            return if ((code xor Int.MIN_VALUE) < (bound xor Int.MIN_VALUE)) {
                range = bound
                p[i] = (prob + ((2048 - prob) ushr 5)).toShort()
                if (range and -0x1000000 == 0) { code = (code shl 8) or readByte(); range = range shl 8 }
                0
            } else {
                range -= bound; code -= bound
                p[i] = (prob - (prob ushr 5)).toShort()
                if (range and -0x1000000 == 0) { code = (code shl 8) or readByte(); range = range shl 8 }
                1
            }
        }

        fun direct(bits: Int): Int {
            var result = 0
            repeat(bits) {
                range = range ushr 1
                val t = (code - range) ushr 31
                code -= range and (t - 1)
                result = (result shl 1) or (1 - t)
                if (range and -0x1000000 == 0) { code = (code shl 8) or readByte(); range = range shl 8 }
            }
            return result
        }

        fun tree(p: ShortArray, bits: Int): Int {
            var m = 1
            repeat(bits) { m = (m shl 1) + bit(p, m) }
            return m - (1 shl bits)
        }

        fun reverse(p: ShortArray, start: Int, bits: Int): Int {
            var m = 1; var symbol = 0
            for (i in 0 until bits) {
                val b = bit(p, start + m)
                m = (m shl 1) + b
                symbol = symbol or (b shl i)
            }
            return symbol
        }

        inner class LenDecoder {
            val choice = probs(2)
            val low = Array(1 shl POS_BITS_MAX) { probs(1 shl 3) }
            val mid = Array(1 shl POS_BITS_MAX) { probs(1 shl 3) }
            val high = probs(1 shl 8)
            fun decode(posState: Int): Int {
                if (bit(choice, 0) == 0) return tree(low[posState], 3)
                if (bit(choice, 1) == 0) return 8 + tree(mid[posState], 3)
                return 16 + tree(high, 8)
            }
        }

        fun run(out: ByteArray): Int {
            init()
            val size = out.size
            var state = 0
            var rep0 = 0; var rep1 = 0; var rep2 = 0; var rep3 = 0
            var now = 0
            var prev = 0
            while (now < size) {
                val posState = now and posStateMask
                if (bit(isMatch, (state shl POS_BITS_MAX) + posState) == 0) {
                    val lit = literals[((now and literalPosMask) shl lc) + (prev ushr (8 - lc))]
                    var symbol = 1
                    if (state >= 7) {
                        if (rep0 >= now) throw LzmaException("distance")
                        var match = out[now - rep0 - 1].toInt() and 0xFF
                        while (symbol < 0x100) {
                            val matchBit = (match ushr 7) and 1
                            match = match shl 1
                            val b = bit(lit, ((1 + matchBit) shl 8) + symbol)
                            symbol = (symbol shl 1) or b
                            if (matchBit != b) {
                                while (symbol < 0x100) symbol = (symbol shl 1) or bit(lit, symbol)
                                break
                            }
                        }
                    } else {
                        while (symbol < 0x100) symbol = (symbol shl 1) or bit(lit, symbol)
                    }
                    prev = symbol and 0xFF
                    out[now++] = prev.toByte()
                    state = if (state < 4) 0 else if (state < 10) state - 3 else state - 6
                    continue
                }
                var len: Int
                if (bit(isRep, state) == 1) {
                    len = 0
                    if (bit(isRepG0, state) == 0) {
                        if (bit(isRep0Long, (state shl POS_BITS_MAX) + posState) == 0) {
                            state = if (state < 7) 9 else 11
                            len = 1
                        }
                    } else {
                        val distance: Int
                        if (bit(isRepG1, state) == 0) distance = rep1
                        else {
                            if (bit(isRepG2, state) == 0) distance = rep2
                            else { distance = rep3; rep3 = rep2 }
                            rep2 = rep1
                        }
                        rep1 = rep0; rep0 = distance
                    }
                    if (len == 0) {
                        len = repLenDecoder.decode(posState) + MATCH_MIN
                        state = if (state < 7) 8 else 11
                    }
                } else {
                    rep3 = rep2; rep2 = rep1; rep1 = rep0
                    len = MATCH_MIN + lenDecoder.decode(posState)
                    state = if (state < 7) 7 else 10
                    val lenState = if (len - MATCH_MIN < LEN_TO_POS_STATES) len - MATCH_MIN else LEN_TO_POS_STATES - 1
                    val slot = tree(posSlot[lenState], 6)
                    if (slot >= START_POS_MODEL) {
                        val directBits = (slot ushr 1) - 1
                        rep0 = (2 or (slot and 1)) shl directBits
                        if (slot < END_POS_MODEL) {
                            rep0 += reverse(posDecoders, rep0 - slot - 1, directBits)
                        } else {
                            rep0 += direct(directBits - ALIGN_BITS) shl ALIGN_BITS
                            rep0 += reverse(posAlign, 0, ALIGN_BITS)
                            if (rep0 < 0) {
                                if (rep0 == -1) return now   // end marker
                                throw LzmaException("distance")
                            }
                        }
                    } else rep0 = slot
                }
                if (rep0 < 0 || rep0 >= now) throw LzmaException("distance")
                var from = now - rep0 - 1
                val stop = minOf(size, now + len)
                while (now < stop) out[now++] = out[from++]
                prev = out[now - 1].toInt() and 0xFF
            }
            return now
        }
    }
}

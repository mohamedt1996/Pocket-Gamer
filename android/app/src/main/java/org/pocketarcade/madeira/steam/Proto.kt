// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam protobuf helpers (Proto/SteamProtoMessages.swift).
// Hand-written protobuf encoding without a protobuf runtime: field tag, wire
// type, value. Field numbers are Valve's, from its public protocol definitions.

package org.pocketarcade.madeira.steam

import java.io.ByteArrayOutputStream

object Wire {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LENGTH = 2
    const val FIXED32 = 5
}

/** Protobuf writer. Like Madeira's encoder it omits zero and empty values unless an `always` variant is used. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()
    val size: Int get() = out.size()

    fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun string(field: Int, value: String) {
        if (value.isEmpty()) return
        bytes(field, value.toByteArray(Charsets.UTF_8))
    }

    fun bytes(field: Int, value: ByteArray) {
        if (value.isEmpty()) return
        tag(field, Wire.LENGTH); varint(value.size.toLong()); out.write(value)
    }

    fun message(field: Int, value: ByteArray) = bytes(field, value)
    fun message(field: Int, build: ProtoWriter.() -> Unit) = bytes(field, ProtoWriter().apply(build).bytes())

    fun uint32(field: Int, value: Int) {
        if (value == 0) return
        tag(field, Wire.VARINT); varint(value.toLong() and 0xFFFFFFFFL)
    }

    fun uint64(field: Int, value: Long) {
        if (value == 0L) return
        tag(field, Wire.VARINT); varint(value)
    }

    /** int32: negative values are sign-extended to a 64-bit varint, as protobuf requires. */
    fun int32(field: Int, value: Int) {
        if (value == 0) return
        int32Always(field, value)
    }

    fun int32Always(field: Int, value: Int) {
        tag(field, Wire.VARINT); varint(value.toLong())
    }

    fun bool(field: Int, value: Boolean) {
        if (!value) return
        tag(field, Wire.VARINT); varint(1)
    }

    fun fixed32(field: Int, value: Int) {
        if (value == 0) return
        tag(field, Wire.FIXED32)
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF)
    }

    fun fixed64(field: Int, value: Long) {
        if (value == 0L) return
        fixed64Always(field, value)
    }

    fun fixed64Always(field: Int, value: Long) {
        tag(field, Wire.FIXED64)
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }
}

inline fun proto(build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(build).bytes()

/** Protobuf reader. Every length is checked against the bytes left: lengths come from the network. */
class ProtoReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
    var wire: Int = 0
        private set

    val atEnd: Boolean get() = pos >= end

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (pos < end) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw SteamException(SteamError.Protobuf("Varint too long"))
        }
        throw SteamException(SteamError.Protobuf("Unexpected end of data reading varint"))
    }

    /** The next field number, or -1 at the end. Sets [wire]. */
    fun next(): Int {
        if (atEnd) return -1
        val tag = varint()
        wire = (tag and 7).toInt()
        if (wire != Wire.VARINT && wire != Wire.FIXED64 && wire != Wire.LENGTH && wire != Wire.FIXED32) {
            throw SteamException(SteamError.Protobuf("Unknown wire type: $wire"))
        }
        return (tag ushr 3).toInt()
    }

    private fun length(): Int {
        val n = varint()
        if (n < 0 || n > (end - pos).toLong()) throw SteamException(SteamError.Protobuf("Length past end of data"))
        return n.toInt()
    }

    fun bytes(): ByteArray {
        val n = length()
        val r = data.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    /** A sub-message reader over the next length-delimited field, without copying. */
    fun message(): ProtoReader {
        val n = length()
        val r = ProtoReader(data, pos, pos + n)
        pos += n
        return r
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun fixed32(): Int {
        if (pos + 4 > end) throw SteamException(SteamError.Protobuf("Unexpected end of data reading fixed32"))
        var v = 0
        for (i in 0 until 4) v = v or ((data[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun fixed64(): Long {
        if (pos + 8 > end) throw SteamException(SteamError.Protobuf("Unexpected end of data reading fixed64"))
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    /** A 64-bit value Steam sends as fixed64 or as a varint, depending on the message version. */
    fun uint64Any(): Long = if (wire == Wire.FIXED64) fixed64() else varint()

    fun int32(): Int = varint().toInt()
    fun bool(): Boolean = varint() != 0L

    fun skip() {
        when (wire) {
            Wire.VARINT -> varint()
            Wire.FIXED64 -> pos += 8
            Wire.LENGTH -> { val n = length(); pos += n }
            Wire.FIXED32 -> pos += 4
        }
        if (pos > end) throw SteamException(SteamError.Protobuf("Skip went past end of data"))
    }

    /** Calls [block] for each field; [block] must read or [skip] the value. */
    inline fun fields(block: ProtoReader.(field: Int) -> Unit) {
        while (true) {
            val f = next()
            if (f < 0) return
            block(f)
        }
    }
}

fun ByteArray.proto(): ProtoReader = ProtoReader(this)

internal fun ByteArray.hex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
    }
    return sb.toString()
}

internal fun String.unhex(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

internal fun ByteArray.u32le(at: Int): Long =
    ((this[at].toLong() and 0xFF) or ((this[at + 1].toLong() and 0xFF) shl 8) or
        ((this[at + 2].toLong() and 0xFF) shl 16) or ((this[at + 3].toLong() and 0xFF) shl 24))

internal fun ByteArray.u16le(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u64le(at: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((this[at + i].toLong() and 0xFF) shl (8 * i))
    return v
}

internal fun ByteArrayOutputStream.u32le(value: Long) {
    for (i in 0 until 4) write(((value ushr (8 * i)) and 0xFF).toInt())
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's VDFParser (SwiftSteam/Library/SteamLibraryFetcher.swift)
// and SteamKeyValues (SteamKeyValues.swift).

package org.pocketarcade.madeira.steam

/** Valve Data Format: text KeyValues as PICS sends app info, and the binary package scan. */
object Vdf {
    /**
     * Parses text KeyValues into nested maps; leaves are Strings. `\"` and `\\` are
     * escapes inside a quoted string, any other backslash is literal (paths). Keys keep
     * their case (PICS app info is case-sensitive in practice).
     */
    fun parseText(data: ByteArray): Map<String, Any> = parseText(String(data, Charsets.UTF_8))

    fun parseText(text: String): Map<String, Any> {
        val p = TextParser(text)
        return p.section()
    }

    private class TextParser(val s: String) {
        var i = 0
        val n = s.length

        fun skip() {
            while (i < n) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++
                else if (c == '/' && i + 1 < n && s[i + 1] == '/') { while (i < n && s[i] != '\n') i++ }
                else break
            }
        }

        /** A token, with whether it was quoted (so a quoted "{" is a value, not a brace). */
        fun token(): Pair<String, Boolean>? {
            skip()
            if (i >= n) return null
            val c = s[i]
            if (c == '{' || c == '}') { i++; return c.toString() to false }
            val sb = StringBuilder()
            if (c == '"') {
                i++
                while (i < n && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < n && (s[i + 1] == '"' || s[i + 1] == '\\')) {
                        sb.append(s[i + 1]); i += 2; continue
                    }
                    sb.append(s[i]); i++
                }
                i++
                return sb.toString() to true
            }
            while (i < n) {
                val ch = s[i]
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '{' || ch == '}' || ch == '"') break
                sb.append(ch); i++
            }
            return sb.toString() to false
        }

        fun section(): Map<String, Any> {
            val dict = LinkedHashMap<String, Any>()
            while (true) {
                val (key, keyQuoted) = token() ?: break
                if (!keyQuoted && key == "}") break
                if (!keyQuoted && key == "{") continue
                val (value, valueQuoted) = token() ?: break
                if (!valueQuoted && value == "{") dict[key] = section()
                else if (!valueQuoted && value == "}") break
                else dict[key] = value
            }
            return dict
        }
    }

    private const val TYPE_NONE = 0
    private const val TYPE_STRING = 1
    private const val TYPE_INT32 = 2
    private const val TYPE_END = 8

    /** The numeric values listed under `key` ("appids", "depotids") in a binary package buffer. */
    fun packageIDs(key: String, data: ByteArray): List<Int> {
        val needle = key.toByteArray(Charsets.UTF_8) + 0
        var offset = indexOf(data, needle).let { if (it < 0) return emptyList() else it + needle.size }
        val ids = ArrayList<Int>()
        while (offset < data.size) {
            val type = data[offset].toInt() and 0xFF
            offset++
            if (type == TYPE_END) break
            val end = nul(data, offset) ?: break
            offset = end + 1
            when (type) {
                TYPE_INT32 -> {
                    if (offset + 4 > data.size) break
                    ids += data.u32le(offset).toInt()
                    offset += 4
                }
                TYPE_STRING -> { val e = nul(data, offset) ?: break; offset = e + 1 }
                TYPE_NONE -> continue
            }
        }
        return ids
    }

    private fun nul(data: ByteArray, from: Int): Int? {
        var e = from
        while (e < data.size && data[e].toInt() != 0) e++
        return if (e < data.size) e else null
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * Steam's text KeyValues for install records and library folders, as Madeira's
 * SteamKeyValues reads them: keys are lower-cased (the last duplicate wins), only
 * `\"` and `\\` are escapes, and the input is bounded (4 MiB, 100k tokens,
 * 16k per value, depth 32).
 */
class KeyValues private constructor(val string: String?, val fields: Map<String, KeyValues>) {
    operator fun get(key: String): KeyValues? = fields[key.lowercase()]

    companion object {
        fun parse(data: ByteArray): KeyValues {
            if (data.size > 4 shl 20) throw SteamException(SteamError.ManifestFetchFailed("Steam metadata is too large."))
            return KeyValues(null, Parser(data).obj(0))
        }

        private fun invalid(reason: String): Nothing = throw SteamException(SteamError.ManifestFetchFailed(reason))
    }

    private class Parser(val b: ByteArray) {
        var pos = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        var tokens = 0

        // null = end; OPEN / CLOSE markers, else a word.
        fun token(): Any? {
            while (pos < b.size) {
                val c = b[pos].toInt() and 0xFF
                if (c <= 32) { pos++; continue }
                if (c == 47 && pos + 1 < b.size && b[pos + 1].toInt() == 47) {
                    while (pos < b.size && b[pos].toInt() != 10) pos++
                    continue
                }
                break
            }
            if (pos >= b.size) return null
            if (++tokens > 100_000) invalid("Steam metadata has too many entries.")
            val first = b[pos].toInt() and 0xFF; pos++
            if (first == 123) return OPEN
            if (first == 125) return CLOSE
            val v = java.io.ByteArrayOutputStream()
            if (first == 34) {
                while (pos < b.size) {
                    val c = b[pos].toInt() and 0xFF; pos++
                    if (c == 34) return String(v.toByteArray(), Charsets.UTF_8)
                    if (c == 92 && pos < b.size && (b[pos].toInt() == 34 || b[pos].toInt() == 92)) { v.write(b[pos].toInt()); pos++ }
                    else v.write(c)
                    if (v.size() > 16_384) invalid("Steam metadata contains an oversized value.")
                }
                invalid("Steam metadata is incomplete. Refresh after the download finishes.")
            }
            v.write(first)
            while (pos < b.size) {
                val c = b[pos].toInt() and 0xFF
                if (c <= 32 || c == 123 || c == 125) break
                v.write(c); pos++
                if (v.size() > 16_384) invalid("Steam metadata contains an oversized value.")
            }
            return String(v.toByteArray(), Charsets.UTF_8)
        }

        fun obj(depth: Int): Map<String, KeyValues> {
            if (depth >= 32) invalid("Steam metadata is nested too deeply.")
            val result = LinkedHashMap<String, KeyValues>()
            while (true) {
                val key = token() ?: break
                if (key === CLOSE) {
                    if (depth == 0) invalid("Steam metadata has an unexpected closing brace.")
                    return result
                }
                val value = token()
                if (key !is String || value == null || value === CLOSE) invalid("Steam metadata is incomplete. Try refreshing it.")
                result[key.lowercase()] = if (value === OPEN) KeyValues(null, obj(depth + 1)) else KeyValues(value as String, emptyMap())
            }
            if (depth != 0) invalid("Steam metadata is incomplete. Try refreshing it.")
            return result
        }

        companion object {
            val OPEN = Any()
            val CLOSE = Any()
        }
    }
}

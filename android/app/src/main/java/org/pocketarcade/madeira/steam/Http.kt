// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// HTTPS and JSON for the Steam client, without Android dependencies, so the
// whole client also runs (and is tested) on a plain JVM.

package org.pocketarcade.madeira.steam

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class HttpResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** What the Steam client needs from HTTP. Tests replace it with a scripted one. */
interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000, maxBytes: Long = 64L shl 20): HttpResponse
    suspend fun post(url: String, body: ByteArray, contentType: String, timeoutMs: Int = 20_000): HttpResponse
    /** Any method with explicit headers and body (Steam Cloud's upload parts). */
    suspend fun send(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray?, timeoutMs: Int = 120_000): HttpResponse
    /** Streams a GET into [file] (HTTPS only), calling [progress] with bytes written. Returns the status. */
    suspend fun download(url: String, file: File, progress: (Long) -> Unit = {}, timeoutMs: Int = 60_000, maxBytes: Long = Long.MAX_VALUE): Int
}

/** HttpURLConnection-based client: HTTPS only, no credentials in URLs, bounded bodies. */
class UrlConnectionHttp(private val userAgent: String = "Valve/Steam HTTP Client 1.0") : HttpClient {
    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val u = URL(url)
        require(u.protocol == "https" && u.userInfo == null) { "HTTPS only" }
        return (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("User-Agent", userAgent)
        }
    }

    private fun read(input: InputStream?, maxBytes: Long): ByteArray {
        if (input == null) return ByteArray(0)
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            var total = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw SteamException(SteamError.ConnectionFailed("Response too large"))
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun response(c: HttpURLConnection, maxBytes: Long): HttpResponse {
        val code = c.responseCode
        val headers = HashMap<String, String>()
        for ((k, v) in c.headerFields) if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v.last()
        val body = try { read(if (code >= 400) c.errorStream else c.inputStream, maxBytes) } catch (e: java.io.IOException) { if (code >= 400) ByteArray(0) else throw e }
        return HttpResponse(code, headers, body)
    }

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int, maxBytes: Long): HttpResponse = withContext(Dispatchers.IO) {
        val c = open(url, timeoutMs)
        try {
            for ((k, v) in headers) c.setRequestProperty(k, v)
            response(c, maxBytes)
        } finally { c.disconnect() }
    }

    override suspend fun post(url: String, body: ByteArray, contentType: String, timeoutMs: Int): HttpResponse = withContext(Dispatchers.IO) {
        val c = open(url, timeoutMs)
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            response(c, 16L shl 20)
        } finally { c.disconnect() }
    }

    override suspend fun send(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray?, timeoutMs: Int): HttpResponse = withContext(Dispatchers.IO) {
        val c = open(url, timeoutMs)
        try {
            c.requestMethod = method
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            response(c, 64L shl 20)
        } finally { c.disconnect() }
    }

    override suspend fun download(url: String, file: File, progress: (Long) -> Unit, timeoutMs: Int, maxBytes: Long): Int = withContext(Dispatchers.IO) {
        val c = open(url, timeoutMs)
        c.instanceFollowRedirects = false
        try {
            val code = c.responseCode
            if (code != 200) return@withContext code
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw SteamException(SteamError.ChunkDownloadFailed("larger than expected"))
                        out.write(buf, 0, n)
                        progress(total)
                    }
                }
            }
            code
        } finally { c.disconnect() }
    }
}

/** Minimal JSON reader (objects, arrays, strings, numbers, booleans, null). */
object Json {
    fun parse(text: String): Any? = P(text).run { val v = value(); ws(); if (i != s.length) fail(); v }

    private class P(val s: String) {
        var i = 0
        fun fail(): Nothing = throw IllegalArgumentException("Invalid JSON at $i")
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun lit(word: String, v: Any?): Any? { if (!s.startsWith(word, i)) fail(); i += word.length; return v }
        fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            if (t.isEmpty()) fail()
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail()
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { if (i + 4 > s.length) fail(); sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out += value(); ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                fail()
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                out[k] = value(); ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                fail()
            }
        }
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** Serialises maps, lists, strings, numbers, booleans and null. */
    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean, is Int, is Long -> v.toString()
        is Number -> if (v.toDouble().isFinite()) v.toString() else "null"
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + write(it.value) }
        is Iterable<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> quote(v.toString())
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(key: String): Map<String, Any?>? = (this as? Map<String, Any?>)?.get(key) as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Any?.arr(key: String): List<Any?>? = (this as? Map<String, Any?>)?.get(key) as? List<Any?>
internal fun Any?.str(key: String): String? = (this as? Map<*, *>)?.get(key) as? String
internal fun Any?.long(key: String): Long? = when (val v = (this as? Map<*, *>)?.get(key)) { is Long -> v; is Double -> v.toLong(); is String -> v.toLongOrNull(); else -> null }
internal fun Any?.bool(key: String): Boolean? = (this as? Map<*, *>)?.get(key) as? Boolean

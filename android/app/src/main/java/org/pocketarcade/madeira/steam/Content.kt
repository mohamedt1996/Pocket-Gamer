// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam Content/ContentDecryptor.swift and
// Content/DepotManifest.swift.

package org.pocketarcade.madeira.steam

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Zstandard decoding (JNI around the reference educational decoder). */
object Zstd {
    @Volatile var available: Boolean = false
        private set

    /** Loads the native decoder: a library name on Android, an absolute path in tests. */
    fun load(library: String) {
        try {
            if (library.startsWith("/")) System.load(library) else System.loadLibrary(library)
            available = true
        } catch (e: Throwable) {
            SteamLog.event("[steam] zstd decoder unavailable: ${e.javaClass.simpleName}")
        }
    }

    /** Decodes one zstd frame into at most [capacity] bytes, or null on corrupt input. */
    fun decompress(src: ByteArray, offset: Int, length: Int, capacity: Int): ByteArray? {
        if (!available) return null
        return nativeDecompress(src, offset, length, capacity)
    }

    @JvmStatic private external fun nativeDecompress(src: ByteArray, offset: Int, length: Int, capacity: Int): ByteArray?
}

/**
 * Decrypts, decompresses and checks Steam content chunks: AES-256 with the depot key,
 * then one of Steam's containers (VSZa zstd, VZ LZMA, PKZip), then Steam's Adler-32.
 */
object ContentDecryptor {
    const val MAX_CHUNK_BYTES = 64 * 1024 * 1024

    /**
     * The first 16 bytes are the IV, itself AES-ECB encrypted with the depot key; the rest
     * is AES-256-CBC with PKCS7 padding under that IV.
     */
    fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        if (key.size != 32) throw SteamException(SteamError.DecryptionFailed("Invalid depot key size: ${key.size}, expected 32"))
        if (data.size <= 16) throw SteamException(SteamError.DecryptionFailed("Encrypted data too small"))
        return try {
            val k = SecretKeySpec(key, "AES")
            val ecb = Cipher.getInstance("AES/ECB/NoPadding")
            ecb.init(Cipher.DECRYPT_MODE, k)
            val iv = ecb.doFinal(data, 0, 16)
            val cbc = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cbc.init(Cipher.DECRYPT_MODE, k, IvParameterSpec(iv))
            cbc.doFinal(data, 16, data.size - 16)
        } catch (e: Exception) {
            throw SteamException(SteamError.DecryptionFailed("AES decryption failed"), e)
        }
    }

    fun decompress(data: ByteArray, expectedSize: Int): ByteArray {
        if (data.size > 23 && data[0] == 'V'.code.toByte() && data[1] == 'S'.code.toByte() && data[2] == 'Z'.code.toByte() && data[3] == 'a'.code.toByte()) {
            return try { vzstd(data, expectedSize) } catch (e: Exception) { throw SteamException(SteamError.ChunkDecodeFailed("vzstd"), e) }
        }
        if (data.size >= 2 && data[0] == 'V'.code.toByte() && data[1] == 'Z'.code.toByte()) {
            return try { vzip(data, expectedSize) } catch (e: Exception) { throw SteamException(SteamError.ChunkDecodeFailed("vzip"), e) }
        }
        if (data.size >= 30 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() && data[2].toInt() == 3 && data[3].toInt() == 4) {
            if (expectedSize <= 0 || expectedSize > MAX_CHUNK_BYTES) throw SteamException(SteamError.ChunkDecodeFailed("zip-size"))
            return pkzip(data, expectedSize)
        }
        if (data.size == expectedSize) return data
        throw SteamException(SteamError.ChunkDecodeFailed(formatTag(data)))
    }

    fun formatTag(data: ByteArray): String = data.take(4).joinToString("") { b ->
        val v = b.toInt() and 0xFF
        if (v in 0x30..0x7A) v.toChar().toString() else String.format("%02x", v)
    }

    /** "VZa" + crc32(4) | LZMA properties(5) | raw LZMA1 | crc32(4) + size(4) + "zv". */
    private fun vzip(data: ByteArray, expectedSize: Int): ByteArray {
        if (data.size <= 22) throw SteamException(SteamError.DecompressionFailed)
        val size = data.u32le(data.size - 6)
        if (size <= 0 || size > MAX_CHUNK_BYTES || (expectedSize > 0 && size != expectedSize.toLong())) throw SteamException(SteamError.DecompressionFailed)
        val out = Lzma.decode(data.copyOfRange(7, 12), data, 12, data.size - 10 - 12, size.toInt())
        if (out.size != size.toInt()) throw SteamException(SteamError.DecompressionFailed)
        return out
    }

    /** "VSZa" + crc32(4) | zstd frame | crc32(4) + size(4) + "zsv" (15 bytes). */
    private fun vzstd(data: ByteArray, expectedSize: Int): ByteArray {
        if (expectedSize <= 0 || expectedSize > MAX_CHUNK_BYTES) throw SteamException(SteamError.DecompressionFailed)
        return Zstd.decompress(data, 8, data.size - 15 - 8, expectedSize) ?: throw SteamException(SteamError.DecompressionFailed)
    }

    /** A single-entry PKZip archive (older content): stored or deflated. */
    private fun pkzip(data: ByteArray, capacity: Int): ByteArray {
        val flags = data.u16le(6); val method = data.u16le(8)
        val csize = data.u32le(18); val usize = data.u32le(22)
        val start = 30 + data.u16le(26) + data.u16le(28)
        if (start > data.size) throw SteamException(SteamError.ChunkDecodeFailed("zip-1"))
        var avail = data.size - start
        val sized = flags and 8 == 0 && csize != 0L
        if (sized) {
            if (csize > avail) throw SteamException(SteamError.ChunkDecodeFailed("zip-1"))
            avail = csize.toInt()
            if (usize > capacity) throw SteamException(SteamError.ChunkDecodeFailed("zip-4"))
        }
        if (method == 0) {
            val n = if (sized) csize.toInt() else capacity
            if (n > avail) throw SteamException(SteamError.ChunkDecodeFailed("zip-3"))
            return data.copyOfRange(start, start + n)
        }
        if (method != 8) throw SteamException(SteamError.ChunkDecodeFailed("zip-2"))
        val inflater = Inflater(true)
        try {
            inflater.setInput(data, start, avail)
            val out = ByteArray(capacity)
            var produced = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(out, produced, capacity - produced)
                produced += n
                if (n == 0) {
                    if (inflater.finished()) break
                    if (produced == capacity) throw SteamException(SteamError.ChunkDecodeFailed("zip-4"))
                    if (inflater.needsInput()) {
                        // Raw deflate may want one extra dummy byte at the end of input.
                        throw SteamException(SteamError.ChunkDecodeFailed("zip-3"))
                    }
                }
            }
            return if (produced == capacity) out else out.copyOf(produced)
        } catch (e: java.util.zip.DataFormatException) {
            throw SteamException(SteamError.ChunkDecodeFailed("zip-3"), e)
        } finally { inflater.end() }
    }

    /** Steam's chunk checksum: Adler-32 seeded with 0 instead of 1. */
    fun adler32(data: ByteArray): Int {
        var a = 0L; var b = 0L
        var i = 0
        while (i < data.size) {
            val n = minOf(5552, data.size - i)
            for (k in 0 until n) { a += data[i + k].toInt() and 0xFF; b += a }
            a %= 65521; b %= 65521
            i += n
        }
        return ((b shl 16) or a).toInt()
    }

    fun processChunk(encrypted: ByteArray, key: ByteArray, expectedCRC: Int, expectedSize: Int): ByteArray {
        val plain = decompress(decrypt(encrypted, key), expectedSize)
        if (expectedCRC != 0 && adler32(plain) != expectedCRC) throw SteamException(SteamError.ChecksumMismatch)
        return plain
    }

    fun sha1(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray =
        MessageDigest.getInstance("SHA-1").apply { update(data, offset, length) }.digest()
}

/** A parsed depot manifest: the files of one depot and their chunks. */
class DepotManifest(val depotID: Int, val manifestGID: Long, val files: List<FileEntry>) {
    class FileEntry(val filename: String, val size: Long, val flags: Int, val chunks: List<ChunkEntry>) {
        val isDirectory: Boolean get() = flags and 0x40 != 0
        val isSymlink: Boolean get() = flags and 0x200 != 0
        val isCustomExecutable: Boolean get() = flags and CUSTOM_EXECUTABLE != 0
    }

    class ChunkEntry(val sha: ByteArray, val crc: Int, val offset: Long, val compressedSize: Int, val uncompressedSize: Int) {
        val shaHex: String get() = sha.hex()
    }

    val totalUncompressedSize: Long get() = files.sumOf { it.size }
    val totalCompressedSize: Long get() = files.sumOf { f -> f.chunks.sumOf { it.compressedSize.toLong() and 0xFFFFFFFFL } }

    companion object {
        /** EDepotFileFlag.CustomExecutable: Valve's client customises the file per user. */
        const val CUSTOM_EXECUTABLE = 0x80

        /**
         * Parses a manifest payload. Modern payloads have an 8-byte header (magic, length)
         * before ContentManifestPayload; filenames may be base64 AES with the depot key.
         */
        fun parse(depotID: Int, manifestGID: Long, data: ByteArray, depotKey: ByteArray? = null): DepotManifest {
            if (data.size < 4) throw SteamException(SteamError.ManifestFetchFailed("Manifest data too small"))
            val (start, end) = if (data[0].toInt() == 0x0A) 0 to data.size else {
                val len = if (data.size >= 8) data.u32le(4) else 0L
                8 to (if (len > 0 && 8 + len <= data.size) (8 + len).toInt() else data.size)
            }
            val files = ArrayList<FileEntry>()
            ProtoReader(data, start, end).fields { f ->
                if (f == 1) parseFile(message(), depotKey)?.let { files += it } else skip()
            }
            return DepotManifest(depotID, manifestGID, files)
        }

        private fun parseFile(r: ProtoReader, key: ByteArray?): FileEntry? {
            var name = ""; var size = 0L; var flags = 0
            val chunks = ArrayList<ChunkEntry>()
            r.fields { f ->
                when (f) {
                    1 -> name = string()
                    2 -> size = varint()
                    3 -> flags = varint().toInt()
                    6 -> parseChunk(message())?.let { chunks += it }
                    else -> skip()
                }
            }
            if (name.isEmpty()) return null
            return FileEntry(decryptFilename(name, key).replace('\\', '/'), size, flags, chunks)
        }

        private fun parseChunk(r: ProtoReader): ChunkEntry? {
            var sha = ByteArray(0); var crc = 0; var offset = 0L; var original = 0; var compressed = 0
            r.fields { f ->
                when (f) {
                    1 -> sha = bytes()
                    2 -> crc = fixed32()
                    3 -> offset = varint()
                    4 -> original = varint().toInt()
                    5 -> compressed = varint().toInt()
                    else -> skip()
                }
            }
            if (sha.isEmpty()) return null
            return ChunkEntry(sha, crc, offset, compressed, original)
        }

        /** Encrypted filenames are base64 AES-256-CBC (depot key, embedded IV); kept only if the result is sane. */
        fun decryptFilename(name: String, key: ByteArray?): String {
            if (key == null) return name
            val stripped = name.filterNot { it.isWhitespace() }
            val cipher = try { Base64.getMimeDecoder().decode(stripped) } catch (_: Exception) { return name }
            if (cipher.size <= 16) return name
            val plain = try { ContentDecryptor.decrypt(cipher, key) } catch (_: Exception) { return name }
            var n = plain.size
            while (n > 0 && plain[n - 1].toInt() == 0) n--
            if (n == 0) return name
            val decoded = try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plain, 0, n)).toString()
            } catch (_: Exception) { return name }
            if (decoded.any { it.code < 0x20 || it.code == 0x7F }) return name
            return decoded
        }

        /** A manifest from the CDN is a single-entry zip holding the payload. */
        fun unzipPayload(data: ByteArray): ByteArray {
            if (data.size < 4 || data[0] != 'P'.code.toByte() || data[1] != 'K'.code.toByte()) return data
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                zip.nextEntry ?: throw SteamException(SteamError.ManifestFetchFailed("Empty manifest archive"))
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(65536)
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 512 shl 20) throw SteamException(SteamError.ManifestFetchFailed("Manifest too large"))
                }
                return out.toByteArray()
            }
        }
    }
}

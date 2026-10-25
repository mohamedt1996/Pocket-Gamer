// SPDX-License-Identifier: GPL-3.0-or-later
// Chunk layout and assembly adapted from GameNative's EpicDownloadManager and Legendary.
package org.pocketarcade.madeira.epic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.pocketarcade.madeira.epic.manifest.ChunkInfo
import org.pocketarcade.madeira.epic.manifest.EpicManifest
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.Inflater

class EpicDownloader(private val http: EpicHttp) {
    data class Progress(val done: Long, val total: Long, val file: String)
    suspend fun install(build: EpicBuild, game: EpicGame, root: File, progress: (Progress) -> Unit): EpicManifest = withContext(Dispatchers.IO) {
        val manifest = EpicManifest.readAll(build.manifest)
        require(manifest.meta?.appName == game.app) { "Epic manifest does not match the selected app" }
        val files = manifest.fileManifestList?.elements ?: error("Epic manifest has no file list")
        require(manifest.meta?.isFileData == false) { "Legacy Epic file-data manifests are unsupported" }
        require(files.map { it.filename.lowercase(java.util.Locale.ROOT) }.distinct().size == files.size) { "Duplicate Epic file paths" }
        val chunks = manifest.chunkDataList?.elements?.associateBy { it.guidStr } ?: error("Epic manifest has no chunks")
        root.mkdirs()
        val destinations = files.map { safeFile(root, it.filename).also { _ -> require(it.symlinkTarget.isEmpty()) { "Epic symlink files are not supported" } } }
        val total = files.fold(0L) { sum, file -> Math.addExact(sum, file.fileSize.also { require(it >= 0) }) }
        // Completed files are reused only after hashing them. Partial files never count as installed.
        val valid = files.indices.associateWith { index ->
            val file = destinations[index]
            file.isFile && file.length() == files[index].fileSize && digest(file).contentEquals(files[index].hash)
        }
        val needed = files.indices.filter { valid[it] != true }.fold(0L) { sum, index ->
            val entry = files[index]
            val partial = sidecar(root, entry.filename, ".pocket-part")
            val checkpoint = sidecar(root, entry.filename, ".pocket-state")
            val saved = runCatching { org.json.JSONObject(checkpoint.readText()) }.getOrNull()
            val nextPart = saved?.optInt("nextPart", -1) ?: -1
            val offset = if (nextPart in 0..entry.chunkParts.size) entry.chunkParts.take(nextPart).sumOf { it.size.toLong() } else -1L
            val reusable = if (saved?.optString("hash") == entry.hash.joinToString("") { "%02x".format(it) } &&
                offset in 0..entry.fileSize && partial.length() == offset) offset else 0L
            Math.addExact(sum, entry.fileSize - reusable)
        }
        require(root.usableSpace >= Math.addExact(needed, 64L * 1024 * 1024)) { "Not enough storage for this Epic app" }
        val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 4
        }
        var done = 0L
        for (index in files.indices) {
            currentCoroutineContext().ensureActive()
            val entry = files[index]; val dest = destinations[index]
            if (valid[index] != true) {
                dest.parentFile!!.mkdirs()
                val partial = sidecar(root, entry.filename, ".pocket-part")
                val checkpoint = sidecar(root, entry.filename, ".pocket-state")
                val expectedHash = entry.hash.joinToString("") { "%02x".format(it) }
                val saved = runCatching { org.json.JSONObject(checkpoint.readText()) }.getOrNull()
                var firstPart = saved?.optInt("nextPart", 0) ?: 0
                val offset = if (firstPart in 0..entry.chunkParts.size)
                    entry.chunkParts.take(firstPart).sumOf { it.size.toLong() } else -1L
                if (saved?.optString("hash") != expectedHash || offset < 0 || partial.length() != offset) {
                    firstPart = 0; checkpoint.delete()
                }
                var verified = false
                try {
                    java.io.RandomAccessFile(partial, "rw").use { output ->
                        var fileOffset = entry.chunkParts.take(firstPart).sumOf { it.size.toLong() }
                        output.setLength(fileOffset); output.seek(fileOffset)
                        for (partIndex in firstPart until entry.chunkParts.size) {
                            val part = entry.chunkParts[partIndex]
                            currentCoroutineContext().ensureActive()
                            require(part.fileOffset == fileOffset && part.offset >= 0 && part.size >= 0) { "Invalid Epic file parts" }
                            val info = chunks[part.guidStr] ?: error("Epic file refers to a missing chunk")
                            val data = cache[info.guidStr] ?: fetch(info, manifest.getChunkDir(), build.mirrors).also { cache[info.guidStr] = it }
                            require(part.offset.toLong() + part.size <= data.size) { "Epic chunk bounds do not match the manifest" }
                            output.write(data, part.offset, part.size); fileOffset += part.size
                            val temp = sidecar(root, entry.filename, ".pocket-state.tmp")
                            temp.writeText(org.json.JSONObject().put("hash", expectedHash).put("nextPart", partIndex + 1).toString())
                            Files.move(temp.toPath(), checkpoint.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            progress(Progress(done + fileOffset, total, entry.filename))
                        }
                        require(fileOffset == entry.fileSize) { "Epic file length does not match its manifest" }
                    }
                    if (!digest(partial).contentEquals(entry.hash)) {
                        checkpoint.delete(); partial.delete()
                        error("Epic file checksum failed; retry the download")
                    }
                    Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    verified = true
                } finally {
                    if (verified) checkpoint.delete()
                    if (!checkpoint.exists()) partial.delete()
                }
            }
            done += entry.fileSize
            progress(Progress(done, total, entry.filename))
        }
        // The marker is written by the account model only after every file verifies.
        manifest
    }
    private suspend fun fetch(chunk: ChunkInfo, dir: String, mirrors: List<String>): ByteArray {
        require(chunk.windowSize in 1..(16 * 1024 * 1024)) { "Unsupported Epic chunk size" }
        for (url in mirrors) {
            currentCoroutineContext().ensureActive()
            val manifest = url.toHttpUrl()
            val path = manifest.encodedPath.substringBeforeLast('/') + "/" + chunk.getPath(dir)
            val request = Request.Builder().url(manifest.newBuilder().encodedPath(path).build()).build()
            try { return decodeChunk(http.get(request, 20 * 1024 * 1024), chunk) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Try the next manifest-provided CDN, never send account tokens. */ }
        }
        throw IOException("Epic chunk download or checksum failed. Resume to retry.")
    }
    companion object {
        private fun sidecar(root: File, name: String, suffix: String) = safeFile(root, name + suffix, true)
        fun safeFile(root: File, name: String): File = safeFile(root, name, false)
        private fun safeFile(root: File, name: String, sidecar: Boolean): File {
            require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') &&
                name.split('/').none { it == ".." || it == "." || it.isEmpty() } &&
                (sidecar || listOf(".pocket-part", ".pocket-state", ".pocket-state.tmp").none { name.endsWith(it, true) })) { "Invalid Epic install path" }
            val dest = File(root, name)
            require(dest.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Epic install path escapes the game folder" }
            return dest
        }
        fun digest(file: File): ByteArray {
            val sha = MessageDigest.getInstance("SHA-1")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val size = input.read(buffer); if (size < 0) break; sha.update(buffer, 0, size) }
            }
            return sha.digest()
        }
        fun inflateExact(data: ByteArray, size: Int): ByteArray {
            require(size in 0..(64 * 1024 * 1024)) { "Epic decompressed data is too large" }
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val output = ByteArray(size)
                var count = 0
                while (!inflater.finished() && count < output.size) {
                    val n = inflater.inflate(output, count, output.size - count)
                    require(n > 0) { "Incomplete Epic compressed data" }; count += n
                }
                require(count == size && inflater.finished()) { "Epic compressed data length mismatch" }
                return output
            } finally { inflater.end() }
        }
        fun decodeChunk(bytes: ByteArray, chunk: ChunkInfo): ByteArray {
            require(bytes.size >= 41) { "Truncated Epic chunk" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.int == 0xB1FE3AA2.toInt()) { "Invalid Epic chunk magic" }
            val version = b.int; val header = b.int; val compressed = b.int
            require(version in 1..3 && header in 41..1024 && header <= bytes.size && compressed == bytes.size - header) { "Invalid Epic chunk header" }
            require(IntArray(4) { b.int }.contentEquals(chunk.guid)) { "Wrong Epic chunk GUID" }
            b.long; val flags = b.get().toInt() and 255
            require(flags and 0xfe == 0) { "Encrypted Epic chunks are unsupported" }
            val payload = bytes.copyOfRange(header, bytes.size)
            val data = if (flags and 1 != 0) inflateExact(payload, chunk.windowSize) else payload
            require(data.size == chunk.windowSize && MessageDigest.getInstance("SHA-1").digest(data).contentEquals(chunk.shaHash)) {
                "Epic chunk checksum mismatch"
            }
            return data
        }
    }
}

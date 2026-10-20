// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam/Content/DepotDownloader.swift: downloads an
// owned app's Windows depots from Steam's content network into a Steam library
// folder. Keys and manifest request codes come from the CM connection, which
// issues them only to an account that owns the depot. Nothing here alters the
// downloaded files.

package org.pocketarcade.madeira.steam

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.coroutines.coroutineContext

data class DownloadProgress(
    val phase: Phase = Phase.PREPARING,
    /** Compressed bytes of the whole install. */
    val totalBytes: Long = 0,
    /** Compressed bytes already on disk, including resumed chunks. */
    val doneBytes: Long = 0,
    val bytesPerSecond: Double = 0.0,
) {
    enum class Phase { PREPARING, DOWNLOADING, FINISHING }
    val fraction: Double get() = if (totalBytes > 0) minOf(1.0, doneBytes.toDouble() / totalBytes) else 0.0
}

/** Per-install content-server health: failing servers move to the back of every chunk's rotation. */
class ContentHostHealth {
    private val failures = HashMap<String, Int>()
    private var reported = 0

    @Synchronized fun order(hosts: List<String>, seed: Int): List<String> {
        if (hosts.isEmpty()) return hosts
        val rotated = hosts.indices.map { hosts[Math.floorMod(it + seed, hosts.size)] }
        return rotated.withIndex().sortedWith(compareBy({ failures[it.value] ?: 0 }, { it.index })).map { it.value }
    }

    fun recordFailure(host: String, reason: String) {
        val report = synchronized(this) {
            failures[host] = (failures[host] ?: 0) + 1
            (failures[host] == 3 && reported < 8).also { if (it) reported++ }
        }
        if (report) SteamLog.event("[steam-cdn] host demoted host=${host.removePrefix("https://")} reason=$reason")
    }

    @Synchronized fun recordSuccess(host: String) {
        val c = failures[host] ?: return
        if (c > 0) failures[host] = c - 1
    }
}

/** Append-only record of completed chunks for one depot manifest (hex work-item keys). */
class Journal(private val file: File) {
    private val buffer = StringBuilder()
    private var pending = 0

    init {
        if (file.isFile && file.length() > 0) {
            RandomAccessFile(file, "r").use { r -> r.seek(file.length() - 1); if (r.read() != '\n'.code) buffer.append('\n') }
        }
    }

    @Synchronized fun append(key: Long) {
        buffer.append(java.lang.Long.toHexString(key)).append('\n')
        if (++pending >= 64) flush()
    }

    @Synchronized fun flush() {
        if (buffer.isEmpty()) return
        file.appendText(buffer.toString())
        buffer.setLength(0); pending = 0
    }

    companion object {
        fun load(file: File): Set<Long> {
            if (!file.isFile) return emptySet()
            return file.readLines().mapNotNull { it.trim().toULongOrNull(16)?.toLong() }.toSet()
        }
    }
}

class DepotDownloader(private val session: CmSession, private val http: HttpClient) {
    private val depotKeys = HashMap<Int, ByteArray>()
    private val cdnAuth = HashMap<String, String>()
    private val lock = Mutex()
    var maxConcurrentChunks = 8
    var attemptsPerChunk = 5
    private val hostPoolSize = 6
    /** Replaces the content server directory (tests). */
    var contentHosts: (suspend (Int) -> List<String>)? = null
    private var depotCache: File? = null

    private class DepotPlan(val depotID: Int, val manifestGID: Long, val key: ByteArray, val manifest: DepotManifest,
                            val hosts: List<String>, val auth: Map<String, String>, val health: ContentHostHealth)

    private class WorkItem(val file: Int, val chunk: Int) { val key: Long get() = (file.toLong() shl 32) or chunk.toLong() }

    private class Prepared {
        val paths = ArrayList<List<String>>()
        val existing = ArrayList<List<Boolean>>()
        val pending = ArrayList<List<WorkItem>>()
        val journals = ArrayList<File>()
        var totalBytes = 0L
        var doneBytes = 0L
        var totalUncompressed = 0L
        var remainingUncompressed = 0L
        var fileCount = 0
    }

    /**
     * Downloads an app into `steamApps/common/<installdir>` and writes its appmanifest.
     * Returns the install folder. Cancellation keeps completed chunks journaled.
     */
    suspend fun install(app: AppInfo, steamApps: File, ownedDepots: suspend () -> Set<Int>? = { null },
                        report: (DownloadProgress) -> Unit): File {
        val depots = app.installDepots()
        if (depots.isEmpty()) throw SteamException(SteamError.DepotNotFound(app.appID))
        SteamLog.event("[steam-depot] selection app=${app.appID} build=${app.buildID} ${app.depotSelectionSummary()}")
        val folderName = SteamInstallFiles.safeFolderName(app.installDir.ifEmpty { "app_${app.appID}" })
        val installDir = File(File(steamApps, "common"), folderName)
        val journalDir = File(File(steamApps, "downloading"), app.appID.toString())
        installDir.mkdirs(); journalDir.mkdirs()
        if (!installDir.isDirectory) throw SteamException(SteamError.ChunkDownloadFailed("Cannot create the game folder."))
        var state = DownloadProgress()
        report(state)
        depotCache = File(steamApps, "depotcache")
        val hosts = contentHosts?.invoke(app.appID) ?: contentServers(app.appID)
        if (hosts.isEmpty()) throw SteamException(SteamError.ChunkDownloadFailed("No content servers are available."))
        val health = ContentHostHealth()
        val pool = hosts.take(hostPoolSize)
        val plans = ArrayList<DepotPlan>()
        val licenseSkipped = ArrayList<Int>()
        var accountID = 0L
        for (depot in depots) {
            coroutineContext.ensureActive()
            val gid = depot.publicManifestID ?: continue
            val key = try { depotKey(depot.depotID, app.appID) } catch (e: SteamException) {
                val refused = (e.error as? SteamError.DepotKeyNotFound)?.depotID ?: throw e
                val owned = ownedDepots()
                if (owned != null && owned.isNotEmpty() && refused !in owned) { licenseSkipped += refused; continue }
                throw e
            }
            if (session.steamID != 0L) accountID = session.steamID
            val contentApp = if (!app.freeToDownload) (depot.fromApp ?: app.appID) else app.appID
            val manifest = fetchManifest(depot.depotID, contentApp, gid, key, hosts)
            val auth = HashMap<String, String>()
            for (host in pool) auth[host] = cdnAuthFragment(depot.depotID, contentApp, host)
            plans += DepotPlan(depot.depotID, gid, key, manifest, pool, auth, health)
        }
        if (licenseSkipped.isNotEmpty()) {
            SteamLog.event("[steam-depot] license app=${app.appID} skipped=${licenseSkipped.joinToString(",")} kept=${plans.joinToString(",") { it.depotID.toString() }}")
        }
        if (plans.isEmpty()) {
            licenseSkipped.firstOrNull()?.let { throw SteamException(SteamError.DepotKeyNotFound(it)) }
            throw SteamException(SteamError.DepotNotFound(app.appID))
        }
        val prepared = withContext(Dispatchers.IO) { prepare(plans, installDir, journalDir) }
        state = state.copy(phase = DownloadProgress.Phase.DOWNLOADING, totalBytes = prepared.totalBytes, doneBytes = prepared.doneBytes)
        report(state)
        SteamLog.event("[steam-depot] install begin app=${app.appID} depots=${plans.size} files=${prepared.fileCount} resume=${if (prepared.doneBytes > 0) 1 else 0}")
        if (prepared.remainingUncompressed > 0) {
            val available = installDir.usableSpace
            if (available in 1 until prepared.remainingUncompressed) throw SteamException(SteamError.InsufficientDiskSpace(prepared.remainingUncompressed, available))
        }
        val started = System.currentTimeMillis()
        val resumed = state.doneBytes
        var done = state.doneBytes
        var lastReport = 0L
        val progressLock = Any()
        for ((index, plan) in plans.withIndex()) {
            val journal = Journal(prepared.journals[index])
            val work = prepared.pending[index]
            val paths = prepared.paths[index]
            val existing = prepared.existing[index]
            try {
                coroutineScope {
                    val queue = Channel<WorkItem>(Channel.UNLIMITED)
                    work.forEach { queue.trySend(it) }
                    queue.close()
                    val workers = (0 until minOf(maxConcurrentChunks, work.size)).map {
                        async(Dispatchers.IO) {
                            for (item in queue) {
                                val chunk = plan.manifest.files[item.file].chunks[item.chunk]
                                val path = paths[item.file]
                                if (!(existing[item.file] && chunkAlreadyPresent(chunk, path))) {
                                    fetchChunk(chunk, plan, path, attemptsPerChunk, item.file + item.chunk)
                                }
                                journal.append(item.key)
                                val snapshot = synchronized(progressLock) {
                                    done += chunk.compressedSize.toLong() and 0xFFFFFFFFL
                                    val now = System.currentTimeMillis()
                                    if (now - lastReport >= 250) {
                                        lastReport = now
                                        val elapsed = (now - started) / 1000.0
                                        state = state.copy(doneBytes = done,
                                            bytesPerSecond = if (elapsed > 1) (done - resumed) / elapsed else state.bytesPerSecond)
                                        state
                                    } else null
                                }
                                snapshot?.let(report)
                            }
                        }
                    }
                    workers.awaitAll()
                }
            } finally { journal.flush() }
        }
        state = state.copy(phase = DownloadProgress.Phase.FINISHING, doneBytes = done)
        report(state)
        val installed = plans.map { InstalledDepot(it.depotID, it.manifestGID, it.manifest.totalUncompressedSize) }
        val owners = HashMap<Int, Int>()
        for (d in depots) d.fromApp?.let { if (it != app.appID) owners[d.depotID] = it }
        val own = installed.filter { owners[it.depotID] == null }
        val shared = installed.filter { owners[it.depotID] != null }
        val custom = plans.flatMap { p -> p.manifest.files.filter { it.isCustomExecutable && !it.isDirectory }.map { it.filename.replace('/', '\\') } }
        if (custom.isNotEmpty()) SteamLog.event("[steam-record] custom-executables app=${app.appID} custom=${custom.size}")
        AppManifestWriter.write(steamApps, app.appID, app.name, folderName, app.buildID, accountID, prepared.totalUncompressed,
            own, shared.map { it.depotID to owners[it.depotID]!! }, custom)
        if (shared.isNotEmpty()) {
            var written = 0; var skipped = 0
            for (ownerID in owners.values.toSet().sorted()) {
                val list = shared.filter { owners[it.depotID] == ownerID }
                if (list.isEmpty()) continue
                val owner = app.sharedOwners[ownerID]
                if (owner == null) { skipped++; continue }
                val ownerFolder = SteamInstallFiles.safeFolderName(owner.installDir)
                AppManifestWriter.mergeOwner(steamApps, ownerID, owner.name, owner.buildID,
                    if (owner.installDir.isEmpty()) folderName else ownerFolder, accountID, list)
                written++
            }
            SteamLog.event("[steam-shared-record] app=${app.appID} shared=${shared.size} owners-written=$written owners-skipped=$skipped")
        }
        journalDir.deleteRecursively()
        SteamLog.event("[steam-depot] install complete app=${app.appID} bytes=${prepared.totalUncompressed} seconds=${(System.currentTimeMillis() - started) / 1000}")
        return installDir
    }

    private fun prepare(plans: List<DepotPlan>, installDir: File, journalDir: File): Prepared {
        val result = Prepared()
        val folded = HashMap<String, String>()
        val names = plans.map { "depot_${it.depotID}_${java.lang.Long.toUnsignedString(it.manifestGID)}.journal" }.toSet()
        journalDir.list()?.filter { it !in names }?.forEach { File(journalDir, it).delete() }
        for (plan in plans) {
            val journalFile = File(journalDir, "depot_${plan.depotID}_${java.lang.Long.toUnsignedString(plan.manifestGID)}.journal")
            val done = Journal.load(journalFile)
            val paths = ArrayList<String>(); val existing = ArrayList<Boolean>(); val pending = ArrayList<WorkItem>()
            for ((fi, file) in plan.manifest.files.withIndex()) {
                val relative = if (file.isSymlink) null else SteamInstallFiles.safeRelativePath(file.filename, folded)
                if (relative == null) {
                    if (!file.isSymlink) SteamLog.trace("rejected manifest path in depot ${plan.depotID}")
                    paths += ""; existing += false; continue
                }
                val target = File(installDir, relative)
                if (file.isDirectory) { target.mkdirs(); paths += ""; existing += false; continue }
                target.parentFile?.mkdirs()
                val before = if (target.isFile) target.length() else 0L
                val had = before > 0
                existing += had
                RandomAccessFile(target, "rw").use { if (it.length() != file.size) it.setLength(file.size) }
                paths += target.path
                result.fileCount++
                result.totalUncompressed += file.size
                var pendingBytes = 0L
                for ((ci, chunk) in file.chunks.withIndex()) {
                    val item = WorkItem(fi, ci)
                    val size = chunk.compressedSize.toLong() and 0xFFFFFFFFL
                    result.totalBytes += size
                    if (item.key in done) result.doneBytes += size
                    else { pending += item; pendingBytes += chunk.uncompressedSize.toLong() and 0xFFFFFFFFL }
                }
                result.remainingUncompressed += if (had) maxOf(0L, file.size - before) else pendingBytes
            }
            result.paths += paths; result.existing += existing; result.pending += pending; result.journals += journalFile
        }
        return result
    }

    private fun chunkAlreadyPresent(chunk: DepotManifest.ChunkEntry, path: String): Boolean {
        val len = chunk.uncompressedSize
        if (chunk.sha.size != 20 || len <= 0 || len > ContentDecryptor.MAX_CHUNK_BYTES) return false
        return try {
            RandomAccessFile(path, "r").use { f ->
                if (f.length() < chunk.offset + len) return false
                val buf = ByteArray(len)
                f.seek(chunk.offset)
                f.readFully(buf)
                ContentDecryptor.sha1(buf).contentEquals(chunk.sha)
            }
        } catch (_: Exception) { false }
    }

    private suspend fun fetchChunk(chunk: DepotManifest.ChunkEntry, plan: DepotPlan, path: String, attempts: Int, seed: Int) {
        var last: Exception = SteamException(SteamError.ChunkDownloadFailed("No content server responded."))
        for (attempt in 0 until maxOf(1, attempts)) {
            coroutineContext.ensureActive()
            val order = plan.health.order(plan.hosts, seed)
            val host = order[attempt % order.size]
            val url = "$host/depot/${plan.depotID}/chunk/${chunk.shaHex}${plan.auth[host] ?: ""}"
            try {
                val response = http.get(url, timeoutMs = 30_000, maxBytes = ContentDecryptor.MAX_CHUNK_BYTES.toLong() + 1024)
                if (response.code !in 200..299) throw SteamException(SteamError.ChunkDownloadFailed("Content server returned HTTP ${response.code}."))
                val data = ContentDecryptor.processChunk(response.body, plan.key, chunk.crc, chunk.uncompressedSize)
                if (data.size != chunk.uncompressedSize) throw SteamException(SteamError.ChecksumMismatch)
                write(data, path, chunk.offset)
                plan.health.recordSuccess(host)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                val reason = when (val err = (e as? SteamException)?.error) {
                    is SteamError.ChunkDecodeFailed -> "decode-${err.format}"
                    SteamError.ChecksumMismatch -> "checksum"
                    is SteamError.ChunkDownloadFailed -> "http"
                    else -> e.javaClass.simpleName
                }
                plan.health.recordFailure(host, reason)
                SteamLog.trace("chunk attempt ${attempt + 1} failed: $reason")
                if (attempt + 1 < attempts) delay((attempt + 1) * 400L)
            }
        }
        throw last
    }

    private fun write(data: ByteArray, path: String, offset: Long) {
        FileChannel.open(File(path).toPath(), StandardOpenOption.WRITE).use { ch ->
            val buf = ByteBuffer.wrap(data)
            var pos = offset
            while (buf.hasRemaining()) pos += ch.write(buf, pos)
        }
    }

    // MARK: Manifest

    private suspend fun fetchManifest(depotID: Int, appID: Int, gid: Long, key: ByteArray, hosts: List<String>): DepotManifest {
        val requestCode = manifestRequestCode(depotID, appID, gid)
        var last: Exception = SteamException(SteamError.ManifestFetchFailed("No content server returned the manifest."))
        for (host in hosts.take(6)) {
            coroutineContext.ensureActive()
            val auth = cdnAuthFragment(depotID, appID, host)
            val code = if (requestCode == 0L) "" else "/${java.lang.Long.toUnsignedString(requestCode)}"
            try {
                val response = http.get("$host/depot/$depotID/manifest/${java.lang.Long.toUnsignedString(gid)}/5$code$auth", timeoutMs = 60_000, maxBytes = 256L shl 20)
                if (response.code !in 200..299) throw SteamException(SteamError.ManifestFetchFailed("HTTP ${response.code}"))
                val cache = depotCache
                return withContext(Dispatchers.Default) {
                    val (manifest, payload) = parseManifestKeepingPayload(response.body, depotID, gid, key)
                    if (cache != null && manifest.files.any { it.isCustomExecutable }) {
                        val f = File(cache, "${depotID}_${java.lang.Long.toUnsignedString(gid)}.manifest")
                        if (f.length() != payload.size.toLong()) try { atomicWrite(f, payload) } catch (_: Exception) {}
                    }
                    manifest
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                SteamLog.trace("manifest from a content server failed: ${e.message}")
            }
        }
        throw last
    }

    private suspend fun depotKey(depotID: Int, appID: Int): ByteArray {
        lock.withLock { depotKeys[depotID] }?.let { return it }
        session.ensureConnected()
        val response = session.sendAndWait(EMsg.CLIENT_GET_DEPOT_DECRYPTION_KEY, CmMessages.depotKeyRequest(depotID, appID), 15_000)
        val r = CmMessages.depotKeyResponse(response.body)
        if (r.eresult != EResult.OK || r.key.size != 32) {
            SteamLog.event("[steam-depot] depot-key refused app=$appID depot=$depotID eresult=${r.eresult}")
            throw SteamException(SteamError.DepotKeyNotFound(depotID))
        }
        lock.withLock { depotKeys[depotID] = r.key }
        return r.key
    }

    private suspend fun manifestRequestCode(depotID: Int, appID: Int, gid: Long): Long {
        session.ensureConnected()
        return CmMessages.manifestRequestCodeResponse(
            session.callServiceMethod(ServiceMethod.GET_MANIFEST_REQUEST_CODE, CmMessages.manifestRequestCode(appID, depotID, gid), 15_000))
    }

    private suspend fun contentServers(appID: Int): List<String> {
        val hosts = ArrayList<String>()
        try {
            val r = http.get("https://api.steampowered.com/IContentServerDirectoryService/GetServersForSteamPipe/v1/?cell_id=${session.cellID}&max_servers=20")
            if (r.code !in 200..299) throw SteamException(SteamError.ChunkDownloadFailed("Content directory HTTP ${r.code}"))
            val servers = Json.parse(String(r.body, Charsets.UTF_8)).obj("response").arr("servers")
                ?: throw SteamException(SteamError.ChunkDownloadFailed("Content directory returned unexpected data"))
            var skippedHttp = 0; var skippedOther = 0
            for (server in servers) {
                val host = server.str("vhost") ?: server.str("host") ?: ""
                if (!usableContentHost(host)) { skippedOther++; continue }
                when (eligibility(server, appID)) {
                    Eligibility.USABLE -> {}
                    Eligibility.NO_HTTPS -> { skippedHttp++; continue }
                    Eligibility.OTHER -> { skippedOther++; continue }
                }
                val entry = "https://$host"
                if (entry !in hosts) hosts += entry
            }
            SteamLog.event("[steam-cdn] offered=${servers.size} usable=${hosts.size} skipped-no-https=$skippedHttp skipped-other=$skippedOther")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            SteamLog.trace("content directory failed: ${e.message}")
        }
        val fallback = "https://steampipe.akamaized.net"
        if (fallback !in hosts) hosts += fallback
        return hosts
    }

    enum class Eligibility { USABLE, NO_HTTPS, OTHER }

    private suspend fun cdnAuthFragment(depotID: Int, appID: Int, host: String): String {
        val cacheKey = "$depotID|$host"
        lock.withLock { cdnAuth[cacheKey] }?.let { return it }
        val bare = try { URI(host).host ?: host } catch (_: Exception) { host }
        val fragment = try {
            session.ensureConnected()
            val token = CmMessages.cdnAuthTokenResponse(session.callServiceMethod(ServiceMethod.GET_CDN_AUTH_TOKEN, CmMessages.cdnAuthToken(depotID, bare, appID), 10_000))
            if (token.isEmpty()) "" else if (token.startsWith("?") || token.startsWith("&")) token else "?auth=$token"
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            SteamLog.trace("content authorization request failed; continuing without a token")
            ""
        }
        lock.withLock { cdnAuth[cacheKey] = fragment }
        return fragment
    }

    companion object {
        fun hasPartialDownload(appID: Int, steamApps: File): Boolean =
            File(steamApps, "downloading/$appID").list()?.any { it.endsWith(".journal") } == true

        fun usableContentHost(host: String): Boolean {
            if (!host.contains('.') || host.contains(' ') || host.contains('/') || host.contains('*') || host.contains("lancache") || host.contains(':')) return false
            return host.endsWith(".steamcontent.com") || host.endsWith(".akamaized.net") || host.endsWith(".steamstatic.com")
        }

        fun eligibility(server: Any?, appID: Int): Eligibility {
            server.str("https_support")?.lowercase()?.let { if (it != "mandatory" && it != "optional") return Eligibility.NO_HTTPS }
            if (server.bool("use_as_proxy") == true) return Eligibility.OTHER
            val allowed = server.arr("allowed_app_ids")
            if (!allowed.isNullOrEmpty() && allowed.none { (it as? Long)?.toInt() == appID || it == appID.toString() }) return Eligibility.OTHER
            return Eligibility.USABLE
        }

        /** The manifest and the binary bytes it was parsed from (unzipped, decrypted). */
        fun parseManifestKeepingPayload(raw: ByteArray, depotID: Int, gid: Long, key: ByteArray): Pair<DepotManifest, ByteArray> {
            val payload = DepotManifest.unzipPayload(raw)
            try {
                val plain = DepotManifest.parse(depotID, gid, payload, key)
                if (plain.files.isNotEmpty()) return plain to payload
            } catch (_: Exception) {}
            // Older depots encrypt the whole manifest with the depot key.
            val decrypted = ContentDecryptor.decrypt(payload, key)
            val inflated = try { ContentDecryptor.decompress(decrypted, 0) } catch (_: Exception) { decrypted }
            return DepotManifest.parse(depotID, gid, inflated, key) to inflated
        }
    }
}

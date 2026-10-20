// SPDX-License-Identifier: GPL-3.0-or-later
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SteamCloud.swift and the Steam Cloud part of
// SteamOwnedLibrary.swift: Steam Cloud saves for games in the Steam library of
// the Wine prefix. It asks Steam which save files the account has in the cloud
// for a game, finds the same files in the prefix and compares them, then
// downloads and uploads what changed on one side only. A save that changed on
// both sides, or that differs with no record of an earlier sync, waits for the
// user's choice. A file a transfer replaces is backed up first.

package org.pocketarcade.madeira.steam

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.Inflater

/** One file of an app's Steam Cloud, as Cloud.GetAppFileChangelist lists it. */
data class CloudFile(val prefix: String, val name: String, val sha: ByteArray, val timestamp: Long, val size: Long, val persistState: Int) {
    val path: String get() = prefix + name
    override fun equals(other: Any?) = other is CloudFile && path == other.path && sha.contentEquals(other.sha) && size == other.size && timestamp == other.timestamp
    override fun hashCode() = path.hashCode()
}

class CloudListing(val changeNumber: Long, val files: List<CloudFile>) {
    companion object {
        fun request(appID: Int): ByteArray = proto { uint32(1, appID); uint64(2, 0) }

        fun parse(data: ByteArray): CloudListing {
            var change = 0L
            val prefixes = ArrayList<String>()
            val raw = ArrayList<Pair<CloudFile, Int?>>()
            data.proto().fields { f ->
                when {
                    f == 1 && wire == Wire.VARINT -> change = varint()
                    f == 4 && wire == Wire.LENGTH -> prefixes += string()
                    f == 2 && wire == Wire.LENGTH -> {
                        var name = ""; var sha = ByteArray(0); var ts = 0L; var size = 0L; var persist = 0; var index: Int? = null
                        message().fields { g ->
                            when {
                                g == 1 && wire == Wire.LENGTH -> name = string()
                                g == 2 && wire == Wire.LENGTH -> sha = bytes()
                                g == 3 && wire == Wire.VARINT -> ts = varint()
                                g == 4 && wire == Wire.VARINT -> size = varint()
                                g == 5 && wire == Wire.VARINT -> persist = int32()
                                g == 7 && wire == Wire.VARINT -> index = int32()
                                else -> skip()
                            }
                        }
                        raw += CloudFile("", name, sha, ts, size, persist) to index
                        if (raw.size > 20_000) throw SteamException(SteamError.ManifestFetchFailed("Too many Steam Cloud files."))
                    }
                    else -> skip()
                }
            }
            return CloudListing(change, raw.map { (file, i) -> if (i != null && i in prefixes.indices) file.copy(prefix = prefixes[i]) else file })
        }
    }
}

/** Maps Steam's save folders to folders of the Wine prefix. */
class CloudPaths(
    val drive: File,
    /** drive_c/users/<name> */
    val userFolder: File,
    val installFolder: File,
    /** Steam's folder for Steam Cloud API files: userdata/<account>/<app>/remote. */
    val remoteFolder: File,
    val steamID: Long,
    val overrides: List<RootOverride>,
) {
    fun folder(root: String): File? = when (root.lowercase()) {
        "gameinstall" -> installFolder
        "winmydocuments" -> File(userFolder, "Documents")
        "winappdatalocal" -> File(userFolder, "AppData/Local")
        "winappdatalocallow" -> File(userFolder, "AppData/LocalLow")
        "winappdataroaming" -> File(userFolder, "AppData/Roaming")
        "winsavedgames" -> File(userFolder, "Saved Games")
        else -> null
    }

    fun components(relative: String): List<String>? {
        val text = relative.replace('\\', '/')
            .replace("{64BitSteamID}", java.lang.Long.toUnsignedString(steamID))
            .replace("{Steam3AccountID}", (steamID and 0xFFFFFFFFL).toString())
        val parts = text.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 32 || parts.any { it == "." || it == ".." || it.contains(':') || it.toByteArray().size > 255 }) return null
        return parts
    }

    /** The base folder and parts a cloud path maps to, with the Windows root override applied. */
    fun location(cloudPath: String): Pair<File, List<String>>? {
        var (root, rest) = split(cloudPath)
        if (root != null) {
            overrides.firstOrNull { it.root.equals(root, true) && it.os.equals("Windows", true) }?.let { o ->
                if (o.useInstead.isNotEmpty()) root = o.useInstead
                if (o.addPath.isNotEmpty()) rest = o.addPath + "/" + rest
            }
        }
        val parts = components(rest) ?: return null
        val r = root ?: return remoteFolder to parts
        val base = folder(r) ?: return null
        return base to parts
    }

    companion object {
        /** The Windows user folder games write to: the newest of the prefix's own users. */
        fun userFolder(drive: File, preferred: String? = null): File? {
            val users = File(drive, "users")
            val names = users.list()?.filter { !it.startsWith(".") && !it.equals("Public", true) } ?: return null
            preferred?.let { p -> names.firstOrNull { it.equals(p, true) }?.let { return File(users, it) } }
            val newest = names.maxByOrNull { File(users, "$it/AppData").lastModified() } ?: return null
            return File(users, newest)
        }

        fun canonical(root: String): String =
            listOf("GameInstall", "WinMyDocuments", "WinAppDataLocal", "WinAppDataLocalLow", "WinAppDataRoaming", "WinSavedGames")
                .firstOrNull { it.equals(root, true) } ?: root

        /** "%Root%rest" -> (Root, rest); no placeholder: (null, path), Steam's remote folder. */
        fun split(path: String): Pair<String?, String> {
            if (!path.startsWith("%")) return null to path
            val end = path.indexOf('%', 1)
            if (end < 0) return null to path
            return path.substring(1, end) to path.substring(end + 1)
        }

        /** Follows names case-insensitively, as Wine does on a case-sensitive file system. */
        fun resolve(base: File, parts: List<String>): File {
            var f = base
            for (part in parts) {
                val exact = File(f, part)
                f = if (exact.exists()) exact else f.list()?.firstOrNull { it.equals(part, true) }?.let { File(f, it) } ?: exact
            }
            return f
        }
    }
}

data class CloudEntry(
    val path: String,
    val kind: Kind,
    val cloudSize: Long = 0,
    val cloudTime: Long = 0,
    val localSize: Long = 0,
    val localTime: Long = 0,
    val cloudSHA: String = "",
    val localSHA: String = "",
) {
    enum class Kind { SAME, DIFFER, CLOUD_ONLY, LOCAL_ONLY }
    val key: String get() = path.lowercase()
    val name: String get() = path.substringAfterLast('/')
}

class CloudAudit(val changeNumber: Long, val cloudFiles: Int, val entries: List<CloudEntry>, val unmapped: List<String>) {
    fun count(kind: CloudEntry.Kind) = entries.count { it.kind == kind }
    val summary: String get() = "cloud-change=$changeNumber cloud-files=$cloudFiles same=${count(CloudEntry.Kind.SAME)} differ=${count(CloudEntry.Kind.DIFFER)} " +
        "missing-local=${count(CloudEntry.Kind.CLOUD_ONLY)} local-only=${count(CloudEntry.Kind.LOCAL_ONLY)} unmapped=${unmapped.size}"

    companion object {
        fun sha1(file: File): String? = try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            md.digest().hex()
        } catch (_: Exception) { null }

        private fun key(f: File) = (try { f.canonicalPath } catch (_: Exception) { f.absolutePath }).lowercase()

        /** A shell-style wildcard (* and ?), without case. */
        fun matches(pattern: String, name: String): Boolean {
            val regex = StringBuilder("(?i)")
            for (c in pattern) when (c) { '*' -> regex.append(".*"); '?' -> regex.append('.'); else -> regex.append(Regex.escape(c.toString())) }
            return Regex(regex.toString()).matches(name)
        }

        /** Compares the listing with the prefix. Reads files; changes nothing. */
        fun run(listing: CloudListing, saveFiles: List<SaveFile>, paths: CloudPaths): CloudAudit {
            val entries = ArrayList<CloudEntry>()
            val unmapped = ArrayList<String>()
            val known = HashSet<String>()
            var cloudFiles = 0
            for (file in listing.files) {
                if (file.persistState != 0) continue
                cloudFiles++
                val place = paths.location(file.path) ?: run { unmapped += file.path; null } ?: continue
                val f = CloudPaths.resolve(place.first, place.second)
                known += key(f)
                var entry = CloudEntry(file.path, CloudEntry.Kind.CLOUD_ONLY, cloudSize = file.size, cloudTime = file.timestamp, cloudSHA = file.sha.hex())
                if (f.isFile) {
                    val sha = sha1(f) ?: ""
                    entry = entry.copy(localSize = f.length(), localTime = f.lastModified() / 1000, localSHA = sha,
                        kind = if (f.length() == file.size && sha == entry.cloudSHA) CloudEntry.Kind.SAME else CloudEntry.Kind.DIFFER)
                }
                entries += entry
            }
            class Folder(val label: String, val dir: File, val pattern: String, val recursive: Boolean)
            val folders = ArrayList<Folder>()
            for (save in saveFiles) {
                if (save.platforms.isNotEmpty() && "windows" !in save.platforms) continue
                val place = paths.location("%${save.root}%" + (if (save.path.isEmpty()) "x" else save.path + "/x")) ?: continue
                if (paths.overrides.any { it.root.equals(save.root, true) && it.os.equals("Windows", true) }) continue
                val dir = CloudPaths.resolve(place.first, place.second.dropLast(1))
                val folderPath = if (save.path.isEmpty()) "" else (paths.components(save.path)?.joinToString("/") ?: save.path) + "/"
                folders += Folder("%${CloudPaths.canonical(save.root)}%$folderPath", dir, save.pattern.ifEmpty { "*" }, save.recursive)
            }
            folders += Folder("", paths.remoteFolder, "*", true)
            val seen = HashSet<String>()
            for (folder in folders) {
                if (!folder.dir.isDirectory) continue
                val base = try { folder.dir.canonicalPath } catch (_: Exception) { continue }
                var visited = 0
                val walk = if (folder.recursive) folder.dir.walkTopDown().maxDepth(32) else folder.dir.walkTopDown().maxDepth(1)
                for (f in walk) {
                    if (++visited > 5_000) break
                    if (!f.isFile || !matches(folder.pattern, f.name)) continue
                    val k = key(f)
                    if (k in known || !seen.add(k)) continue
                    val relative = f.canonicalPath.removePrefix(base).trimStart('/')
                    entries += CloudEntry(folder.label + relative, CloudEntry.Kind.LOCAL_ONLY, localSize = f.length(), localTime = f.lastModified() / 1000,
                        localSHA = sha1(f) ?: "")
                }
            }
            return CloudAudit(listing.changeNumber, cloudFiles, entries, unmapped)
        }
    }
}

/** Which files changed on one side only (copied without asking) and which need the user's choice. */
class CloudPlan(val download: List<CloudEntry>, val upload: List<CloudEntry>, val conflicts: List<CloudEntry>, val settled: Map<String, String>) {
    companion object {
        const val MISSING = "missing:"
        const val DELETED = "deleted:"

        /** `baseline`: SHA-1 of each file when it was last the same on both sides, or a mark. */
        fun make(audit: CloudAudit, baseline: Map<String, String>): CloudPlan {
            val download = ArrayList<CloudEntry>(); val upload = ArrayList<CloudEntry>(); val conflicts = ArrayList<CloudEntry>()
            val settled = HashMap<String, String>()
            for (e in audit.entries) {
                val known = baseline[e.key]
                val marked = known != null && (known.startsWith(MISSING) || known.startsWith(DELETED))
                when (e.kind) {
                    CloudEntry.Kind.SAME -> settled[e.key] = e.cloudSHA
                    CloudEntry.Kind.DIFFER -> when {
                        known != null && !marked && known == e.cloudSHA && known != e.localSHA -> upload += e
                        known != null && !marked && known == e.localSHA && known != e.cloudSHA -> download += e
                        else -> conflicts += e
                    }
                    CloudEntry.Kind.CLOUD_ONLY -> when {
                        known == null -> download += e
                        known == DELETED + e.cloudSHA -> {}
                        known.startsWith(DELETED) -> download += e
                        else -> { conflicts += e; if (!marked) settled[e.key] = MISSING + known }
                    }
                    CloudEntry.Kind.LOCAL_ONLY -> if (known == null && e.localSHA.isNotEmpty()) upload += e
                }
            }
            return CloudPlan(download, upload, conflicts, settled)
        }
    }
}

/** Where a cloud file can be fetched (Cloud.ClientFileDownload). */
class CloudDownloadInfo(val fileSize: Long, val rawSize: Long, val host: String, val path: String, val https: Boolean,
                        val headers: List<Pair<String, String>>, val encrypted: Boolean) {
    val url: String? get() = if (host.isEmpty() || host.contains('/') || host.contains('@')) null
        else (if (https) "https://" else "http://") + host + (if (path.startsWith("/")) path else "/$path")

    companion object {
        fun request(appID: Int, path: String): ByteArray = proto { uint32(1, appID); string(2, path) }

        fun parse(data: ByteArray): CloudDownloadInfo {
            var fileSize = 0L; var rawSize = 0L; var host = ""; var path = ""; var https = true; var encrypted = false
            val headers = ArrayList<Pair<String, String>>()
            data.proto().fields { f ->
                when {
                    f == 2 && wire == Wire.VARINT -> fileSize = varint()
                    f == 3 && wire == Wire.VARINT -> rawSize = varint()
                    f == 7 && wire == Wire.LENGTH -> host = string()
                    f == 8 && wire == Wire.LENGTH -> path = string()
                    f == 9 && wire == Wire.VARINT -> https = bool()
                    f == 11 && wire == Wire.VARINT -> encrypted = bool()
                    f == 10 && wire == Wire.LENGTH -> {
                        var n = ""; var v = ""
                        message().fields { g -> when { g == 1 && wire == Wire.LENGTH -> n = string(); g == 2 && wire == Wire.LENGTH -> v = string(); else -> skip() } }
                        if (n.isNotEmpty() && headers.size < 32) headers += n to v
                    }
                    else -> skip()
                }
            }
            return CloudDownloadInfo(fileSize, rawSize, host, path, https, headers, encrypted)
        }
    }
}

/** One HTTP request of a file upload (ClientCloudFileUploadBlockDetails). */
class CloudUploadBlock(val host: String, val path: String, val https: Boolean, val method: Int, val headers: List<Pair<String, String>>,
                       val offset: Long, val length: Long, val explicitBody: ByteArray) {
    val url: String? get() = if (host.isEmpty() || host.contains('/') || host.contains('@')) null
        else (if (https) "https://" else "http://") + host + (if (path.startsWith("/")) path else "/$path")

    companion object {
        fun parse(data: ByteArray): Pair<Boolean, List<CloudUploadBlock>> {
            var encrypt = false
            val blocks = ArrayList<CloudUploadBlock>()
            data.proto().fields { f ->
                when {
                    f == 1 && wire == Wire.VARINT -> encrypt = bool()
                    f == 2 && wire == Wire.LENGTH -> {
                        var host = ""; var path = ""; var https = true; var method = 0; var offset = 0L; var length = 0L; var body = ByteArray(0)
                        val headers = ArrayList<Pair<String, String>>()
                        message().fields { g ->
                            when {
                                g == 1 && wire == Wire.LENGTH -> host = string()
                                g == 2 && wire == Wire.LENGTH -> path = string()
                                g == 3 && wire == Wire.VARINT -> https = bool()
                                g == 4 && wire == Wire.VARINT -> method = int32()
                                g == 6 && wire == Wire.VARINT -> offset = varint()
                                g == 7 && wire == Wire.VARINT -> length = varint()
                                g == 8 && wire == Wire.LENGTH -> body = bytes()
                                g == 5 && wire == Wire.LENGTH -> {
                                    var n = ""; var v = ""
                                    message().fields { h -> when { h == 1 && wire == Wire.LENGTH -> n = string(); h == 2 && wire == Wire.LENGTH -> v = string(); else -> skip() } }
                                    if (n.isNotEmpty() && headers.size < 32) headers += n to v
                                }
                                else -> skip()
                            }
                        }
                        blocks += CloudUploadBlock(host, path, https, method, headers, offset, length, body)
                        if (blocks.size > 4096) throw SteamException(SteamError.ManifestFetchFailed("Steam asked for too many upload parts."))
                    }
                    else -> skip()
                }
            }
            return encrypt to blocks
        }
    }
}

object CloudTransfer {
    const val MAX_FILE_BYTES = 256 shl 20

    /** The first entry of a ZIP archive (a compressed cloud file): stored or deflated. */
    fun unzipFirstEntry(data: ByteArray, rawSize: Int): ByteArray? {
        if (data.size < 30 || data.u32le(0) != 0x04034b50L || rawSize < 0 || rawSize > MAX_FILE_BYTES) return null
        val method = data.u16le(8)
        val start = 30 + data.u16le(26) + data.u16le(28)
        var packed = data.u32le(18).toInt()
        if (packed == 0) {
            var at = data.size - 46
            var found = false
            while (at >= start) { if (data.u32le(at) == 0x02014b50L) { packed = data.u32le(at + 20).toInt(); found = true; break }; at-- }
            if (!found) return null
        }
        if (start > data.size || packed < 0 || start + packed > data.size) return null
        if (method == 0) return if (packed == rawSize) data.copyOfRange(start, start + packed) else null
        if (method != 8) return null
        if (rawSize == 0) return ByteArray(0)
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data, start, packed)
            val out = ByteArray(rawSize)
            var n = 0
            while (n < rawSize && !inflater.finished()) {
                val k = inflater.inflate(out, n, rawSize - n)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
            if (n == rawSize) out else null
        } catch (_: Exception) { null } finally { inflater.end() }
    }

    /** One cloud file's bytes, checked against the SHA-1 the cloud's list gives. */
    suspend fun fetch(http: HttpClient, info: CloudDownloadInfo, expectedSHA: String): ByteArray {
        if (info.encrypted) throw SteamException(SteamError.ManifestFetchFailed("This Steam Cloud file is encrypted, which is not supported."))
        val url = info.url
        if (url == null || info.fileSize > MAX_FILE_BYTES || info.rawSize > MAX_FILE_BYTES) throw SteamException(SteamError.ManifestFetchFailed("Steam gave no usable address for this Steam Cloud file."))
        val r = http.send("GET", url, info.headers, null, 60_000)
        if (r.code != 200) throw SteamException(SteamError.ManifestFetchFailed("Steam Cloud download failed (HTTP ${r.code})."))
        var file = r.body
        val zipped = file.size >= 4 && file[0] == 0x50.toByte() && file[1] == 0x4B.toByte() && file[2].toInt() == 3 && file[3].toInt() == 4
        if (info.fileSize != info.rawSize || (file.size.toLong() != info.rawSize && zipped)) {
            file = unzipFirstEntry(file, info.rawSize.toInt()) ?: throw SteamException(SteamError.ManifestFetchFailed("A Steam Cloud file could not be unpacked."))
        }
        if (ContentDecryptor.sha1(file).hex() != expectedSHA) throw SteamException(SteamError.ManifestFetchFailed("A Steam Cloud file did not match its checksum."))
        return file
    }

    /** Puts bytes in place; an existing file is first copied under [backup] (same relative path). */
    fun place(file: ByteArray, target: File, backup: File, relative: List<String>, time: Long) {
        if (target.exists()) {
            val copy = relative.fold(backup) { f, p -> File(f, p) }
            copy.parentFile?.mkdirs()
            target.copyTo(copy, overwrite = true)
        }
        atomicWrite(target, file)
        if (time > 0) target.setLastModified(time * 1000)
    }

    /** Sends a file's parts where Steam asked for them (POST is 3; otherwise PUT). Returns the statuses. */
    suspend fun send(http: HttpClient, file: ByteArray, blocks: List<CloudUploadBlock>): List<Int> {
        val statuses = ArrayList<Int>()
        for (b in blocks) {
            val url = b.url ?: throw SteamException(SteamError.ManifestFetchFailed("Steam gave no usable address for an upload."))
            val body = if (b.explicitBody.isNotEmpty()) b.explicitBody else {
                val start = minOf(b.offset, file.size.toLong()).toInt()
                val end = minOf(b.offset + b.length, file.size.toLong()).toInt()
                if ((end - start).toLong() != b.length) throw SteamException(SteamError.ManifestFetchFailed("Steam asked for a part outside the file."))
                file.copyOfRange(start, end)
            }
            val r = http.send(if (b.method == 3) "POST" else "PUT", url, b.headers, body)
            statuses += r.code
            if (r.code !in 200..299) throw SteamException(SteamError.ManifestFetchFailed("Steam Cloud upload failed (HTTP ${r.code})."))
        }
        return statuses
    }
}

/** One game's Steam Cloud state, as its page shows it. */
data class CloudState(
    val phase: Phase = Phase.Checking,
    val conflicts: List<CloudEntry> = emptyList(),
    val audit: CloudAudit? = null,
    val checkedAt: Long = 0,
    val lastDownload: Pair<Int, Int>? = null,
    val lastUpload: Int? = null,
    val problem: String? = null,
) {
    sealed class Phase {
        object Checking : Phase()
        object Ready : Phase()
        data class Failed(val message: String) : Phase()
        data class Downloading(val done: Int, val of: Int) : Phase()
        data class Uploading(val done: Int, val of: Int) : Phase()
    }
}

/**
 * Steam Cloud sync for one account over the app's CM session. [baselines] persists the
 * record of what was last the same on both sides; [backups] is where replaced copies go.
 */
class SteamCloud(
    private val session: SteamSession,
    private val fetcher: LibraryFetcher,
    private val http: HttpClient,
    private val backups: File,
    private val baselines: SteamStore,
    private val clientID: () -> Long,
    private val machineName: String,
) {
    class Context(val info: AppInfo, val paths: CloudPaths)

    private fun baselineKey(appID: Int) = "cloud-baseline-$appID"

    fun baseline(appID: Int): Map<String, String> {
        val text = baselines.get(baselineKey(appID)) ?: return emptyMap()
        return text.lines().mapNotNull { line -> line.indexOf('\t').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }.toMap()
    }

    fun recordBaseline(appID: Int, settled: Map<String, String>) {
        if (settled.isEmpty()) return
        val files = HashMap(baseline(appID))
        var changed = false
        for ((k, v) in settled) if (files[k] != v && !k.contains('\n') && !k.contains('\t')) { files[k] = v; changed = true }
        if (changed) baselines.put(baselineKey(appID), files.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    fun clearBaselines(appIDs: Collection<Int>) { for (id in appIDs) baselines.put(baselineKey(id), null) }

    suspend fun context(appID: Int, game: InstalledGame, drive: File, clientRoot: File): Context? {
        val user = CloudPaths.userFolder(drive) ?: return null
        session.ensureConnected()
        val steamID = session.steamID
        val info = fetcher.appInfo(appID) ?: return null
        val paths = CloudPaths(drive, user, File(drive, "${game.library}/common/${game.installDir}"),
            File(clientRoot, "userdata/${steamID and 0xFFFFFFFFL}/$appID/remote"), steamID, info.rootOverrides)
        return Context(info, paths)
    }

    suspend fun listing(appID: Int): CloudListing =
        CloudListing.parse(session.callServiceMethod(ServiceMethod.CLOUD_GET_APP_FILE_CHANGELIST, CloudListing.request(appID), 20_000))

    /** Compares and returns the plan; records files found identical. */
    suspend fun check(appID: Int, context: Context): Pair<CloudAudit, CloudPlan> {
        val audit = CloudAudit.run(listing(appID), context.info.saveFiles, context.paths)
        val plan = CloudPlan.make(audit, baseline(appID))
        recordBaseline(appID, plan.settled)
        SteamLog.event("[steam-cloud] app=$appID ${audit.summary} | plan download=${plan.download.size} upload=${plan.upload.size} ask=${plan.conflicts.size}")
        return audit to plan
    }

    suspend fun file(appID: Int, entry: CloudEntry): ByteArray =
        CloudTransfer.fetch(http, CloudDownloadInfo.parse(session.callServiceMethod(ServiceMethod.CLOUD_CLIENT_FILE_DOWNLOAD,
            CloudDownloadInfo.request(appID, entry.path), 20_000)), entry.cloudSHA)

    fun backupFolder(appID: Int, name: String): File {
        val safe = name.map { if ("/\\:*?\"<>|".contains(it)) '-' else it }.joinToString("").take(80).trim().trim('.')
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", java.util.Locale.ROOT).format(java.util.Date())
        return File(File(backups, if (safe.isEmpty()) "$appID" else "$safe ($appID)"), stamp)
    }

    /** Downloads cloud files into the prefix, backing up what they replace. Returns how many arrived. */
    suspend fun download(appID: Int, wanted: List<CloudEntry>, context: Context, backup: File, progress: (Int) -> Unit): Pair<Int, Int> {
        var done = 0; var backedUp = 0
        val settled = HashMap<String, String>()
        try {
            for (e in wanted) {
                val (base, parts) = context.paths.location(e.path) ?: continue
                val bytes = file(appID, e)
                val target = CloudPaths.resolve(base, parts)
                val existed = target.exists()
                CloudTransfer.place(bytes, target, File(backup, "device"), listOf(CloudPaths.split(e.path).first ?: "remote") + parts, e.cloudTime)
                done++; if (existed) backedUp++
                settled[e.key] = e.cloudSHA
                progress(done)
            }
        } finally { recordBaseline(appID, settled) }
        SteamLog.event("[steam-cloud] app=$appID downloaded files=$done backed-up=$backedUp")
        return done to backedUp
    }

    /** Uploads device saves as one batch, after keeping a copy of each cloud file it replaces. */
    suspend fun upload(appID: Int, wanted: List<CloudEntry>, context: Context, backup: File, progress: (Int) -> Unit): Int {
        var done = 0
        var batch = 0L
        suspend fun complete(ok: Boolean) {
            if (batch == 0L) return
            try { session.callServiceMethod(ServiceMethod.CLOUD_COMPLETE_APP_UPLOAD_BATCH, proto { uint32(1, appID); uint64(2, batch); uint32(3, if (ok) 1 else 2) }, 30_000) } catch (_: Exception) {}
        }
        try {
            for (e in wanted.filter { it.cloudSHA.isNotEmpty() }) {
                val (_, parts) = context.paths.location(e.path) ?: continue
                val bytes = file(appID, e)
                val target = (listOf(CloudPaths.split(e.path).first ?: "remote") + parts).fold(File(backup, "cloud")) { f, p -> File(f, p) }
                atomicWrite(target, bytes)
            }
            val begin = proto {
                uint32(1, appID); string(2, machineName)
                for (e in wanted) string(3, e.path)
                uint64(5, clientID()); uint64(6, context.info.buildID.toLong() and 0xFFFFFFFFL)
            }
            session.callServiceMethod(ServiceMethod.CLOUD_BEGIN_APP_UPLOAD_BATCH, begin, 30_000).proto().fields { f ->
                if (f == 1 && wire == Wire.VARINT) batch = varint() else skip()
            }
            if (batch == 0L) throw SteamException(SteamError.ManifestFetchFailed("Steam did not open an upload for this game."))
            for (e in wanted) {
                val (base, parts) = context.paths.location(e.path) ?: continue
                val f = CloudPaths.resolve(base, parts)
                val bytes = f.readBytes()
                if (bytes.size > CloudTransfer.MAX_FILE_BYTES) throw SteamException(SteamError.ManifestFetchFailed("A save is too large to upload."))
                val sha = ContentDecryptor.sha1(bytes)
                val request = proto {
                    uint32(1, appID); uint32(2, bytes.size); uint32(3, bytes.size); bytes(4, sha)
                    uint64(5, maxOf(0L, f.lastModified() / 1000)); string(6, e.path); uint32(7, -1); uint32(9, session.cellID); uint64(13, batch)
                }
                val (encrypt, blocks) = CloudUploadBlock.parse(session.callServiceMethod(ServiceMethod.CLOUD_CLIENT_BEGIN_FILE_UPLOAD, request, 30_000))
                if (encrypt) throw SteamException(SteamError.ManifestFetchFailed("Steam asked for an encrypted upload, which is not supported."))
                val sent = try { CloudTransfer.send(http, bytes, blocks); true } catch (_: Exception) { false }
                var committed = false
                session.callServiceMethod(ServiceMethod.CLOUD_CLIENT_COMMIT_FILE_UPLOAD, proto { bool(1, sent); uint32(2, appID); bytes(3, sha); string(4, e.path) }, 30_000)
                    .proto().fields { g -> if (g == 1 && wire == Wire.VARINT) committed = bool() else skip() }
                SteamLog.event("[steam-cloud] app=$appID upload file=${done + 1}/${wanted.size} bytes=${bytes.size} parts=${blocks.size} committed=${if (committed) 1 else 0}")
                if (!sent || !committed) throw SteamException(SteamError.ManifestFetchFailed("Steam did not accept a save file."))
                done++
                progress(done)
            }
            complete(true)
        } catch (e: Exception) {
            complete(false)
            throw e
        }
        return done
    }

    /** The user's choice for differing saves: the cloud's replace the device's, or the reverse. */
    suspend fun resolve(appID: Int, conflicts: List<CloudEntry>, useCloud: Boolean, context: Context, backup: File) {
        if (useCloud) download(appID, conflicts, context, backup) {}
        else {
            recordBaseline(appID, conflicts.filter { it.kind == CloudEntry.Kind.CLOUD_ONLY }.associate { it.key to CloudPlan.DELETED + it.cloudSHA })
            val present = conflicts.filter { it.kind != CloudEntry.Kind.CLOUD_ONLY }
            if (present.isNotEmpty()) upload(appID, present, context, backup) {}
        }
    }
}

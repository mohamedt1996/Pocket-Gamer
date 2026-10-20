// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Port of Madeira's SteamOwnedLibrary.swift and SteamGames.swift: the account's
// owned games (cached per account), Steam's playtime, the games installed in the
// Steam prefix, downloads (one at a time, resumable, paused for game sessions)
// and Steam Cloud saves. The app's own Steam connection is closed while a game
// session holds the account, so Valve's client is the only one logged on.

package org.pocketarcade.madeira.model

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.pocketarcade.madeira.core.DirectStart
import org.pocketarcade.madeira.core.PeInspector
import org.pocketarcade.madeira.steam.AppInfo
import org.pocketarcade.madeira.steam.CloudEntry
import org.pocketarcade.madeira.steam.CloudPlan
import org.pocketarcade.madeira.steam.CloudState
import org.pocketarcade.madeira.steam.DepotDownloader
import org.pocketarcade.madeira.steam.DownloadProgress
import org.pocketarcade.madeira.steam.InstalledGame
import org.pocketarcade.madeira.steam.LaunchOption
import org.pocketarcade.madeira.steam.LibraryFetcher
import org.pocketarcade.madeira.steam.Playtime
import org.pocketarcade.madeira.steam.SteamCloud
import org.pocketarcade.madeira.steam.SteamError
import org.pocketarcade.madeira.steam.SteamException
import org.pocketarcade.madeira.steam.SteamInstallFiles
import org.pocketarcade.madeira.steam.SteamInstallPaths
import org.pocketarcade.madeira.steam.SteamLog
import org.pocketarcade.madeira.steam.SteamSession
import java.io.File
import java.security.MessageDigest

/** One game of the account's library, as cached. */
data class OwnedGame(
    val id: Int,
    val name: String,
    val installDir: String,
    val buildID: Int,
    val libraryCapsule: String? = null,
    val libraryHero: String? = null,
    val headerImage: String? = null,
    val parentID: Int? = null,
    val launches: List<LaunchOption>? = null,
    val downloadBytes: Long = 0,
    val installBytes: Long = 0,
) {
    val folderName: String get() = SteamInstallFiles.safeFolderName(installDir.ifEmpty { "app_$id" })

    constructor(info: AppInfo) : this(info.appID, info.name, info.installDir, info.buildID, info.libraryCapsule, info.libraryHero,
        info.headerImage, info.parentID, info.launches, info.downloadSize(), info.installSize())

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("installDir", installDir); put("buildID", buildID)
        libraryCapsule?.let { put("capsule", it) }; libraryHero?.let { put("hero", it) }; headerImage?.let { put("header", it) }
        parentID?.let { put("parent", it) }; put("download", downloadBytes); put("install", installBytes)
        launches?.let { list ->
            put("launches", JSONArray(list.map { o ->
                JSONObject().apply {
                    put("executable", o.executable); o.index?.let { put("index", it) }; o.requiredDLC?.let { put("dlc", it) }
                    put("arguments", o.arguments); put("workingDir", o.workingDir); put("type", o.type)
                    put("oslist", o.oslist); put("osarch", o.osarch); put("betaKey", o.betaKey)
                }
            }))
        }
    }

    companion object {
        fun from(o: JSONObject): OwnedGame? {
            val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
            val launches = o.optJSONArray("launches")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val l = a.optJSONObject(i) ?: return@mapNotNull null
                    LaunchOption(l.optString("executable"), if (l.has("index")) l.optInt("index") else null, l.optString("dlc", "").ifEmpty { null },
                        l.optString("arguments"), l.optString("workingDir"), l.optString("type"), l.optString("oslist"), l.optString("osarch"), l.optString("betaKey"))
                }
            }
            return OwnedGame(id, o.optString("name"), o.optString("installDir"), o.optInt("buildID"),
                o.optString("capsule", "").ifEmpty { null }, o.optString("hero", "").ifEmpty { null }, o.optString("header", "").ifEmpty { null },
                if (o.has("parent")) o.optInt("parent") else null, launches, o.optLong("download"), o.optLong("install"))
        }
    }
}

/** Public store artwork (no account data), as Madeira's SteamGamesRules builds it. */
object SteamArtwork {
    private const val ASSET_BASE = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/"

    fun safe(name: String) = name.isNotEmpty() && name.length <= 256 && !name.startsWith("/") && !name.contains("..") &&
        name.all { it.code < 128 && (it.isLetterOrDigit() || it in "._-/") }

    private fun asset(id: Int, name: String?) = name?.takeIf(::safe)?.let { "$ASSET_BASE$id/$it" }

    /** Capsule candidates in order; a card tries them one after another. */
    fun capsules(appID: Int, owned: (Int) -> OwnedGame?): List<String> {
        val urls = LinkedHashSet<String>()
        fun direct(id: Int) {
            asset(id, owned(id)?.libraryCapsule)?.let { urls += it }
            urls += "https://cdn.cloudflare.steamstatic.com/steam/apps/$id/library_600x900.jpg"
        }
        direct(appID)
        owned(appID)?.parentID?.let { if (it != appID) direct(it) }
        asset(appID, owned(appID)?.headerImage)?.let { urls += it }
        urls += "https://cdn.cloudflare.steamstatic.com/steam/apps/$appID/header.jpg"
        return urls.toList()
    }

    fun heroes(appID: Int, owned: (Int) -> OwnedGame?): List<String> {
        val urls = LinkedHashSet<String>()
        asset(appID, owned(appID)?.libraryHero)?.let { urls += it }
        urls += "https://cdn.cloudflare.steamstatic.com/steam/apps/$appID/library_hero.jpg"
        owned(appID)?.parentID?.let { urls += "https://cdn.cloudflare.steamstatic.com/steam/apps/$it/library_hero.jpg" }
        asset(appID, owned(appID)?.headerImage)?.let { urls += it }
        urls += "https://cdn.cloudflare.steamstatic.com/steam/apps/$appID/header.jpg"
        return urls.toList()
    }
}

data class Download(val state: State, val progress: DownloadProgress = DownloadProgress()) {
    sealed class State {
        object Queued : State()
        object Active : State()
        object Paused : State()
        data class Failed(val message: String) : State()
    }
}

/** The Steam prefix: one Wine prefix holding Valve's client (Madeira Dock) and every Steam game. */
class SteamPrefix(context: Context) {
    val home = File(context.filesDir, "games/$ID")
    val prefix = File(home, "prefix")
    val drive = File(prefix, "drive_c")
    val steamApps: File get() = SteamInstallPaths.steamApps(drive)
    val clientRoot: File get() = File(drive, org.pocketarcade.madeira.core.SteamRuntimeFiles.RELATIVE_ROOT)
    val clientInstalled: Boolean get() = File(clientRoot, "steamclient64.dll").isFile && File(clientRoot, "steam.exe").isFile

    /** GameStore keeps a game.json per game folder; the Steam prefix has one marked hidden. */
    fun ensure() {
        home.mkdirs(); prefix.mkdirs()
        val json = File(home, "game.json")
        if (!json.isFile) json.writeText(JSONObject().apply {
            put("id", ID); put("title", "Steam"); put("exe", ""); put("graphics", "dxvk"); put("hidden", true)
        }.toString(2))
    }

    companion object {
        /** A fixed id in GameStore's format (32 hex digits). */
        const val ID = "57ea0000000000000000000000000001"
    }
}

class SteamLibrary(
    private val context: Context,
    private val scope: CoroutineScope,
    private val account: SteamAccount,
    private val session: SteamSession,
    private val fetcher: LibraryFetcher,
    private val downloader: DepotDownloader,
    private val cloudSync: SteamCloud,
    private val prefix: SteamPrefix,
    private val configs: SteamGameConfigs,
    private val settings: AppSettings,
) {
    private val _owned = MutableStateFlow<List<OwnedGame>>(emptyList())
    val owned: StateFlow<List<OwnedGame>> = _owned
    private val _installed = MutableStateFlow<List<InstalledGame>>(emptyList())
    val installed: StateFlow<List<InstalledGame>> = _installed
    private val _playtime = MutableStateFlow<Map<Int, Playtime>>(emptyMap())
    val playtime: StateFlow<Map<Int, Playtime>> = _playtime
    private val _downloads = MutableStateFlow<Map<Int, Download>>(emptyMap())
    val downloads: StateFlow<Map<Int, Download>> = _downloads
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _cloud = MutableStateFlow<Map<Int, CloudState>>(emptyMap())
    val cloud: StateFlow<Map<Int, CloudState>> = _cloud
    val error = MutableStateFlow<String?>(null)
    private var updatedAt = 0L

    private val queue = ArrayList<Int>()
    private var active: Pair<Int, Job>? = null
    private var inSession = false
    private val resumeAfterSession = HashSet<Int>()
    private var started = false
    private var cloudAudited = false
    private val cloudBusy = HashSet<Int>()

    /** Starts and stops the download notification service. */
    var onDownloadsChanged: ((active: Int?, progress: DownloadProgress?) -> Unit)? = null

    private val cacheFile = File(context.filesDir, "steam-library.json")
    private val playtimeFile = File(context.filesDir, "steam-playtime.json")

    fun game(appID: Int): OwnedGame? = _owned.value.firstOrNull { it.id == appID }
    fun installedGame(appID: Int): InstalledGame? = _installed.value.firstOrNull { it.appID == appID }
    val hasActiveDownload: Boolean get() = _downloads.value.values.any { it.state == Download.State.Active || it.state == Download.State.Queued }

    private fun accountKey(name: String?): String? = name?.let { n ->
        MessageDigest.getInstance("SHA-256").digest(n.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Once per app run: read the cache, scan the prefix, and refresh when the list is older than six hours. */
    fun start() {
        if (started) return
        started = true
        account.onChange = { scope.launch { signInChanged() } }
        scope.launch {
            if (account.signedIn) loadCaches()
            rescanInstalled()
            SteamLog.event("[steam-library] start signed-in=${if (account.signedIn) 1 else 0} cached=${_owned.value.size}")
            if (account.signedIn) {
                if (System.currentTimeMillis() - updatedAt > 6 * 3600_000L) refresh(interactive = false) else refreshPlaytime()
            }
        }
    }

    private suspend fun signInChanged() {
        if (!account.signedIn) {
            for (id in _downloads.value.keys.toList()) pause(id)
            try { session.disconnectGracefully() } catch (_: Exception) {}
            cacheFile.delete(); playtimeFile.delete()
            cloudSync.clearBaselines(_installed.value.map { it.appID })
            _owned.value = emptyList(); _playtime.value = emptyMap(); _downloads.value = emptyMap(); _cloud.value = emptyMap()
            updatedAt = 0
            SteamLog.event("[steam-library] signed out: list cleared")
        } else {
            try { session.disconnectGracefully() } catch (_: Exception) {}
            _owned.value = emptyList(); _playtime.value = emptyMap(); updatedAt = 0
            refresh(interactive = true)
        }
    }

    private suspend fun loadCaches() = withContext(Dispatchers.IO) {
        val key = accountKey(account.accountName.value)
        try {
            if (cacheFile.isFile && cacheFile.length() < 64L shl 20) {
                val o = JSONObject(cacheFile.readText())
                if (o.optInt("version") == 2 && o.optString("account") == key) {
                    val a = o.optJSONArray("games") ?: JSONArray()
                    _owned.value = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(OwnedGame::from) }
                    updatedAt = o.optLong("updated")
                }
            }
            if (_owned.value.isNotEmpty() && playtimeFile.isFile) {
                val o = JSONObject(playtimeFile.readText())
                _playtime.value = o.keys().asSequence().mapNotNull { k ->
                    val p = o.optJSONObject(k) ?: return@mapNotNull null
                    k.toIntOrNull()?.let { it to Playtime(p.optInt("minutes"), p.optLong("last")) }
                }.toMap()
            }
        } catch (_: Exception) {}
    }

    private fun writeCache() {
        val key = accountKey(account.accountName.value) ?: return
        try {
            val o = JSONObject().apply {
                put("version", 2); put("updated", updatedAt); put("account", key)
                put("games", JSONArray(_owned.value.map { it.toJson() }))
            }
            File(cacheFile.path + ".tmp").apply { writeText(o.toString()) }.renameTo(cacheFile)
        } catch (_: Exception) {}
    }

    /** The install records in the prefix, read again off the main thread. */
    suspend fun rescanInstalled() {
        val games = withContext(Dispatchers.IO) { InstalledGame.scan(prefix.drive) }
        _installed.value = games
    }

    /** `interactive` refreshes report failures; the automatic one only logs them. */
    suspend fun refresh(interactive: Boolean = true) {
        if (!account.signedIn || _refreshing.value || inSession) return
        _refreshing.value = true
        try {
            val apps = fetcher.ownedApps()
            val games = apps.filter { it.installableOnWindows }.map { OwnedGame(it) }.sortedBy { it.name.lowercase() }
            _owned.value = games
            updatedAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) { writeCache() }
            SteamLog.event("[steam-library] owned apps=${apps.size} windows-installable=${games.size}")
            refreshPlaytime()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            handleError(e, "library", interactive)
        } finally {
            _refreshing.value = false
        }
        rescanInstalled()
    }

    suspend fun refreshPlaytime() {
        if (!account.signedIn || inSession) return
        try {
            val p = fetcher.playtime()
            _playtime.value = p
            withContext(Dispatchers.IO) {
                val o = JSONObject()
                for ((id, t) in p) o.put(id.toString(), JSONObject().apply { put("minutes", t.minutes); put("last", t.lastPlayed) })
                playtimeFile.writeText(o.toString())
            }
            SteamLog.event("[steam-playtime] apps=${p.size}")
            auditCloud()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            SteamLog.event("[steam-playtime] unavailable reason=${SteamAccount.reason(e)}")
        }
    }

    private fun handleError(e: Exception, context: String, report: Boolean) {
        val err = (e as? SteamException)?.error
        if (err is SteamError.LogonDenied && err.code in SteamError.SIGN_IN_EXPIRED_CODES) {
            error.value = "Your Steam sign-in is no longer valid. Sign out and sign in again in Settings › Steam."
            SteamLog.event("[steam-account] stored sign-in rejected code=${err.code}")
            return
        }
        if (report) error.value = SteamAccount.message(e)
        SteamLog.event("[steam-$context] failed reason=${SteamAccount.reason(e)} reported=${if (report) 1 else 0}")
    }

    /** Steam's launch configuration for an owned app, from the cache or asked of Steam once. */
    suspend fun launchOptions(appID: Int): List<LaunchOption>? {
        game(appID)?.launches?.let { if (it.all { o -> o.index != null }) return it }
        if (!account.signedIn || inSession) return null
        return try {
            val info = fetcher.appInfo(appID) ?: return null
            _owned.value = _owned.value.map { if (it.id == appID) it.copy(launches = info.launches) else it }
            writeCache()
            info.launches
        } catch (e: CancellationException) { throw e } catch (e: Exception) { handleError(e, "start", false); null }
    }

    // MARK: Downloads

    fun updateAvailable(appID: Int): Boolean {
        val installedBuild = installedGame(appID)?.buildID ?: return false
        val latest = game(appID)?.buildID ?: return false
        return latest > 0 && latest > installedBuild
    }

    fun hasPartialDownload(appID: Int) = DepotDownloader.hasPartialDownload(appID, prefix.steamApps)

    private fun setDownload(appID: Int, d: Download?) {
        _downloads.value = _downloads.value.toMutableMap().apply { if (d == null) remove(appID) else put(appID, d) }
    }

    fun install(appID: Int) {
        if (!account.signedIn) { error.value = "Sign in to Steam to download games."; return }
        if (active?.first == appID || appID in queue) return
        if (inSession) { resumeAfterSession += appID; setDownload(appID, Download(Download.State.Paused)); return }
        setDownload(appID, (_downloads.value[appID] ?: Download(Download.State.Queued)).copy(state = Download.State.Queued))
        queue += appID
        pump()
    }

    fun pause(appID: Int) {
        val current = active
        if (current != null && current.first == appID) current.second.cancel()
        else if (queue.remove(appID)) setDownload(appID, _downloads.value[appID]?.copy(state = Download.State.Paused))
        resumeAfterSession.remove(appID)
    }

    /** Stops a download; a first-time install also loses its partial files. */
    fun cancelInstall(appID: Int) {
        val running = active?.takeIf { it.first == appID }?.second
        val installed = installedGame(appID)?.installed == true
        pause(appID)
        setDownload(appID, null)
        if (installed) return
        val folder = game(appID)?.folderName ?: return
        scope.launch {
            running?.join()
            withContext(Dispatchers.IO) { SteamInstallFiles.delete(appID, folder, prefix.steamApps) }
            SteamLog.event("[steam-depot] cancelled app=$appID partial-files-removed=1")
            rescanInstalled()
        }
    }

    /** Checks an install's files against the current build; chunks on disk are compared by SHA-1 first. */
    fun repair(appID: Int) { SteamLog.event("[steam-repair] app=$appID"); install(appID) }

    fun uninstall(appID: Int) {
        val g = installedGame(appID) ?: return
        if (!SteamInstallPaths.isManaged(g.library) || inSession) return
        pause(appID); setDownload(appID, null)
        scope.launch {
            withContext(Dispatchers.IO) { SteamInstallFiles.delete(appID, g.installDir, prefix.steamApps) }
            SteamLog.event("[steam-depot] uninstalled app=$appID")
            rescanInstalled()
        }
    }

    private fun pump() {
        if (active != null || inSession || queue.isEmpty()) return
        val appID = queue.removeAt(0)
        setDownload(appID, (_downloads.value[appID] ?: Download(Download.State.Active)).copy(state = Download.State.Active))
        val job = scope.launch { run(appID) }
        active = appID to job
        onDownloadsChanged?.invoke(appID, _downloads.value[appID]?.progress)
    }

    private suspend fun run(appID: Int) {
        try {
            val info = fetcher.installInfo(appID) ?: throw SteamException(SteamError.AppInfoNotFound(appID))
            prefix.ensure()
            withContext(Dispatchers.IO) { SteamInstallPaths.common(prefix.drive).mkdirs() }
            var last = 0L
            val folder = downloader.install(info, prefix.steamApps, ownedDepots = {
                try { fetcher.ownedDepotIDs() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            }) { progress ->
                val now = System.currentTimeMillis()
                if (now - last >= 250 || progress.phase != DownloadProgress.Phase.DOWNLOADING) {
                    last = now
                    _downloads.value[appID]?.let { setDownload(appID, it.copy(progress = progress)) }
                    onDownloadsChanged?.invoke(appID, progress)
                }
            }
            setDownload(appID, null)
            if (!configs.has(appID)) configs.save(appID, GameConfig(added = System.currentTimeMillis()))
            rescanInstalled()
            inspect(appID, folder, info.launches)
            SteamLog.event("[steam-depot] library entry app=$appID")
        } catch (e: CancellationException) {
            _downloads.value[appID]?.let { setDownload(appID, it.copy(state = Download.State.Paused)) }
            SteamLog.event("[steam-depot] paused app=$appID")
        } catch (e: Exception) {
            setDownload(appID, _downloads.value[appID]?.copy(state = Download.State.Failed(SteamAccount.message(e))))
            handleError(e, "depot", false)
        } finally {
            active = null
            onDownloadsChanged?.invoke(null, null)
            scope.launch { pump() }
        }
    }

    /** The installed program's bitness and graphics APIs, for the card's pills and the "auto" graphics choice. */
    suspend fun inspect(appID: Int, folder: File? = null, launches: List<LaunchOption>? = null) {
        val g = installedGame(appID)
        val root = folder ?: g?.let { File(prefix.drive, "${it.library}/common/${it.installDir}") } ?: return
        val cfg = configs.get(appID)
        val result = withContext(Dispatchers.IO) {
            val program = DirectStart.program(cfg.program, launches ?: game(appID)?.launches, root)?.let { File(root, it) }
            val info = program?.let { PeInspector.inspect(it) }
            val apis = program?.let { PeInspector.graphicsAPIs(it) }.orEmpty()
            Triple(info?.bits ?: 0, apis, g?.sizeOnDisk ?: 0L)
        }
        configs.update(appID) { it.copy(bits = result.first, apis = result.second.sorted(), sizeBytes = result.third) }
    }

    // MARK: Game sessions

    /** A game session starts: downloads pause and the app's connection logs off, so the account is free for Valve's client. */
    suspend fun sessionStarting() {
        if (inSession) return
        inSession = true
        active?.let { (id, job) -> resumeAfterSession += id; job.cancel(); job.join() }
        for (id in queue) { resumeAfterSession += id; setDownload(id, _downloads.value[id]?.copy(state = Download.State.Paused)) }
        queue.clear()
        session.suspendForGame()
        SteamLog.event("[steam-depot] paused for a game session count=${resumeAfterSession.size}")
    }

    /** The game session ended: the connection comes back, downloads continue, playtime and saves are refreshed. */
    fun sessionEnded(appID: Int?) {
        if (!inSession) return
        inSession = false
        session.resume()
        val resume = resumeAfterSession.sorted()
        resumeAfterSession.clear()
        for (id in resume) install(id)
        scope.launch {
            rescanInstalled()
            delay(5_000)
            refreshPlaytime()
            if (appID != null && settings.steamCloud.value) syncCloud(appID)
        }
    }

    val sessionActive: Boolean get() = inSession

    // MARK: Steam Cloud

    fun setCloud(appID: Int, change: (CloudState) -> CloudState) {
        _cloud.value = _cloud.value.toMutableMap().apply { put(appID, change(get(appID) ?: CloudState())) }
    }

    private suspend fun auditCloud() {
        if (cloudAudited || inSession || !settings.steamCloud.value) return
        cloudAudited = true
        val games = _installed.value.filter { it.installed }.take(40)
        SteamLog.event("[steam-cloud] sync games=${games.size}")
        for (g in games) { if (inSession) break; syncCloud(g.appID) }
    }

    private suspend fun context(appID: Int): SteamCloud.Context? {
        val g = installedGame(appID) ?: return null
        return cloudSync.context(appID, g, prefix.drive, prefix.clientRoot)
    }

    suspend fun checkCloud(appID: Int): CloudPlan? {
        if (!settings.steamCloud.value || !account.signedIn || inSession) return null
        setCloud(appID) { it.copy(phase = CloudState.Phase.Checking) }
        return try {
            val ctx = context(appID) ?: run {
                setCloud(appID) { it.copy(phase = CloudState.Phase.Failed(NO_FOLDERS)) }
                return null
            }
            val (audit, plan) = withContext(Dispatchers.IO) { cloudSync.check(appID, ctx) }
            setCloud(appID) { it.copy(phase = CloudState.Phase.Ready, audit = audit, conflicts = plan.conflicts, checkedAt = System.currentTimeMillis()) }
            plan
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            setCloud(appID) { it.copy(phase = CloudState.Phase.Failed(SteamAccount.message(e))) }
            SteamLog.event("[steam-cloud] app=$appID failed reason=${SteamAccount.reason(e)}")
            null
        }
    }

    /** Compares, then copies what changed on one side only. Saves that need a choice wait on the game's page. */
    suspend fun syncCloud(appID: Int) {
        if (!cloudBusy.add(appID)) return
        try {
            val plan = checkCloud(appID) ?: return
            if (!settings.cloudAutomatic.value) return
            var changed = false
            if (plan.download.isNotEmpty()) changed = transfer(appID, plan.download, download = true) || changed
            if (plan.upload.isNotEmpty() && !inSession) changed = transfer(appID, plan.upload, download = false) || changed
            if (changed) checkCloud(appID)
        } finally { cloudBusy.remove(appID) }
    }

    /** The user's choice for differing saves: the cloud's, or this device's. */
    suspend fun resolveCloud(appID: Int, useCloud: Boolean) {
        val state = _cloud.value[appID] ?: return
        if (state.conflicts.isEmpty() || !cloudBusy.add(appID)) return
        try {
            val ctx = context(appID) ?: return
            val name = game(appID)?.name ?: installedGame(appID)?.name ?: ""
            withContext(Dispatchers.IO) { cloudSync.resolve(appID, state.conflicts, useCloud, ctx, cloudSync.backupFolder(appID, name)) }
            SteamLog.event("[steam-cloud] app=$appID choice=${if (useCloud) "cloud" else "device"} files=${state.conflicts.size}")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            setCloud(appID) { it.copy(problem = "Steam Cloud stopped: ${SteamAccount.message(e)}") }
        } finally { cloudBusy.remove(appID) }
        checkCloud(appID)
    }

    private suspend fun transfer(appID: Int, wanted: List<CloudEntry>, download: Boolean): Boolean {
        val ctx = try { context(appID) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return false
        val name = game(appID)?.name ?: installedGame(appID)?.name ?: ""
        val backup = cloudSync.backupFolder(appID, name)
        setCloud(appID) { it.copy(phase = if (download) CloudState.Phase.Downloading(0, wanted.size) else CloudState.Phase.Uploading(0, wanted.size), problem = null) }
        var done = 0
        try {
            withContext(Dispatchers.IO) {
                if (download) done = cloudSync.download(appID, wanted, ctx, backup) { n ->
                    setCloud(appID) { it.copy(phase = CloudState.Phase.Downloading(n, wanted.size)) }
                }.first
                else done = cloudSync.upload(appID, wanted, ctx, backup) { n ->
                    setCloud(appID) { it.copy(phase = CloudState.Phase.Uploading(n, wanted.size)) }
                }
            }
            setCloud(appID) { if (download) it.copy(lastDownload = done to 0) else it.copy(lastUpload = done) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            setCloud(appID) { it.copy(problem = (if (download) "Steam Cloud download stopped: " else "Steam Cloud upload stopped: ") + SteamAccount.message(e)) }
        }
        setCloud(appID) { it.copy(phase = CloudState.Phase.Ready) }
        return done > 0
    }

    /** Why a game should wait before it starts: its saves may not be the latest. Null: go ahead. */
    fun cloudHold(appID: Int): String? {
        if (!settings.steamCloud.value || !settings.cloudPlayCheck.value || !account.signedIn || inSession) return null
        if (appID in cloudBusy) return "Steam Cloud is syncing this game's saves."
        val s = _cloud.value[appID] ?: return null
        return when (val p = s.phase) {
            is CloudState.Phase.Failed -> if (p.message == NO_FOLDERS) null else null
            CloudState.Phase.Checking, is CloudState.Phase.Downloading, is CloudState.Phase.Uploading -> "Steam Cloud is syncing this game's saves."
            CloudState.Phase.Ready -> when {
                s.problem != null -> s.problem
                s.conflicts.isNotEmpty() -> "${s.conflicts.size} save${if (s.conflicts.size == 1) "" else "s"} differ between this device and Steam Cloud. Choose which to keep on the game's page."
                else -> null
            }
        }
    }

    companion object {
        const val NO_FOLDERS = "This game's save folders could not be found."
    }
}

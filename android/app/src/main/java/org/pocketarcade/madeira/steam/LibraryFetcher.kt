// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam/Library/SteamLibraryFetcher.swift and the
// playtime part of SteamOwnedLibrary.swift.

package org.pocketarcade.madeira.steam

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Steam's own playtime record for one app: minutes in total and the last time played (Unix seconds, 0 = never). */
data class Playtime(val minutes: Int, val lastPlayed: Long) {
    companion object {
        /** Player.GetOwnedGames request for the account's own library, with app info and free games. */
        fun request(steamID: Long): ByteArray = proto {
            uint64(1, steamID)
            bool(3, true)   // include_played_free_games
            bool(5, true)   // include_free_sub
        }

        /** CPlayer_GetOwnedGames_Response: games = 2 { appid = 1, playtime_forever = 4, rtime_last_played = 11 }. */
        fun parse(data: ByteArray): Map<Int, Playtime> {
            val out = HashMap<Int, Playtime>()
            data.proto().fields { f ->
                if (f == 2) {
                    var app = 0; var minutes = 0; var last = 0L
                    message().fields { g ->
                        when (g) {
                            1 -> app = int32()
                            4 -> minutes = int32()
                            11 -> last = varint() and 0xFFFFFFFFL
                            else -> skip()
                        }
                    }
                    if (app > 0) out[app] = Playtime(minutes, last)
                } else skip()
            }
            return out
        }
    }
}

/** Fetches the owned games through PICS: licences -> packages -> apps -> app info. */
class LibraryFetcher(private val session: CmSession) {
    private var ownedDepotCache: Set<Int>? = null

    /** The account's playable apps (games, demos, applications). */
    suspend fun ownedApps(): List<AppInfo> {
        session.ensureConnected()
        val packages = session.awaitLicenseList(15_000)
        SteamLog.trace("Got ${packages.size} owned packages")
        val packageTokens = accessTokens(packages = packages)
        val appIDs = appIDsFromPackages(packages, packageTokens)
        val appTokens = accessTokens(apps = appIDs.toList())
        val infos = appInfo(appIDs.toList(), appTokens)
        return infos.filter { it.type.isPlayable }
    }

    /** Depot IDs in the account's licences, to leave out depots of editions it does not own. */
    suspend fun ownedDepotIDs(): Set<Int> {
        ownedDepotCache?.let { return it }
        session.ensureConnected()
        val packages = session.awaitLicenseList(15_000)
        val tokens = accessTokens(packages = packages)
        val depots = HashSet<Int>()
        for (batch in packages.chunked(50)) {
            val responses = session.sendAndWaitPICS(CmMessages.picsRequest(emptyList(), batch.map { it to (tokens[it] ?: 0L) }))
            for (r in responses) for (p in CmMessages.picsResponse(r.body).packages) depots += Vdf.packageIDs("depotids", p.buffer)
        }
        ownedDepotCache = depots
        return depots
    }

    suspend fun appInfo(appID: Int): AppInfo? {
        session.ensureConnected()
        val tokens = accessTokens(apps = listOf(appID))
        return appInfo(listOf(appID), tokens).firstOrNull()
    }

    /** The app's metadata for an install, with the depots it shares from other apps resolved. */
    suspend fun installInfo(appID: Int): AppInfo? {
        coroutineContext.ensureActive()
        val app = appInfo(appID) ?: return null
        val owners = HashMap<Int, AppInfo>()
        owners[appID] = app
        val visited = hashSetOf(appID)
        val references = HashSet<String>()
        fun eligible(d: DepotInfo) = !d.isSharedInstall && d.dlcAppID == null && d.supports("windows") && !d.lowViolence &&
            (d.language.isEmpty() || d.language.equals("english", true))
        val pending = app.depots.filter { eligible(it) && it.publicManifestID == null && it.fromApp != null }
            .map { it.fromApp!! to it.depotID }.toMutableList()
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (id, depot) = pending.removeAt(pending.size - 1)
            if (!references.add("$id:$depot")) continue
            if (references.size > 1024) throw SteamException(SteamError.ManifestFetchFailed("Too many shared content references."))
            if (visited.add(id)) {
                if (visited.size > 32) throw SteamException(SteamError.ManifestFetchFailed("Too many shared content dependencies."))
                appInfo(id)?.let { owners[id] = it }
            }
            val source = owners[id]?.depots?.firstOrNull { it.depotID == depot }
            if (source != null && source.publicManifestID == null) source.fromApp?.let { pending += it to depot }
        }
        for (round in 0 until owners.size) {
            var changed = 0
            for (id in owners.keys.sorted()) changed += owners[id]!!.inheritDepots(owners)
            if (changed == 0) break
        }
        val resolved = app.inheritDepots(owners)
        val missing = app.depots.count { eligible(it) && it.fromApp != null && it.publicManifestID == null }
        SteamLog.event("[steam-shared] app=$appID owners=${visited.size - 1} resolved=$resolved missing=$missing")
        if (missing > 0) throw SteamException(SteamError.ManifestFetchFailed("Steam did not provide required shared content metadata. Refresh and retry the download."))
        for (id in app.installDepots().mapNotNull { it.fromApp }.toSet().minus(appID).sorted()) {
            coroutineContext.ensureActive()
            if (owners[id] == null) {
                if (owners.size > 64) break
                appInfo(id)?.let { owners[id] = it }
            }
            owners[id]?.let { app.sharedOwners[id] = SharedOwner(it.name, it.installDir, it.buildID) }
        }
        return app
    }

    /** The account's playtime over the existing connection (Player.GetOwnedGames). */
    suspend fun playtime(): Map<Int, Playtime> {
        session.ensureConnected()
        return Playtime.parse(session.callServiceMethod(ServiceMethod.GET_OWNED_GAMES, Playtime.request(session.steamID), 20_000))
    }

    private suspend fun accessTokens(apps: List<Int> = emptyList(), packages: List<Int> = emptyList()): Map<Int, Long> {
        val response = session.sendAndWait(EMsg.CLIENT_PICS_ACCESS_TOKEN_REQUEST, CmMessages.picsAccessTokenRequest(apps, packages), 30_000)
        val t = CmMessages.picsAccessTokenResponse(response.body)
        return t.apps + t.packages
    }

    private suspend fun appIDsFromPackages(packages: List<Int>, tokens: Map<Int, Long>): Set<Int> {
        val ids = LinkedHashSet<Int>()
        for (batch in packages.chunked(50)) {
            val responses = session.sendAndWaitPICS(CmMessages.picsRequest(emptyList(), batch.map { it to (tokens[it] ?: 0L) }))
            for (r in responses) for (p in CmMessages.picsResponse(r.body).packages) ids += Vdf.packageIDs("appids", p.buffer)
        }
        return ids
    }

    private suspend fun appInfo(appIDs: List<Int>, tokens: Map<Int, Long>): List<AppInfo> {
        val out = ArrayList<AppInfo>()
        for (batch in appIDs.chunked(50)) {
            val responses = session.sendAndWaitPICS(CmMessages.picsRequest(batch.map { it to (tokens[it] ?: 0L) }, emptyList()))
            for (r in responses) for (a in CmMessages.picsResponse(r.body).apps) AppInfo.parse(a.appID, a.buffer)?.let { out += it }
        }
        return out
    }
}

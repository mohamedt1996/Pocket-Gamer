// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam Core/SteamConnection.swift and
// Core/CMServerList.swift. The WebSocket itself is behind CmTransport (OkHttp on
// Android, a scripted one in tests).

package org.pocketarcade.madeira.steam

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small persistent key-value store (SharedPreferences on Android). */
interface SteamStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryStore : SteamStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

/**
 * WebSocket to a Steam CM (wss://host:port/cmsocket/, subprotocol "steamdataport").
 * Each binary frame is one complete Steam message. TLS is the encryption.
 */
interface CmTransport {
    interface Listener {
        fun onMessage(data: ByteArray)
        fun onClosed(error: Throwable?)
    }

    /** Opens the socket; returns once the handshake completed, throws otherwise. */
    suspend fun connect(url: String, listener: Listener)
    suspend fun send(data: ByteArray)
    fun close()
    val isOpen: Boolean
}

/** Discovers and ranks CM WebSocket endpoints (ISteamDirectory). */
class CmServerList(private val http: HttpClient, private val store: SteamStore, private val clock: () -> Long = System::currentTimeMillis) {
    data class Server(val host: String, val port: Int, var lastAttempt: Long = 0, var failCount: Int = 0) {
        val healthy: Boolean get() = failCount < 3
        val endpoint: String get() = "$host:$port"
        val url: String get() = "wss://$host:$port/cmsocket/"
    }

    private val lock = Mutex()
    private var servers: List<Server> = emptyList()
    private var fetchedAt = 0L

    suspend fun server(): Server = lock.withLock {
        if (servers.isEmpty() || clock() - fetchedAt > STALE_MS) refresh()
        val healthy = servers.filter { it.healthy }.sortedBy { it.lastAttempt }
        healthy.firstOrNull() ?: run {
            servers.forEach { it.failCount = 0 }
            servers.firstOrNull() ?: throw SteamException(SteamError.NoServersAvailable)
        }
    }

    suspend fun markFailed(endpoint: String) = lock.withLock {
        servers.firstOrNull { it.endpoint == endpoint }?.let { it.failCount++; it.lastAttempt = clock() }
    }

    suspend fun markSuccess(endpoint: String) = lock.withLock {
        servers.firstOrNull { it.endpoint == endpoint }?.let { it.failCount = 0; it.lastAttempt = clock() }
    }

    private suspend fun refresh() {
        val fresh = fetch()
        if (fresh.isEmpty()) throw SteamException(SteamError.NoServersAvailable)
        servers = fresh
        fetchedAt = clock()
        store.put(CACHE_KEY, fresh.joinToString("\n") { it.endpoint })
    }

    private fun cached(): List<Server> = store.get(CACHE_KEY)?.lines()?.mapNotNull(::parseEndpoint).orEmpty()

    private suspend fun fetch(): List<Server> {
        val response = try {
            http.get("https://api.steampowered.com/ISteamDirectory/GetCMListForConnect/v1/?cellid=0&cmtype=websockets")
        } catch (e: Exception) {
            val cached = cached()
            if (cached.isNotEmpty()) { SteamLog.trace("CM directory unreachable, using cached servers"); return cached }
            throw SteamException(SteamError.ConnectionFailed(e.message ?: "CM directory unreachable"), e)
        }
        if (response.code != 200) {
            val cached = cached()
            if (cached.isNotEmpty()) { SteamLog.trace("CM directory unavailable, using cached servers"); return cached }
            throw SteamException(SteamError.ConnectionFailed("CM directory returned non-200 status"))
        }
        val json = try { Json.parse(String(response.body, Charsets.UTF_8)) } catch (_: Exception) { null }
        val list = json.obj("response").arr("serverlist")
            ?: throw SteamException(SteamError.ConnectionFailed("Invalid CM directory response format"))
        val servers = list.mapNotNull { entry -> entry.str("endpoint")?.let(::parseEndpoint) }
        SteamLog.trace("Fetched ${servers.size} WebSocket CM servers")
        return servers
    }

    companion object {
        private const val CACHE_KEY = "steam_cm_servers_websocket"
        private const val STALE_MS = 3_600_000L

        fun parseEndpoint(endpoint: String): Server? {
            val parts = endpoint.split(":")
            if (parts.size != 2 || parts[0].isEmpty()) return null
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return Server(parts[0], port)
        }
    }
}

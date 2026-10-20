// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam/Core/SteamSession.swift, LicenseListBox.swift
// and SteamCMSession.swift: the logged-on CM session (logon, heartbeat,
// reconnection, job routing). It is suspended while a game session runs, so
// Valve's own client is the only one logged on with the account.

package org.pocketarcade.madeira.steam

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/** What the library fetcher and the depot downloader need from a logged-on connection. */
interface CmSession {
    val cellID: Int
    val steamID: Long
    suspend fun ensureConnected()
    suspend fun awaitLicenseList(timeoutMs: Long = 15_000): List<Int>
    suspend fun sendAndWait(emsg: Int, body: ByteArray, timeoutMs: Long = 10_000): IncomingMessage
    suspend fun sendAndWaitPICS(body: ByteArray, timeoutMs: Long = 30_000): List<IncomingMessage>
    suspend fun callServiceMethod(method: String, body: ByteArray, timeoutMs: Long = 10_000): ByteArray
}

class Credentials(val accountName: String, val refreshToken: String)

class SteamSession(
    private val transportFactory: () -> CmTransport,
    private val serverList: CmServerList,
    private val credentials: () -> Credentials?,
    private val machineName: String,
    /** CMsgClientLogon.client_os_type. Madeira reports macOS (-102); Windows 10 is 16. */
    private val osType: Int = 16,
    private val idleTimeoutMs: Long = 60_000,
    private val logonTimeoutMs: Long = 20_000,
    private val backoffBaseMs: Long = 1_000,
) : CmSession {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "steam-session").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    @Volatile override var steamID: Long = 0; private set
    @Volatile override var cellID: Int = 0; private set
    @Volatile var isSuspended = false; private set

    private var transport: CmTransport? = null
    private var endpoint: String? = null
    private var sessionID = 0
    private var heartbeatSeconds = 30
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var idleJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var jobCounter = 0L
    private val pendingJobs = HashMap<Long, CompletableDeferred<IncomingMessage>>()
    private class PicsJob(val messages: MutableList<IncomingMessage>, val done: CompletableDeferred<List<IncomingMessage>>)
    private val pendingPics = HashMap<Long, PicsJob>()
    private val handlers = HashMap<Int, (IncomingMessage) -> Unit>()

    // License list pushed after each logon.
    private var licenses: List<Int>? = null
    private val licenseWaiters = ArrayList<CompletableDeferred<List<Int>>>()

    companion object {
        /** Universe Public, Individual, Desktop, account 0: pre-logon messages. Raw 0 gets EResult 5 on some CMs. */
        const val PRE_LOGON_STEAM_ID = 76561197960265728L
    }

    // MARK: Lifecycle

    /** Connects and logs on with the stored sign-in, trying a second CM on timeout or TryAnotherCM. */
    suspend fun connectAndLogin(): Unit = withContext(thread) {
        if (isSuspended) throw SteamException(SteamError.Disconnected)
        if (_state.value != ConnectionState.DISCONNECTED && _state.value != ConnectionState.RECONNECTING) return@withContext
        _state.value = ConnectionState.CONNECTING
        try {
            licenses = null
            handlers[EMsg.CLIENT_LICENSE_LIST] = { m ->
                try {
                    val (eresult, list) = CmMessages.licenseList(m.body)
                    SteamLog.trace("ClientLicenseList: ${list.size} packages, eresult=$eresult")
                    setLicenses(list.map { it.packageID })
                } catch (e: Exception) { SteamLog.trace("ClientLicenseList parse failed: $e") }
            }
            open()
            _state.value = ConnectionState.CONNECTED
            sendRaw(MessageCodec.encodeClientMessage(EMsg.CLIENT_HELLO, CmMessages.hello(), PRE_LOGON_STEAM_ID, 0))
            val tokens = credentials()
            if (tokens != null) {
                var last: SteamException = SteamException(SteamError.ConnectionTimeout)
                var ok = false
                for (attempt in 1..2) {
                    try {
                        if (attempt > 1) {
                            SteamLog.trace("Logon attempt $attempt/2: another CM")
                            endpoint?.let { serverList.markFailed(it) }
                            closeTransport()
                            open()
                            _state.value = ConnectionState.CONNECTED
                            sendRaw(MessageCodec.encodeClientMessage(EMsg.CLIENT_HELLO, CmMessages.hello(), PRE_LOGON_STEAM_ID, 0))
                        }
                        if (isSuspended) throw SteamException(SteamError.Disconnected)
                        logon(tokens)
                        ok = true
                        break
                    } catch (e: SteamException) {
                        val retry = e.error == SteamError.ConnectionTimeout ||
                            (e.error is SteamError.ConnectionFailed && (e.error as SteamError.ConnectionFailed).reason == "TryAnotherCM")
                        if (!retry) throw e
                        last = e
                    }
                }
                if (!ok) throw last
            }
        } catch (e: Throwable) {
            closeTransport()
            _state.value = ConnectionState.DISCONNECTED
            throw e
        }
    }

    private suspend fun open() {
        val server = serverList.server()
        endpoint = server.endpoint
        SteamLog.trace("Connecting to CM")
        val t = transportFactory()
        transport = t
        try {
            t.connect(server.url, object : CmTransport.Listener {
                override fun onMessage(data: ByteArray) { scope.launch { if (transport === t) handleMessage(data) } }
                override fun onClosed(error: Throwable?) { scope.launch { if (transport === t) handleDisconnect(error) } }
            })
        } catch (e: Exception) {
            serverList.markFailed(server.endpoint)
            transport = null
            throw if (e is SteamException) e else SteamException(SteamError.ConnectionFailed(e.message ?: "WebSocket failed"), e)
        }
        serverList.markSuccess(server.endpoint)
    }

    private fun closeTransport() {
        val t = transport
        transport = null
        t?.close()
    }

    override suspend fun ensureConnected() {
        if (isSuspended) throw SteamException(SteamError.Disconnected)
        when (_state.value) {
            ConnectionState.AUTHENTICATED -> { withContext(thread) { resetIdleTimer() }; return }
            ConnectionState.CONNECTING -> {
                repeat(40) {
                    delay(500)
                    if (_state.value == ConnectionState.AUTHENTICATED) return
                    if (_state.value == ConnectionState.DISCONNECTED) return@repeat
                }
                if (_state.value != ConnectionState.AUTHENTICATED) throw SteamException(SteamError.ConnectionTimeout)
                return
            }
            else -> {}
        }
        connectAndLogin()
        withContext(thread) { resetIdleTimer() }
    }

    override suspend fun awaitLicenseList(timeoutMs: Long): List<Int> {
        val waiter = withContext(thread) {
            licenses?.let { return@withContext null to it }
            CompletableDeferred<List<Int>>().also { licenseWaiters += it } to null
        }
        waiter.second?.let { return it }
        val d = waiter.first!!
        return try {
            withTimeout(timeoutMs) { d.await() }
        } catch (_: TimeoutCancellationException) {
            withContext(thread) { licenseWaiters.remove(d) }
            throw SteamException(SteamError.ConnectionTimeout)
        }
    }

    private fun setLicenses(ids: List<Int>) {
        licenses = ids
        val waiting = licenseWaiters.toList()
        licenseWaiters.clear()
        waiting.forEach { it.complete(ids) }
    }

    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            if (_state.value == ConnectionState.AUTHENTICATED) {
                SteamLog.trace("Idle timeout: disconnecting to release the server-side session")
                disconnectGracefullyNow()
            }
        }
    }

    /** Sends ClientLogOff so Steam releases the session, then closes the socket. */
    suspend fun disconnectGracefully() = withContext(thread) { disconnectGracefullyNow() }

    private suspend fun disconnectGracefullyNow() {
        if (_state.value == ConnectionState.AUTHENTICATED) {
            try { sendRaw(MessageCodec.encodeClientMessage(EMsg.CLIENT_LOG_OFF, ByteArray(0), steamID, sessionID)) } catch (_: Exception) {}
        }
        disconnectNow()
    }

    /** A game session takes the account: log off, close, and refuse to connect until [resume]. */
    suspend fun suspendForGame() = withContext(thread) {
        isSuspended = true
        disconnectGracefullyNow()
    }

    fun resume() { isSuspended = false }

    private suspend fun logon(tokens: Credentials) {
        val result = CompletableDeferred<Unit>()
        val onResponse: (IncomingMessage) -> Unit = handler@{ m ->
            clearLogonHandlers()
            val eresult: Int; val hb: Int; val cell: Int
            if (m.isProtobuf) {
                val r = try { CmMessages.logonResponse(m.body) } catch (e: Exception) { result.completeExceptionally(e); return@handler }
                eresult = r.eresult; hb = if (r.heartbeatSeconds > 0) r.heartbeatSeconds else 30; cell = r.cellID
            } else {
                if (m.body.size < 4) { result.completeExceptionally(SteamException(SteamError.InvalidMessage)); return@handler }
                eresult = m.body.u32le(0).toInt(); hb = 30; cell = 0
            }
            SteamLog.trace("ClientLogonResponse: eresult=$eresult")
            if (eresult == EResult.TRY_ANOTHER_CM) { result.completeExceptionally(SteamException(SteamError.ConnectionFailed("TryAnotherCM"))); return@handler }
            if (eresult != EResult.OK) { result.completeExceptionally(SteamException(SteamError.LogonDenied(eresult))); return@handler }
            if (isSuspended) {
                scope.launch {
                    try { sendRaw(MessageCodec.encodeClientMessage(EMsg.CLIENT_LOG_OFF, ByteArray(0), m.header.steamID, m.header.sessionID)) } catch (_: Exception) {}
                    closeTransport()
                }
                result.completeExceptionally(SteamException(SteamError.Disconnected))
                return@handler
            }
            steamID = m.header.steamID
            sessionID = m.header.sessionID
            heartbeatSeconds = hb
            cellID = cell
            _state.value = ConnectionState.AUTHENTICATED
            reconnectAttempts = 0
            SteamLog.trace("Authenticated")
            startHeartbeat()
            resetIdleTimer()
            result.complete(Unit)
        }
        handlers[EMsg.CLIENT_LOGON_RESPONSE] = onResponse
        handlers[EMsg.CLIENT_LOGON_RESPONSE_LEGACY] = onResponse
        handlers[EMsg.CLIENT_LOGGED_OFF] = {
            clearLogonHandlers()
            result.completeExceptionally(SteamException(SteamError.AuthenticationFailed("Server sent ClientLoggedOff during logon")))
        }
        try {
            sendRaw(MessageCodec.encodeClientMessage(EMsg.CLIENT_LOGON,
                CmMessages.logon(tokens.accountName, tokens.refreshToken, machineName, osType), PRE_LOGON_STEAM_ID, 0))
            withTimeout(logonTimeoutMs) { result.await() }
        } catch (_: TimeoutCancellationException) {
            clearLogonHandlers()
            SteamLog.trace("ClientLogon timed out")
            throw SteamException(SteamError.ConnectionTimeout)
        } catch (e: Throwable) {
            clearLogonHandlers()
            throw e
        }
    }

    private fun clearLogonHandlers() {
        handlers.remove(EMsg.CLIENT_LOGON_RESPONSE)
        handlers.remove(EMsg.CLIENT_LOGON_RESPONSE_LEGACY)
        handlers.remove(EMsg.CLIENT_LOGGED_OFF)
    }

    suspend fun disconnect() = withContext(thread) { disconnectNow() }

    private fun disconnectNow() {
        heartbeatJob?.cancel(); heartbeatJob = null
        reconnectJob?.cancel(); reconnectJob = null
        idleJob?.cancel(); idleJob = null
        val err = SteamException(SteamError.Disconnected)
        pendingJobs.values.forEach { it.completeExceptionally(err) }
        pendingJobs.clear()
        pendingPics.values.forEach { it.done.completeExceptionally(err) }
        pendingPics.clear()
        closeTransport()
        _state.value = ConnectionState.DISCONNECTED
        steamID = 0
        sessionID = 0
        SteamLog.trace("Session disconnected")
    }

    // MARK: Sending

    private suspend fun sendRaw(data: ByteArray) {
        val t = transport
        if (t == null || !t.isOpen) throw SteamException(SteamError.Disconnected)
        t.send(data)
    }

    private fun nextJobID(): Long = ++jobCounter

    override suspend fun sendAndWait(emsg: Int, body: ByteArray, timeoutMs: Long): IncomingMessage {
        val (job, waiter) = withContext(thread) {
            resetIdleTimer()
            val job = nextJobID()
            val d = CompletableDeferred<IncomingMessage>()
            pendingJobs[job] = d
            try { sendRaw(MessageCodec.encodeClientMessage(emsg, body, steamID, sessionID, job)) }
            catch (e: Exception) { pendingJobs.remove(job); throw e }
            job to d
        }
        return try {
            withTimeout(timeoutMs) { waiter.await() }
        } catch (_: TimeoutCancellationException) {
            withContext(thread) { pendingJobs.remove(job) }
            throw SteamException(SteamError.ConnectionTimeout)
        }
    }

    /** Sends a PICS product-info request and collects every part until pending_response_count is 0. */
    override suspend fun sendAndWaitPICS(body: ByteArray, timeoutMs: Long): List<IncomingMessage> {
        val (job, acc) = withContext(thread) {
            resetIdleTimer()
            val job = nextJobID()
            val acc = PicsJob(ArrayList(), CompletableDeferred())
            pendingPics[job] = acc
            try { sendRaw(MessageCodec.encodeClientMessage(EMsg.CLIENT_PICS_PRODUCT_INFO_REQUEST, body, steamID, sessionID, job)) }
            catch (e: Exception) { pendingPics.remove(job); throw e }
            job to acc
        }
        return try {
            withTimeout(timeoutMs) { acc.done.await() }
        } catch (_: TimeoutCancellationException) {
            withContext(thread) {
                pendingPics.remove(job)
                if (acc.messages.isEmpty()) throw SteamException(SteamError.ConnectionTimeout)
                acc.messages.toList()
            }
        }
    }

    override suspend fun callServiceMethod(method: String, body: ByteArray, timeoutMs: Long): ByteArray {
        val (job, waiter) = withContext(thread) {
            resetIdleTimer()
            val job = nextJobID()
            val d = CompletableDeferred<IncomingMessage>()
            pendingJobs[job] = d
            try { sendRaw(MessageCodec.encodeServiceMethod(method, body, steamID, sessionID, job)) }
            catch (e: Exception) { pendingJobs.remove(job); throw e }
            job to d
        }
        val response = try {
            withTimeout(timeoutMs) { waiter.await() }
        } catch (_: TimeoutCancellationException) {
            withContext(thread) { pendingJobs.remove(job) }
            throw SteamException(SteamError.ConnectionTimeout)
        }
        if (response.header.eresult != 1) SteamLog.trace("$method header eresult=${response.header.eresult}")
        return response.body
    }

    /** The EResult Steam put in a service response header (1 = OK). */
    suspend fun callServiceMethodWithResult(method: String, body: ByteArray, timeoutMs: Long = 10_000): Pair<Int, ByteArray> {
        val (job, waiter) = withContext(thread) {
            resetIdleTimer()
            val job = nextJobID()
            val d = CompletableDeferred<IncomingMessage>()
            pendingJobs[job] = d
            try { sendRaw(MessageCodec.encodeServiceMethod(method, body, steamID, sessionID, job)) }
            catch (e: Exception) { pendingJobs.remove(job); throw e }
            job to d
        }
        val response = try {
            withTimeout(timeoutMs) { waiter.await() }
        } catch (_: TimeoutCancellationException) {
            withContext(thread) { pendingJobs.remove(job) }
            throw SteamException(SteamError.ConnectionTimeout)
        }
        return response.header.eresult to response.body
    }

    // MARK: Receiving

    private fun handleMessage(data: ByteArray) {
        val m = try { MessageCodec.decode(data) } catch (e: Exception) { SteamLog.trace("Undecodable message (${data.size} bytes)"); return }
        SteamLog.trace("<- ${EMsg.name(m.emsg)} (${m.body.size} bytes)")
        if (m.emsg == EMsg.MULTI) { handleMulti(m); return }
        val target = m.header.jobTarget
        if (m.emsg == EMsg.CLIENT_PICS_PRODUCT_INFO_RESPONSE && target != NO_JOB) {
            val acc = pendingPics[target]
            if (acc != null) {
                acc.messages += m
                val pending = try { CmMessages.picsResponse(m.body).pendingResponseCount } catch (_: Exception) { 0 }
                if (pending == 0) { pendingPics.remove(target); acc.done.complete(acc.messages.toList()) }
                return
            }
        }
        if (target != NO_JOB) {
            pendingJobs.remove(target)?.let { it.complete(m); return }
        }
        handlers[m.emsg]?.let { it(m); return }
        when (m.emsg) {
            EMsg.CLIENT_LOGGED_OFF -> { SteamLog.trace("Received ClientLoggedOff"); handleDisconnect(null) }
            else -> SteamLog.trace("Unhandled EMsg ${m.emsg}")
        }
    }

    private fun handleMulti(m: IncomingMessage) {
        try {
            val multi = CmMessages.multi(m.body)
            var payload = multi.body
            if (multi.sizeUnzipped > 0) payload = gunzip(payload, multi.sizeUnzipped) ?: run { SteamLog.trace("Failed to decompress multi"); return }
            var offset = 0
            while (offset + 4 <= payload.size) {
                val len = payload.u32le(offset)
                offset += 4
                if (len < 0 || offset + len > payload.size) break
                handleMessage(payload.copyOfRange(offset, offset + len.toInt()))
                offset += len.toInt()
            }
        } catch (e: Exception) { SteamLog.trace("Failed to parse multi: $e") }
    }

    private fun gunzip(data: ByteArray, expected: Int): ByteArray? = try {
        val limit = if (expected in 1..(256 shl 20)) expected else data.size * 10
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val out = ByteArrayOutputStream(limit)
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > 256 shl 20) return null
            }
            out.toByteArray()
        }
    } catch (_: Exception) { null }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatSeconds * 1000L)
                try { sendRaw(MessageCodec.encodeClientMessage(EMsg.CLIENT_HEARTBEAT, ByteArray(0), steamID, sessionID)) }
                catch (e: Exception) { SteamLog.trace("Heartbeat failed"); break }
            }
        }
    }

    private fun handleDisconnect(error: Throwable?) {
        if (_state.value == ConnectionState.DISCONNECTED) return
        val wasAuthenticated = _state.value == ConnectionState.AUTHENTICATED
        heartbeatJob?.cancel()
        transport = null
        SteamLog.trace("Connection lost${error?.let { ": ${it.message}" } ?: ""}")
        val err = SteamException(SteamError.Disconnected)
        pendingJobs.values.forEach { it.completeExceptionally(err) }; pendingJobs.clear()
        pendingPics.values.forEach { it.done.completeExceptionally(err) }; pendingPics.clear()
        if (wasAuthenticated && !isSuspended && reconnectAttempts < maxReconnectAttempts) {
            _state.value = ConnectionState.RECONNECTING
            attemptReconnect()
        } else {
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    private fun attemptReconnect() {
        reconnectJob = scope.launch {
            val delayMs = backoffBaseMs shl reconnectAttempts
            reconnectAttempts++
            SteamLog.trace("Reconnecting in ${delayMs}ms (attempt $reconnectAttempts/$maxReconnectAttempts)")
            delay(delayMs)
            try {
                connectAndLogin()
                SteamLog.trace("Reconnected")
            } catch (e: Exception) {
                SteamLog.trace("Reconnection failed")
                if (reconnectAttempts < maxReconnectAttempts && !isSuspended) {
                    _state.value = ConnectionState.RECONNECTING
                    attemptReconnect()
                } else _state.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun close() {
        scope.launch { disconnectNow() }
    }
}

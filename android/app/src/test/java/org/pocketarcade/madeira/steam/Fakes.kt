// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// Scripted stand-ins for the network: an HTTP client answering from a table and a
// Steam CM that answers logon, licences, PICS and service calls.

package org.pocketarcade.madeira.steam

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

internal class FakeHttp(val handler: (method: String, url: String, body: ByteArray?) -> HttpResponse) : HttpClient {
    val requests = ArrayList<String>()

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int, maxBytes: Long): HttpResponse {
        synchronized(requests) { requests += "GET $url" }
        return handler("GET", url, null)
    }

    override suspend fun post(url: String, body: ByteArray, contentType: String, timeoutMs: Int): HttpResponse {
        synchronized(requests) { requests += "POST $url" }
        return handler("POST", url, body)
    }

    override suspend fun send(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray?, timeoutMs: Int): HttpResponse {
        synchronized(requests) { requests += "$method $url" }
        return handler(method, url, body)
    }

    override suspend fun download(url: String, file: File, progress: (Long) -> Unit, timeoutMs: Int, maxBytes: Long): Int {
        val r = get(url, emptyMap(), timeoutMs, maxBytes)
        if (r.code == 200) { file.writeBytes(r.body); progress(r.body.size.toLong()) }
        return r.code
    }

    companion object {
        fun ok(body: ByteArray, headers: Map<String, String> = emptyMap()) = HttpResponse(200, headers, body)
        fun json(text: String) = ok(text.toByteArray())
        val notFound = HttpResponse(404, emptyMap(), ByteArray(0))
    }
}

internal const val CM_LIST = """{"response":{"serverlist":[{"endpoint":"cm1.steamserver.net:443"},{"endpoint":"cm2.steamserver.net:27021"}],"success":true}}"""

/** A Steam CM answering what the client sends. */
internal class FakeCm(
    var logonResult: Int = EResult.OK,
    val packages: List<Int> = listOf(100),
    val packageApps: Map<Int, List<Int>> = mapOf(100 to listOf(440)),
    val apps: Map<Int, String> = mapOf(440 to APP_VDF),
    val depotKeys: Map<Int, ByteArray> = emptyMap(),
    val services: Map<String, (ByteArray) -> ByteArray> = emptyMap(),
    /** Answer logons only from this connection number on (1-based); earlier ones time out. */
    val answerFrom: Int = 1,
) {
    val steamID = 76561198000000001L
    var connections = 0
    var sent = ArrayList<Int>()
    val logons = ArrayList<ByteArray>()

    fun transport(): CmTransport = object : CmTransport {
        private var listener: CmTransport.Listener? = null
        private var open = false
        private val number = ++connections
        override val isOpen get() = open

        override suspend fun connect(url: String, listener: CmTransport.Listener) {
            require(url.startsWith("wss://") && url.endsWith("/cmsocket/"))
            this.listener = listener; open = true
        }

        override suspend fun send(data: ByteArray) {
            val m = MessageCodec.decode(data)
            synchronized(sent) { sent += m.emsg }
            val reply = { emsg: Int, body: ByteArray, target: Long -> listener?.onMessage(MessageCodec.encode(emsg, ProtoHeader(steamID, 9, jobTarget = target), body)) }
            when (m.emsg) {
                EMsg.CLIENT_LOGON -> {
                    logons += m.body
                    if (number < answerFrom) return
                    reply(EMsg.CLIENT_LOGON_RESPONSE, proto { int32(1, logonResult); int32(3, 9); uint32(7, 31) }, NO_JOB)
                    if (logonResult == EResult.OK) {
                        // Licence list inside a gzip'd Multi, as Steam often sends it.
                        val licence = MessageCodec.encode(EMsg.CLIENT_LICENSE_LIST, ProtoHeader(steamID, 9),
                            proto { int32(1, 1); for (p in packages) message(2) { uint32(1, p); fixed32(2, 1); uint32(7, 0) } })
                        val inner = ByteArrayOutputStream().apply { u32le(licence.size.toLong()); write(licence) }.toByteArray()
                        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(inner) } }.toByteArray()
                        listener?.onMessage(MessageCodec.encode(EMsg.MULTI, ProtoHeader(), proto { uint32(1, inner.size); bytes(2, gz) }))
                    }
                }
                EMsg.CLIENT_PICS_ACCESS_TOKEN_REQUEST -> {
                    var pkgs = ArrayList<Int>(); var appIDs = ArrayList<Int>()
                    m.body.proto().fields { f -> when (f) { 1 -> pkgs += int32(); 2 -> appIDs += int32(); else -> skip() } }
                    reply(EMsg.CLIENT_PICS_ACCESS_TOKEN_RESPONSE, proto {
                        for (p in pkgs) message(1) { uint32(1, p); uint64(2, 1000L + p) }
                        for (a in appIDs) message(3) { uint32(1, a); uint64(2, 2000L + a) }
                    }, m.header.jobSource)
                }
                EMsg.CLIENT_PICS_PRODUCT_INFO_REQUEST -> {
                    val pkgs = ArrayList<Int>(); val appIDs = ArrayList<Int>()
                    m.body.proto().fields { f ->
                        when (f) {
                            1 -> message().fields { g -> if (g == 1) pkgs += int32() else skip() }
                            2 -> message().fields { g -> if (g == 1) appIDs += int32() else skip() }
                            else -> skip()
                        }
                    }
                    // Two parts per request: pending_response_count 1, then 0.
                    reply(EMsg.CLIENT_PICS_PRODUCT_INFO_RESPONSE, proto {
                        for (p in pkgs) message(3) { uint32(1, p); bytes(5, packageBuffer(packageApps[p].orEmpty())) }
                        uint32(6, 1)
                    }, m.header.jobSource)
                    reply(EMsg.CLIENT_PICS_PRODUCT_INFO_RESPONSE, proto {
                        for (a in appIDs) apps[a]?.let { text -> message(1) { uint32(1, a); bytes(5, text.toByteArray()) } }
                    }, m.header.jobSource)
                }
                EMsg.CLIENT_GET_DEPOT_DECRYPTION_KEY -> {
                    var depot = 0
                    m.body.proto().fields { f -> if (f == 1) depot = int32() else skip() }
                    val key = depotKeys[depot]
                    reply(EMsg.CLIENT_GET_DEPOT_DECRYPTION_KEY_RESPONSE, proto {
                        int32(1, if (key != null) 1 else 15); uint32(2, depot); if (key != null) bytes(3, key)
                    }, m.header.jobSource)
                }
                EMsg.SERVICE_METHOD_CALL_FROM_CLIENT -> {
                    val handler = services[m.header.targetJobName]
                    listener?.onMessage(MessageCodec.encode(EMsg.SERVICE_METHOD_RESPONSE,
                        ProtoHeader(steamID, 9, jobTarget = m.header.jobSource, eresult = if (handler != null) 1 else 2),
                        handler?.invoke(m.body) ?: ByteArray(0)))
                }
            }
        }

        override fun close() { if (open) { open = false } }
    }

    companion object {
        fun packageBuffer(appIDs: List<Int>): ByteArray {
            val out = ByteArrayOutputStream()
            fun str(s: String) { out.write(s.toByteArray()); out.write(0) }
            out.write(0); str("1"); out.write(0); str("appids")
            appIDs.forEachIndexed { i, id -> out.write(2); str("$i"); out.write(ByteArray(4) { (id ushr (8 * it)).toByte() }) }
            out.write(8); out.write(8); out.write(8)
            return out.toByteArray()
        }
    }
}

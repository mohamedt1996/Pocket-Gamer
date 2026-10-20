// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam Core/SteamProtocol.swift, Core/SteamMessageCodec.swift
// and the CM messages of Proto/SteamProtoMessages.swift. The numbers are Valve's
// (steammessages / enums.proto): interface facts of Steam's public protocol.

package org.pocketarcade.madeira.steam

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, RECONNECTING }

/** The message types the library and downloads use. */
object EMsg {
    const val PROTO_MASK = 0x80000000.toInt()
    const val MULTI = 1
    const val CLIENT_HEARTBEAT = 703
    const val CLIENT_LOGON_RESPONSE_LEGACY = 751
    const val CLIENT_LICENSE_LIST = 780
    const val CLIENT_HELLO = 4006
    const val CLIENT_GET_DEPOT_DECRYPTION_KEY = 5438
    const val CLIENT_GET_DEPOT_DECRYPTION_KEY_RESPONSE = 5439
    const val CLIENT_LOGON = 5514
    const val CLIENT_LOGON_RESPONSE = 5515
    const val CLIENT_LOG_OFF = 5516
    const val CLIENT_LOGGED_OFF = 5517
    const val CLIENT_PICS_PRODUCT_INFO_REQUEST = 8903
    const val CLIENT_PICS_PRODUCT_INFO_RESPONSE = 8904
    const val CLIENT_PICS_ACCESS_TOKEN_REQUEST = 8905
    const val CLIENT_PICS_ACCESS_TOKEN_RESPONSE = 8906
    const val SERVICE_METHOD = 146
    const val SERVICE_METHOD_RESPONSE = 147
    const val SERVICE_METHOD_CALL_FROM_CLIENT = 151

    fun name(raw: Int): String = when (raw) {
        MULTI -> "Multi"; CLIENT_LOGON -> "ClientLogon"; CLIENT_LOGON_RESPONSE -> "ClientLogonResponse"
        CLIENT_LOGON_RESPONSE_LEGACY -> "ClientLogOnResponse(legacy)"; CLIENT_LOGGED_OFF -> "ClientLoggedOff"
        CLIENT_HEARTBEAT -> "ClientHeartBeat"; CLIENT_HELLO -> "ClientHello"
        SERVICE_METHOD_RESPONSE -> "ServiceMethodResponse"
        else -> "EMsg$raw"
    }
}

/** Unified-service methods. */
object ServiceMethod {
    const val GET_MANIFEST_REQUEST_CODE = "ContentServerDirectory.GetManifestRequestCode#1"
    const val GET_CDN_AUTH_TOKEN = "ContentServerDirectory.GetCDNAuthToken#1"
    const val GET_OWNED_GAMES = "Player.GetOwnedGames#1"
    const val CLOUD_GET_APP_FILE_CHANGELIST = "Cloud.GetAppFileChangelist#1"
    const val CLOUD_CLIENT_FILE_DOWNLOAD = "Cloud.ClientFileDownload#1"
    const val CLOUD_BEGIN_APP_UPLOAD_BATCH = "Cloud.BeginAppUploadBatch#1"
    const val CLOUD_CLIENT_BEGIN_FILE_UPLOAD = "Cloud.ClientBeginFileUpload#1"
    const val CLOUD_CLIENT_COMMIT_FILE_UPLOAD = "Cloud.ClientCommitFileUpload#1"
    const val CLOUD_COMPLETE_APP_UPLOAD_BATCH = "Cloud.CompleteAppUploadBatchBlocking#1"
}

object EResult {
    const val OK = 1
    const val TRY_ANOTHER_CM = 48
}

/** No job: the header's default job IDs. */
const val NO_JOB = -1L

data class ProtoHeader(
    var steamID: Long = 0,
    var sessionID: Int = 0,
    var jobSource: Long = NO_JOB,
    var jobTarget: Long = NO_JOB,
    var targetJobName: String = "",
    var eresult: Int = 0,
) {
    fun serialize(): ByteArray = proto {
        fixed64Always(1, steamID)
        int32Always(2, sessionID)
        if (jobSource != NO_JOB) fixed64(10, jobSource)
        if (jobTarget != NO_JOB) fixed64(11, jobTarget)
        string(12, targetJobName)
        int32(13, eresult)
    }

    companion object {
        fun parse(r: ProtoReader): ProtoHeader {
            val h = ProtoHeader()
            r.fields { f ->
                when (f) {
                    1 -> h.steamID = fixed64()
                    2 -> h.sessionID = int32()
                    10 -> h.jobSource = fixed64()
                    11 -> h.jobTarget = fixed64()
                    12 -> h.targetJobName = string()
                    13 -> h.eresult = int32()
                    else -> skip()
                }
            }
            return h
        }
    }
}

class IncomingMessage(
    val emsg: Int,
    val isProtobuf: Boolean,
    val header: ProtoHeader,
    val body: ByteArray,
)

/**
 * Steam3 wire format over the WebSocket: one binary frame per message.
 * Protobuf: [4B EMsg|0x80000000][4B header length][CMsgProtoBufHeader][body].
 * Old style: [4B EMsg][8B target job][8B source job][body], or the 36-byte extended header.
 */
object MessageCodec {
    fun encode(emsg: Int, header: ProtoHeader, body: ByteArray): ByteArray {
        val h = header.serialize()
        val out = ByteArrayOutputStream(8 + h.size + body.size)
        out.u32le((emsg or EMsg.PROTO_MASK).toLong() and 0xFFFFFFFFL)
        out.u32le(h.size.toLong())
        out.write(h)
        out.write(body)
        return out.toByteArray()
    }

    fun encodeClientMessage(emsg: Int, body: ByteArray, steamID: Long = 0, sessionID: Int = 0, jobID: Long? = null): ByteArray =
        encode(emsg, ProtoHeader(steamID = steamID, sessionID = sessionID, jobSource = jobID ?: NO_JOB), body)

    fun encodeServiceMethod(method: String, body: ByteArray, steamID: Long = 0, sessionID: Int = 0, jobID: Long? = null): ByteArray =
        encode(EMsg.SERVICE_METHOD_CALL_FROM_CLIENT,
            ProtoHeader(steamID = steamID, sessionID = sessionID, jobSource = jobID ?: NO_JOB, targetJobName = method), body)

    fun decode(data: ByteArray): IncomingMessage {
        if (data.size < 4) throw SteamException(SteamError.InvalidMessage)
        val raw = data.u32le(0).toInt()
        val isProto = raw and EMsg.PROTO_MASK != 0
        val emsg = raw and EMsg.PROTO_MASK.inv()
        return if (isProto) decodeProtobuf(data, emsg) else decodeOld(data, emsg)
    }

    private fun decodeProtobuf(data: ByteArray, emsg: Int): IncomingMessage {
        if (data.size < 8) throw SteamException(SteamError.InvalidMessage)
        val len = data.u32le(4)
        if (len < 0 || data.size.toLong() < 8 + len) throw SteamException(SteamError.InvalidMessage)
        val header = ProtoHeader.parse(ProtoReader(data, 8, 8 + len.toInt()))
        return IncomingMessage(emsg, true, header, data.copyOfRange(8 + len.toInt(), data.size))
    }

    private fun decodeOld(data: ByteArray, emsg: Int): IncomingMessage {
        if (data.size < 5) throw SteamException(SteamError.InvalidMessage)
        val extended = (data[4].toInt() and 0xFF) == 36 && data.size >= 7 && data.u16le(5) == 2
        val headerSize = if (extended) 36 else 20
        if (data.size < headerSize) throw SteamException(SteamError.InvalidMessage)
        val h = ProtoHeader()
        if (extended) {
            h.jobTarget = data.u64le(7)
            h.jobSource = data.u64le(15)
            h.steamID = data.u64le(24)
            h.sessionID = data.u32le(32).toInt()
        } else {
            h.jobTarget = data.u64le(4)
            h.jobSource = data.u64le(12)
        }
        return IncomingMessage(emsg, false, h, data.copyOfRange(headerSize, data.size))
    }
}

// MARK: - CM messages

object CmMessages {
    const val PROTOCOL_VERSION = 65580

    fun hello(): ByteArray = proto { uint32(1, PROTOCOL_VERSION) }

    /**
     * CMsgClientLogon with the refresh token (CM logon accepts the refresh token in
     * `access_token`; only it establishes a session).
     */
    fun logon(accountName: String, refreshToken: String, machineName: String, osType: Int, cellID: Int = 0, localIPv4: Int = localIPv4()): ByteArray = proto {
        uint32(1, PROTOCOL_VERSION)
        message(11) { fixed32(1, localIPv4 xor 0xBAADF00D.toInt()) }
        if (cellID != 0) uint32(3, cellID)
        uint32(5, 1771)
        string(6, "english")
        uint32(7, osType)
        bool(8, true)
        string(50, accountName)
        string(96, machineName)
        bool(102, true)
        string(108, refreshToken)
    }

    fun localIPv4(): Int = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { !it.isLoopback && it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }
            ?.address?.let { b -> ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF) }
            ?: 0
    } catch (_: Exception) { 0 }

    class LogonResponse(val eresult: Int, val heartbeatSeconds: Int, val cellID: Int)

    fun logonResponse(body: ByteArray): LogonResponse {
        var e = 0; var hb = 0; var cell = 0
        body.proto().fields { f ->
            when (f) { 1 -> e = int32(); 3 -> hb = int32(); 7 -> cell = int32(); else -> skip() }
        }
        return LogonResponse(e, hb, cell)
    }

    class License(val packageID: Int, val flags: Int, val licenseType: Int, val ownerID: Int)

    fun licenseList(body: ByteArray): Pair<Int, List<License>> {
        var eresult = 0
        val list = ArrayList<License>()
        body.proto().fields { f ->
            when (f) {
                1 -> eresult = int32()
                2 -> {
                    var id = 0; var flags = 0; var type = 0; var owner = 0
                    message().fields { g ->
                        when (g) { 1 -> id = int32(); 7 -> flags = int32(); 9 -> type = int32(); 14 -> owner = int32(); else -> skip() }
                    }
                    list += License(id, flags, type, owner)
                }
                else -> skip()
            }
        }
        return eresult to list
    }

    /** PICS product info request: apps and packages with their access tokens. */
    fun picsRequest(apps: List<Pair<Int, Long>>, packages: List<Pair<Int, Long>>, metaDataOnly: Boolean = false): ByteArray = proto {
        for ((id, token) in packages) message(1) { uint32(1, id); uint64(2, token) }
        for ((id, token) in apps) message(2) { uint32(1, id); uint64(2, token) }
        bool(3, metaDataOnly)
    }

    class PicsApp(val appID: Int, val changeNumber: Int, val missingToken: Boolean, val buffer: ByteArray)
    class PicsPackage(val packageID: Int, val changeNumber: Int, val buffer: ByteArray)
    class PicsResponse(val apps: List<PicsApp>, val packages: List<PicsPackage>, val unknownApps: List<Int>,
                       val unknownPackages: List<Int>, val pendingResponseCount: Int)

    fun picsResponse(body: ByteArray): PicsResponse {
        val apps = ArrayList<PicsApp>(); val pkgs = ArrayList<PicsPackage>()
        val unknownApps = ArrayList<Int>(); val unknownPkgs = ArrayList<Int>()
        var pending = 0
        body.proto().fields { f ->
            when (f) {
                1 -> {
                    var id = 0; var change = 0; var missing = false; var buf = ByteArray(0)
                    message().fields { g ->
                        when {
                            g == 1 -> id = int32()
                            g == 2 -> change = int32()
                            g == 3 && wire == Wire.VARINT -> missing = bool()
                            g == 5 -> buf = bytes()
                            else -> skip()
                        }
                    }
                    apps += PicsApp(id, change, missing, buf)
                }
                2 -> unknownApps += int32()
                3 -> {
                    var id = 0; var change = 0; var buf = ByteArray(0)
                    message().fields { g -> when (g) { 1 -> id = int32(); 2 -> change = int32(); 5 -> buf = bytes(); else -> skip() } }
                    pkgs += PicsPackage(id, change, buf)
                }
                4 -> unknownPkgs += int32()
                6 -> pending = int32()
                else -> skip()
            }
        }
        return PicsResponse(apps, pkgs, unknownApps, unknownPkgs, pending)
    }

    fun picsAccessTokenRequest(apps: List<Int>, packages: List<Int>): ByteArray = proto {
        for (p in packages) uint32(1, p)
        for (a in apps) uint32(2, a)
    }

    class AccessTokens(val apps: Map<Int, Long>, val packages: Map<Int, Long>, val appsDenied: List<Int>, val packagesDenied: List<Int>)

    fun picsAccessTokenResponse(body: ByteArray): AccessTokens {
        val apps = HashMap<Int, Long>(); val pkgs = HashMap<Int, Long>()
        val appsDenied = ArrayList<Int>(); val pkgsDenied = ArrayList<Int>()
        body.proto().fields { f ->
            when (f) {
                1 -> { var id = 0; var t = 0L; message().fields { g -> when (g) { 1 -> id = int32(); 2 -> t = varint(); else -> skip() } }; pkgs[id] = t }
                2 -> pkgsDenied += int32()
                3 -> { var id = 0; var t = 0L; message().fields { g -> when (g) { 1 -> id = int32(); 2 -> t = varint(); else -> skip() } }; apps[id] = t }
                4 -> appsDenied += int32()
                else -> skip()
            }
        }
        return AccessTokens(apps, pkgs, appsDenied, pkgsDenied)
    }

    fun depotKeyRequest(depotID: Int, appID: Int): ByteArray = proto { uint32(1, depotID); uint32(2, appID) }

    class DepotKey(val eresult: Int, val depotID: Int, val key: ByteArray)

    fun depotKeyResponse(body: ByteArray): DepotKey {
        var e = 0; var d = 0; var k = ByteArray(0)
        body.proto().fields { f -> when (f) { 1 -> e = int32(); 2 -> d = int32(); 3 -> k = bytes(); else -> skip() } }
        return DepotKey(e, d, k)
    }

    class Multi(val sizeUnzipped: Int, val body: ByteArray)

    fun multi(body: ByteArray): Multi {
        var size = 0; var b = ByteArray(0)
        body.proto().fields { f -> when (f) { 1 -> size = int32(); 2 -> b = bytes(); else -> skip() } }
        return Multi(size, b)
    }

    /** ContentServerDirectory.GetManifestRequestCode: app 1, depot 2, manifest gid 3. Response field 1. */
    fun manifestRequestCode(appID: Int, depotID: Int, manifestID: Long): ByteArray = proto {
        uint32(1, appID); uint32(2, depotID); uint64(3, manifestID)
    }

    fun manifestRequestCodeResponse(body: ByteArray): Long {
        var code = 0L
        body.proto().fields { f -> if (f == 1) code = uint64Any() else skip() }
        return code
    }

    /** ContentServerDirectory.GetCDNAuthToken: depot 1, host 2, app 3. Response: token 1. */
    fun cdnAuthToken(depotID: Int, host: String, appID: Int): ByteArray = proto {
        uint32(1, depotID); string(2, host); uint32(3, appID)
    }

    fun cdnAuthTokenResponse(body: ByteArray): String {
        var token = ""
        body.proto().fields { f -> if (f == 1) token = string() else skip() }
        return token
    }
}

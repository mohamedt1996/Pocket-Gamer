// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors

package org.pocketarcade.madeira.steam

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.net.URLDecoder
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

class SessionTest {
    private fun session(cm: FakeCm, signedIn: Boolean = true, logonTimeoutMs: Long = 20_000): SteamSession {
        val http = FakeHttp { _, url, _ -> if (url.contains("GetCMListForConnect")) FakeHttp.json(CM_LIST) else FakeHttp.notFound }
        return SteamSession({ cm.transport() }, CmServerList(http, MemoryStore()),
            { if (signedIn) Credentials("gabe", "refresh.token.here") else null }, "Pixel", logonTimeoutMs = logonTimeoutMs)
    }

    @Test fun logonLicencesAndLibrary() = runBlocking<Unit> {
        val cm = FakeCm()
        val s = session(cm)
        s.ensureConnected()
        assertEquals(ConnectionState.AUTHENTICATED, s.state.value)
        assertEquals(cm.steamID, s.steamID)
        assertEquals(31, s.cellID)
        assertEquals(listOf(100), s.awaitLicenseList())
        // Hello first, then the logon with the refresh token in access_token (field 108).
        assertEquals(EMsg.CLIENT_HELLO, cm.sent[0]); assertEquals(EMsg.CLIENT_LOGON, cm.sent[1])
        var token = ""; var account = ""
        cm.logons[0].proto().fields { f -> when (f) { 108 -> token = string(); 50 -> account = string(); else -> skip() } }
        assertEquals("refresh.token.here", token); assertEquals("gabe", account)
        val apps = LibraryFetcher(s).ownedApps()
        assertEquals(listOf(440), apps.map { it.appID })
        assertEquals("Team Fortress 2", apps[0].installDir)
        s.disconnectGracefully()
        assertEquals(EMsg.CLIENT_LOG_OFF, cm.sent.last())
        assertEquals(ConnectionState.DISCONNECTED, s.state.value)
    }

    @Test fun deniedLogonReportsCode() = runBlocking<Unit> {
        val s = session(FakeCm(logonResult = 5))
        try { s.ensureConnected(); fail("logged on") } catch (e: SteamException) { assertEquals(SteamError.LogonDenied(5), e.error) }
        assertEquals(ConnectionState.DISCONNECTED, s.state.value)
    }

    @Test fun timeoutTriesAnotherServer() = runBlocking<Unit> {
        val cm = FakeCm(answerFrom = 2)
        val s = session(cm, logonTimeoutMs = 300)
        s.ensureConnected()
        assertEquals(2, cm.connections)
        assertEquals(ConnectionState.AUTHENTICATED, s.state.value)
    }

    @Test fun suspendedSessionRefusesToConnect() = runBlocking<Unit> {
        val s = session(FakeCm())
        s.ensureConnected()
        s.suspendForGame()
        try { s.ensureConnected(); fail("connected while suspended") } catch (e: SteamException) { assertEquals(SteamError.Disconnected, e.error) }
        s.resume()
        s.ensureConnected()
        assertEquals(ConnectionState.AUTHENTICATED, s.state.value)
    }

    @Test fun serviceMethodsRouteByJob() = runBlocking<Unit> {
        val cm = FakeCm(services = mapOf(ServiceMethod.GET_OWNED_GAMES to { _ -> proto { message(2) { uint32(1, 440); int32(4, 61) } } }))
        val s = session(cm)
        val p = LibraryFetcher(s).playtime()
        assertEquals(61, p[440]!!.minutes)
        val (eresult, _) = s.callServiceMethodWithResult("Unknown.Method#1", ByteArray(0))
        assertEquals(2, eresult)
    }

    @Test fun cmListFallsBackToCache() = runBlocking<Unit> {
        val store = MemoryStore()
        CmServerList(FakeHttp { _, _, _ -> FakeHttp.json(CM_LIST) }, store).server()
        val offline = CmServerList(FakeHttp { _, _, _ -> throw java.io.IOException("offline") }, store)
        assertEquals("cm1.steamserver.net:443", offline.server().endpoint)
        try { CmServerList(FakeHttp { _, _, _ -> FakeHttp.notFound }, MemoryStore()).server(); fail("no servers") } catch (_: SteamException) {}
    }
}

class AuthTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.genKeyPair()

    private fun field(body: ByteArray?, name: String = "input_protobuf_encoded"): ByteArray {
        val text = String(body ?: ByteArray(0))
        val value = text.substringAfter("$name=")
        return Base64.getDecoder().decode(URLDecoder.decode(value, "UTF-8"))
    }

    @Test fun passwordWithDeviceCode() = runBlocking<Unit> {
        var polls = 0
        var submitted = ""
        var decrypted = ""
        val pub = keys.public as RSAPublicKey
        val http = FakeHttp { method, url, body ->
            when {
                url.contains("GetPasswordRSAPublicKey") -> {
                    assertEquals("GET", method)
                    val req = field(url.substringAfter("?").toByteArray())
                    var name = ""; req.proto().fields { f -> if (f == 1) name = string() else skip() }
                    assertEquals("gabe", name)
                    FakeHttp.ok(proto { string(1, pub.modulus.toString(16)); string(2, pub.publicExponent.toString(16)); uint64(3, 777) })
                }
                url.contains("BeginAuthSessionViaCredentials") -> {
                    var enc = ""; var ts = 0L
                    field(body).proto().fields { f -> when (f) { 3 -> enc = string(); 4 -> ts = varint(); else -> skip() } }
                    assertEquals(777L, ts)
                    val c = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, keys.private) }
                    decrypted = String(c.doFinal(Base64.getDecoder().decode(enc)))
                    FakeHttp.ok(proto {
                        uint64(1, 55); bytes(2, byteArrayOf(1, 2)); fixed32(3, java.lang.Float.floatToIntBits(0.01f))
                        message(4) { uint32(1, 4) }; message(4) { uint32(1, 3) }; fixed64(5, 76561198000000001L)
                    })
                }
                url.contains("UpdateAuthSessionWithSteamGuardCode") -> {
                    var code = ""; var type = 0
                    field(body).proto().fields { f -> when (f) { 3 -> code = string(); 4 -> type = int32(); else -> skip() } }
                    submitted = "$code/$type"
                    FakeHttp.ok(ByteArray(0))
                }
                url.contains("PollAuthSessionStatus") -> {
                    polls++
                    if (submitted.isEmpty()) FakeHttp.ok(ByteArray(0))
                    else FakeHttp.ok(proto { string(3, "R"); string(4, "A"); string(6, "gabe_n") })
                }
                else -> FakeHttp.notFound
            }
        }
        val auth = SteamCredentialAuth(SteamAuthApi(http), "Pixel")
        val prompt = auth.begin("gabe", "hunter2")!!
        assertEquals("hunter2", decrypted)
        assertEquals(SteamGuardType.DEVICE, prompt.codeType)
        assertTrue(prompt.canApprove)
        auth.submitSteamGuardCode(" ab12c ", SteamGuardType.DEVICE)
        assertEquals("AB12C/3", submitted)
        val tokens = auth.pollForTokens()
        assertEquals("R", tokens.refreshToken); assertEquals("gabe_n", tokens.accountName)
        assertTrue(polls >= 1)
    }

    @Test fun eresultHeaderMapsToErrors() = runBlocking<Unit> {
        val http = FakeHttp { _, _, _ -> FakeHttp.ok(ByteArray(0), mapOf("x-eresult" to "5")) }
        try { SteamCredentialAuth(SteamAuthApi(http), "P").begin("a", "b"); fail("accepted") }
        catch (e: SteamException) { assertEquals(SteamError.InvalidCredentials, e.error) }
        val limited = FakeHttp { _, _, _ -> HttpResponse(429, emptyMap(), ByteArray(0)) }
        try { SteamQrAuth(SteamAuthApi(limited), "P").begin(); fail("accepted") }
        catch (e: SteamException) { assertEquals(SteamError.RateLimited, e.error) }
        assertEquals(SteamError.AuthSessionExpired, SteamAuthApi.error(27))
    }

    @Test fun qrRotatesChallenge() = runBlocking<Unit> {
        var polls = 0
        val http = FakeHttp { _, url, _ ->
            when {
                url.contains("BeginAuthSessionViaQR") -> FakeHttp.ok(proto { uint64(1, 9); string(2, "https://s.team/q/1/1"); bytes(3, byteArrayOf(4)); fixed32(4, java.lang.Float.floatToIntBits(0.001f)) })
                url.contains("PollAuthSessionStatus") -> { polls++; FakeHttp.ok(if (polls == 1) proto { string(2, "https://s.team/q/1/2") } else proto { string(3, "R"); string(4, "A"); string(6, "n") }) }
                else -> FakeHttp.notFound
            }
        }
        val qr = SteamQrAuth(SteamAuthApi(http), "P")
        assertEquals("https://s.team/q/1/1", qr.begin())
        val rotated = ArrayList<String>()
        val t = qr.poll { rotated += it }
        assertEquals(listOf("https://s.team/q/1/2"), rotated)
        assertEquals("n", t.accountName)
    }

    @Test fun jwtClaims() {
        fun jwt(json: String) = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray()) + ".sig"
        assertEquals(76561198000000001L, SteamJwt.subject(jwt("""{"sub":"76561198000000001","exp":5}""")))
        assertTrue(!SteamJwt.usable(jwt("""{"exp":5}"""), 10))
        assertTrue(SteamJwt.usable(jwt("""{"exp":50}"""), 10))
        assertTrue(SteamJwt.usable("opaque", 10))
        assertTrue(!SteamJwt.usable("", 10))
        assertEquals(BigInteger.ONE, BigInteger.ONE)
    }
}

class DownloaderTest {
    @Test fun installsResumesAndWritesRecord() = runBlocking<Unit> {
        System.getProperty("zstd.lib")?.let { Zstd.load(it) }
        val key = ByteArray(32) { (it + 1).toByte() }
        val fileA = ByteArray(300_000) { (it % 251).toByte() }
        val fileB = "readme".toByteArray()
        // fileA in two chunks (zip-deflated), fileB in one (stored zip).
        val parts = listOf(fileA.copyOfRange(0, 200_000), fileA.copyOfRange(200_000, 300_000), fileB)
        val shas = parts.map { ContentDecryptor.sha1(it) }
        val bodies = parts.mapIndexed { i, p -> steamEncrypt(zipSingle("c", p, deflate = i != 2), key) }
        val manifest = proto {
            message(1) {
                string(1, "Bin\\Game.exe"); uint64(2, fileA.size.toLong()); uint32(3, 0x80)
                for (i in 0..1) message(6) {
                    bytes(1, shas[i]); fixed32(2, ContentDecryptor.adler32(parts[i])); uint64(3, if (i == 0) 0 else 200_000)
                    uint32(4, parts[i].size); uint32(5, bodies[i].size)
                }
            }
            message(1) { string(1, "bin"); uint32(3, 0x40) }
            message(1) {
                string(1, "bin/readme.txt"); uint64(2, fileB.size.toLong())
                message(6) { bytes(1, shas[2]); fixed32(2, ContentDecryptor.adler32(fileB)); uint32(4, fileB.size); uint32(5, bodies[2].size) }
            }
            message(1) { string(1, "../escape.txt"); uint64(2, 1) }
        }
        var failChunk = true
        val http = FakeHttp { _, url, _ ->
            when {
                url.contains("/manifest/441/5/9999") -> FakeHttp.ok(zipSingle("z", manifest))
                url.contains("/chunk/") -> {
                    val sha = url.substringAfter("/chunk/").substringBefore("?")
                    val i = shas.indexOfFirst { it.hex() == sha }
                    if (i == 1 && failChunk) { failChunk = false; HttpResponse(503, emptyMap(), ByteArray(0)) } else FakeHttp.ok(bodies[i])
                }
                else -> FakeHttp.notFound
            }
        }
        val app = AppInfo(440, name = "Game", installDir = "Game", buildID = 77)
        app.depots += DepotInfo(441, manifests = mapOf("public" to 441L))
        val cm = FakeCm(depotKeys = mapOf(441 to key), services = mapOf(
            ServiceMethod.GET_MANIFEST_REQUEST_CODE to { _ -> proto { fixed64(1, 9999) } },
            ServiceMethod.GET_CDN_AUTH_TOKEN to { _ -> proto { string(1, "tok") } }))
        val s = SteamSession({ cm.transport() }, CmServerList(FakeHttp { _, _, _ -> FakeHttp.json(CM_LIST) }, MemoryStore()), { Credentials("a", "b") }, "P")
        val dir = Files.createTempDirectory("steamapps").toFile()
        try {
            val d = DepotDownloader(s, http)
            d.contentHosts = { listOf("https://cache1.steamcontent.com", "https://cache2.steamcontent.com") }
            val progress = ArrayList<DownloadProgress>()
            val folder = d.install(app, dir) { synchronized(progress) { progress += it } }
            assertEquals(File(dir, "common/Game"), folder)
            assertArrayEquals(fileA, File(folder, "Bin/Game.exe").readBytes())
            assertArrayEquals(fileB, File(folder, "Bin/readme.txt").readBytes())
            assertTrue(!File(dir, "common/escape.txt").exists())
            assertTrue(http.requests.any { it.contains("?auth=tok") })
            val record = SteamInstallFiles.record(440, dir)!!
            assertEquals("77", record["buildid"]!!.string)
            assertEquals("Bin\\Game.exe", record["CheckGuid"]!!["0"]!!.string)
            assertEquals("441", record["InstalledDepots"]!!["441"]!!["manifest"]!!.string)
            assertNotNull(File(dir, "depotcache/441_441.manifest").takeIf { it.isFile })
            assertTrue(!File(dir, "downloading/440").exists())
            assertEquals(DownloadProgress.Phase.FINISHING, progress.last().phase)

            // A second install of the same build finds every chunk on disk and fetches none.
            val before = http.requests.count { it.contains("/chunk/") }
            d.install(app, dir) {}
            assertEquals(before, http.requests.count { it.contains("/chunk/") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun refusedKeyForUnownedDepotIsSkipped() = runBlocking<Unit> {
        val app = AppInfo(440, name = "Game", installDir = "Game")
        app.depots += DepotInfo(441, manifests = mapOf("public" to 1L))
        app.depots += DepotInfo(442, manifests = mapOf("public" to 2L))
        val cm = FakeCm(depotKeys = emptyMap())
        val s = SteamSession({ cm.transport() }, CmServerList(FakeHttp { _, _, _ -> FakeHttp.json(CM_LIST) }, MemoryStore()), { Credentials("a", "b") }, "P")
        val d = DepotDownloader(s, FakeHttp { _, _, _ -> FakeHttp.notFound })
        d.contentHosts = { listOf("https://c.steamcontent.com") }
        val dir = Files.createTempDirectory("steamapps").toFile()
        try {
            d.install(app, dir, ownedDepots = { setOf(999) }) {}
            fail("installed without keys")
        } catch (e: SteamException) {
            assertEquals(SteamError.DepotKeyNotFound(441), e.error)
        } finally { dir.deleteRecursively() }
        assertTrue(DepotDownloader.usableContentHost("cache5-fra1.steamcontent.com"))
        assertTrue(!DepotDownloader.usableContentHost("lancache.steamcontent.com"))
        assertEquals(DepotDownloader.Eligibility.NO_HTTPS, DepotDownloader.eligibility(mapOf("https_support" to "unavailable"), 1))
        assertEquals(DepotDownloader.Eligibility.OTHER, DepotDownloader.eligibility(mapOf("allowed_app_ids" to listOf(5L)), 1))
        assertEquals(DepotDownloader.Eligibility.USABLE, DepotDownloader.eligibility(mapOf("allowed_app_ids" to listOf(1L)), 1))
    }
}

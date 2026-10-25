// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.pocketarcade.madeira.epic.manifest.*
import org.pocketarcade.madeira.session.CommandLine
import org.pocketarcade.madeira.session.LaunchSpec
import org.pocketarcade.madeira.session.WineLaunch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.Deflater

class EpicIntegrationTest {
    private val game = EpicGame("test-app", "test-namespace", "test-catalog", "Test game")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes)
    private fun chunk(data: ByteArray) = ChunkInfo(guid = intArrayOf(1, 2, 3, 4), hash = 123u,
        shaHash = sha(data), windowSize = data.size, fileSize = (data.size + 66).toLong())
    private fun chunkBytes(data: ByteArray, info: ChunkInfo, compressed: Boolean = false): ByteArray {
        val payload = if (compressed) {
            val d = Deflater(); d.setInput(data); d.finish()
            val out = ByteArray(data.size + 64); val n = d.deflate(out); d.end(); out.copyOf(n)
        } else data
        return ByteBuffer.allocate(66 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0xB1FE3AA2.toInt()); putInt(3); putInt(66); putInt(payload.size)
            info.guid.forEach { putInt(it) }; putLong(info.hash.toLong()); put(if (compressed) 1.toByte() else 0.toByte())
            put(info.shaHash); put(2); putInt(data.size); put(payload)
        }.array()
    }
    private fun manifest(data: ByteArray, filename: String = "Game.exe", split: Boolean = false): ByteArray {
        val info = chunk(data)
        val m = BinaryManifest()
        m.meta = ManifestMeta(appName = game.app, buildVersion = "1", launchExe = filename)
        m.chunkDataList = ChunkDataList(count = 1, elements = mutableListOf(info))
        val parts = if (split) mutableListOf(ChunkPart(info.guid, 0, data.size / 2, 0),
            ChunkPart(info.guid, data.size / 2, data.size - data.size / 2, (data.size / 2).toLong()))
            else mutableListOf(ChunkPart(info.guid, 0, data.size, 0))
        m.fileManifestList = FileManifestList(count = 1, elements = mutableListOf(
            FileManifest(filename = filename, hash = sha(data), fileSize = data.size.toLong(), chunkParts = parts)))
        m.customFields = CustomFields()
        return m.serialize()
    }
    @Test fun codeParsingAcceptsEpicResponseWithoutKeepingPassword() {
        assertEquals("one-use-code", EpicApi.authorizationCode("{\"authorizationCode\":\"one-use-code\"}"))
        assertEquals("one-use-code", EpicApi.authorizationCode(" one-use-code "))
        assertThrows(IllegalArgumentException::class.java) { EpicApi.authorizationCode("https://not-a-code/") }
    }
    @Test fun authUsesFormAndRefreshRotatesTokens() = runBlocking {
        val requests = mutableListOf<Request>()
        val http = object : EpicHttp {
            override suspend fun get(request: Request, limit: Int): ByteArray {
                requests += request
                return """{"account_id":"account","displayName":"Player","access_token":"access","refresh_token":"rotated","expires_in":3600}""".toByteArray()
            }
        }
        val api = EpicApi(http)
        assertEquals("Player", api.signIn("one-use-code").name)
        assertEquals("rotated", api.refresh("previous-refresh").refresh)
        assertEquals("POST", requests[0].method)
        assertTrue(requests[0].header("Authorization")!!.startsWith("Basic "))
        assertEquals(null, requests[0].url.query)
        val body = okio.Buffer(); requests[1].body!!.writeTo(body)
        assertTrue(body.readUtf8().contains("grant_type=refresh_token"))
    }
    @Test fun libraryPaginatesFiltersPlatformsAndResolvesTitles() = runBlocking {
        var pages = 0
        val api = EpicApi(object : EpicHttp {
            override suspend fun get(request: Request, limit: Int): ByteArray {
                assertEquals("Bearer access", request.header("Authorization"))
                return if (request.url.host.startsWith("library-service")) {
                    pages++
                    if (pages == 1) """{"records":[{"appName":"test-app","namespace":"ns","catalogItemId":"item","platform":["Windows"]},{"appName":"android","namespace":"ns","catalogItemId":"mobile","platform":["Android"]}],"responseMetadata":{"nextCursor":"page 2"}}""".toByteArray()
                    else """{"records":[],"responseMetadata":{}}""".toByteArray()
                } else """{"item":{"title":"Owned Game","keyImages":[{"type":"DieselGameBoxWide","url":"https://images.example/cover.png"}]}}""".toByteArray()
            }
        })
        val games = api.library("access")
        assertEquals(2, pages); assertEquals(1, games.size); assertEquals("Owned Game", games.single().title)
    }
    @Test fun repeatedCursorFailsInsteadOfLooping() = runBlocking {
        val api = EpicApi(object : EpicHttp { override suspend fun get(request: Request, limit: Int) =
            """{"records":[],"responseMetadata":{"nextCursor":"same"}}""".toByteArray() })
        try { api.library("access"); fail("Repeated cursor accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun chunkChecksCompressionGuidAndContent() {
        val data = "verified Epic payload".repeat(20).toByteArray(); val info = chunk(data)
        for (compressed in listOf(false, true)) assertArrayEquals(data, EpicDownloader.decodeChunk(chunkBytes(data, info, compressed), info))
        val corrupt = chunkBytes(data, info).also { it[it.lastIndex] = 0 }
        assertThrows(IllegalArgumentException::class.java) { EpicDownloader.decodeChunk(corrupt, info) }
        assertThrows(IllegalArgumentException::class.java) { EpicDownloader.decodeChunk(chunkBytes(data, info), info.copy(guid = intArrayOf(5, 6, 7, 8))) }
    }
    @Test fun installAssemblesVerifiesAndResumesWithoutDownloadingAgain() = runBlocking {
        val data = "game-data".repeat(100).toByteArray(); val info = chunk(data); var requests = 0
        val downloader = EpicDownloader(object : EpicHttp {
            override suspend fun get(request: Request, limit: Int): ByteArray {
                requests++; assertNull(request.header("Authorization")); assertEquals("signed", request.url.queryParameter("token"))
                return chunkBytes(data, info, true)
            }
        })
        val root = Files.createTempDirectory("epic-install").toFile()
        try {
            val build = EpicBuild(manifest(data), listOf("https://cdn.example/build/game.manifest?token=signed"))
            val result = downloader.install(build, game, root) {}
            assertEquals("Game.exe", result.meta!!.launchExe); assertArrayEquals(data, File(root, "Game.exe").readBytes())
            downloader.install(build, game, root) {}
            assertEquals(1, requests)
            File(root, "Game.exe").writeText("corrupt")
            downloader.install(build, game, root) {}
            assertEquals(2, requests); assertArrayEquals(data, File(root, "Game.exe").readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptDownloadsNeverReplaceExistingFiles() = runBlocking {
        val data = "original-data".toByteArray(); val root = Files.createTempDirectory("epic-bad").toFile()
        try {
            File(root, "Game.exe").writeText("keep me")
            val downloader = EpicDownloader(object : EpicHttp { override suspend fun get(request: Request, limit: Int) = ByteArray(10) })
            try { downloader.install(EpicBuild(manifest(data), listOf("https://cdn.example/manifest")), game, root) {}; fail("Corrupt chunk accepted") }
            catch (_: java.io.IOException) {}
            assertEquals("keep me", File(root, "Game.exe").readText())
            assertFalse(File(root, "Game.exe.pocket-part").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun pausedFileResumesFromVerifiedPartAndRejectsTamperedPartial() = runBlocking {
        val data = "long game payload".repeat(64).toByteArray(); val info = chunk(data)
        var requests = 0
        val downloader = EpicDownloader(object : EpicHttp {
            override suspend fun get(request: Request, limit: Int): ByteArray {
                requests++; return chunkBytes(data, info)
            }
        })
        val root = Files.createTempDirectory("epic-partial").toFile()
        val build = EpicBuild(manifest(data, split = true), listOf("https://cdn.example/game.manifest"))
        try {
            try {
                downloader.install(build, game, root) { if (it.done == data.size / 2L) throw kotlinx.coroutines.CancellationException() }
                fail("Expected pause")
            } catch (_: kotlinx.coroutines.CancellationException) {}
            val partial = File(root, "Game.exe.pocket-part")
            val state = File(root, "Game.exe.pocket-state")
            assertEquals(data.size / 2L, partial.length()); assertTrue(state.isFile)
            assertEquals(1, JSONObject(state.readText()).getInt("nextPart"))
            downloader.install(build, game, root) {}
            assertArrayEquals(data, File(root, "Game.exe").readBytes())
            assertFalse(partial.exists()); assertFalse(state.exists()); assertEquals(2, requests)

            File(root, "Game.exe").delete()
            try {
                downloader.install(build, game, root) { if (it.done == data.size / 2L) throw kotlinx.coroutines.CancellationException() }
                fail("Expected pause")
            } catch (_: kotlinx.coroutines.CancellationException) {}
            partial.writeBytes(ByteArray(partial.length().toInt()))
            try { downloader.install(build, game, root) {}; fail("Tampered partial accepted") }
            catch (_: IllegalStateException) {}
            assertFalse(partial.exists()); assertFalse(state.exists())
            downloader.install(build, game, root) {}
            assertArrayEquals(data, File(root, "Game.exe").readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun pathsRejectTraversalAndExistingSymlinkEscapes() {
        val root = Files.createTempDirectory("epic-path").toFile(); val outside = Files.createTempDirectory("epic-outside").toFile()
        try {
            for (name in listOf("../escape", "/absolute", "C:/escape", "dir\\escape", "dir/../escape", "Game.exe.pocket-part", "Game.exe.pocket-state"))
                assertThrows(IllegalArgumentException::class.java) { EpicDownloader.safeFile(root, name) }
            Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { EpicDownloader.safeFile(root, "linked/escape") }
        } finally { File(root, "linked").delete(); root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun manifestTamperingIsRejected() {
        val bytes = manifest("hello".toByteArray())
        bytes[12] = (bytes[12].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { EpicManifest.readAll(bytes) }
    }
    @Test fun wowboxRegistryKeepsX64FexAndUnrelatedSettings() {
        val prefix = Files.createTempDirectory("epic-prefix").toFile()
        try {
            WineLaunch.seedRegistry(prefix)
            val file = File(prefix, "system.reg")
            val result = RuntimeComponents.registry(file.readText(), "wowbox64.dll")
            assertTrue(result.contains("@=\"libarm64ecfex.dll\"")); assertTrue(result.contains("@=\"wowbox64.dll\""))
            assertEquals(result, RuntimeComponents.registry(result, "wowbox64.dll"))
            assertThrows(IllegalArgumentException::class.java) { RuntimeComponents.registry(result.replace("wowbox64.dll", "custom.dll"), "wowbox64.dll") }
        } finally { prefix.deleteRecursively() }
    }
    @Test fun epicLaunchQuotingPreservesPathsAndDisplayNames() {
        val args = listOf("Z:\\Games\\Rocket League\\Launcher.exe", "-AUTH_PASSWORD=one-use-code", "-epicusername=Player Name", "trailing\\", "say\"hello")
        assertEquals(args, CommandLine.split(args.joinToString(" ", transform = EpicLibrary::quote)))
    }
    @Test fun cancelledDownloadPreservesCancellation() = runBlocking {
        val root = Files.createTempDirectory("epic-cancel").toFile()
        val downloader = EpicDownloader(object : EpicHttp {
            override suspend fun get(request: Request, limit: Int): ByteArray = throw kotlinx.coroutines.CancellationException()
        })
        try {
            try { downloader.install(EpicBuild(manifest("test".toByteArray()), listOf("https://cdn.example/manifest")), game, root) {}; fail("Cancellation swallowed") }
            catch (_: kotlinx.coroutines.CancellationException) {}
            assertFalse(File(root, "Game.exe").exists()); assertFalse(File(root, "Game.exe.pocket-part").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun hostComponentRejectsWrongPeArchitectureAndMalformedTar() {
        val bytes = ByteArray(128); bytes[0] = 77; bytes[1] = 90
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(60, 64); b.putInt(64, 0x4550); b.putShort(68, 0x8664.toShort())
        assertThrows(IllegalArgumentException::class.java) { RuntimeComponents.validateDll(bytes) }
        b.putShort(68, 0xaa64.toShort()); RuntimeComponents.validateDll(bytes)
        assertThrows(IllegalStateException::class.java) { RuntimeComponents.dllFromTar(ByteArray(512)) }
    }
    @Test fun linuxRunnerRequiresAndroidArm64ElfAndExactTarEntry() {
        val binary = ByteArray(256)
        val b = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
        binary[0] = 0x7f; binary[1] = 'E'.code.toByte(); binary[2] = 'L'.code.toByte(); binary[3] = 'F'.code.toByte()
        binary[4] = 2; binary[5] = 1
        b.putShort(18, 183.toShort()); b.putLong(32, 64); b.putShort(54, 56); b.putShort(56, 2)
        b.putInt(64, 1); b.putLong(72, 0); b.putLong(80, 0); b.putLong(112, 4096)
        b.putInt(120, 3); b.putLong(128, 192); b.putLong(152, 21)
        "/system/bin/linker64".toByteArray().copyInto(binary, 192)
        RuntimeComponents.validateBox64(binary)
        assertThrows(IllegalArgumentException::class.java) { RuntimeComponents.validateBox64(binary, 16384) }
        b.putLong(112, 16384)
        RuntimeComponents.validateBox64(binary, 16384)
        val tar = ByteArray(1024)
        "./usr/bin/box64".toByteArray().copyInto(tar)
        "00000000400\u0000".toByteArray().copyInto(tar, 124)
        tar[156] = '0'.code.toByte(); binary.copyInto(tar, 512)
        assertArrayEquals(binary, RuntimeComponents.tarEntry(tar, "./usr/bin/box64"))
        assertThrows(IllegalStateException::class.java) { RuntimeComponents.tarEntry(tar, "./usr/bin/other") }
        b.putShort(18, 62)
        assertThrows(IllegalArgumentException::class.java) { RuntimeComponents.validateBox64(binary) }
    }
    @Test fun credentialsDebugStringIsRedacted() {
        val credentials = EpicCredentials("account", "Player", "access-value", "refresh-value", 123)
        assertFalse(credentials.toString().contains("access-value"))
        assertFalse(credentials.toString().contains("refresh-value"))
    }
    @Test fun eacReportDoesNotClaimCompatibilityOrContainAccountTokens() {
        val report = EacCompatibility.report("RocketLeague", "Launcher.exe", true, "wowbox64", true)
        assertEquals("unverified", report.getString("eacOnlineStatus"))
        assertTrue(report.getString("protonLinuxEacBridge").contains("not available"))
        assertFalse(report.toString().contains("AUTH_PASSWORD")); assertFalse(report.has("access_token"))
    }
    @Test fun eacDetectionRecognizesCommonEpicBootstrapperNames() {
        assertTrue(EacCompatibility.detects(listOf("RocketLeague/Binaries/EasyAntiCheat_EOS_Setup.exe"), "RocketLeague.exe"))
        assertTrue(EacCompatibility.detects(emptyList(), "Bin/start_protected_game.exe"))
        assertFalse(EacCompatibility.detects(listOf("Binaries/NoEac.exe"), "RocketLeague.exe"))
    }
    @Test fun eacCaptureRecordsOnlyStagesAndNeverCopiesWineLogSecrets() {
        val folder = Files.createTempDirectory("epic-eac").toFile()
        try {
            val report = File(folder, "report.json")
            report.writeText(EacCompatibility.report("RocketLeague", "Launcher.exe", true, "fex", true, true).toString())
            val log = File(folder, "wine.log")
            log.writeText("123:trace:loaddll:build_module Loaded L\\\"EasyAntiCheat_EOS.dll\\\"\n" +
                "124:err:module:import_dll EasyAntiCheat_x64.so missing -AUTH_PASSWORD=secret-exchange-code\n")
            EacCompatibility.capture(report, log)
            val result = JSONObject(report.readText())
            assertTrue(result.getBoolean("eacWindowsModuleObserved"))
            assertTrue(result.getBoolean("eacErrorObserved"))
            assertTrue(result.getBoolean("linux64BridgeObserved"))
            assertEquals("unverified", result.getString("eacOnlineStatus"))
            assertFalse(report.readText().contains("secret-exchange-code"))
            assertFalse(report.readText().contains("AUTH_PASSWORD"))
        } finally { folder.deleteRecursively() }
    }
    @Test fun eacDiagnosticLaunchFieldsSurviveWineIntentSerialization() {
        val spec = LaunchSpec("/game", "Game.exe", "/game", "Rocket League", eacReport = "/game/report.json", eacDiagnostics = true)
        val parsed = LaunchSpec.fromJson(spec.toJson())
        assertEquals(spec.eacReport, parsed.eacReport)
        assertTrue(parsed.eacDiagnostics)
    }
}

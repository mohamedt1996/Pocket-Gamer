// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors

package org.pocketarcade.madeira.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

internal const val APP_VDF = """
"440"
{
	"common"
	{
		"name"		"Team \"Fortress\" 2"
		"type"		"game"
		"oslist"		"windows,linux"
		"parent"		"0"
		"library_assets_full"
		{
			"library_capsule" { "image" { "english" "abc/library_600x900.jpg" } "image2x" { "english" "abc/library_600x900_2x.jpg" } }
			"library_hero" { "image" { "german" "h.jpg" } }
		}
		"header_image" { "english" "../evil.jpg" }
	}
	"config"
	{
		"installdir"		"Team Fortress 2"
		"launch"
		{
			"1" { "executable" "tf_win64.exe" "arguments" "-game tf /Name=\"Two Words\"" "type" "default" "config" { "oslist" "windows" "osarch" "64" } }
			"0" { "executable" "hl2.exe" "arguments" "-game tf" "config" { "oslist" "windows" } }
			"2" { "executable" "" }
		}
	}
	"depots"
	{
		"441" { "name" "content" "maxsize" "1000" "manifests" { "public" { "gid" "18446744073709551615" "size" "5000" "download" "2500" } } }
		"442" { "config" { "oslist" "windows" "osarch" "32" } "manifests" { "public" "123" } }
		"443" { "config" { "oslist" "windows" "osarch" "64" } "manifests" { "public" "124" } }
		"444" { "config" { "oslist" "linux" } "manifests" { "public" "125" } }
		"445" { "config" { "language" "german" } "manifests" { "public" "126" } }
		"446" { "dlcappid" "999" "manifests" { "public" "127" } }
		"447" { "sharedinstall" "1" "manifests" { "public" "128" } }
		"448" { "config" { "lowviolence" "1" } "manifests" { "public" "129" } }
		"449" { "depotfromapp" "228980" }
		"branches" { "public" { "buildid" "12345" } }
	}
	"ufs"
	{
		"savefiles" { "0" { "root" "WinMyDocuments" "path" "My Games/TF" "pattern" "*.sav" "recursive" "1" "platforms" { "1" "Windows" } } }
		"rootoverrides" { "0" { "root" "WinMyDocuments" "os" "MacOS" "useinstead" "MacHome" "addpath" "Documents" } }
	}
}
"""

class LibraryDataTest {
    @Test fun appInfoParse() {
        val app = AppInfo.parse(440, APP_VDF.toByteArray())!!
        assertEquals("Team \"Fortress\" 2", app.name)
        assertEquals(AppType.GAME, app.type)
        assertEquals("Team Fortress 2", app.installDir)
        assertEquals(12345, app.buildID)
        assertEquals("abc/library_600x900_2x.jpg", app.libraryCapsule)
        assertEquals("h.jpg", app.libraryHero)
        assertNull(app.headerImage)
        assertNull(app.parentID)
        assertEquals(listOf("hl2.exe", "tf_win64.exe"), app.launches.map { it.executable })
        assertEquals("-game tf /Name=\"Two Words\"", app.launches[1].arguments)
        assertEquals(1, app.launches[1].index)
        assertEquals(-1L, app.depots.first { it.depotID == 441 }.publicManifestID)
        assertEquals(2500L, app.depots.first { it.depotID == 441 }.publicDownloadBytes)
        assertEquals(listOf(441, 443), app.installDepots().map { it.depotID })
        assertEquals(listOf(441, 444), app.installDepots("linux").map { it.depotID })
        assertEquals(2500L + 0, app.downloadSize())
        assertEquals(228980, app.depots.first { it.depotID == 449 }.fromApp)
        assertTrue(app.installableOnWindows)
        assertEquals(1, app.saveFiles.size)
        assertEquals(listOf("windows"), app.saveFiles[0].platforms)
        assertTrue(app.saveFiles[0].recursive)
        assertEquals("MacHome", app.rootOverrides[0].useInstead)
        assertTrue(app.depotSelectionSummary().contains("444[-]os"))
    }

    @Test fun installDepotsFallBackTo32Bit() {
        val app = AppInfo(1, name = "x")
        app.depots += DepotInfo(2, osarch = "32", manifests = mapOf("public" to 1L))
        app.depots += DepotInfo(3, manifests = mapOf("public" to 1L))
        assertEquals(listOf(2, 3), app.installDepots().map { it.depotID })
    }

    @Test fun sharedDepotsInherit() {
        val owner = AppInfo(228980, name = "Redist")
        owner.depots += DepotInfo(449, manifests = mapOf("public" to 55L), publicSizeBytes = 10, oslist = "windows")
        val app = AppInfo.parse(440, APP_VDF.toByteArray())!!
        assertEquals(1, app.inheritDepots(mapOf(228980 to owner)))
        assertEquals(55L, app.depots.first { it.depotID == 449 }.publicManifestID)
        assertTrue(app.installDepots().any { it.depotID == 449 })
    }

    @Test fun textVdfQuotedBracesAndComments() {
        val v = Vdf.parseText("// c\n\"a\" { \"b\" \"{\" \"c\" \"x\\\\y\" unq val }")
        @Suppress("UNCHECKED_CAST") val a = v["a"] as Map<String, Any>
        assertEquals("{", a["b"]); assertEquals("x\\y", a["c"]); assertEquals("val", a["unq"])
    }

    @Test fun binaryPackageIDs() {
        val out = ByteArrayOutputStream()
        fun str(s: String) { out.write(s.toByteArray()); out.write(0) }
        out.write(0); str("123")
        out.write(1); str("billingtype"); str("1")
        out.write(0); str("appids")
        out.write(2); str("0"); out.write(byteArrayOf(0x40, 0x01, 0, 0))
        out.write(2); str("1"); out.write(byteArrayOf(0x41, 0x01, 0, 0))
        out.write(8)
        out.write(0); str("depotids")
        out.write(2); str("0"); out.write(byteArrayOf(0x42, 0x01, 0, 0))
        out.write(8); out.write(8); out.write(8)
        assertEquals(listOf(320, 321), Vdf.packageIDs("appids", out.toByteArray()))
        assertEquals(listOf(322), Vdf.packageIDs("depotids", out.toByteArray()))
        assertEquals(emptyList<Int>(), Vdf.packageIDs("missing", out.toByteArray()))
    }

    @Test fun keyValuesBehaveLikeSteams() {
        val kv = KeyValues.parse("\uFEFF\"AppState\" { \"AppID\" \"1\" \"appid\" \"2\" \"Name\" \"a\\\"b\" \"x\" { } }".toByteArray())
        assertEquals("2", kv["appstate"]!!["APPID"]!!.string)
        assertEquals("a\"b", kv["AppState"]!!["name"]!!.string)
        assertNotNull(kv["AppState"]!!["x"])
        for (bad in listOf("}", "\"a\" {", "\"a\"", "\"a\" \"b")) {
            try { KeyValues.parse(bad.toByteArray()); fail("accepted $bad") } catch (_: SteamException) {}
        }
        val deep = "\"a\" {".repeat(40) + "}".repeat(40)
        try { KeyValues.parse(deep.toByteArray()); fail("accepted deep") } catch (_: SteamException) {}
    }

    @Test fun appManifestRecordRoundTrip() {
        val dir = Files.createTempDirectory("acf").toFile()
        try {
            AppManifestWriter.write(dir, 440, "Team \"F\" 2", "Team Fortress 2", 99, 76561198000000000L, 1234,
                listOf(InstalledDepot(441, -1L, 1000)), listOf(449 to 228980), listOf("bin\\game.exe"))
            val text = File(dir, "appmanifest_440.acf").readText()
            assertTrue(text.startsWith("\"AppState\"\n{\n\t\"appid\"\t\t\"440\"\n\t\"universe\"\t\t\"1\""))
            assertTrue(text.contains("\"manifest\"\t\t\"18446744073709551615\""))
            assertTrue(text.contains("\"DownloadType\"\t\t\"1\""))
            assertEquals(99, SteamInstallFiles.buildID(440, dir))
            assertEquals(1234L, SteamInstallFiles.sizeOnDisk(440, dir))
            val game = InstalledGame.fromRecord(File(dir, "appmanifest_440.acf").readBytes(), SteamInstallPaths.LIBRARY_RELATIVE)!!
            assertEquals("Team \"F\" 2", game.name)
            assertTrue(game.installed); assertTrue(game.customExecutables)
            assertEquals("C:\\Program Files (x86)\\Steam\\steamapps\\common\\Team Fortress 2", game.windowsInstallPath)
            AppManifestWriter.mergeOwner(dir, 228980, "", 5, "Team Fortress 2", 1, listOf(InstalledDepot(449, 7, 10)))
            AppManifestWriter.mergeOwner(dir, 228980, "Redist", 5, "Team Fortress 2", 1, listOf(InstalledDepot(450, 8, 20)))
            val owner = SteamInstallFiles.record(228980, dir)!!
            assertEquals(setOf("449", "450"), owner["InstalledDepots"]!!.fields.keys)
            assertEquals("30", owner["SizeOnDisk"]!!.string)
        } finally { dir.deleteRecursively() }
    }

    @Test fun safePathsAndScan() {
        val folded = HashMap<String, String>()
        assertEquals("Bin/a.dll", SteamInstallFiles.safeRelativePath("Bin\\a.dll", folded))
        assertEquals("Bin/b.dll", SteamInstallFiles.safeRelativePath("bin/b.dll", folded))
        assertNull(SteamInstallFiles.safeRelativePath("../x", folded))
        assertNull(SteamInstallFiles.safeRelativePath("c:/x", folded))
        assertEquals("x", SteamInstallFiles.safeFolderName("a/b\\x"))
        assertEquals("app", SteamInstallFiles.safeFolderName(".."))
        assertEquals("Games/Lib/steamapps", InstalledGame.libraryRelative("C:\\Games\\Lib"))
        assertNull(InstalledGame.libraryRelative("D:\\Games"))

        val drive = Files.createTempDirectory("drive").toFile()
        try {
            val apps = SteamInstallPaths.steamApps(drive).apply { mkdirs() }
            AppManifestWriter.write(apps, 10, "Ten", "Ten", 1, 1, 1, listOf(InstalledDepot(11)))
            AppManifestWriter.write(apps, 20, "Twenty", "Twenty", 1, 1, 1, listOf(InstalledDepot(21)), listOf(30 to 30))
            // App 30 only lends depot 30 to app 20: hidden.
            AppManifestWriter.write(apps, 30, "Shared", "Twenty", 1, 1, 1, listOf(InstalledDepot(30)))
            val other = File(drive, "Games/Lib/steamapps").apply { mkdirs() }
            AppManifestWriter.write(other, 40, "Forty", "Forty", 1, 1, 1, listOf(InstalledDepot(41)))
            File(apps, "libraryfolders.vdf").writeText("\"libraryfolders\" { \"0\" { \"path\" \"C:\\\\Games\\\\Lib\" } }")
            assertEquals(listOf(40, 10, 20), InstalledGame.scan(drive).map { it.appID })
            SteamInstallFiles.delete(20, "Twenty", apps)
            assertFalse(File(apps, "appmanifest_30.acf").exists())
            assertFalse(File(apps, "appmanifest_20.acf").exists())
        } finally { drive.deleteRecursively() }
    }

    @Test fun playtimeParse() {
        val body = proto {
            uint32(1, 2)
            message(2) { uint32(1, 440); int32(4, 125); uint32(11, 1700000000) }
            message(2) { uint32(1, 570); int32(4, 0) }
        }
        val p = Playtime.parse(body)
        assertEquals(Playtime(125, 1700000000L), p[440])
        assertEquals(0L, p[570]!!.lastPlayed)
    }
}

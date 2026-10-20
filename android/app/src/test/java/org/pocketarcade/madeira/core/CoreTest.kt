// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors

package org.pocketarcade.madeira.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.pocketarcade.madeira.steam.LaunchOption
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    /** A minimal PE32+ with one import descriptor naming `dll`. */
    private fun pe(machine: Int, dll: String?): ByteArray {
        val b = ByteArray(1024)
        fun w16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        fun w32(o: Int, v: Int) { w16(o, v and 0xFFFF); w16(o + 2, v ushr 16) }
        b[0] = 'M'.code.toByte(); b[1] = 'Z'.code.toByte(); w32(60, 64)
        w32(64, 0x4550); w16(68, machine); w16(70, 1); w16(84, 240)
        w16(88, 0x20b)
        if (dll != null) w32(64 + 144, 0x1000)
        val sec = 64 + 24 + 240
        w32(sec + 12, 0x1000); w32(sec + 16, 0x200); w32(sec + 20, 0x200)
        if (dll != null) {
            w32(0x200 + 12, 0x1100)
            val name = dll.toByteArray(); System.arraycopy(name, 0, b, 0x300, name.size)
        }
        return b
    }

    @Test fun peInspection() {
        val dir = Files.createTempDirectory("pe").toFile()
        try {
            val game = File(dir, "Game.exe").apply { writeBytes(pe(0x8664, "D3D11.dll")) }
            val info = PeInspector.inspect(game)!!
            assertEquals(64, info.bits); assertEquals(listOf("d3d11.dll"), info.imports)
            assertEquals(setOf("D3D11"), PeInspector.graphicsAPIs(game))
            assertEquals("D3D11", PeInspector.badge(setOf("D3D11")))
            assertNull(PeInspector.badge(setOf("D3D11", "D3D9")))
            assertEquals("dxvk", PeInspector.recommendedGraphics(setOf("D3D9")))
            assertEquals("vkd3d", PeInspector.recommendedGraphics(setOf("D3D12", "D3D11")))
            // A launcher importing nothing: its sibling's import counts, and a run-time load by name.
            val launcher = File(dir, "Launcher.exe").apply { writeBytes(pe(0x14c, null) + "vulkan-1.dll\u0000".toByteArray()) }
            assertEquals(32, PeInspector.inspect(launcher)!!.bits)
            assertEquals(setOf("D3D11", "Vulkan"), PeInspector.graphicsAPIs(launcher))
            assertNull(PeInspector.inspect(File(dir, "missing.exe")))
        } finally { dir.deleteRecursively() }
    }

    @Test fun directStartChoice() {
        val root = Files.createTempDirectory("game").toFile()
        try {
            File(root, "Bin64").mkdirs(); File(root, "Bin64/Game.EXE").writeText("x"); File(root, "launcher.exe").writeText("x")
            val options = listOf(
                LaunchOption("launcher.exe", index = 0, type = "none"),
                LaunchOption("bin64\\game.exe", index = 1, type = "default", osarch = "64", arguments = " -dx11 "),
                LaunchOption("server.exe", index = 2, type = "server"),
                LaunchOption("beta.exe", index = 3, type = "default", betaKey = "beta"),
            )
            val c = DirectStart.choose(options, root)!!
            assertEquals("Bin64/Game.EXE", c.program); assertEquals("-dx11", c.arguments); assertEquals(1, c.launchIndex)
            assertNull(DirectStart.relativePath("../x.exe"))
            assertNull(DirectStart.relativePath("C:/x.exe"))
            assertEquals(listOf("Bin64/Game.EXE", "launcher.exe"), DirectStart.programs(root))
            assertEquals("launcher.exe", DirectStart.program("LAUNCHER.exe", null, root))
        } finally { root.deleteRecursively() }
    }

    @Test fun runtimeZipAndRegistry() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("bin\\steamclient64.dll")); z.write("client".repeat(1000).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("steam.exe")); z.write("exe".toByteArray()); z.closeEntry()
        }
        val files = LinkedHashMap<String, ByteArray>()
        SteamRuntimeFiles.unpack(out.toByteArray()) { n, d -> files[n] = d }
        assertEquals(listOf("bin/steamclient64.dll", "steam.exe"), files.keys.toList())
        assertArrayEquals("exe".toByteArray(), files["steam.exe"])
        val dup = ByteArrayOutputStream()
        ZipOutputStream(dup).use { z -> for (n in listOf("../evil.dll")) { z.putNextEntry(ZipEntry(n)); z.write(1); z.closeEntry() } }
        try { SteamRuntimeFiles.unpack(dup.toByteArray()) { _, _ -> }; fail("accepted traversal") } catch (_: DockException) {}

        val user = SteamRuntimeFiles.registry("WINE REGISTRY Version 2\n;; All keys relative to \\\\User\\\\S-1\n\n", machine = false)
        assertTrue(user.contains("[Software\\\\Valve\\\\Steam]\n\"SteamPath\"=\"C:\\\\Program Files (x86)\\\\Steam\""))
        assertTrue(user.contains("\"SteamClientDll64\"=\"C:\\\\Program Files (x86)\\\\Steam\\\\steamclient64.dll\""))
        assertEquals(user, SteamRuntimeFiles.registry(user, machine = false))
        try { SteamRuntimeFiles.registry("WINE REGISTRY Version 2\n[Software\\\\Valve\\\\Steam]\n\"SteamPath\"=\"D:\\\\x\"\n", false); fail("conflict accepted") } catch (_: DockException) {}
    }

    @Test fun handoffAndReport() {
        val e = DockHandoff.envelope("gabe", "eyJ.abc-_", 76561198000000001L, 440)
        assertEquals("MDOCK001", String(e, 0, 8))
        assertEquals(8 + 8 + 4 + 2 + 2 + 4 + 9, e.size)
        assertEquals(440, (e[16].toInt() and 0xFF) or ((e[17].toInt() and 0xFF) shl 8))
        try { DockHandoff.envelope("gabe", "bad token", 76561198000000001L, 440); fail("accepted") } catch (_: DockException) {}
        try { DockHandoff.envelope("gabe", "t", 5, 440); fail("accepted") } catch (_: DockException) {}
        val report = DockHandoff.parseReport("[steam-host] ml2000 probe-result=45\n[steam-host] ml2000 launch-client-error=26\n[steam-host] bogus x=1\n[steam-host] ml2000 partial=".toByteArray())
        assertEquals(45, report.result)
        assertEquals("Steam says this game is not available in your region.", report.failure)
        assertNull(DockHandoff.parseReport("[steam-host] ml2000 probe-result=0\n".toByteArray()).failure)
    }

    @Test fun installScripts() {
        val script = """
"InstallScript"
{
	"Run Process"
	{
		"VCRedist"
		{
			"HasRunKey"		"HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2022"
			"process 1"		"%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
			"command 1"		"/install /quiet /norestart"
			"MinimumHasRunValue"	"1"
		}
		"DirectX"
		{
			"process 1"		"%INSTALLDIR%/_CommonRedist/DirectX/DXSETUP.exe"
			"command 1"		"/silent & evil"
		}
	}
	"Run Process"
	{
		"Extra"
		{
			"process 1"		"%INSTALLDIR%\\extra.msi"
		}
	}
}
""".toByteArray()
        val p = InstallScripts.processes(script, "C:\\Program Files (x86)\\Steam\\steamapps\\common\\Game", 440)
        assertEquals(listOf("vcredist", "extra"), p.map { it.run.name })
        assertEquals("C:\\Program Files (x86)\\Steam\\steamapps\\common\\Game\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe", p[0].executable)
        assertEquals("/install /quiet /norestart", p[0].arguments)
        assertTrue(p[0].run.machine)
        assertEquals("Software\\Valve\\Steam\\Apps\\440", p[1].run.key)
        val reg = "WINE REGISTRY Version 2\n\n[Software\\\\Valve\\\\Steam] 1\n\"x\"=\"y\"\n"
        val (marked, changed) = InstallScripts.mark(p.map { it.run }, reg, 99)
        assertEquals(4, changed)
        assertTrue(InstallScripts.marked(p[0].run, marked))
        assertFalse(InstallScripts.marked(p[0].run, reg))
        assertEquals(0, InstallScripts.mark(p.map { it.run }, marked, 100).second)
        val batch = InstallScripts.batch(p, "C:\\r.txt", "C:\\windows\\system32\\dockhost.exe")
        assertTrue(batch.contains("call \"C:\\Program Files (x86)"))
        assertTrue(batch.contains("msiexec.exe /i \""))
        val r = InstallScripts.results("begin 2\r\nservices started\r\nstart 1 vcredist\r\nexit 1 3010\r\nstart 2 extra\r\n")
        assertEquals(2, r.total); assertEquals(3010, r.exits[1]); assertNull(r.exits[2]); assertFalse(r.ended)
        assertEquals("started", r.services)
        assertTrue(InstallScripts.Results.succeeded(3010))
    }
}

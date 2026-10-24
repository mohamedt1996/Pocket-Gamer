// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LinuxProtonTest {
    private fun elf(file: File) {
        file.parentFile!!.mkdirs()
        val header = ByteArray(20)
        header[0] = 0x7f; header[1] = 'E'.code.toByte(); header[2] = 'L'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = 2; header[5] = 1; header[18] = 62
        file.writeBytes(header); file.setExecutable(true)
    }
    @Test fun protonPlanUsesSeparatePrefixAndOfficialEacRuntime() {
        val base = Files.createTempDirectory("linux-proton-plan").toFile()
        try {
            val root = File(base, "runtime").apply { mkdirs() }
            val box64 = File(base, "box64").apply { writeText("runner"); setExecutable(true) }
            elf(File(root, "rootfs/usr/bin/python3"))
            File(root, "rootfs/usr/local/lib/python3.11").mkdirs()
            elf(File(root, "proton/files/bin/wine"))
            File(root, "rootfs/lib/x86_64-linux-gnu/libc.so.6").apply { parentFile!!.mkdirs(); writeText("glibc") }
            File(root, "rootfs/usr/lib/i386-linux-gnu/libc.so.6").apply { parentFile!!.mkdirs(); writeText("i386 glibc") }
            File(root, "rootfs/lib/ld-linux.so.2").apply { parentFile!!.mkdirs(); writeText("i386 loader") }
            for (arch in listOf("x86_64", "i686"))
                File(root, "rootfs/usr/share/vulkan/icd.d/lvp_icd.$arch.json").apply {
                    parentFile!!.mkdirs(); writeText("{}")
                }
            File(root, "proton/proton").writeText("proton")
            val gameDir = File(base, "game").apply { mkdirs() }
            val exe = File(gameDir, "content/RocketLeague.exe").apply { parentFile!!.mkdirs(); writeText("PE") }
            val eac = File(root, "eac_runtime/v2/lib/x86_64-linux-gnu/easyanticheat.so")
            assertThrows(IllegalArgumentException::class.java) {
                LinuxProton.prepare(root, box64, gameDir, exe, "Rocket League", emptyList(), ":0", true)
            }
            eac.apply { parentFile!!.mkdirs(); writeText("official runtime") }
            val plan = LinuxProton.prepare(root, box64, gameDir, exe, "Rocket League", listOf("-EpicPortal"), ":0", true)
            assertEquals(listOf(box64.absolutePath, File(root, "rootfs/usr/bin/python3").absolutePath,
                File(root, "proton/proton").absolutePath, "run", exe.absolutePath, "-EpicPortal"), plan.command)
            assertEquals(File(root, "eac_runtime").absolutePath, plan.environment["PROTON_EAC_RUNTIME"])
            assertTrue(plan.environment["BOX64_LD_LIBRARY_PATH"]!!.contains("i386-linux-gnu"))
            assertTrue(plan.environment["BOX64_LD_LIBRARY_PATH"]!!.contains("rootfs/usr/local/lib"))
            assertEquals(File(root, "rootfs/usr/local").absolutePath, plan.environment["PYTHONHOME"])
            assertTrue(plan.environment["VK_ICD_FILENAMES"]!!.endsWith("lvp_icd.x86_64.json"))
            assertTrue(plan.environment["STEAM_COMPAT_DATA_PATH"]!!.contains("sessions/proton/compatdata"))
            assertFalse(plan.environment.containsKey("WINEPREFIX"))
            assertEquals(plan, LinuxProton.Plan.fromJson(plan.toJson()))
            val steamState = File(base, "steam-state")
            val steamPlan = LinuxProton.prepare(root, box64, gameDir, exe, "Steam game", emptyList(), ":0", false, steamState)
            assertTrue(steamPlan.environment["STEAM_COMPAT_DATA_PATH"]!!.startsWith(steamState.absolutePath))
            assertTrue(steamPlan.logFile.startsWith(steamState.absolutePath))
            val rootExe = File(gameDir, "Game.exe").apply { writeText("PE") }
            val rootPlan = LinuxProton.prepare(root, box64, gameDir, rootExe, "Steam root game", emptyList(), ":0", false, steamState)
            assertEquals(gameDir.canonicalPath, rootPlan.workingDir)
            val desktop = LinuxProton.desktop(root, box64, File(base, "desktop-state"), ":0")
            assertEquals("explorer.exe", desktop.command.last())
            assertTrue(desktop.environment["STEAM_COMPAT_DATA_PATH"]!!.contains("desktop-state"))
            assertEquals("No Proton session has started yet", LinuxProton.statusText(File(base, "desktop-state")))
            val status = File(base, "desktop-state/sessions/proton/status.json")
            status.writeText("""{"state":"failed","message":"Cannot find X11 display"}""")
            assertTrue(LinuxProton.statusText(File(base, "desktop-state")).contains("Cannot find X11 display"))
        } finally { base.deleteRecursively() }
    }
}

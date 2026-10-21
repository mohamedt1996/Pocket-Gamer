// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors

package org.pocketarcade.madeira.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketarcade.madeira.platform.FileTrees
import java.io.File
import java.nio.file.Files

class SessionTest {
    @Test fun splitsQuotedProgramAndArguments() {
        assertEquals(listOf("Z:\\Games\\My Game\\game.exe", "-windowed", "-w", "1280"),
            CommandLine.split("\"Z:\\Games\\My Game\\game.exe\" -windowed -w 1280"))
    }

    @Test fun keepsInstallerPathsWhole() {
        assertEquals(listOf("msiexec", "/i", "Z:\\data\\my setup.msi"), CommandLine.split("msiexec /i \"Z:\\data\\my setup.msi\""))
    }

    @Test fun followsWindowsBackslashRules() {
        assertEquals(listOf("a\\\\b", "c\"d", "e\\"), CommandLine.split("a\\\\b c\\\"d \"e\\\\\""))
        assertEquals(listOf("say \"hi\""), CommandLine.split("\"say \"\"hi\"\"\""))
        assertEquals(listOf(""), CommandLine.split("\"\""))
        assertEquals(emptyList<String>(), CommandLine.split("   "))
        assertEquals(listOf("explorer.exe"), CommandLine.split("explorer.exe"))
    }

    @Test fun newPrefixIsSeededAs64Bit() {
        val prefix = Files.createTempDirectory("prefix").toFile()
        WineLaunch.seedRegistry(prefix)
        val system = File(prefix, "system.reg").readText()
        assertTrue(system.startsWith("WINE REGISTRY Version 2\n"))
        assertTrue(system.lines().contains("#arch=win64"))
        assertTrue(system.contains("@=\"libarm64ecfex.dll\""))
        val user = File(prefix, "user.reg").readText()
        assertTrue(user.contains("[Software\\\\Wine\\\\Drivers]\n\"Audio\"=\"directaudio\"\n\"Graphics\"=\"android\""))
        // Seeding again changes nothing.
        WineLaunch.seedRegistry(prefix)
        assertEquals(system, File(prefix, "system.reg").readText())
        assertEquals(user, File(prefix, "user.reg").readText())
        FileTrees.delete(prefix)
    }

    @Test fun repairsPrefixSeededWithoutArch() {
        val prefix = Files.createTempDirectory("prefix").toFile()
        File(prefix, "system.reg").writeText("WINE REGISTRY Version 2\n\n[Software\\\\Microsoft\\\\Wow64\\\\amd64]\n@=\"libarm64ecfex.dll\"\n")
        File(prefix, "user.reg").writeText("WINE REGISTRY Version 2\n;; All keys relative to \\\\User\\\\S-1-5-21-0-0-0-1000\n\n#arch=win64\n")
        WineLaunch.seedRegistry(prefix)
        val system = File(prefix, "system.reg").readText()
        assertTrue(system.startsWith("WINE REGISTRY Version 2\n\n#arch=win64\n"))
        assertTrue(system.contains("@=\"libarm64ecfex.dll\""))
        assertTrue(File(prefix, "user.reg").readText().contains("\"Graphics\"=\"android\""))
        FileTrees.delete(prefix)
    }

    @Test fun deletingAGameFolderDoesNotFollowItsLinks() {
        val outside = Files.createTempDirectory("outside").toFile()
        val keep = File(outside, "keep.txt").apply { writeText("keep") }
        val game = Files.createTempDirectory("game").toFile()
        val dos = File(game, "prefix/dosdevices").apply { mkdirs() }
        Files.createSymbolicLink(File(dos, "z:").toPath(), outside.toPath())
        File(game, "prefix/drive_c/windows").mkdirs()
        assertTrue(FileTrees.delete(game))
        assertFalse(game.exists())
        assertTrue(keep.isFile)
        FileTrees.delete(outside)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SteamEacInstallerTest {
    @Test fun incompleteSteamDownloadKeepsExistingRuntime() {
        val root = Files.createTempDirectory("eac-steam-test").toFile()
        try {
            val current = root.resolve("eac_runtime").apply { mkdirs() }
            current.resolve("old.txt").writeText("installed")
            val pending = root.resolve("pending").apply { mkdirs() }
            pending.resolve("readme.txt").writeText("incomplete")
            val result = runCatching { SteamEacInstaller.activate(pending, root) }
            assertTrue(result.isFailure)
            assertEquals("installed", current.resolve("old.txt").readText())
            assertFalse(current.resolve("readme.txt").exists())

            pending.resolve("v2/lib/x86_64-linux-gnu").mkdirs()
            pending.resolve("v2/lib/x86_64-linux-gnu/easyanticheat.so").writeText("bridge")
            SteamEacInstaller.activate(pending, root)
            assertTrue(current.resolve("v2/lib/x86_64-linux-gnu/easyanticheat.so").isFile)
            assertFalse(current.resolve("old.txt").exists())
        } finally { root.deleteRecursively() }
    }
}

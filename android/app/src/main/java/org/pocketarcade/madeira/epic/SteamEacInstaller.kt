// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pocketarcade.madeira.steam.DepotDownloader
import org.pocketarcade.madeira.steam.DownloadProgress
import org.pocketarcade.madeira.steam.LibraryFetcher
import java.io.File
import java.nio.file.Files

/** Downloads Valve's separate Proton EAC tool with the signed-in Steam account. */
class SteamEacInstaller(
    private val context: Context,
    private val fetcher: LibraryFetcher,
    private val downloader: DepotDownloader,
) {
    companion object {
        const val APP_ID = 1826330

        /** Swap only a complete official tool into the active Proton runtime. */
        internal fun activate(folder: File, root: File) {
            require(File(folder, "v2/lib/x86_64-linux-gnu/easyanticheat.so").isFile ||
                File(folder, "v2/lib/x86_64-linux-gnu/easyanticheat_x64.so").isFile) {
                "Steam's Linux tool is missing its x86-64 EAC bridge; existing runtime was kept"
            }
            val dest = File(root, LinuxProtonInstaller.Component.EAC.folder)
            val backup = File(root, ".eac_runtime-steam-old")
            require(!backup.exists() || backup.deleteRecursively()) { "Could not clear the old EAC backup" }
            if (dest.exists()) Files.move(dest.toPath(), backup.toPath())
            try { Files.move(folder.toPath(), dest.toPath()) }
            catch (e: Exception) {
                if (backup.exists()) Files.move(backup.toPath(), dest.toPath())
                throw e
            }
            backup.deleteRecursively()
        }
    }

    suspend fun install(report: (DownloadProgress) -> Unit) {
        val app = fetcher.installInfo(APP_ID, "linux")
            ?: error("Steam did not provide Proton EasyAntiCheat Runtime metadata for this account")
        require(app.installDepots("linux").isNotEmpty()) {
            "Steam did not provide a Linux depot for Proton EasyAntiCheat Runtime"
        }
        val root = LinuxProton.root(context)
        val steamApps = File(root, ".steam-eac-download/steamapps")
        val folder = downloader.install(app, steamApps, os = "linux", report = report)
        withContext(Dispatchers.IO) { activate(folder, root) }
    }

}

// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam/Install/AppManifestWriter.swift and
// SteamInstall.swift: Steam's on-disk install record and safe install paths.

package org.pocketarcade.madeira.steam

import java.io.File

/** Where downloads go: Madeira Dock's own Steam library folder in the prefix's drive_c. */
object SteamInstallPaths {
    const val LIBRARY_RELATIVE = "Program Files (x86)/Steam/steamapps"
    fun steamApps(drive: File) = File(drive, LIBRARY_RELATIVE)
    fun common(drive: File) = File(steamApps(drive), "common")
    fun isManaged(library: String) = library.equals(LIBRARY_RELATIVE, ignoreCase = true)
}

data class InstalledDepot(val depotID: Int, val manifestGID: Long? = null, val size: Long? = null, val dlcAppID: Int? = null)

/** Writes `appmanifest_<appid>.acf` exactly as Steam's client writes it (Steam silently rejects other forms). */
object AppManifestWriter {
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun u64(v: Long) = java.lang.Long.toUnsignedString(v)

    fun render(
        appID: Int, name: String, installDir: String, buildID: Int, steamID: Long, sizeOnDisk: Long = 0,
        installedDepots: List<InstalledDepot>? = null, sharedDepots: List<Pair<Int, Int>> = emptyList(),
        customExecutables: List<String> = emptyList(), timestamp: Long = System.currentTimeMillis() / 1000,
    ): String {
        val l = ArrayList<String>()
        l += "\"AppState\""
        l += "{"
        l += "\t\"appid\"\t\t\"${appID.toLong() and 0xFFFFFFFFL}\""
        l += "\t\"universe\"\t\t\"1\""
        l += "\t\"name\"\t\t\"${esc(name)}\""
        l += "\t\"StateFlags\"\t\t\"4\""
        l += "\t\"installdir\"\t\t\"${esc(installDir)}\""
        l += "\t\"LastUpdated\"\t\t\"$timestamp\""
        l += "\t\"LastPlayed\"\t\t\"0\""
        l += "\t\"SizeOnDisk\"\t\t\"${u64(sizeOnDisk)}\""
        l += "\t\"StagingSize\"\t\t\"0\""
        l += "\t\"buildid\"\t\t\"${buildID.toLong() and 0xFFFFFFFFL}\""
        l += "\t\"LastOwner\"\t\t\"${u64(steamID)}\""
        if (installedDepots != null) l += "\t\"DownloadType\"\t\t\"1\""
        l += "\t\"UpdateResult\"\t\t\"0\""
        l += "\t\"BytesToDownload\"\t\t\"0\""
        l += "\t\"BytesDownloaded\"\t\t\"0\""
        l += "\t\"BytesToStage\"\t\t\"0\""
        l += "\t\"BytesStaged\"\t\t\"0\""
        l += "\t\"TargetBuildID\"\t\t\"${buildID.toLong() and 0xFFFFFFFFL}\""
        l += "\t\"AutoUpdateBehavior\"\t\t\"0\""
        l += "\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\""
        l += "\t\"ScheduledAutoUpdate\"\t\t\"0\""
        if (!installedDepots.isNullOrEmpty()) {
            l += "\t\"InstalledDepots\""
            l += "\t{"
            for (d in installedDepots) {
                l += "\t\t\"${d.depotID}\""
                l += "\t\t{"
                d.manifestGID?.let { l += "\t\t\t\"manifest\"\t\t\"${u64(it)}\"" }
                d.size?.let { l += "\t\t\t\"size\"\t\t\"$it\"" }
                d.dlcAppID?.let { l += "\t\t\t\"dlcappid\"\t\t\"$it\"" }
                l += "\t\t}"
            }
            l += "\t}"
        }
        if (sharedDepots.isNotEmpty()) {
            l += "\t\"SharedDepots\""
            l += "\t{"
            for ((depot, owner) in sharedDepots.sortedBy { it.first }) l += "\t\t\"$depot\"\t\t\"$owner\""
            l += "\t}"
        }
        if (customExecutables.isNotEmpty()) {
            l += "\t\"CheckGuid\""
            l += "\t{"
            customExecutables.take(256).forEachIndexed { i, p -> l += "\t\t\"$i\"\t\t\"${esc(p)}\"" }
            l += "\t}"
        }
        l += "\t\"UserConfig\""; l += "\t{"; l += "\t\t\"language\"\t\t\"english\""; l += "\t}"
        l += "\t\"MountedConfig\""; l += "\t{"; l += "\t\t\"language\"\t\t\"english\""; l += "\t}"
        l += "}"
        return l.joinToString("\n")
    }

    fun write(steamApps: File, appID: Int, name: String, installDir: String, buildID: Int, steamID: Long, sizeOnDisk: Long = 0,
              installedDepots: List<InstalledDepot>? = null, sharedDepots: List<Pair<Int, Int>> = emptyList(),
              customExecutables: List<String> = emptyList()) {
        val text = render(appID, name, installDir, buildID, steamID, sizeOnDisk, installedDepots, sharedDepots, customExecutables)
        atomicWrite(File(steamApps, "appmanifest_$appID.acf"), text.toByteArray(Charsets.UTF_8))
    }

    /** Records depots installed for another app's shared use under their owner app, keeping its other depots. */
    fun mergeOwner(steamApps: File, ownerAppID: Int, ownerName: String, ownerBuildID: Int, installDir: String, steamID: Long, depots: List<InstalledDepot>) {
        val merged = sortedMapOf<Int, InstalledDepot>()
        SteamInstallFiles.record(ownerAppID, steamApps)?.let { state ->
            for ((key, value) in state["InstalledDepots"]?.fields.orEmpty()) {
                val id = key.toIntOrNull()?.takeIf { it > 0 } ?: continue
                merged[id] = InstalledDepot(id, value["manifest"]?.string?.toULongOrNull()?.toLong(), value["size"]?.string?.toLongOrNull(),
                    value["dlcappid"]?.string?.toIntOrNull())
            }
        }
        for (d in depots) merged[d.depotID] = d
        val all = merged.values.toList()
        val size = all.sumOf { maxOf(0L, it.size ?: 0L) }
        write(steamApps, ownerAppID, ownerName.ifEmpty { "App $ownerAppID" }, installDir, ownerBuildID, steamID, size, all)
    }
}

internal fun atomicWrite(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) throw java.io.IOException("Cannot write ${file.name}")
    }
}

object SteamInstallFiles {
    /** The `AppState` of an app's record when it is readable, bounded and names this app. */
    fun record(appID: Int, steamApps: File): KeyValues? {
        val f = File(steamApps, "appmanifest_$appID.acf")
        if (!f.isFile || f.length() > 1 shl 20) return null
        return try {
            val state = KeyValues.parse(f.readBytes())["AppState"] ?: return null
            if (state["appid"]?.string == appID.toString()) state else null
        } catch (_: Exception) { null }
    }

    fun buildID(appID: Int, steamApps: File): Int? = record(appID, steamApps)?.get("buildid")?.string?.toLongOrNull()?.toInt()
    fun sizeOnDisk(appID: Int, steamApps: File): Long? = record(appID, steamApps)?.get("SizeOnDisk")?.string?.toLongOrNull()?.takeIf { it > 0 }

    /** Validates a manifest path and folds directory spelling to the first one seen (Windows semantics). */
    fun safeRelativePath(name: String, folded: MutableMap<String, String>): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 64 || name.toByteArray().size >= 1024) return null
        if (parts.any { it == "." || it == ".." || it.contains(':') || it.any { c -> c.code < 0x20 } }) return null
        var built = ArrayList<String>()
        for ((i, part) in parts.withIndex()) {
            if (i == parts.size - 1) { built += part; break }
            val key = (built + part).joinToString("/").lowercase()
            val existing = folded[key]
            if (existing != null) built = ArrayList(existing.split('/'))
            else { built += part; folded[key] = built.joinToString("/") }
        }
        return built.joinToString("/")
    }

    /** Install folders come from app metadata; keep them to one safe component. */
    fun safeFolderName(name: String): String {
        val cleaned = name.replace('\\', '/').split('/').lastOrNull { it.isNotEmpty() } ?: ""
        if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." || cleaned.contains(':') || cleaned.any { it.code < 0x20 }) return "app"
        return cleaned
    }

    /** Removes an app's install folder, record, journal and the records of owners of its shared depots. */
    fun delete(appID: Int, folderName: String, steamApps: File) {
        val common = File(steamApps, "common").canonicalFile
        val folder = File(common, safeFolderName(folderName)).canonicalFile
        if (folder.parentFile == common) folder.deleteRecursively()
        record(appID, steamApps)?.let { state ->
            val shared = state["SharedDepots"]?.fields.orEmpty().entries.sortedBy { it.key }.take(64)
            for ((_, owner) in shared) {
                val ownerID = owner.string?.toIntOrNull()?.takeIf { it > 0 && it != appID } ?: continue
                val ownerState = record(ownerID, steamApps) ?: continue
                val dir = ownerState["installdir"]?.string ?: continue
                val mine = shared.filter { it.value.string == owner.string }.map { it.key }.toSet()
                val held = ownerState["InstalledDepots"]?.fields.orEmpty().keys
                if (!safeFolderName(dir).equals(safeFolderName(folderName), ignoreCase = true) && !mine.containsAll(held)) continue
                File(steamApps, "appmanifest_$ownerID.acf").delete()
            }
        }
        File(steamApps, "appmanifest_$appID.acf").delete()
        File(steamApps, "downloading/$appID").deleteRecursively()
    }
}

/** A game Steam's client lists as installed in the prefix (its `appmanifest_<appid>.acf`). */
data class InstalledGame(
    val appID: Int,
    val name: String,
    val installDir: String,
    /** Drive-relative steamapps folder. */
    val library: String,
    val installed: Boolean,
    /** The record lists per-user executables (CheckGuid). */
    val customExecutables: Boolean,
    val buildID: Int?,
    val sizeOnDisk: Long?,
) {
    val windowsInstallPath: String get() = "C:\\" + ("$library/common/$installDir").replace('/', '\\')

    companion object {
        fun validAppID(v: Long) = v > 0 && v < 0xFFFFFFFFL

        fun validFolderName(name: String): Boolean = name.isNotEmpty() && name.toByteArray().size <= 240 && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains(':') && !name.contains('"') && name.none { it.code < 32 } &&
            !name.endsWith(" ") && !name.endsWith(".")

        fun fromRecord(data: ByteArray, library: String): InstalledGame? {
            if (data.size > 1 shl 20) return null
            val state = try { KeyValues.parse(data)["AppState"] } catch (_: Exception) { null } ?: return null
            val appID = state["appid"]?.string?.toLongOrNull()?.takeIf(::validAppID)?.toInt() ?: return null
            val folder = state["installdir"]?.string?.takeIf(::validFolderName) ?: return null
            val flags = state["StateFlags"]?.string?.toLongOrNull() ?: 0
            val name = state["name"]?.string?.take(200).orEmpty()
            return InstalledGame(appID, name.ifEmpty { "App $appID" }, folder, library, flags and 4L != 0L,
                state["CheckGuid"]?.fields?.isNotEmpty() == true,
                state["buildid"]?.string?.toLongOrNull()?.toInt(), state["SizeOnDisk"]?.string?.toLongOrNull())
        }

        /** "C:\\Games\\SteamLibrary" -> "Games/SteamLibrary/steamapps" (C: only). */
        fun libraryRelative(windowsPath: String): String? {
            val p = windowsPath.replace('\\', '/')
            if (!p.lowercase().startsWith("c:/")) return null
            val parts = p.substring(3).split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.size > 16 || !parts.all(::validFolderName)) return null
            return (parts + "steamapps").joinToString("/")
        }

        /**
         * Installed games in the client's library and the other libraries on C: its
         * libraryfolders.vdf lists. Apps that only lend depots to another install are left out.
         */
        fun scan(drive: File): List<InstalledGame> {
            val clientLibrary = SteamInstallPaths.LIBRARY_RELATIVE
            val libraries = arrayListOf(clientLibrary)
            File(drive, "$clientLibrary/libraryfolders.vdf").takeIf { it.isFile && it.length() <= 1 shl 20 }?.let { f ->
                try {
                    val root = KeyValues.parse(f.readBytes())
                    for ((_, entry) in root["libraryfolders"]?.fields.orEmpty().entries.sortedBy { it.key }) {
                        val rel = entry["path"]?.string?.replace("\\\\", "\\")?.let(::libraryRelative) ?: continue
                        if (libraries.none { it.equals(rel, ignoreCase = true) }) libraries += rel
                    }
                } catch (_: Exception) {}
            }
            val result = ArrayList<InstalledGame>()
            val lent = HashMap<Int, MutableSet<String>>()
            val held = HashMap<Int, Set<String>>()
            val driveCanon = drive.canonicalPath + "/"
            for (library in libraries.take(16)) {
                val folder = File(drive, library)
                if (!folder.canonicalPath.startsWith(driveCanon)) continue
                val names = folder.list()?.sorted() ?: continue
                for (file in names.take(2000)) {
                    if (!file.startsWith("appmanifest_") || !file.endsWith(".acf")) continue
                    val data = try { File(folder, file).readBytes() } catch (_: Exception) { continue }
                    val state = try { KeyValues.parse(data)["AppState"] } catch (_: Exception) { null }
                    val appID = state?.get("appid")?.string?.toIntOrNull()
                    if (state != null && appID != null) {
                        for ((depot, owner) in state["SharedDepots"]?.fields.orEmpty().entries.take(64)) {
                            val ownerID = owner.string?.toLongOrNull()?.takeIf(::validAppID)?.toInt() ?: continue
                            if (ownerID != appID) lent.getOrPut(ownerID) { HashSet() } += depot
                        }
                    }
                    val game = fromRecord(data, library) ?: continue
                    if (result.any { it.appID == game.appID }) continue
                    held[game.appID] = state?.get("InstalledDepots")?.fields?.keys?.take(256)?.toSet().orEmpty()
                    result += game
                }
            }
            result.removeAll { g -> lent[g.appID]?.containsAll(held[g.appID].orEmpty()) == true }
            return result.sortedBy { it.name.lowercase() }
        }
    }
}

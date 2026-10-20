// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Kotlin port of Madeira's SwiftSteam/Library/SteamAppInfo.swift.

package org.pocketarcade.madeira.steam

enum class AppType(val pics: String) {
    GAME("Game"), DLC("DLC"), TOOL("Tool"), DEMO("Demo"), APPLICATION("Application"), MUSIC("Music"), UNKNOWN("");

    val isPlayable: Boolean get() = this == GAME || this == DEMO || this == APPLICATION

    companion object {
        /** PICS does not capitalise type names consistently, so they match without case. */
        fun of(raw: String): AppType = entries.firstOrNull { it != UNKNOWN && it.pics.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}

data class DepotInfo(
    val depotID: Int,
    var name: String = "",
    var maxSize: Long = 0,
    var oslist: String = "",
    var osarch: String = "",
    var dlcAppID: Int? = null,
    var manifests: Map<String, Long> = emptyMap(),
    var publicDownloadBytes: Long = 0,
    var publicSizeBytes: Long = 0,
    /** Redistributables (DirectX, VC++) living in Steam's common store, not the game folder. */
    var isSharedInstall: Boolean = false,
    var language: String = "",
    var lowViolence: Boolean = false,
    /** `depotfromapp`: the depot belongs to another app. */
    var fromApp: Int? = null,
) {
    fun supports(os: String): Boolean = oslist.isEmpty() || oslist.lowercase().contains(os.lowercase())
    val publicManifestID: Long? get() = manifests["public"]
}

/** One `ufs.savefiles` entry. */
data class SaveFile(val root: String, val path: String, val pattern: String, val recursive: Boolean, val platforms: List<String>)

/** One `ufs.rootoverrides` entry. */
data class RootOverride(val root: String, val os: String, val useInstead: String, val addPath: String)

data class SharedOwner(val name: String, val installDir: String, val buildID: Int)

/** One entry of an app's `config.launch`. Untrusted text: bounded here. */
data class LaunchOption(
    val executable: String,
    val index: Int? = null,
    val requiredDLC: String? = null,
    val arguments: String = "",
    val workingDir: String = "",
    val type: String = "",
    val oslist: String = "",
    val osarch: String = "",
    val betaKey: String = "",
) {
    companion object {
        fun parse(launch: Map<String, Any>): List<LaunchOption> {
            val keys = launch.keys.mapNotNull { k -> k.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.let { it.toInt() to k } }
                .sortedBy { it.first }
            val out = ArrayList<LaunchOption>()
            for ((index, key) in keys.take(32)) {
                @Suppress("UNCHECKED_CAST")
                val entry = launch[key] as? Map<String, Any> ?: continue
                val exe = entry["executable"] as? String ?: continue
                if (exe.isEmpty() || exe.toByteArray().size > 512) continue
                @Suppress("UNCHECKED_CAST")
                val config = entry["config"] as? Map<String, Any> ?: emptyMap()
                fun text(v: Any?, limit: Int = 256): String? {
                    if (v == null) return ""
                    val s = v as? String ?: return null
                    return if (s.toByteArray().size <= limit) s else null
                }
                out += LaunchOption(
                    executable = exe, index = index,
                    requiredDLC = text(config["ownsdlc"]) ?: continue,
                    arguments = text(entry["arguments"], 2048) ?: continue,
                    workingDir = text(entry["workingdir"], 512) ?: continue,
                    type = text(entry["type"]) ?: continue,
                    oslist = text(config["oslist"]) ?: continue,
                    osarch = text(config["osarch"]) ?: continue,
                    betaKey = text(config["betakey"]) ?: continue,
                )
            }
            return out
        }
    }
}

/** App metadata parsed from Steam's product info (PICS). */
data class AppInfo(
    val appID: Int,
    var name: String = "",
    var type: AppType = AppType.GAME,
    var installDir: String = "",
    var oslist: String = "",
    var depots: MutableList<DepotInfo> = ArrayList(),
    var buildID: Int = 0,
    var freeToDownload: Boolean = false,
    var sharedOwners: MutableMap<Int, SharedOwner> = HashMap(),
    var libraryCapsule: String? = null,
    var libraryHero: String? = null,
    var headerImage: String? = null,
    var parentID: Int? = null,
    var launches: List<LaunchOption> = emptyList(),
    var saveFiles: List<SaveFile> = emptyList(),
    var rootOverrides: List<RootOverride> = emptyList(),
) {
    val supportsWindows: Boolean get() = oslist.lowercase().contains("windows") || oslist.isEmpty()

    /** Copies content metadata of shared depots from their owner apps. Returns how many depots it completed. */
    fun inheritDepots(owners: Map<Int, AppInfo>): Int {
        var changed = 0
        for (i in depots.indices) {
            val local = depots[i]
            val owner = local.fromApp ?: continue
            if (local.publicManifestID != null) continue
            val source = owners[owner]?.depots?.firstOrNull { it.depotID == local.depotID } ?: continue
            if (source.publicManifestID == null) continue
            depots[i] = local.copy(
                manifests = source.manifests,
                publicDownloadBytes = source.publicDownloadBytes,
                publicSizeBytes = source.publicSizeBytes,
                oslist = local.oslist.ifEmpty { source.oslist },
                osarch = local.osarch.ifEmpty { source.osarch },
                language = local.language.ifEmpty { source.language },
                lowViolence = local.lowViolence || source.lowViolence,
            )
            changed++
        }
        return changed
    }

    /**
     * The depots a Windows install takes: matching OS, neutral or matching osarch, common or
     * English content, no low-violence alternates, DLC or shared redistributables, and only
     * depots with a public manifest. 64-bit falls back to 32-bit when only those exist.
     */
    fun installDepots(os: String = "windows", arch: String = "64", language: String = "english"): List<DepotInfo> {
        fun select(a: String) = depots.filter { d ->
            d.supports(os) && d.dlcAppID == null && !d.isSharedInstall && d.publicManifestID != null && !d.lowViolence &&
                (d.osarch.isEmpty() || d.osarch == a) && (d.language.isEmpty() || d.language.equals(language, ignoreCase = true))
        }.sortedBy { it.depotID.toLong() and 0xFFFFFFFFL }
        val preferred = select(arch)
        if (arch == "64" && preferred.none { it.osarch == "64" } && depots.any { it.osarch == "32" && it.supports(os) }) return select("32")
        return preferred
    }

    fun depotSelectionSummary(limit: Int = 24): String {
        val chosen = installDepots().map { it.depotID }.toSet()
        return depots.sortedBy { it.depotID }.take(limit).joinToString(",") { d ->
            val why = when {
                d.depotID in chosen -> "sel"
                !d.supports("windows") -> "os"
                d.dlcAppID != null -> "dlc"
                d.isSharedInstall -> "shared"
                d.publicManifestID == null -> "nomanifest"
                d.lowViolence -> "lowviolence"
                d.language.isNotEmpty() && !d.language.equals("english", true) -> "lang"
                else -> "arch"
            }
            "${d.depotID}[${d.osarch.ifEmpty { "-" }}]$why${d.fromApp?.let { "<$it" } ?: ""}"
        }
    }

    val installableOnWindows: Boolean get() = supportsWindows && type.isPlayable && installDepots().isNotEmpty()

    /** Approximate compressed download size; saturates instead of overflowing on malformed sizes. */
    fun downloadSize(os: String = "windows"): Long = installDepots(os).fold(0L) { total, d ->
        val bytes = if (d.publicDownloadBytes > 0) d.publicDownloadBytes else d.maxSize
        if (bytes <= 0) total else if (total > Long.MAX_VALUE - bytes) Long.MAX_VALUE else total + bytes
    }

    fun installSize(os: String = "windows"): Long = installDepots(os).fold(0L) { total, d ->
        val bytes = if (d.publicSizeBytes > 0) d.publicSizeBytes else d.maxSize
        if (bytes <= 0) total else if (total > Long.MAX_VALUE - bytes) Long.MAX_VALUE else total + bytes
    }

    companion object {
        fun parse(appID: Int, buffer: ByteArray): AppInfo? = parse(appID, Vdf.parseText(buffer))

        @Suppress("UNCHECKED_CAST")
        fun parse(appID: Int, vdf: Map<String, Any>): AppInfo? {
            val root = (vdf[appID.toString()] as? Map<String, Any>) ?: (vdf["appinfo"] as? Map<String, Any>) ?: vdf
            val info = AppInfo(appID)
            fun map(v: Any?) = v as? Map<String, Any>
            fun asset(node: Any?): String? {
                val langs = map(node) ?: return null
                val value = (langs["english"] as? String) ?: langs.keys.sorted().firstNotNullOfOrNull { langs[it] as? String }
                if (value.isNullOrEmpty() || value.toByteArray().size > 256 || value.contains("..") || value.startsWith("/")) return null
                return value
            }
            map(root["common"])?.let { common ->
                info.name = common["name"] as? String ?: ""
                info.type = AppType.of(common["type"] as? String ?: "")
                info.oslist = common["oslist"] as? String ?: ""
                info.freeToDownload = common["freetodownload"] == "1"
                map(common["library_assets_full"])?.let { assets ->
                    val capsule = map(assets["library_capsule"])
                    info.libraryCapsule = asset(capsule?.get("image2x")) ?: asset(capsule?.get("image"))
                    info.libraryHero = asset(map(assets["library_hero"])?.get("image"))
                }
                info.headerImage = asset(common["header_image"])
                (common["parent"] as? String)?.toLongOrNull()?.toInt()?.let { if (it != 0 && it != appID) info.parentID = it }
            }
            map(root["config"])?.let { config ->
                info.installDir = config["installdir"] as? String ?: ""
                map(config["launch"])?.let { info.launches = LaunchOption.parse(it) }
            }
            map(root["depots"])?.let { depots ->
                for ((key, value) in depots) {
                    val id = key.toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL }?.toInt() ?: continue
                    val depot = map(value) ?: continue
                    val di = DepotInfo(id)
                    di.name = depot["name"] as? String ?: ""
                    map(depot["config"])?.let { c ->
                        di.oslist = c["oslist"] as? String ?: ""
                        di.osarch = c["osarch"] as? String ?: ""
                        di.language = c["language"] as? String ?: ""
                        di.lowViolence = c["lowviolence"] == "1"
                    }
                    (depot["maxsize"] as? String)?.toLongOrNull()?.let { di.maxSize = it }
                    (depot["dlcappid"] as? String)?.toLongOrNull()?.let { di.dlcAppID = it.toInt() }
                    di.isSharedInstall = depot["sharedinstall"] == "1"
                    (depot["depotfromapp"] as? String)?.toLongOrNull()?.toInt()?.let { if (it != appID) di.fromApp = it }
                    map(depot["manifests"])?.let { manifests ->
                        val m = HashMap<String, Long>()
                        for ((branch, entry) in manifests) {
                            when (entry) {
                                is String -> entry.toULongOrNull()?.let { m[branch] = it.toLong() }
                                is Map<*, *> -> {
                                    (entry["gid"] as? String)?.toULongOrNull()?.let { m[branch] = it.toLong() }
                                    if (branch == "public") {
                                        (entry["download"] as? String)?.toLongOrNull()?.let { di.publicDownloadBytes = it }
                                        (entry["size"] as? String)?.toLongOrNull()?.let { di.publicSizeBytes = it }
                                    }
                                }
                            }
                        }
                        di.manifests = m
                    }
                    info.depots += di
                }
                map(map(depots["branches"])?.get("public"))?.let { pub ->
                    (pub["buildid"] as? String)?.toLongOrNull()?.let { info.buildID = it.toInt() }
                }
            }
            map(root["ufs"])?.let { ufs ->
                fun text(v: Any?): String = (v as? String)?.takeIf { it.toByteArray().size <= 512 } ?: ""
                fun entries(node: Any?): List<Map<String, Any>> {
                    val d = map(node) ?: return emptyList()
                    return d.keys.mapNotNull { k -> k.toIntOrNull()?.let { it to k } }.sortedBy { it.first }.take(64)
                        .mapNotNull { map(d[it.second]) }
                }
                info.saveFiles = entries(ufs["savefiles"]).map { e ->
                    val platforms = map(e["platforms"])?.values?.mapNotNull { (it as? String)?.lowercase() }.orEmpty().sorted()
                    SaveFile(text(e["root"]), text(e["path"]), text(e["pattern"]), e["recursive"] == "1", platforms)
                }
                info.rootOverrides = entries(ufs["rootoverrides"]).map { e ->
                    RootOverride(text(e["root"]), text(e["os"]), text(e["useinstead"]), text(e["addpath"]))
                }
            }
            return if (info.name.isEmpty()) null else info
        }
    }
}

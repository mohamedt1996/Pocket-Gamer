// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Madeira Dock on Android (MadeiraDock.swift, SteamRuntime.swift,
// DockInstallers.swift): downloads Valve's client components into the Steam
// prefix, writes the one-use sign-in transfer, runs the game's one-time
// installs, and starts the game through dockhost.exe and Valve's own client.

package org.pocketarcade.madeira.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.pocketarcade.madeira.core.DockException
import org.pocketarcade.madeira.core.DockHandoff
import org.pocketarcade.madeira.core.DirectStart
import org.pocketarcade.madeira.core.InstallProcess
import org.pocketarcade.madeira.core.InstallScripts
import org.pocketarcade.madeira.core.SteamRuntimeFiles
import org.pocketarcade.madeira.session.LaunchSpec
import org.pocketarcade.madeira.steam.HttpClient
import org.pocketarcade.madeira.steam.InstalledGame
import org.pocketarcade.madeira.steam.LaunchOption
import org.pocketarcade.madeira.steam.SteamJwt
import org.pocketarcade.madeira.steam.SteamLog
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class DockModel(
    private val context: Context,
    private val http: HttpClient,
    private val prefix: SteamPrefix,
    private val vault: org.pocketarcade.madeira.platform.TokenVault,
) {
    sealed class Components {
        object Missing : Components()
        data class Downloading(val fraction: Float, val text: String) : Components()
        object Ready : Components()
        data class Failed(val message: String) : Components()
    }

    private val _components = MutableStateFlow(if (prefix.clientInstalled) Components.Ready else Components.Missing)
    val components: StateFlow<Components> = _components

    /** Built into this APK (the runtime's addons/dock). */
    fun bundled(runtimeRoot: File) = File(runtimeRoot, "addons/dock/dockhost.exe").isFile

    /**
     * Whether this build can start Madeira Dock. A runtime packaged in the APK always carries
     * dockhost.exe (package-runtime.py requires it), but it is only unpacked at the first game
     * start, so the unpacked file alone would say "missing" on a fresh install.
     */
    fun hostAvailable(runtime: org.pocketarcade.madeira.PortRuntime) = runtime.bundled() || bundled(runtime.root())

    fun refresh() { if (_components.value !is Components.Downloading) _components.value = if (prefix.clientInstalled) Components.Ready else Components.Missing }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Downloads Valve's client packages from Valve's update CDN, checks the pinned sums, and puts them in the prefix. */
    suspend fun installComponents() = withContext(Dispatchers.IO) {
        if (_components.value is Components.Downloading) return@withContext
        val cache = File(context.cacheDir, "steam-client").apply { mkdirs() }
        try {
            prefix.ensure()
            val root = prefix.clientRoot
            if (File(root, "steam.exe").isFile && File(root, "steamclient64.dll").isFile) { _components.value = Components.Ready; return@withContext }
            val total = SteamRuntimeFiles.totalBytes.toFloat()
            var before = 0L
            val stage = File(cache, "stage").apply { deleteRecursively(); mkdirs() }
            val files = ArrayList<String>()
            for ((i, p) in SteamRuntimeFiles.PACKAGES.withIndex()) {
                coroutineContext.ensureActive()
                val file = File(cache, p.file)
                if (!(file.isFile && file.length() == p.bytes && sha256(file) == p.sha256)) {
                    file.delete()
                    val text = "Downloading Steam components (${i + 1} of ${SteamRuntimeFiles.PACKAGES.size})…"
                    _components.value = Components.Downloading(before / total, text)
                    val code = http.download(SteamRuntimeFiles.ORIGIN + p.file, file, { n -> _components.value = Components.Downloading((before + n) / total, text) }, 60_000, p.bytes)
                    if (code != 200 || file.length() != p.bytes || sha256(file) != p.sha256) {
                        file.delete()
                        throw DockException("Steam's components could not be verified (HTTP $code). Try downloading them again.")
                    }
                }
                before += p.bytes
                _components.value = Components.Downloading(before / total, "Verifying Steam components…")
                SteamRuntimeFiles.unpack(file.readBytes()) { name, bytes ->
                    if (files.any { it.equals(name, true) }) throw DockException("Steam's components could not be verified.")
                    val out = File(stage, name); out.parentFile?.mkdirs(); out.writeBytes(bytes); files += name
                }
            }
            if (sha256(File(stage, "steamclient64.dll")) != SteamRuntimeFiles.CLIENT_SHA256 || "steam.exe" !in files) {
                throw DockException("Steam's client is not the build Madeira Dock supports. Try downloading again.")
            }
            // Existing different files are a conflict, never replaced; steam.exe goes last.
            for (name in files) {
                val target = File(root, name)
                if (target.isFile && sha256(target) != sha256(File(stage, name))) throw DockException("Existing Steam files need attention. They were kept.")
            }
            for (name in files.sortedWith(compareBy({ it == "steam.exe" }, { it }))) {
                val target = File(root, name)
                if (target.isFile) continue
                target.parentFile?.mkdirs()
                File(stage, name).copyTo(target)
            }
            stage.deleteRecursively()
            for (p in SteamRuntimeFiles.PACKAGES) File(cache, p.file).delete()
            SteamLog.event("[dock] client components ready")
            _components.value = Components.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            _components.value = if (prefix.clientInstalled) Components.Ready else Components.Missing
            throw e
        } catch (e: Exception) {
            SteamLog.event("[dock] components failed reason=${e.javaClass.simpleName}")
            _components.value = Components.Failed(e.message ?: "Steam's components could not be installed.")
        }
    }

    /** Removes Valve's client files (not the games, nor the prefix). */
    suspend fun removeComponents() = withContext(Dispatchers.IO) {
        val root = prefix.clientRoot
        root.listFiles()?.forEach { if (it.name != "steamapps") org.pocketarcade.madeira.platform.FileTrees.delete(it) }
        _components.value = Components.Missing
    }

    // MARK: A Dock start

    private val transfer: File get() = File(context.noBackupFilesDir, "dock/launch.auth")

    /** Removes an unconsumed sign-in transfer (after a session, at sign-out, at app start). */
    fun cleanup() { transfer.delete() }

    private fun online(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Steam registry values for client discovery, imported at the start of the session. */
    private fun registryFile(): File {
        val root = SteamRuntimeFiles.WINDOWS_ROOT.replace("\\", "\\\\")
        val text = "Windows Registry Editor Version 5.00\r\n\r\n" +
            "[HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam]\r\n\"InstallPath\"=\"$root\"\r\n\r\n" +
            "[HKEY_LOCAL_MACHINE\\Software\\Wow6432Node\\Valve\\Steam]\r\n\"InstallPath\"=\"$root\"\r\n\r\n" +
            "[HKEY_CURRENT_USER\\Software\\Valve\\Steam]\r\n\"SteamPath\"=\"$root\"\r\n\"SteamExe\"=\"$root\\\\steam.exe\"\r\n\r\n" +
            "[HKEY_CURRENT_USER\\Software\\Valve\\Steam\\ActiveProcess]\r\n\"SteamClientDll\"=\"$root\\\\steamclient.dll\"\r\n\"SteamClientDll64\"=\"$root\\\\steamclient64.dll\"\r\n"
        val f = File(prefix.drive, "windows/pocketarcade-steam.reg")
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    /** The game's install-script programs and which of them run this time. */
    fun plannedInstalls(game: InstalledGame): Pair<List<InstallProcess>, List<InstallProcess>> {
        val folder = File(prefix.drive, "${game.library}/common/${game.installDir}")
        val found = InstallScripts.scripts(folder).flatMap { InstallScripts.processes(it.readBytes(), game.windowsInstallPath, game.appID) }.distinct()
        val registry = listOf("system.reg", "user.reg").joinToString("\n") { File(prefix.prefix, it).takeIf { f -> f.isFile }?.readText() ?: "" }
        val pending = found.filter { p ->
            !InstallScripts.marked(p.run, registry) && File(prefix.drive, p.executable.removePrefix("C:\\").replace('\\', '/')).let { f ->
                f.isFile || f.parentFile?.list()?.any { it.equals(f.name, true) } == true
            }
        }.take(8)
        return found to pending
    }

    /** Records the programs the last session's batch ran successfully (only while no session runs). */
    fun absorbInstallResults() {
        val result = File(prefix.drive, InstallScripts.RESULT_NAME)
        val ledger = File(prefix.prefix, "pocketarcade-installs.txt")
        if (!result.isFile || !ledger.isFile) return
        val runs = ledger.readLines().mapNotNull { line ->
            val p = line.split('\t'); if (p.size != 4) null else org.pocketarcade.madeira.core.InstallRun(p[0], p[1] == "1", p[2], p[3].toLongOrNull() ?: 1)
        }
        val r = InstallScripts.results(result.readText())
        val ok = runs.filterIndexed { i, _ -> r.exits[i + 1]?.let { InstallScripts.Results.succeeded(it) } == true }
        if (ok.isNotEmpty()) {
            val now = System.currentTimeMillis() / 1000
            for ((file, machine) in listOf("system.reg" to true, "user.reg" to false)) {
                val f = File(prefix.prefix, file)
                if (!f.isFile) continue
                val selected = ok.filter { it.machine == machine }
                if (selected.isEmpty()) continue
                val (text, changed) = InstallScripts.mark(selected, f.readText(), now)
                if (changed > 0) {
                    val backup = File(f.path + ".pocketarcade-bak")
                    if (!backup.exists()) f.copyTo(backup)
                    f.writeText(text)
                }
            }
            SteamLog.event("[dock-installers] recorded=${ok.size} of ${runs.size}")
        }
        result.delete(); ledger.delete()
    }

    /**
     * Everything for a Dock start: validation, the batch (Steam's registry paths, Wine Mono
     * when wanted, the game's one-time installs, then the host), and the environment.
     */
    fun prepareLaunch(game: InstalledGame, launchOption: Int?, title: String, monoInstaller: File?): Pair<String, Map<String, String>> {
        if (!InstalledGame.validAppID(game.appID.toLong()) || !InstalledGame.validFolderName(game.installDir)) throw DockException("This game's Steam install record is invalid.")
        if (!prefix.clientInstalled) throw DockException("Steam's client files are missing. Download Valve's client components in Settings › Steam.")
        if (!game.installed || !File(prefix.drive, "${game.library}/common/${game.installDir}").isDirectory) throw DockException("Steam does not list this game as fully installed.")
        val stored = vault.load() ?: throw DockException("Sign in to Steam to play.")
        if (!SteamJwt.usable(stored.refreshToken)) throw DockException("Your Steam sign-in needs renewal. Sign in to Steam again.")
        val steamID = SteamJwt.subject(stored.refreshToken) ?: throw DockException("Your Steam sign-in needs renewal. Sign in to Steam again.")
        absorbInstallResults()
        // The one-use transfer, private to the app; only its guest path goes into the environment.
        cleanup()
        val data = DockHandoff.envelope(stored.accountName, stored.refreshToken, steamID, game.appID)
        transfer.parentFile?.mkdirs()
        RandomAccessFile(transfer, "rw").use { it.setLength(0); it.write(data) }
        data.fill(0)
        transfer.setReadable(false, false); transfer.setReadable(true, true)
        File(prefix.drive, "madeira-dock.txt").delete()
        val reg = registryFile()
        // The batch: registry paths, Wine Mono, one-time installs, then the host.
        val (_, pending) = plannedInstalls(game)
        val ledger = File(prefix.prefix, "pocketarcade-installs.txt")
        ledger.writeText(pending.joinToString("") { "${it.run.name}\t${if (it.run.machine) 1 else 0}\t${it.run.key}\t${it.run.value}\n" })
        File(prefix.drive, InstallScripts.RESULT_NAME).delete()
        val lines = ArrayList<String>()
        lines += "@echo off"
        lines += "C:\\windows\\regedit.exe /S \"C:\\windows\\${reg.name}\""
        if (monoInstaller != null && !File(prefix.drive, "windows/mono/mono-2.0").isDirectory) {
            lines += "C:\\windows\\system32\\msiexec.exe /i \"" + LaunchSpec.windowsPath(monoInstaller) + "\" /qn"
        }
        if (pending.isNotEmpty()) {
            val batch = File(prefix.drive, InstallScripts.SCRIPT_NAME)
            batch.writeText(InstallScripts.batch(pending, "C:\\" + InstallScripts.RESULT_NAME, DockHandoff.EXECUTABLE))
            lines += "call C:\\" + InstallScripts.SCRIPT_NAME
        }
        lines += DockHandoff.EXECUTABLE
        File(prefix.drive, "pocketarcade-dock.cmd").writeText(lines.joinToString("\r\n") + "\r\n")
        val env = HashMap<String, String>()
        for (k in listOf("MADEIRA_STEAM_HOST_PROBE", "MADEIRA_STEAM_HOST_SESSION", "MADEIRA_STEAM_HOST_LOGIN", "MADEIRA_STEAM_HOST_LAUNCH")) env[k] = "1"
        env["MADEIRA_STEAM_HOST_APPID"] = game.appID.toString()
        env["MADEIRA_STEAM_HOST_LAUNCH_OPTION"] = (launchOption ?: 0).toString()
        env["MADEIRA_STEAM_HOST_CLIENT_DIR"] = SteamRuntimeFiles.WINDOWS_ROOT
        env["MADEIRA_STEAM_HOST_EXPECTED_INSTALL"] = game.windowsInstallPath
        env["MADEIRA_STEAM_HOST_LOG"] = "C:\\madeira-dock.txt"
        if (game.customExecutables) env["MADEIRA_STEAM_HOST_CEG"] = "1"
        env["MADEIRA_DOCK_AUTH_FILE"] = "\\\\?\\unix" + transfer.absolutePath.replace('/', '\\')
        if (!online()) env["MADEIRA_DOCK_OFFLINE"] = "1"
        SteamLog.event("[dock-launch] app=${game.appID} installers=${pending.size} offline=${env.containsKey("MADEIRA_DOCK_OFFLINE")}")
        return "C:\\windows\\system32\\cmd.exe /c C:\\pocketarcade-dock.cmd" to env
    }

    /** The default Windows launch entry's original key, for Valve's LaunchApp. */
    fun launchOption(options: List<LaunchOption>?, game: InstalledGame): Int? {
        val root = File(prefix.drive, "${game.library}/common/${game.installDir}")
        return options?.let { DirectStart.choose(it, root)?.launchIndex }
    }

    val reportFile: File get() = File(prefix.drive, "madeira-dock.txt")
    val installerResults: File get() = File(prefix.drive, InstallScripts.RESULT_NAME)
}

/**
 * Wine Mono, the .NET Framework runtime Wine's mscoree loads, downloaded from WineHQ and
 * checked against the SHA-256 this Wine build pins. Wine installs it from its cache
 * folder ($XDG_CACHE_HOME/wine) when it creates a prefix; existing prefixes get it in
 * their next session.
 */
class WineMono(private val context: Context, private val http: HttpClient, private val wineVariant: String) {
    val version: String get() = if (wineVariant == "madeira") "11.0.0" else "10.4.1"
    private val sha256: String get() = if (wineVariant == "madeira") "d7efede0b9bdcf52134f8cd6ced59d9fece976f70b11a400bdb47b864569cd27"
        else "071f4b2887e1c97a11d791ff3d65be9429eed6dec4c2708888bfd546ba358e23"
    val file: File get() = File(context.filesDir, "wine-cache/wine/wine-mono-$version-x86.msi")
    private val url: String get() = "https://dl.winehq.org/wine/wine-mono/$version/wine-mono-$version-x86.msi"

    sealed class State {
        object Missing : State()
        data class Downloading(val fraction: Float) : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(if (file.isFile) State.Ready else State.Missing)
    val state: StateFlow<State> = _state

    suspend fun download() = withContext(Dispatchers.IO) {
        if (_state.value is State.Downloading) return@withContext
        try {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            _state.value = State.Downloading(0f)
            var size = 85_000_000f
            val code = http.download(url, part, { n -> _state.value = State.Downloading((n / size).coerceAtMost(0.99f)) }, 60_000, 512L shl 20)
            if (code != 200) throw DockException("WineHQ answered HTTP $code.")
            val md = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            if (md.digest().joinToString("") { "%02x".format(it) } != sha256) { part.delete(); throw DockException("The download did not match Wine Mono's checksum.") }
            part.renameTo(file)
            size = file.length().toFloat()
            _state.value = State.Ready
        } catch (e: kotlinx.coroutines.CancellationException) { _state.value = State.Missing; throw e
        } catch (e: Exception) { _state.value = State.Failed(e.message ?: "Wine Mono could not be downloaded.") }
    }

    fun remove() { file.delete(); _state.value = State.Missing }
}

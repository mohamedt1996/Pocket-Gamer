// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.pocketarcade.madeira.Launcher
import org.pocketarcade.madeira.core.PeInspector
import org.pocketarcade.madeira.model.GameConfig
import org.pocketarcade.madeira.session.LaunchSpec
import java.io.File
import java.security.MessageDigest

class EpicLibrary(private val context: Context, private val scope: CoroutineScope, val api: EpicApi = EpicApi(),
                  private val steamEac: SteamEacInstaller? = null, private val steamSignedIn: () -> Boolean = { false }) {
    data class State(val account: String? = null, val games: List<EpicGame> = emptyList(), val busy: Boolean = false,
                     val error: String? = null, val componentRevision: Int = 0, val box64Probe: String? = null,
                     val protonStatusApp: String? = null, val protonStatus: String? = null,
                     val bundleProgress: String? = null)
    private val vault = EpicVault(context)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    val progress = MutableStateFlow<EpicDownloader.Progress?>(null)
    val downloading = MutableStateFlow<String?>(null)
    private val credentialsLock = Mutex()
    private var credentials: EpicCredentials? = null
    private var generation = 0
    private var task: Job? = null
    private var downloadTask: Job? = null
    private val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "Epic").apply { mkdirs() }
    private val cache = File(context.filesDir, "epic-library.json")
    init {
        scope.launch { credentialsLock.withLock {
            credentials = withContext(Dispatchers.IO) { vault.load() }
            val c = credentials ?: return@withLock
            val saved = withContext(Dispatchers.IO) { runCatching { JSONObject(cache.readText()) }.getOrNull() }
            val list = if (saved?.optString("accountId") == c.accountId) saved.optJSONArray("games") else null
            _state.value = State(account = c.name, games = runCatching {
                (0 until (list?.length() ?: 0)).map { EpicGame.parse(list!!.getJSONObject(it)) }
            }.getOrDefault(emptyList()))
        } }
    }
    private fun action(block: suspend () -> Unit) {
        task?.cancel()
        _state.value = _state.value.copy(busy = true, error = null)
        val epoch = generation
        task = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (epoch == generation) _state.value = _state.value.copy(error = "Epic could not complete this request. Check your connection or sign in again.") }
            finally { if (epoch == generation) _state.value = _state.value.copy(busy = false) }
        }
    }
    fun signIn(code: String) = action {
        credentialsLock.withLock {
            val value = api.signIn(code)
            withContext(Dispatchers.IO) { vault.save(value) }
            credentials = value
            _state.value = State(account = value.name, busy = true)
        }
        fetchLibrary()
    }
    fun refresh() = action { fetchLibrary() }
    fun installWoWBox64() = action {
        RuntimeComponents.install(context, api.http)
        _state.value = _state.value.copy(componentRevision = _state.value.componentRevision + 1)
    }
    fun installBox64() = action {
        try {
            RuntimeComponents.installBox64(context, api.http)
            _state.value = _state.value.copy(componentRevision = _state.value.componentRevision + 1, box64Probe = null)
        } catch (e: IllegalArgumentException) {
            _state.value = _state.value.copy(box64Probe = if (e.message?.contains("page size") == true)
                "This Android page size has no compatible Box64 build."
                else "Box64 archive validation failed. The component was not installed.")
        }
    }
    fun probeBox64() = action {
        val result = try { RuntimeComponents.probeBox64(context) }
            catch (_: Exception) { "Box64 could not start on this device. Repair the component or inspect Android logs." }
        _state.value = _state.value.copy(box64Probe = result)
    }
    fun importLinuxComponent(component: LinuxProtonInstaller.Component, uri: Uri) = action {
        try {
            withContext(Dispatchers.IO) { LinuxProtonInstaller.install(context, component, uri) }
            _state.value = _state.value.copy(componentRevision = _state.value.componentRevision + 1)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message ?: "Linux runtime archive could not be installed")
        }
    }
    fun installBundledLinux() = action {
        val packages = LinuxProtonInstaller.bundled(context)
        require(packages.isNotEmpty()) { "This APK has no included Linux Proton packages" }
        try {
            for (packageFile in packages) {
                _state.value = _state.value.copy(bundleProgress = "Installing ${packageFile.component.folder}…")
                withContext(Dispatchers.IO) { LinuxProtonInstaller.installBundled(context, packageFile) }
                _state.value = _state.value.copy(componentRevision = _state.value.componentRevision + 1)
            }
            _state.value = _state.value.copy(bundleProgress = "Included Linux packages installed")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message ?: "Could not install the included Linux packages", bundleProgress = null)
        }
    }
    fun installEacFromSteam() = action {
        try {
            require(steamSignedIn()) { "Sign in to Steam in Settings first, then install Proton EasyAntiCheat Runtime here" }
            val installer = steamEac ?: error("Steam downloader is unavailable")
            _state.value = _state.value.copy(bundleProgress = "Connecting to Steam for Proton EasyAntiCheat Runtime…")
            installer.install { progress ->
                val label = when (progress.phase) {
                    org.pocketarcade.madeira.steam.DownloadProgress.Phase.PREPARING -> "Preparing Steam EAC download…"
                    org.pocketarcade.madeira.steam.DownloadProgress.Phase.DOWNLOADING ->
                        "Downloading Steam EAC: ${(progress.fraction * 100).toInt()}%"
                    org.pocketarcade.madeira.steam.DownloadProgress.Phase.FINISHING -> "Verifying Steam EAC runtime…"
                }
                _state.value = _state.value.copy(bundleProgress = label)
            }
            _state.value = _state.value.copy(componentRevision = _state.value.componentRevision + 1,
                bundleProgress = "Proton EasyAntiCheat Runtime installed from Steam")
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message ?: "Steam EAC download failed; check Steam sign-in and try again",
                bundleProgress = null)
        }
    }
    private suspend fun validCredentials(): EpicCredentials = credentialsLock.withLock {
        val c = credentials ?: error("Sign in to Epic first")
        if (c.expiresAt > System.currentTimeMillis() + 120_000) return@withLock c
        val fresh = api.refresh(c.refresh)
        require(fresh.accountId == c.accountId) { "Epic account changed during refresh" }
        withContext(Dispatchers.IO) { vault.save(fresh) }; credentials = fresh
        fresh
    }
    private suspend fun fetchLibrary() {
        val c = validCredentials()
        val games = api.library(c.access)
        withContext(Dispatchers.IO) {
            val data = JSONObject().put("accountId", c.accountId).put("games", JSONArray(games.map { it.json() }))
            val temp = File(cache.parentFile, cache.name + ".tmp"); temp.writeText(data.toString())
            java.nio.file.Files.move(temp.toPath(), cache.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        _state.value = _state.value.copy(account = c.name, games = games)
    }
    fun signOut() {
        generation++; task?.cancel(); downloadTask?.cancel()
        scope.launch { credentialsLock.withLock { withContext(Dispatchers.IO) { vault.clear(); cache.delete() }; credentials = null } }
        _state.value = State()
    }
    fun clearError() { _state.value = _state.value.copy(error = null) }
    fun reportError(message: String) { _state.value = _state.value.copy(error = message) }
    fun folder(game: EpicGame): File = File(base, key(game.app))
    fun installed(game: EpicGame): JSONObject? = runCatching {
        if (File(folder(game), "incomplete").exists()) return null
        JSONObject(File(folder(game), "installed.json").readText()).takeIf { it.optString("app") == game.app }
    }.getOrNull()
    fun requestDownload(game: EpicGame) {
        if (downloading.value != null) return
        downloading.value = game.app
        try { context.startForegroundService(Intent(context, EpicDownloadService::class.java).putExtra("app", game.app)) }
        catch (_: Exception) { downloading.value = null; _state.value = _state.value.copy(error = "Android could not start the Epic download service. Try again while the app is open.") }
    }
    fun cancelDownload() { downloadTask?.cancel() }
    suspend fun download(app: String) {
        downloadTask = currentCoroutineContext().job
        try {
            val game = _state.value.games.firstOrNull { it.app == app } ?: error("App is no longer in the Epic library")
            val c = validCredentials(); val build = api.build(c.access, game)
            folder(game).mkdirs()
            // Failed updates can leave a mixture of old/new files. Never launch it as installed.
            File(folder(game), "incomplete").writeText("Verification in progress")
            val manifest = EpicDownloader(api.http).install(build, game, File(folder(game), "content")) { progress.value = it }
            val meta = manifest.meta ?: error("Epic manifest has no launch information")
            EpicDownloader.safeFile(File(folder(game), "content"), meta.launchExe.replace('\\', '/')).also { require(it.isFile) }
            val record = JSONObject().put("app", game.app).put("build", meta.buildVersion).put("exe", meta.launchExe)
                .put("arguments", meta.launchCommand).put("deploymentId", build.deploymentId)
                .put("eac", EacCompatibility.detects(manifest.fileManifestList!!.elements.map { it.filename }, meta.launchExe))
                .put("prereq", meta.prereqPath).put("prereqArgs", meta.prereqArgs)
            val marker = File(folder(game), "installed.json")
            val temp = File(folder(game), "installed.json.tmp"); temp.writeText(record.toString())
            java.nio.file.Files.move(temp.toPath(), marker.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            File(folder(game), "incomplete").delete()
            _state.value = _state.value.copy()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.value = _state.value.copy(error = "Epic download did not finish. Check storage, connection and sign-in, then resume. Completed files will be verified and reused.") }
        finally { progress.value = null; downloading.value = null; downloadTask = null }
    }
    fun play(game: EpicGame, translator: String, cap: String = "auto", graphics: String = "auto", eacDiagnostics: Boolean = false) = action {
        require(translator in listOf("fex", "wowbox64"))
        require(graphics in listOf("auto", "wine", "dxvk", "vkd3d"))
        val installed = installed(game) ?: error("Download this app first")
        val content = File(folder(game), "content")
        val exe = EpicDownloader.safeFile(content, installed.getString("exe").replace('\\', '/'))
        require(exe.isFile)
        val c = validCredentials()
        withContext(Dispatchers.IO) {
            EacCompatibility.write(folder(game), EacCompatibility.report(game.app, installed.getString("exe"),
                installed.optBoolean("eac"), translator, installed.optString("deploymentId").isNotBlank(), eacDiagnostics))
        }
        val exchange = api.exchange(c.access)
        val arguments = listOf(LaunchSpec.windowsPath(exe)) + epicArguments(game, installed, c, exchange)
        // Separate prefixes avoid switching translators in an existing Wine session/prefix.
        val home = File(folder(game), "sessions/$translator").apply { mkdirs() }
        val apis = withContext(Dispatchers.IO) { PeInspector.graphicsAPIs(exe) }
        val config = GameConfig(apis = apis.toList(), graphics = graphics)
        Launcher.start(context, LaunchSpec(home.absolutePath, arguments.joinToString(" ", transform = ::quote), exe.parent!!,
            game.title, graphics = config.resolvedGraphics(), frameCap = cap, translator32 = translator,
            eacReport = File(folder(game), "epic-launch-report.json").absolutePath,
            eacDiagnostics = eacDiagnostics && installed.optBoolean("eac")))
    }
    fun playProton(game: EpicGame, display: String) = action {
        try {
            val installed = installed(game) ?: error("Download this app first")
            val exe = EpicDownloader.safeFile(File(folder(game), "content"), installed.getString("exe").replace('\\', '/'))
            val eac = installed.optBoolean("eac")
            val prepared = withContext(Dispatchers.IO) {
                LinuxProton.prepare(context, folder(game), exe, game.title, emptyList(), display, eac)
            }
            val c = validCredentials()
            val exchange = api.exchange(c.access)
            val plan = prepared.copy(command = prepared.command + epicArguments(game, installed, c, exchange))
            withContext(Dispatchers.IO) {
                EacCompatibility.write(folder(game), EacCompatibility.report(game.app, installed.getString("exe"), eac,
                    "linux-proton", installed.optString("deploymentId").isNotBlank(), false))
            }
            context.startForegroundService(Intent(context, LinuxProtonService::class.java)
                .putExtra(LinuxProtonService.PLAN, plan.toJson()))
            _state.value = _state.value.copy(protonStatusApp = game.app, protonStatus = "Linux Proton starting; use Check status for the result")
        } catch (e: IllegalArgumentException) {
            _state.value = _state.value.copy(error = e.message ?: "Linux Proton setup is incomplete")
        } catch (e: IllegalStateException) {
            _state.value = _state.value.copy(error = e.message ?: "Linux Proton setup is incomplete")
        }
    }
    fun refreshProtonStatus(game: EpicGame) = action {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(folder(game), "sessions/proton/status.json")
                val status = JSONObject(file.readText())
                when (status.optString("state")) {
                    "running" -> "Linux Proton process is running"
                    "exited" -> "Linux Proton exited with code ${status.optInt("exitCode")}; see app-private proton.log"
                    else -> "Linux Proton could not start; see app-private proton.log"
                }
            }.getOrDefault("No Linux Proton launch recorded yet")
        }
        _state.value = _state.value.copy(protonStatusApp = game.app, protonStatus = text)
    }
    private fun epicArguments(game: EpicGame, installed: JSONObject, c: EpicCredentials, exchange: String) =
        org.pocketarcade.madeira.session.CommandLine.split(installed.optString("arguments")) + listOf(
            "-AUTH_LOGIN=unused", "-AUTH_PASSWORD=$exchange", "-AUTH_TYPE=exchangecode", "-epicapp=${game.app}",
            "-epicenv=Prod", "-EpicPortal", "-epicusername=${c.name}", "-epicuserid=${c.accountId}",
            "-epiclocale=en", "-epicsandboxid=${game.namespace}") +
        installed.optString("deploymentId").takeIf { it.isNotBlank() }?.let { listOf("-epicdeploymentid=$it") }.orEmpty()
    fun prerequisites(game: EpicGame, translator: String) = action {
        require(translator in listOf("fex", "wowbox64"))
        val installed = installed(game) ?: error("Download this app first")
        val content = File(folder(game), "content")
        val program = EpicDownloader.safeFile(content, installed.getString("prereq").replace('\\', '/'))
        require(program.isFile)
        val home = File(folder(game), "sessions/$translator").apply { mkdirs() }
        val args = listOf(LaunchSpec.windowsPath(program)) +
            org.pocketarcade.madeira.session.CommandLine.split(installed.optString("prereqArgs"))
        Launcher.start(context, LaunchSpec(home.absolutePath, args.joinToString(" ", transform = ::quote), program.parent!!,
            game.title + " prerequisites", graphics = "wine", translator32 = translator))
    }
    companion object {
        fun key(app: String): String = MessageDigest.getInstance("SHA-256").digest(app.toByteArray()).joinToString("") { "%02x".format(it) }
        fun quote(arg: String): String = buildString {
            append('"'); var slashes = 0
            for (c in arg) {
                if (c == '\\') { slashes++; continue }
                repeat(if (c == '"') slashes * 2 + 1 else slashes) { append('\\') }
                append(c); slashes = 0
            }
            repeat(slashes * 2) { append('\\') }; append('"')
        }
    }
}

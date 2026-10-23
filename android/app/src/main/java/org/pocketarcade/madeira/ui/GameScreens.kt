// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Game details (LibraryDetail, SteamEntrySection, SteamCloudSection): the hero
// artwork, Play or the download, Steam Cloud saves, Start with, graphics, on
// screen, advanced settings and the install's management.

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.pocketarcade.madeira.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.pocketarcade.madeira.AppGraph
import org.pocketarcade.madeira.Launcher
import org.pocketarcade.madeira.core.DirectStart
import org.pocketarcade.madeira.core.PeInspector
import org.pocketarcade.madeira.model.Download
import org.pocketarcade.madeira.model.GameConfig
import org.pocketarcade.madeira.model.Launches
import org.pocketarcade.madeira.model.SteamArtwork
import org.pocketarcade.madeira.steam.CloudState
import org.pocketarcade.madeira.steam.DownloadProgress
import java.io.File

/** The page frame: hero artwork with the title over it, a back button, and the content below. */
@Composable
fun DetailFrame(title: String, heroes: List<String>, heroFile: String?, subtitle: String?, badges: List<String>, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = Madeira.colors
    Box(Modifier.fillMaxSize().background(colors.grouped)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                Artwork(heroes, Modifier.fillMaxSize(), heroFile, Icons.Filled.SportsEsports)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, colors.grouped.copy(alpha = 0.85f), colors.grouped))))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Gutter, vertical = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.label)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
                    if (badges.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Badges(badges) }
                }
            }
            content()
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(12.dp).size(40.dp).glass(colors, CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.label)
        }
    }
}

@Composable
fun PrimaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val colors = Madeira.colors
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.White)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                Text(label, maxLines = 1)
            }
        }
    }
}

/** Settings every library game has: graphics, on screen and advanced. */
@Composable
fun CommonSettings(config: GameConfig, onChange: (GameConfig) -> Unit) {
    val gpu = org.pocketarcade.madeira.platform.GpuCaps.current
    val ideal = PeInspector.recommendedGraphics(config.apis.toSet())
    val recommended = org.pocketarcade.madeira.core.GraphicsChoice.fit(ideal, gpu)
    val names = mapOf("wine" to "Wine", "dxvk" to "DXVK", "vkd3d" to "VKD3D")
    val gpuLine = gpu?.let { "\nGPU: ${it.summary()}." } ?: ""
    val chosen = if (config.graphics == "auto") recommended else config.graphics
    val unsupported = when {
        gpu == null || !gpu.vulkan -> null
        chosen == "vkd3d" && !gpu.vkd3d -> "This GPU's driver cannot run VKD3D-Proton (missing: ${gpu.vkd3dMissing.take(4).joinToString(", ")})."
        (chosen == "dxvk" || chosen == "vkd3d") && !gpu.dxvk -> "This GPU's driver cannot run DXVK (missing: ${gpu.dxvkMissing.take(4).joinToString(", ")})."
        else -> null
    }
    SettingsSection("Graphics", footer = when (config.graphics) {
        "auto" -> "Auto picks ${names[recommended]} for this game" + (if (config.apis.isNotEmpty()) " (it uses ${config.apis.joinToString(", ")})." else ".") +
            (org.pocketarcade.madeira.core.GraphicsChoice.reason(ideal, recommended, gpu)?.let { " $it" } ?: "") +
            " DXVK runs Direct3D 8–11 on Vulkan; VKD3D-Proton runs Direct3D 12."
        "wine" -> "Wine's own Direct3D on Vulkan, for older games, DirectDraw and GPUs DXVK cannot use."
        "dxvk" -> "DXVK runs Direct3D 8–11 on Vulkan." + (unsupported?.let { " $it" } ?: "")
        else -> "VKD3D-Proton runs Direct3D 12 on Vulkan, with DXVK for older APIs." + (unsupported?.let { " $it" } ?: "")
    } + gpuLine) {
        Choice(listOf("auto" to "Auto", "wine" to "Wine", "dxvk" to "DXVK", "vkd3d" to "VKD3D"), config.graphics) { onChange(config.copy(graphics = it)) }
    }
    SettingsSection("On screen") {
        ToggleRow("Touch controls", config.touchControls, "Shown when no controller is connected. Edit them from the in-game menu.") { onChange(config.copy(touchControls = it)) }
        RowDivider()
        ToggleRow("Performance overlay", config.performance, "Frame rate in the corner, and DXVK's HUD.") { onChange(config.copy(performance = it)) }
    }
    SettingsSection("Frame cap", footer = when (config.frameCap) {
        "auto" -> "60 frames per second, or 30 when Android power saver or battery game mode is on at launch. Applies to DXVK and VKD3D-Proton."
        "off" -> "No cap: the game draws as fast as it can. Fastest, and hottest."
        else -> "At most ${config.frameCap} frames per second (DXVK and VKD3D-Proton)."
    }) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            org.pocketarcade.madeira.core.Performance.FRAME_CAPS.forEach { cap ->
                FilterChip(selected = config.frameCap == cap,
                    onClick = { onChange(config.copy(frameCap = cap)) },
                    label = { Text(when (cap) { "auto" -> "Auto"; "off" -> "Off"; else -> cap }) })
            }
        }
    }
    SettingsSection("CPU", footer = when (config.cpuMode) {
        "compatible" -> "Stricter x86 emulation for games that glitch or crash in Balanced. Uses more CPU."
        "fast" -> "Skips x86 memory-ordering emulation and reduces x87 precision. May reduce translation work, but some games crash or misbehave. Compare performance and stability with Balanced."
        else -> "FEX's own defaults: the best mix of speed and compatibility."
    }) {
        Choice(listOf("compatible" to "Compatible", "balanced" to "Balanced", "fast" to "Fast"), config.cpuMode) { onChange(config.copy(cpuMode = it)) }
    }
    var arguments by remember(config.arguments) { mutableStateOf(config.arguments) }
    var environment by remember(config.environment) { mutableStateOf(config.environment) }
    var overrides by remember(config.dllOverrides) { mutableStateOf(config.dllOverrides) }
    SettingsSection("Advanced", footer = "Environment: one NAME=value per line. DLL overrides use Wine's syntax, for example d3dx9_43=n,b.") {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(arguments, { arguments = it }, label = { Text("Arguments") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(environment, { environment = it }, label = { Text("Environment") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(overrides, { overrides = it }, label = { Text("DLL overrides") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (arguments != config.arguments || environment != config.environment || overrides != config.dllOverrides) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onChange(config.copy(arguments = arguments.trim(), environment = environment, dllOverrides = overrides.trim())) }) { Text("Save") }
            }
        }
    }
}

@Composable
fun SteamGameScreen(graph: AppGraph, nav: Navigator, appID: Int) {
    val colors = Madeira.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owned by graph.library.owned.collectAsState()
    val installedList by graph.library.installed.collectAsState()
    val downloads by graph.library.downloads.collectAsState()
    val playtime by graph.library.playtime.collectAsState()
    val cloudStates by graph.library.cloud.collectAsState()
    val cloudOn by graph.settings.steamCloud.collectAsState()
    val version by graph.steamConfigs.changes.collectAsState()
    val game = owned.firstOrNull { it.id == appID }
    val installed = installedList.firstOrNull { it.appID == appID }
    val download = downloads[appID]
    val config = remember(appID, version) { graph.steamConfigs.get(appID) }
    val name = game?.name ?: installed?.name ?: "App $appID"
    val owner: (Int) -> org.pocketarcade.madeira.model.OwnedGame? = { id -> owned.firstOrNull { it.id == id } }
    var starting by remember { mutableStateOf(false) }
    var hold by remember { mutableStateOf<String?>(null) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var programs by remember { mutableStateOf<List<String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val update = graph.library.updateAvailable(appID)

    LaunchedEffect(appID, installed?.buildID) {
        if (installed != null && config.bits == 0) graph.library.inspect(appID)
        if (installed != null && cloudOn && cloudStates[appID] == null) graph.library.checkCloud(appID)
    }

    fun play(skipCloud: Boolean = false) {
        val blocker = Launches.steamBlocker(graph, appID)
        if (blocker != null) {
            when {
                !graph.account.signedIn && config.startWith == "dock" -> nav.push(Screen.SignIn)
                !graph.prefix.clientInstalled && config.startWith == "dock" -> nav.push(Screen.Dock)
                else -> notice = blocker
            }
            return
        }
        if (!skipCloud) graph.library.cloudHold(appID)?.let { hold = it; return }
        starting = true
        scope.launch {
            try { Launcher.start(context, Launches.steam(graph, appID)) }
            catch (e: Exception) { notice = e.message ?: "The game could not start." }
            starting = false
        }
    }

    val subtitle = playtime[appID]?.let { playtimeText(it.minutes, it.lastPlayed) }
    val badges = if (installed != null) steamBadges(SteamItem(appID, name, installed, game), config, update, null) else listOfNotNull(game?.downloadBytes?.takeIf { it > 0 }?.let { "Download " + formatBytes(it) })
    DetailFrame(name, SteamArtwork.heroes(appID, owner), null, subtitle, badges, { nav.pop() }) {
        Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp)) {
            when {
                download != null -> DownloadState(download, onPause = { graph.library.pause(appID) }, onResume = { graph.library.install(appID) },
                    onCancel = { graph.library.cancelInstall(appID) })
                installed != null && installed.installed -> {
                    PrimaryButton(if (starting) "Starting…" else "Play", Icons.Filled.PlayArrow, busy = starting) { play() }
                    if (update) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { graph.library.install(appID) }) { Icon(Icons.Filled.FileDownload, null); Spacer(Modifier.width(6.dp)); Text("Update to the latest build") }
                    }
                    Launches.steamBlocker(graph, appID)?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = colors.secondary) }
                }
                game != null -> {
                    val size = game.downloadBytes
                    val free = graph.prefix.drive.let { it.mkdirs(); it.usableSpace }
                    PrimaryButton("Download" + (if (size > 0) " · " + formatBytes(size) else ""), Icons.Filled.FileDownload) {
                        if (!graph.account.signedIn) nav.push(Screen.SignIn) else graph.library.install(appID)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text((if (game.installBytes > 0) "About ${formatBytes(game.installBytes)} once installed. " else "") + "${formatBytes(free)} free on this device. " +
                        "Downloads come from Steam's content servers; a paused download continues where it stopped.",
                        style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                }
                else -> Text("This game is not in your Steam library.", color = colors.secondary)
            }
        }
        if (installed != null && cloudOn && graph.account.signedIn) CloudSection(graph, appID, cloudStates[appID])
        if (installed != null) {
            SettingsSection("Start with", footer = if (config.startWith == "dock") "Madeira Dock starts the game through Valve's own Steam client: Steam's sign-in, licence check and DRM, without Steam's window."
                else "The game's own program, without Steam. Suits games that do not need Steam.") {
                Choice(listOf("dock" to "Madeira Dock", "game" to "The game"), config.startWith) { v -> graph.steamConfigs.update(appID) { it.copy(startWith = v) } }
                if (config.startWith == "game") {
                    val root = File(graph.prefix.drive, "${installed.library}/common/${installed.installDir}")
                    val current = DirectStart.program(config.program, game?.launches, root)
                    RowDivider()
                    SettingsRow("Program", current ?: "Choose", chevron = true) { scope.launch { programs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DirectStart.programs(root) } } }
                }
            }
            CommonSettings(config) { c -> graph.steamConfigs.save(appID, c); graph.scope.launch { graph.library.inspect(appID) } }
            SettingsSection("Install", footer = "App ${appID} · ${installed.windowsInstallPath}") {
                SettingsRow("Check files", subtitle = "Compares every file with Steam's current build and fetches what differs.", icon = Icons.Filled.Build) { graph.library.repair(appID) }
                RowDivider()
                SettingsRow("Uninstall", tint = colors.red, icon = Icons.Filled.Delete) { confirmUninstall = true }
            }
            val (found, pending) = remember(installed, version) { graph.dock.plannedInstalls(installed) }
            if (found.isNotEmpty()) SettingsSection("One-time installs", footer = "Valve's client runs these before a game's first start; Madeira Dock runs them in the session before the game. Each runs once.") {
                for ((i, p) in found.withIndex()) {
                    if (i > 0) RowDivider()
                    SettingsRow(org.pocketarcade.madeira.core.InstallScripts.label(p.run), if (p in pending) "Next start" else "Done")
                }
            }
        }
    }
    hold?.let { message ->
        AlertDialog(onDismissRequest = { hold = null }, title = { Text("Steam Cloud") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { hold = null; graph.scope.launch { graph.library.syncCloud(appID) } }) { Text("Wait and sync") } },
            dismissButton = { TextButton(onClick = { hold = null; play(skipCloud = true) }) { Text("Launch anyway") } })
    }
    notice?.let { message ->
        AlertDialog(onDismissRequest = { notice = null }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }, title = { Text(name) }, text = { Text(message) })
    }
    if (confirmUninstall) {
        AlertDialog(onDismissRequest = { confirmUninstall = false }, title = { Text("Uninstall $name?") },
            text = { Text("The game's files are deleted from this device. Saves in Steam Cloud stay there.") },
            confirmButton = { TextButton(onClick = { confirmUninstall = false; graph.library.uninstall(appID); nav.pop() }) { Text("Uninstall", color = colors.red) } },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") } })
    }
    programs?.let { list ->
        AlertDialog(onDismissRequest = { programs = null }, title = { Text("Program") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (list.isEmpty()) Text("No Windows programs in the install folder.")
                    for (p in list) Text(p, modifier = Modifier.fillMaxWidth().clickable { graph.steamConfigs.update(appID) { it.copy(program = p) }; programs = null; graph.scope.launch { graph.library.inspect(appID) } }.padding(vertical = 10.dp))
                }
            },
            confirmButton = { TextButton(onClick = { programs = null }) { Text("Cancel") } })
    }
}

@Composable
private fun DownloadState(d: Download, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    val colors = Madeira.colors
    val p = d.progress
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.card).padding(16.dp)) {
        val title = when (val s = d.state) {
            Download.State.Queued -> "Waiting to download"
            Download.State.Active -> when (p.phase) { DownloadProgress.Phase.PREPARING -> "Preparing…"; DownloadProgress.Phase.FINISHING -> "Finishing…"; else -> "Downloading ${(p.fraction * 100).toInt()}%" }
            Download.State.Paused -> "Paused"
            is Download.State.Failed -> s.message
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (d.state is Download.State.Failed) colors.red else colors.label)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { p.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        Spacer(Modifier.height(8.dp))
        if (p.totalBytes > 0) Text("${formatBytes(p.doneBytes)} of ${formatBytes(p.totalBytes)}" + (if (p.bytesPerSecond > 0 && d.state == Download.State.Active) " · ${"%.1f".format(p.bytesPerSecond / 1e6)} MB/s" else ""),
            style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.state == Download.State.Active || d.state == Download.State.Queued) TextButton(onClick = onPause) { Icon(Icons.Filled.Pause, null); Spacer(Modifier.width(4.dp)); Text("Pause") }
            else TextButton(onClick = onResume) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(4.dp)); Text(if (d.state is Download.State.Failed) "Try again" else "Resume") }
            TextButton(onClick = onCancel) { Text("Cancel", color = colors.red) }
        }
    }
}

@Composable
private fun CloudSection(graph: AppGraph, appID: Int, state: CloudState?) {
    val colors = Madeira.colors
    val text = when (val p = state?.phase) {
        null, CloudState.Phase.Checking -> "Checking saves…"
        is CloudState.Phase.Failed -> p.message
        is CloudState.Phase.Downloading -> "Downloading saves (${p.done} of ${p.of})…"
        is CloudState.Phase.Uploading -> "Uploading saves (${p.done} of ${p.of})…"
        CloudState.Phase.Ready -> {
            val a = state?.audit
            when {
                state != null && state.conflicts.isNotEmpty() -> "${state.conflicts.size} save${if (state.conflicts.size == 1) "" else "s"} differ between this device and Steam Cloud."
                a == null || (a.cloudFiles == 0 && a.entries.isEmpty()) -> "No saves in Steam Cloud for this game."
                else -> "Saves are in sync (${a.cloudFiles} in Steam Cloud)."
            }
        }
    }
    SettingsSection("Steam Cloud", footer = state?.problem ?: "Saves sync before and after you play. A save that changed on both sides waits for your choice; replaced copies are kept in Android/data/…/Steam Cloud Backups.") {
        SettingsRow(text, icon = Icons.Filled.Cloud, tint = if (state?.conflicts?.isNotEmpty() == true) colors.orange else null)
        if (state != null && state.conflicts.isNotEmpty()) {
            for (c in state.conflicts.take(6)) { RowDivider(); SettingsRow(c.name, if (c.kind == org.pocketarcade.madeira.steam.CloudEntry.Kind.CLOUD_ONLY) "Missing here" else "Differs") }
            RowDivider()
            SettingsRow("Use the saves from Steam Cloud", tint = colors.accent) { graph.scope.launch { graph.library.resolveCloud(appID, useCloud = true) } }
            RowDivider()
            SettingsRow("Use this device's saves", tint = colors.accent) { graph.scope.launch { graph.library.resolveCloud(appID, useCloud = false) } }
        } else {
            RowDivider()
            SettingsRow("Sync now", tint = colors.accent) { graph.scope.launch { graph.library.syncCloud(appID) } }
        }
    }
}

@Composable
fun LocalGameScreen(graph: AppGraph, nav: Navigator, id: String) {
    val colors = Madeira.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val games by graph.localGames.games.collectAsState()
    val game = games.firstOrNull { it.id == id }
    var starting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var programs by remember { mutableStateOf<List<File>?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) graph.scope.launch { graph.localGames.setCover(id, uri) }
    }
    val installerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && game != null) scope.launch {
            try {
                val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.localGames.store().importDependency(id, uri) }
                Launcher.start(context, Launches.installer(graph, graph.localGames.directory(id), f, game.title))
            } catch (e: Exception) { notice = e.message }
        }
    }
    if (game == null) { Box(Modifier.fillMaxSize().background(colors.grouped)); return }
    val badges = listOfNotNull(game.config.bits.takeIf { it == 32 || it == 64 }?.let { "$it-bit" }, PeInspector.badge(game.config.apis.toSet()),
        game.config.sizeBytes.takeIf { it > 0 }?.let { formatBytes(it) })
    val subtitle = game.config.lastPlayed.takeIf { it > 0 }?.let { playtimeText(0, it / 1000) }
    DetailFrame(game.title, emptyList(), game.cover, subtitle, badges, { nav.pop() }) {
        Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp)) {
            PrimaryButton(if (starting) "Starting…" else "Play", Icons.Filled.PlayArrow, busy = starting) {
                if (!graph.runtime.bundled()) { notice = "This APK has no Windows runtime. Install the full build from the Android CI."; return@PrimaryButton }
                starting = true
                scope.launch {
                    try { Launcher.start(context, Launches.local(graph, game)) } catch (e: Exception) { notice = e.message }
                    starting = false
                }
            }
        }
        SettingsSection("Library details") {
            SettingsRow("Name", game.title, chevron = true) { rename = game.title }
            RowDivider()
            SettingsRow("Program", game.exe.substringAfterLast('/'), chevron = true) { programs = graph.localGames.programs(id) }
            RowDivider()
            SettingsRow("Choose cover image", icon = Icons.Filled.Image) { coverPicker.launch(arrayOf("image/*")) }
        }
        CommonSettings(game.config) { c -> graph.scope.launch { graph.localGames.update(id) { it.copy(config = c) } } }
        SettingsSection("Runtimes", footer = "Installers run in this game's own Windows environment. Visual C++ comes from Microsoft; .NET games use Wine Mono (Settings › .NET Framework).") {
            SettingsRow("Run an installer (EXE or MSI)") { installerPicker.launch(arrayOf("*/*")) }
            RowDivider()
            SettingsRow("Visual C++ (x64) from Microsoft") {
                scope.launch {
                    try {
                        val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.localGames.store().downloadDependency(id, "https://aka.ms/vc14/vc_redist.x64.exe", false) }
                        Launcher.start(context, Launches.installer(graph, graph.localGames.directory(id), f, game.title))
                    } catch (e: Exception) { notice = e.message }
                }
            }
            RowDivider()
            SettingsRow("Visual C++ (x86) from Microsoft") {
                scope.launch {
                    try {
                        val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.localGames.store().downloadDependency(id, "https://aka.ms/vc14/vc_redist.x86.exe", false) }
                        Launcher.start(context, Launches.installer(graph, graph.localGames.directory(id), f, game.title))
                    } catch (e: Exception) { notice = e.message }
                }
            }
        }
        SettingsSection(footer = game.exe) {
            SettingsRow("Remove from library", tint = colors.red, icon = Icons.Filled.Delete) { confirmRemove = true }
        }
    }
    notice?.let { m -> AlertDialog(onDismissRequest = { notice = null }, confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } }, title = { Text(game.title) }, text = { Text(m) }) }
    programs?.let { list ->
        val root = File(graph.localGames.directory(id), "files").absolutePath
        AlertDialog(onDismissRequest = { programs = null }, title = { Text("Program") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    for (f in list) Text(f.absolutePath.removePrefix("$root/"), modifier = Modifier.fillMaxWidth().clickable {
                        graph.scope.launch { graph.localGames.update(id) { it.copy(exe = f.absolutePath) }; graph.localGames.inspect(id); graph.localGames.refresh() }
                        programs = null
                    }.padding(vertical = 10.dp))
                }
            },
            confirmButton = { TextButton(onClick = { programs = null }) { Text("Cancel") } })
    }
    rename?.let { current ->
        var value by remember { mutableStateOf(current) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Name") },
            text = { OutlinedTextField(value, { value = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { rename = null; graph.scope.launch { graph.localGames.update(id) { it.copy(title = value.trim().ifEmpty { it.title }) } } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
    if (confirmRemove) {
        AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove ${game.title}?") },
            text = { Text("Its files and its Windows environment, saves included, are deleted from this device.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; nav.pop(); graph.scope.launch { graph.localGames.remove(id) } }) { Text("Remove", color = colors.red) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
    }
}

@Suppress("unused") private fun openUrl(context: android.content.Context, url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

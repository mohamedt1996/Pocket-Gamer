// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// The library (LibraryView and SteamGamesSection): the Steam section with the
// games being downloaded and those installed, the account's other games under
// "Not installed", and the games added by hand under "Other games". Cards with
// ambient light, three layouts, Sort by, search and pull to refresh.

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.pocketarcade.madeira.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.pocketarcade.madeira.AppGraph
import org.pocketarcade.madeira.core.PeInspector
import org.pocketarcade.madeira.model.Download
import org.pocketarcade.madeira.model.GameConfig
import org.pocketarcade.madeira.model.OwnedGame
import org.pocketarcade.madeira.model.SteamArtwork
import org.pocketarcade.madeira.steam.InstalledGame

/** One Steam game of the section: installed by Steam, owned by the account, or both. */
data class SteamItem(val id: Int, val name: String, val installed: InstalledGame?, val owned: OwnedGame?)

fun steamBadges(item: SteamItem, config: GameConfig, update: Boolean, download: Download?): List<String> {
    if (download != null) return listOf(when (val s = download.state) {
        Download.State.Queued -> "Waiting"
        Download.State.Active -> "Downloading ${(download.progress.fraction * 100).toInt()}%"
        Download.State.Paused -> "Paused"
        is Download.State.Failed -> "Download failed"
    })
    val installed = item.installed ?: return listOfNotNull(item.owned?.downloadBytes?.takeIf { it > 0 }?.let { formatBytes(it) })
    if (!installed.installed) return listOf("Not fully installed")
    return listOfNotNull(
        config.bits.takeIf { it == 32 || it == 64 }?.let { "$it-bit" },
        PeInspector.badge(config.apis.toSet()),
        (installed.sizeOnDisk ?: config.sizeBytes).takeIf { it > 0 }?.let { formatBytes(it) },
        if (update) "Update" else null,
    )
}

@Composable
fun LibraryScreen(graph: AppGraph, nav: Navigator) {
    val colors = Madeira.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owned by graph.library.owned.collectAsState()
    val installed by graph.library.installed.collectAsState()
    val downloads by graph.library.downloads.collectAsState()
    val playtime by graph.library.playtime.collectAsState()
    val refreshing by graph.library.refreshing.collectAsState()
    val cloud by graph.library.cloud.collectAsState()
    val account by graph.account.accountName.collectAsState()
    val localGames by graph.localGames.games.collectAsState()
    val layout by graph.settings.layout.collectAsState()
    val sort by graph.settings.sort.collectAsState()
    val steamCollapsed by graph.settings.steamCollapsed.collectAsState()
    val notInstalledCollapsed by graph.settings.notInstalledCollapsed.collectAsState()
    val otherCollapsed by graph.settings.otherCollapsed.collectAsState()
    @Suppress("UNUSED_VARIABLE") val configsVersion by graph.steamConfigs.changes.collectAsState()
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importing = true
            try { val id = graph.localGames.import(uri); graph.localGames.refresh(); nav.push(Screen.LocalGame(id)) }
            catch (e: Exception) { graph.library.error.value = "This file could not be added: ${e.message}" }
            importing = false
        }
    }

    // The two lists by App ID, filtered by the search text.
    val text = search.trim()
    val byID = LinkedHashMap<Int, SteamItem>()
    for (g in owned) byID[g.id] = SteamItem(g.id, g.name, null, g)
    for (g in installed) byID[g.appID] = byID[g.appID]?.copy(installed = g) ?: SteamItem(g.appID, g.name, g, null)
    val items = byID.values.filter { text.isEmpty() || it.name.contains(text, ignoreCase = true) }.sortedBy { it.name.lowercase() }
    val downloading = items.filter { it.installed == null && downloads.containsKey(it.id) }
    fun lastPlayed(it: SteamItem) = maxOf(graph.steamConfigs.get(it.id).lastPlayed / 1000, playtime[it.id]?.lastPlayed ?: 0L)
    val installedItems = items.filter { it.installed != null }.let { list ->
        when (sort) {
            "name" -> list
            "added" -> list.sortedByDescending { graph.steamConfigs.get(it.id).added }
            "size" -> list.sortedByDescending { it.installed?.sizeOnDisk ?: 0L }
            else -> list.sortedByDescending { lastPlayed(it) }
        }
    }
    val notInstalled = items.filter { it.installed == null && !downloads.containsKey(it.id) }
    val others = localGames.filter { text.isEmpty() || it.title.contains(text, ignoreCase = true) }.let { list ->
        when (sort) {
            "name" -> list.sortedBy { it.title.lowercase() }
            "added" -> list.sortedByDescending { it.config.added }
            "size" -> list.sortedByDescending { it.config.sizeBytes }
            else -> list.sortedByDescending { it.config.lastPlayed }
        }
    }
    val undecided = cloud.filter { it.value.conflicts.isNotEmpty() }.keys
    val owner: (Int) -> OwnedGame? = { id -> owned.firstOrNull { it.id == id } }

    val list = layout == "list" || layout == "compactList"
    val columns = if (list) GridCells.Fixed(1) else GridCells.Adaptive(if (layout == "compact") 100.dp else 150.dp)

    Column(Modifier.fillMaxSize().background(colors.background)) {
        // Title row: the large title on the row of the bar buttons, as Madeira's LibraryLargeTitle.
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = Gutter, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pocket Arcade", style = MaterialTheme.typography.displaySmall, color = colors.label, modifier = Modifier.weight(1f), maxLines = 1)
            GlassIconButton(Icons.Filled.Search, "Search", { searching = !searching; if (!searching) search = "" })
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Filled.Add, "Add a game", { picker.launch(arrayOf("*/*")) })
            Spacer(Modifier.width(8.dp))
            Box {
                GlassIconButton(Icons.Filled.MoreHoriz, "Layout and sorting", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text("Layout", style = MaterialTheme.typography.labelMedium, color = colors.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    for ((value, label) in listOf("cards" to "Cards", "compact" to "Compact", "list" to "List", "compactList" to "Compact list")) {
                        DropdownMenuItem(text = { Text(label) }, onClick = { graph.settings.setLayout(value); menu = false },
                            trailingIcon = { if (layout == value) Icon(Icons.Filled.Check, null) })
                    }
                    HorizontalDivider()
                    Text("Sort by", style = MaterialTheme.typography.labelMedium, color = colors.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    for ((value, label) in listOf("played" to "Recently played", "name" to "Name", "added" to "Recently added", "size" to "Size")) {
                        DropdownMenuItem(text = { Text(label) }, onClick = { graph.settings.setSort(value); menu = false },
                            trailingIcon = { if (sort == value) Icon(Icons.Filled.Check, null) })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Filled.Settings, "Settings", { nav.push(Screen.Settings) })
        }
        AnimatedVisibility(searching) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 6.dp).height(40.dp).glass(colors, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = colors.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (search.isEmpty()) Text("Search", color = colors.tertiary, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(search, { search = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.label),
                        cursorBrush = SolidColor(colors.accent), modifier = Modifier.fillMaxWidth())
                }
                if (search.isNotEmpty()) Icon(Icons.Filled.Close, "Clear", tint = colors.secondary, modifier = Modifier.size(18.dp).clickable { search = "" })
            }
        }
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { graph.library.refresh(); graph.localGames.refresh() } }, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = columns,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp + 48.dp),
                horizontalArrangement = Arrangement.spacedBy(if (list) 0.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(if (list) 2.dp else 18.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        DesktopButton { startDesktop(graph, context) }
                        if (importing) { Spacer(Modifier.width(12.dp)); Text("Adding…", color = colors.secondary) }
                    }
                }
                val steamCount = downloading.size + installedItems.size
                if (steamCount > 0 || account != null) {
                    header(this, "Steam", steamCount, steamCollapsed) { graph.settings.setSteamCollapsed(!steamCollapsed) }
                    if (undecided.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.orange.copy(alpha = 0.15f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Sync, null, tint = colors.orange)
                            Spacer(Modifier.width(10.dp))
                            Text("Steam Cloud saves need your choice: " + undecided.mapNotNull { id -> byID[id]?.name }.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = colors.label)
                        }
                    }
                    if (!steamCollapsed) {
                        if (steamCount == 0 && account != null) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(if (refreshing) "Loading your Steam library…" else "No Steam games installed yet. Download one from Not installed below.",
                                style = MaterialTheme.typography.bodyMedium, color = colors.secondary, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        steamCells(this, downloading + installedItems, list, layout == "compactList", graph, downloads, owner, nav)
                    }
                }
                if (account == null) item(span = { GridItemSpan(maxLineSpan) }) { SignInCard { nav.push(Screen.SignIn) } }
                if (notInstalled.isNotEmpty()) {
                    header(this, "Not installed", notInstalled.size, notInstalledCollapsed) { graph.settings.setNotInstalledCollapsed(!notInstalledCollapsed) }
                    if (!notInstalledCollapsed) steamCells(this, notInstalled, list, layout == "compactList", graph, downloads, owner, nav)
                }
                header(this, "Other games", others.size, otherCollapsed) { graph.settings.setOtherCollapsed(!otherCollapsed) }
                if (!otherCollapsed) {
                    items(others, key = { "local-" + it.id }) { g ->
                        val badges = listOfNotNull(g.config.bits.takeIf { it == 32 || it == 64 }?.let { "$it-bit" }, PeInspector.badge(g.config.apis.toSet()),
                            g.config.sizeBytes.takeIf { it > 0 }?.let { formatBytes(it) })
                        if (list) GameRow(g.title, emptyList(), g.cover, false, badges, null, layout == "compactList") { nav.push(Screen.LocalGame(g.id)) }
                        else GameCard(g.title, emptyList(), g.cover, g.id.hashCode(), false, badges, null, { nav.push(Screen.LocalGame(g.id)) }, showTitle = layout != "compact")
                    }
                    if (others.isEmpty() && text.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.fill).clickable { picker.launch(arrayOf("*/*")) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Add, null, tint = colors.accent)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Add a Windows game", style = MaterialTheme.typography.titleSmall, color = colors.label)
                                Text("An EXE, an installer (EXE or MSI) or a game folder as a ZIP. Each game gets its own Windows environment.",
                                    style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun header(scope: LazyGridScope, title: String, count: Int, collapsed: Boolean, toggle: () -> Unit) {
    scope.item(span = { GridItemSpan(maxLineSpan) }, key = "header-$title") { SectionHeader(title, count, collapsed, toggle) }
}

private fun steamCells(scope: LazyGridScope, list: List<SteamItem>, rows: Boolean, dense: Boolean, graph: AppGraph, downloads: Map<Int, Download>,
                       owner: (Int) -> OwnedGame?, nav: Navigator) {
    scope.items(list, key = { "steam-" + it.id + (if (it.installed != null) "-i" else "") }) { item ->
        val config = graph.steamConfigs.get(item.id)
        val download = downloads[item.id]
        val badges = steamBadges(item, config, graph.library.updateAvailable(item.id), download)
        val progress = download?.takeIf { it.state == Download.State.Active || it.state == Download.State.Paused }?.progress?.fraction?.toFloat()
        val urls = SteamArtwork.capsules(item.id, owner)
        val dimmed = item.installed == null
        if (rows) GameRow(item.name, urls, null, dimmed, badges, progress, dense) { nav.push(Screen.SteamGame(item.id)) }
        else GameCard(item.name, urls, null, item.id, dimmed, badges, progress, { nav.push(Screen.SteamGame(item.id)) })
    }
}

@Composable
fun SignInCard(onClick: () -> Unit) {
    val colors = Madeira.colors
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).background(colors.fill).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, null, tint = colors.accent)
            Spacer(Modifier.width(10.dp))
            Text("Your Steam library", style = MaterialTheme.typography.titleMedium, color = colors.label)
        }
        Spacer(Modifier.height(6.dp))
        Text("Sign in to Steam to see the games you own, download them here and play them through Valve's own client.",
            style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = colors.accent), shape = RoundedCornerShape(50)) {
            Text("Sign in to Steam", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Wine's desktop (explorer) in the Steam prefix: Valve's client folders, installers, anything by hand. */
fun startDesktop(graph: AppGraph, context: android.content.Context) {
    graph.prefix.ensure()
    org.pocketarcade.madeira.Launcher.start(context, org.pocketarcade.madeira.session.LaunchSpec(
        home = graph.prefix.home.absolutePath, command = "explorer.exe", workingDir = graph.prefix.drive.absolutePath,
        title = "Desktop", graphics = "dxvk", touchControls = false, diagnostics = graph.settings.diagnostics.value,
        environment = emptyMap()))
}


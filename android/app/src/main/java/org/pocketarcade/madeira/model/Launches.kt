// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Play: what a library game's start needs and why it may not start yet
// (SteamGamesRules.blocker, SteamDirectStart), and the session it becomes.

package org.pocketarcade.madeira.model

import org.pocketarcade.madeira.AppGraph
import org.pocketarcade.madeira.GameStore
import org.pocketarcade.madeira.core.DirectStart
import org.pocketarcade.madeira.core.DockException
import org.pocketarcade.madeira.session.LaunchSpec
import java.io.File

object Launches {
    /** Why Play is not offered yet, or null. Valve's client still decides at launch. */
    fun steamBlocker(graph: AppGraph, appID: Int): String? {
        val g = graph.library.installedGame(appID) ?: return "Download the game first."
        if (!g.installed) return "Steam does not list this game as fully installed yet."
        if (graph.library.downloads.value.containsKey(appID)) return "This game is being downloaded. Play is available when it is done."
        val config = graph.steamConfigs.get(appID)
        if (config.startWith == "game") {
            val root = File(graph.prefix.drive, "${g.library}/common/${g.installDir}")
            if (DirectStart.program(config.program, graph.library.game(appID)?.launches, root).isNullOrEmpty()) return "Choose the program to start below, under Start with."
            return null
        }
        if (!graph.prefix.clientInstalled) return "Madeira Dock needs Valve's client components. Download them in Settings › Steam › Madeira Dock."
        if (!graph.account.signedIn) return "Sign in to Steam to play."
        if (!graph.dock.bundled(graph.runtime.root()) && graph.runtime.bundled()) return "This build has no Madeira Dock host."
        return null
    }

    private fun quote(path: String) = "\"" + path + "\""

    /** The session for a Steam game, through Madeira Dock or its own program. */
    suspend fun steam(graph: AppGraph, appID: Int): LaunchSpec {
        val g = graph.library.installedGame(appID) ?: throw DockException("The game is not installed.")
        val config = graph.steamConfigs.get(appID)
        val name = graph.library.game(appID)?.name ?: g.name
        val root = File(graph.prefix.drive, "${g.library}/common/${g.installDir}")
        val extra = GameConfig.parseEnvironment(config.environment)
        graph.prefix.ensure()
        val spec = if (config.startWith == "game") {
            val options = graph.library.launchOptions(appID)
            val choice = config.program?.let { p -> DirectStart.onDisk(p, root, false)?.let { DirectStart.Choice(it, config.arguments, null) } }
                ?: options?.let { DirectStart.choose(it, root) }
                ?: DirectStart.programs(root).singleOrNull()?.let { DirectStart.Choice(it, config.arguments, null) }
                ?: throw DockException("Choose the program to start under Start with.")
            val program = File(root, choice.program)
            val folder = when (choice.folder) { null -> program.parentFile ?: root; "" -> root; else -> File(root, choice.folder) }
            val args = listOf(choice.arguments, config.arguments.takeIf { config.program == null } ?: "").filter { it.isNotBlank() }.distinct().joinToString(" ")
            LaunchSpec(graph.prefix.home.absolutePath, quote(LaunchSpec.windowsPath(program)) + (if (args.isEmpty()) "" else " $args"),
                folder.absolutePath, name, graphics = config.resolvedGraphics(), environment = extra, dllOverrides = config.dllOverrides,
                touchControls = config.touchControls, controlLayout = config.controlLayout, performance = config.performance,
                binds = config.binds, stickMouse = config.stickMouse, steamAppID = appID, diagnostics = graph.settings.diagnostics.value)
        } else {
            val options = graph.library.launchOptions(appID)
            val (command, env) = graph.dock.prepareLaunch(g, graph.dock.launchOption(options, g), name, graph.mono.file.takeIf { it.isFile })
            LaunchSpec(graph.prefix.home.absolutePath, command, graph.prefix.drive.absolutePath, name, graphics = config.resolvedGraphics(),
                environment = env + extra, dllOverrides = config.dllOverrides, touchControls = config.touchControls,
                controlLayout = config.controlLayout, performance = config.performance, binds = config.binds, stickMouse = config.stickMouse,
                steamAppID = appID, dockReport = graph.dock.reportFile.absolutePath, dockInstallerResults = graph.dock.installerResults.absolutePath,
                diagnostics = graph.settings.diagnostics.value)
        }
        graph.steamConfigs.update(appID) { it.copy(lastPlayed = System.currentTimeMillis()) }
        return spec
    }

    /** The session for a game added by hand, in its own prefix. */
    suspend fun local(graph: AppGraph, game: LocalGame): LaunchSpec {
        val exe = File(game.exe)
        if (!exe.isFile) throw DockException("The game's program is missing. Choose another one under Program.")
        val command = GameStore.command(exe) + (if (game.config.arguments.isBlank()) "" else " " + game.config.arguments.trim())
        graph.localGames.update(game.id) { it.copy(config = it.config.copy(lastPlayed = System.currentTimeMillis())) }
        return LaunchSpec(graph.localGames.directory(game.id).absolutePath, command, exe.parentFile?.absolutePath ?: "", game.title,
            graphics = game.config.resolvedGraphics(), environment = GameConfig.parseEnvironment(game.config.environment),
            dllOverrides = game.config.dllOverrides, touchControls = game.config.touchControls, controlLayout = game.config.controlLayout,
            performance = game.config.performance, binds = game.config.binds, stickMouse = game.config.stickMouse,
            diagnostics = graph.settings.diagnostics.value)
    }

    /** Runs an installer (EXE or MSI) in a game's prefix, for runtimes the game needs. */
    fun installer(graph: AppGraph, home: File, installer: File, title: String): LaunchSpec =
        LaunchSpec(home.absolutePath, GameStore.command(installer), installer.parentFile?.absolutePath ?: home.absolutePath, title,
            touchControls = false, diagnostics = graph.settings.diagnostics.value)
}

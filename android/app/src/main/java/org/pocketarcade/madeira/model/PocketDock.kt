// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.model

import android.content.Context
import org.pocketarcade.madeira.AppGraph
import org.pocketarcade.madeira.core.DirectStart
import org.pocketarcade.madeira.epic.LinuxProton
import org.pocketarcade.madeira.session.CommandLine
import java.io.File

/** Android-side Steam library launcher using the already bundled Linux Proton stack. */
object PocketDock {
    private const val PREFS = "pocket-dock"
    fun display(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("display", ":0") ?: ":0"
    fun setDisplay(context: Context, value: String) {
        val text = value.trim()
        require(text.isNotEmpty() && text.length <= 128 && !text.contains('\u0000')) { "Enter an X11 display, such as :0" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("display", text).apply()
    }

    suspend fun prepare(graph: AppGraph, appID: Int): LinuxProton.Plan {
        require(graph.account.signedIn) { "Sign in to Steam before starting a Steam game" }
        val installed = graph.library.installedGame(appID) ?: error("Download the Steam game first")
        require(installed.installed) { "Finish downloading the Steam game first" }
        val info = graph.library.game(appID)
        val config = graph.steamConfigs.get(appID)
        val folder = File(graph.prefix.drive, "${installed.library}/common/${installed.installDir}")
        val options = graph.library.launchOptions(appID)
        val choice = config.program?.let { p ->
            DirectStart.onDisk(p, folder, false)?.let { DirectStart.Choice(it, config.arguments, null) }
        } ?: options?.let { DirectStart.choose(it, folder) }
            ?: DirectStart.programs(folder).singleOrNull()?.let { DirectStart.Choice(it, config.arguments, null) }
            ?: error("Choose this game's Windows program under Start with")
        val exe = File(folder, choice.program)
        val args = CommandLine.split(choice.arguments) +
            (if (config.program == null) CommandLine.split(config.arguments) else emptyList())
        val state = File(graph.context.filesDir, "steam-proton/$appID")
        val plan = LinuxProton.prepare(graph.context, folder, exe, info?.name ?: installed.name,
            args, display(graph.context), needsEac = false, stateDir = state)
        return plan.copy(environment = plan.environment + mapOf(
            "SteamAppId" to appID.toString(), "SteamGameId" to appID.toString()))
    }
}

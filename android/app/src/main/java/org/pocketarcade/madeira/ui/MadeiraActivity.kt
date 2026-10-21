// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// The app: setup on a new install, then the library, with game pages, Steam
// sign-in and Settings above it (ContentView.swift's library interface).

package org.pocketarcade.madeira.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.pocketarcade.madeira.AppGraph
import org.pocketarcade.madeira.session.SessionState

/** What the app shows above the library. */
sealed class Screen {
    data class SteamGame(val appID: Int) : Screen()
    data class LocalGame(val id: String) : Screen()
    object Settings : Screen()
    object SignIn : Screen()
    object Onboarding : Screen()
    object Dock : Screen()
    object Logs : Screen()
    object Licenses : Screen()
}

/** A simple back stack for the screens above the library. */
class Navigator(initial: List<Screen>) {
    val stack = mutableStateListOf<Screen>().apply { addAll(initial) }
    val top: Screen? get() = stack.lastOrNull()
    fun push(s: Screen) { stack += s }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
    fun replace(s: Screen) { pop(); push(s) }
    fun popTo(predicate: (Screen) -> Boolean) { while (stack.isNotEmpty() && !predicate(stack.last())) pop() }
}

class MadeiraActivity : ComponentActivity() {
    private val graph get() = AppGraph.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The app process can be killed while a game runs in :wine. Coming back, Steam must stay
        // logged off: Valve's client in the game session is using the account.
        if (SessionState.sessionRunning(this)) graph.library.adoptRunningSession()
        graph.library.start()
        lifecycleScope.launch { graph.localGames.refresh() }
        if (!SessionState.sessionRunning(this)) graph.dock.cleanup()
        setContent {
            val theme by graph.settings.theme.collectAsState()
            val metal by graph.settings.liquidMetal.collectAsState()
            val ambient by graph.settings.ambient.collectAsState()
            MadeiraTheme(theme, metal, ambient) { App() }
        }
    }

    override fun onResume() {
        super.onResume()
        // A game session that ended while the app was away: reopen Steam, sync saves, refresh playtime.
        val info = SessionState.current(this)
        if (info != null && !SessionState.sessionRunning(this)) {
            SessionState.clear(this)
            graph.dock.cleanup()
            graph.library.sessionEnded(info.steamAppID.takeIf { it != 0 })
            lifecycleScope.launch { graph.localGames.refresh() }
        } else if (info == null && graph.library.sessionActive && !SessionState.sessionRunning(this)) {
            graph.library.sessionEnded(null)
        }
        graph.dock.refresh()
        lifecycleScope.launch { graph.library.rescanInstalled() }
    }

    @Composable
    private fun App() {
        val onboardingNeeded = remember { graph.settings.onboardingRevision < OnboardingRevision }
        val nav = remember { Navigator(if (onboardingNeeded) listOf(Screen.Onboarding) else emptyList()) }
        val error by graph.library.error.collectAsState()
        val colors = Madeira.colors
        Box(Modifier.fillMaxSize().background(colors.background)) {
            LibraryScreen(graph, nav)
            AnimatedContent(
                targetState = nav.top,
                transitionSpec = { (slideInVertically { it / 6 } + fadeIn()) togetherWith (slideOutVertically { it / 6 } + fadeOut()) },
                label = "screens",
            ) { screen ->
                when (screen) {
                    null -> Box(Modifier)
                    is Screen.SteamGame -> SteamGameScreen(graph, nav, screen.appID)
                    is Screen.LocalGame -> LocalGameScreen(graph, nav, screen.id)
                    Screen.Settings -> SettingsScreen(graph, nav)
                    Screen.SignIn -> SignInScreen(graph, nav)
                    Screen.Onboarding -> OnboardingScreen(graph, nav)
                    Screen.Dock -> DockScreen(graph, nav)
                    Screen.Logs -> LogScreen(graph, nav)
                    Screen.Licenses -> LicensesScreen(nav)
                }
            }
        }
        BackHandler(enabled = nav.top != null) { nav.pop() }
        error?.let { message ->
            AlertDialog(
                onDismissRequest = { graph.library.error.value = null },
                confirmButton = { TextButton(onClick = { graph.library.error.value = null }) { Text("OK") } },
                title = { Text("Steam") },
                text = { Text(message) },
            )
        }
    }
}

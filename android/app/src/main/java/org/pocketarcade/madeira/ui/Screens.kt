// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Jfishin, 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Steam sign-in (SteamSignInView), setup on a new install (OnboardingView),
// Settings, Madeira Dock's components, logs and licences.

package org.pocketarcade.madeira.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pocketarcade.madeira.AppGraph
import org.pocketarcade.madeira.AppLog
import org.pocketarcade.madeira.BuildConfig
import org.pocketarcade.madeira.core.SteamRuntimeFiles
import org.pocketarcade.madeira.model.DockModel
import org.pocketarcade.madeira.model.WineMono
import org.pocketarcade.madeira.steam.SteamGuardType
import java.io.File
import java.io.RandomAccessFile

/** Bumped when setup gains a step every device should see once. */
const val OnboardingRevision = 1

/** A page with a back button and a large title, its content scrolling below. */
@Composable
fun PageFrame(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val colors = Madeira.colors
    Column(Modifier.fillMaxSize().background(colors.grouped).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).glass(colors, CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.label)
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.label, modifier = Modifier.padding(horizontal = Gutter))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun Paragraph(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Madeira.colors.secondary, modifier = modifier.padding(horizontal = Gutter, vertical = 6.dp))
}

// MARK: Steam sign-in

/** Password with Steam Guard, or a QR code for the Steam Mobile app. */
@Composable
fun SignInForm(graph: AppGraph) {
    val colors = Madeira.colors
    val state by graph.account.state.collectAsState()
    var mode by rememberSaveable { mutableStateOf("password") }
    var name by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(mode) { if (mode == "qr") graph.account.beginQR() else if (graph.account.state.value.qrUrl != null) graph.account.cancel() }
    DisposableEffect(Unit) { onDispose { if (!graph.account.signedIn) graph.account.cancel() } }

    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            SegmentedButton(selected = mode == "password", onClick = { mode = "password" }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Password") }
            SegmentedButton(selected = mode == "qr", onClick = { mode = "qr" }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("QR code") }
        }
        Spacer(Modifier.height(8.dp))
        val guard = state.guard
        if (mode == "qr") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val url = state.qrUrl
                Box(Modifier.size(240.dp).background(Color.White, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                    if (url != null) QrCode(url, Modifier.size(228.dp)) else CircularProgressIndicator(color = colors.accent)
                }
                Spacer(Modifier.height(12.dp))
                Text("In the Steam Mobile app, open Steam Guard and scan this code to sign in. The code renews itself while this page is open.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.secondary, textAlign = TextAlign.Center)
            }
        } else if (guard != null) {
            Text(when (guard.codeType) {
                SteamGuardType.EMAIL -> "Steam sent a code to your email" + (if (guard.hint.isNotEmpty()) " at ${guard.hint}" else "") + "."
                SteamGuardType.DEVICE -> "Enter the code from Steam Guard in the Steam Mobile app."
                null -> "Approve this sign-in in the Steam Mobile app."
            }, style = MaterialTheme.typography.bodyLarge, color = colors.label)
            if (guard.canApprove && guard.codeType != null) Text("Or approve it in the Steam Mobile app; this page continues by itself.", style = MaterialTheme.typography.bodySmall, color = colors.secondary)
            if (guard.codeType != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) }, label = { Text("Steam Guard code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Continue", null, enabled = code.length >= 5, busy = state.busy) { graph.account.submitGuardCode(code) }
            } else {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = colors.accent)
            }
            TextButton(onClick = { graph.account.cancel(); code = "" }) { Text("Cancel") }
        } else {
            OutlinedTextField(name, { name = it }, label = { Text("Steam account name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(secret, { secret = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Sign in", Icons.AutoMirrored.Filled.Login, enabled = name.isNotBlank() && secret.isNotEmpty(), busy = state.busy) { graph.account.signIn(name, secret); secret = "" }
            Spacer(Modifier.height(8.dp))
            Text("Your password goes to Steam encrypted with Steam's key. This device keeps only the sign-in token Steam returns, protected by Android's keystore.",
                style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.red)
        }
    }
}

@Composable
fun SignInScreen(graph: AppGraph, nav: Navigator) {
    val accountName by graph.account.accountName.collectAsState()
    val signedInAtOpen = remember { accountName != null }
    LaunchedEffect(accountName) { if (accountName != null && !signedInAtOpen) nav.pop() }
    PageFrame("Sign in to Steam", { nav.pop() }) {
        Paragraph("Sign in to see your Steam library, download your games and keep saves in Steam Cloud.")
        SignInForm(graph)
    }
}

// MARK: Setup

@Composable
fun OnboardingScreen(graph: AppGraph, nav: Navigator) {
    val colors = Madeira.colors
    var page by rememberSaveable { mutableIntStateOf(0) }
    val accountName by graph.account.accountName.collectAsState()
    val components by graph.dock.components.collectAsState()
    val mono by graph.mono.state.collectAsState()
    fun finish() { graph.settings.onboardingRevision = OnboardingRevision; nav.popTo { it !is Screen.Onboarding } }
    val last = 4

    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (page == 0) LiquidMetal(Modifier.fillMaxSize()) {}
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) TextButton(onClick = { page-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (page < last) TextButton(onClick = { finish() }) { Text("Skip setup") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                when (page) {
                    0 -> Column(Modifier.fillMaxWidth().padding(Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(96.dp).glass(colors, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.SportsEsports, null, tint = Color.White, modifier = Modifier.size(52.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Pocket Arcade", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Built on Madeira", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.height(8.dp))
                        Text("Your Windows games, on Android. Sign in to Steam, download your library, and play with touch controls or a controller.",
                            style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                    }
                    1 -> Column {
                        Title("Steam")
                        if (accountName != null) Paragraph("Signed in as $accountName.")
                        else { Paragraph("Sign in to see your library and download games. You can also do this later from the library."); SignInForm(graph) }
                    }
                    2 -> Column {
                        Title("Madeira Dock")
                        Paragraph("Steam games start through Valve's own Steam client, so Steam's licence checks, DRM and overlay-free sign-in work as they do on a PC. " +
                            "Madeira Dock downloads Valve's client components (about ${formatBytes(SteamRuntimeFiles.totalBytes)}) straight from Valve and checks each one.")
                        ComponentsCard(graph, components)
                    }
                    3 -> Column {
                        Title(".NET Framework")
                        Paragraph("Games built on .NET or XNA need Wine Mono, Wine's .NET Framework (about 80 MB from WineHQ). Most games do not; you can add it later in Settings.")
                        MonoCard(graph, mono)
                    }
                    else -> Column(Modifier.fillMaxWidth().padding(Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.CheckCircle, null, tint = colors.green, modifier = Modifier.size(72.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("You're set", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.label)
                        Spacer(Modifier.height(8.dp))
                        Text("Games you download appear in your library. Pull down to refresh it, and open a game to change how it runs.",
                            style = MaterialTheme.typography.bodyLarge, color = colors.secondary, textAlign = TextAlign.Center)
                    }
                }
            }
            Box(Modifier.padding(Gutter)) {
                PrimaryButton(when (page) { 0 -> "Get started"; last -> "Open library"; else -> "Continue" }, null) {
                    if (page == last) finish() else page++
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Madeira.colors.label, modifier = Modifier.padding(horizontal = Gutter, vertical = 8.dp))
}

@Composable
private fun ComponentsCard(graph: AppGraph, state: DockModel.Components) {
    val colors = Madeira.colors
    SettingsSection {
        when (state) {
            DockModel.Components.Ready -> SettingsRow("Valve's client components", "Installed", icon = Icons.Filled.CheckCircle, tint = null)
            DockModel.Components.Missing -> SettingsRow("Download Valve's client components", formatBytes(SteamRuntimeFiles.totalBytes), icon = Icons.Filled.FileDownload, tint = colors.accent) {
                graph.scope.launch { graph.dock.installComponents() }
            }
            is DockModel.Components.Downloading -> Column(Modifier.padding(16.dp)) {
                Text(state.text, style = MaterialTheme.typography.bodyMedium, color = colors.label)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            }
            is DockModel.Components.Failed -> {
                SettingsRow(state.message, tint = colors.red)
                RowDivider()
                SettingsRow("Try again", tint = colors.accent) { graph.scope.launch { graph.dock.installComponents() } }
            }
        }
    }
}

@Composable
private fun MonoCard(graph: AppGraph, state: WineMono.State) {
    val colors = Madeira.colors
    SettingsSection {
        when (state) {
            WineMono.State.Ready -> SettingsRow("Wine Mono ${graph.mono.version}", "Downloaded", icon = Icons.Filled.CheckCircle)
            WineMono.State.Missing -> SettingsRow("Download Wine Mono ${graph.mono.version}", icon = Icons.Filled.FileDownload, tint = colors.accent) { graph.scope.launch { graph.mono.download() } }
            is WineMono.State.Downloading -> Column(Modifier.padding(16.dp)) {
                Text("Downloading Wine Mono…", style = MaterialTheme.typography.bodyMedium, color = colors.label)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            }
            is WineMono.State.Failed -> {
                SettingsRow(state.message, tint = colors.red)
                RowDivider()
                SettingsRow("Try again", tint = colors.accent) { graph.scope.launch { graph.mono.download() } }
            }
        }
    }
}

// MARK: Settings

@Composable
fun SettingsScreen(graph: AppGraph, nav: Navigator) {
    val colors = Madeira.colors
    val context = LocalContext.current
    val s = graph.settings
    val accountName by graph.account.accountName.collectAsState()
    val components by graph.dock.components.collectAsState()
    val mono by graph.mono.state.collectAsState()
    val cloud by s.steamCloud.collectAsState()
    val cloudAuto by s.cloudAutomatic.collectAsState()
    val cloudCheck by s.cloudPlayCheck.collectAsState()
    val theme by s.theme.collectAsState()
    val metal by s.liquidMetal.collectAsState()
    val ambient by s.ambient.collectAsState()
    val diagnostics by s.diagnostics.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }

    PageFrame("Settings", { nav.pop() }) {
        SettingsSection("Steam") {
            if (accountName != null) {
                SettingsRow("Signed in", accountName)
                RowDivider()
                SettingsRow("Sign out", tint = colors.red) { confirmSignOut = true }
            } else SettingsRow("Sign in to Steam", icon = Icons.AutoMirrored.Filled.Login, tint = colors.accent) { nav.push(Screen.SignIn) }
            RowDivider()
            SettingsRow("Madeira Dock", when (components) {
                DockModel.Components.Ready -> "Ready"; DockModel.Components.Missing -> "Not downloaded"
                is DockModel.Components.Downloading -> "Downloading"; is DockModel.Components.Failed -> "Failed"
            }, chevron = true) { nav.push(Screen.Dock) }
        }
        SettingsSection("Steam Cloud", footer = "Saves sync before a game starts and after it ends. Replaced copies are kept in the app's Steam Cloud Backups folder.") {
            ToggleRow("Steam Cloud", cloud) { s.setSteamCloud(it) }
            RowDivider()
            ToggleRow("Sync automatically", cloudAuto, enabled = cloud) { s.setCloudAutomatic(it) }
            RowDivider()
            ToggleRow("Check saves before playing", cloudCheck, "Asks before a game starts while saves differ or still sync.", enabled = cloud) { s.setCloudPlayCheck(it) }
        }
        SettingsSection(".NET Framework", footer = "Wine Mono runs .NET and XNA games. Wine installs it into a game's Windows environment in its next session.") {
            when (val m = mono) {
                WineMono.State.Ready -> { SettingsRow("Wine Mono ${graph.mono.version}", "Downloaded"); RowDivider(); SettingsRow("Remove", tint = colors.red) { graph.mono.remove() } }
                WineMono.State.Missing -> SettingsRow("Download Wine Mono ${graph.mono.version}", icon = Icons.Filled.FileDownload, tint = colors.accent) { graph.scope.launch { graph.mono.download() } }
                is WineMono.State.Downloading -> Column(Modifier.padding(16.dp)) {
                    Text("Downloading Wine Mono…", color = colors.label)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { m.fraction }, modifier = Modifier.fillMaxWidth())
                }
                is WineMono.State.Failed -> SettingsRow(m.message, tint = colors.red) { graph.scope.launch { graph.mono.download() } }
            }
        }
        SettingsSection("Appearance") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                val options = listOf("system" to "System", "dark" to "Dark", "light" to "Light")
                options.forEachIndexed { i, (v, label) ->
                    SegmentedButton(selected = theme == v, onClick = { s.setTheme(v) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
                }
            }
            RowDivider()
            ToggleRow("Liquid Metal", metal, "The animated metal finish behind the library's title.") { s.setLiquidMetal(it) }
            RowDivider()
            ToggleRow("Ambient light", ambient, "Game artwork glows softly behind its card.") { s.setAmbient(it) }
        }
        SettingsSection("Troubleshooting") {
            SettingsRow("Windows desktop", subtitle = "Opens Explorer in the Steam games' Windows environment.", icon = Icons.Filled.DesktopWindows) { startDesktop(graph, context) }
            RowDivider()
            ToggleRow("Diagnostics", diagnostics, "More detail in the game log, for reporting problems. Slower.") { s.setDiagnostics(it) }
            RowDivider()
            SettingsRow("Logs", chevron = true) { nav.push(Screen.Logs) }
            RowDivider()
            SettingsRow("Run setup again", chevron = true) { nav.push(Screen.Onboarding) }
            RowDivider()
            SettingsRow("Developer tools", subtitle = "The runtime test interface: smoke tests, imports and raw sessions.", chevron = true) {
                context.startActivity(Intent(context, org.pocketarcade.madeira.MainActivity::class.java))
            }
        }
        SettingsSection("About", footer = "Pocket Arcade is free software under the GNU GPL, version 3 or later, built on Madeira by Jfishin and 125hz. Steam is a trademark of Valve; this app is not affiliated with Valve.") {
            SettingsRow("Version", BuildConfig.VERSION_NAME)
            RowDivider()
            SettingsRow("Wine", if (BuildConfig.WINE_VARIANT == "madeira") "Madeira Wine" else "Proton Wine")
            RowDivider()
            SettingsRow("Licences", chevron = true) { nav.push(Screen.Licenses) }
        }
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("Sign out of Steam?") },
        text = { Text("Installed games stay on this device. Sign in again to play them through Madeira Dock.") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; graph.account.signOut() }) { Text("Sign out", color = colors.red) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } })
}

// MARK: Madeira Dock

@Composable
fun DockScreen(graph: AppGraph, nav: Navigator) {
    val colors = Madeira.colors
    val components by graph.dock.components.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    val hostBundled = remember { graph.dock.bundled(graph.runtime.root()) || !graph.runtime.bundled() }
    PageFrame("Madeira Dock", { nav.pop() }) {
        Paragraph("Madeira Dock starts Steam games through Valve's Steam client, signed in with your account, without showing Steam's window. " +
            "Games get Steam's licence check, DRM, achievements and Steamworks as they would on a PC.")
        ComponentsCard(graph, components)
        SettingsSection(footer = "Valve's client components come from client-update.akamai.steamstatic.com and are checked against the SHA-256 Madeira pins. They are Valve's software, under Valve's terms.") {
            SettingsRow("Download size", formatBytes(SteamRuntimeFiles.totalBytes))
            RowDivider()
            SettingsRow("Dock host", if (hostBundled) "Included" else "Missing from this build", tint = if (hostBundled) null else colors.red)
            if (components == DockModel.Components.Ready) {
                RowDivider()
                SettingsRow("Remove components", tint = colors.red) { confirmRemove = true }
            }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("Remove Valve's client components?") },
        text = { Text("Steam games will not start through Madeira Dock until you download them again. Installed games are kept.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; graph.scope.launch { graph.dock.removeComponents(); graph.dock.refresh() } }) { Text("Remove", color = colors.red) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
}

// MARK: Logs

private fun tail(file: File, max: Int = 96 * 1024): String? {
    if (!file.isFile) return null
    return try {
        RandomAccessFile(file, "r").use { f ->
            val start = maxOf(0L, f.length() - max)
            f.seek(start)
            val bytes = ByteArray((f.length() - start).toInt())
            f.readFully(bytes)
            String(bytes, Charsets.UTF_8).let { if (start > 0) it.substringAfter('\n') else it }
        }
    } catch (_: Exception) { null }
}

@Composable
fun LogScreen(graph: AppGraph, nav: Navigator) {
    val colors = Madeira.colors
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf("app") }
    var text by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab, refresh) {
        text = withContext(Dispatchers.IO) {
            when (tab) {
                "app" -> AppLog.snapshot().joinToString("\n").ifEmpty { "No events yet." }
                "wine" -> {
                    val games = File(graph.context.filesDir, "games").listFiles().orEmpty().map { File(it, "wine.log") }.filter { it.isFile }
                    val latest = games.maxByOrNull { it.lastModified() }
                    latest?.let { (tail(it) ?: "") .ifEmpty { "The last session wrote no log." } } ?: "No game has run yet."
                }
                else -> listOfNotNull(tail(graph.dock.reportFile)?.let { "C:\\madeira-dock.txt\n$it" }, tail(graph.dock.installerResults)?.let { "One-time installs\n$it" })
                    .joinToString("\n\n").ifEmpty { "Madeira Dock has not run yet." }
            }
        }
    }
    PageFrame("Logs", { nav.pop() }, trailing = {
        Box(Modifier.size(40.dp).glass(colors, CircleShape).clickable {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.takeLast(200_000))
            context.startActivity(Intent.createChooser(send, "Share log"))
        }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Share, "Share", tint = colors.label) }
    }) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp)) {
            val options = listOf("app" to "App", "wine" to "Last game", "dock" to "Dock")
            options.forEachIndexed { i, (v, label) ->
                SegmentedButton(selected = tab == v, onClick = { tab = v; refresh++ }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
            }
        }
        Paragraph("Logs never contain your password, codes or sign-in tokens. Check them before sharing: game logs can contain file names.")
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.label, lineHeight = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp).background(colors.card, RoundedCornerShape(12.dp)).padding(12.dp))
        }
    }
}

// MARK: Licences

private val NOTICES = listOf(
    Triple("Madeira", "GPL-3.0-or-later with the Madeira Converter Exception", "The Steam client, library, downloads, Steam Cloud and Dock, ported from Madeira for iOS (Jfishin, 125hz)."),
    Triple("Madeira Dock host", "See the bundled notice", "dockhost.exe, from willfaust/madeira-dock."),
    Triple("Wine", "LGPL-2.1-or-later", "The Windows compatibility layer (winehq.org)."),
    Triple("DXVK", "zlib", "Direct3D 8–11 on Vulkan (github.com/doitsujin/dxvk)."),
    Triple("VKD3D-Proton", "LGPL-2.1-or-later", "Direct3D 12 on Vulkan (github.com/HansKristian-Work/vkd3d-proton)."),
    Triple("Wine Mono", "MIT and others", "Downloaded from WineHQ on request."),
    Triple("Zstandard", "BSD-3-Clause", "Steam chunk decompression, from Madeira's decoder."),
    Triple("OkHttp", "Apache-2.0", "Steam's connection manager WebSocket."),
    Triple("Coil", "Apache-2.0", "Game artwork."),
    Triple("ZXing", "Apache-2.0", "Sign-in QR codes."),
    Triple("AndroidX and Jetpack Compose", "Apache-2.0", "The interface."),
    Triple("Kotlin and kotlinx.coroutines", "Apache-2.0", ""),
)

@Composable
fun LicensesScreen(nav: Navigator) {
    val context = LocalContext.current
    val bundled = remember {
        try { context.assets.open("NOTICES.txt").use { String(it.readBytes(), Charsets.UTF_8) } } catch (_: Exception) { null }
    }
    PageFrame("Licences", { nav.pop() }) {
        Paragraph("Pocket Arcade is free software: you can redistribute it and modify it under the terms of the GNU General Public License, version 3 or later. " +
            "Its source code is available from the project's repository.")
        SettingsSection {
            NOTICES.forEachIndexed { i, (name, licence, note) ->
                if (i > 0) RowDivider()
                SettingsRow(name, licence, subtitle = note.ifEmpty { null })
            }
        }
        if (bundled != null) SettingsSection("Notices") {
            SelectionContainer { Text(bundled, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(16.dp), color = Madeira.colors.label) }
        }
        Paragraph("Steam and the Steam logo are trademarks of Valve Corporation. Valve's client components are downloaded from Valve on request and are not part of this app.")
    }
}

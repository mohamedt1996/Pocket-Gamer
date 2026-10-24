// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.pocketarcade.madeira.AppGraph
import org.pocketarcade.madeira.epic.EpicEndpoints
import org.pocketarcade.madeira.epic.LinuxProton
import org.pocketarcade.madeira.epic.LinuxProtonInstaller
import org.pocketarcade.madeira.epic.RuntimeComponents

@Composable
fun EpicScreen(graph: AppGraph, nav: Navigator) {
    val model = graph.epic
    val state by model.state.collectAsState()
    val progress by model.progress.collectAsState()
    val downloading by model.downloading.collectAsState()
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var translator by rememberSaveable { mutableStateOf("fex") }
    var cap by rememberSaveable { mutableStateOf("auto") }
    var graphics by rememberSaveable { mutableStateOf("auto") }
    var eacDiagnostics by rememberSaveable { mutableStateOf(false) }
    var protonDisplay by rememberSaveable { mutableStateOf(":0") }
    var pickingComponent by rememberSaveable { mutableStateOf<String?>(null) }
    val protonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) pickingComponent?.let { model.importLinuxComponent(LinuxProtonInstaller.Component.valueOf(it), uri) }
        pickingComponent = null
    }
    val colors = Madeira.colors
    PageFrame("Epic Games", { nav.pop() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.account == null) {
                Text("Sign in on Epic's website", style = MaterialTheme.typography.titleLarge, color = colors.label)
                Text("Open Epic, sign in with your usual method, then paste the authorization code from the page here. Your Epic password never enters Pocket Arcade.", color = colors.secondary)
                Button(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EpicEndpoints.loginUrl))) }
                    catch (_: Exception) { model.reportError("No browser could open Epic sign-in. Install or enable a browser and try again.") }
                }) { Text("Open Epic sign-in") }
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Authorization code or Epic's JSON response") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                Button(enabled = code.isNotBlank() && !state.busy, onClick = { val value = code; code = ""; model.signIn(value) }) { Text("Connect Epic account") }
            } else {
                Text(state.account!!, style = MaterialTheme.typography.titleLarge, color = colors.label)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !state.busy, onClick = model::refresh) { Text("Refresh library") }
                    TextButton(onClick = model::signOut) { Text("Sign out") }
                }
                OutlinedTextField(search, { search = it }, label = { Text("Search Epic library") }, modifier = Modifier.fillMaxWidth())
                Text("32-bit translator", style = MaterialTheme.typography.titleMedium, color = colors.label)
                Text("64-bit games use the bundled FEX. Optional WoWBox64 is experimental and uses its own game prefix.", color = colors.secondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = translator == "fex", onClick = { translator = "fex" }, label = { Text("FEX") })
                    FilterChip(selected = translator == "wowbox64", enabled = RuntimeComponents.available(context),
                        onClick = { translator = "wowbox64" }, label = { Text("WoWBox64 · experimental") })
                }
                TextButton(enabled = !state.busy, onClick = model::installWoWBox64) {
                    Text(if (RuntimeComponents.available(context)) "Repair WoWBox64 component" else "Download existing WoWBox64 component (about 1 MB)")
                }
                Text("Frame cap for Epic launches", style = MaterialTheme.typography.titleMedium, color = colors.label)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    org.pocketarcade.madeira.core.Performance.FRAME_CAPS.forEach { value ->
                        FilterChip(selected = cap == value, onClick = { cap = value }, label = { Text(when(value) { "auto" -> "Auto"; "off" -> "Off"; else -> "$value FPS" }) })
                    }
                }
                Text("Graphics for Epic launches", style = MaterialTheme.typography.titleMedium, color = colors.label)
                Text("For games with a separate anti-cheat launcher, choose the game's graphics backend here. Auto inspects the launch program.", color = colors.secondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Auto", "dxvk" to "DXVK", "vkd3d" to "VKD3D", "wine" to "Wine").forEach { (value, label) ->
                        FilterChip(selected = graphics == value, onClick = { graphics = value }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = eacDiagnostics, onCheckedChange = { eacDiagnostics = it })
                    Text("Capture EAC loader diagnostics", color = colors.label)
                }
                Text("Adds Wine module logging for EAC games. Turn it off for performance tests; the report never includes raw log lines.", color = colors.secondary)
                Text("Linux Proton research", style = MaterialTheme.typography.titleMedium, color = colors.label)
                Text("Stage a complete x86-64 Linux rootfs, Proton distribution and, for EAC games, the official EAC runtime in the app-private folder. An X11 display must be running on the tablet.", color = colors.secondary)
                Text(LinuxProton.root(context).absolutePath, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                val bundledPackages = remember(state.componentRevision) { LinuxProtonInstaller.bundled(context) }
                if (bundledPackages.isNotEmpty()) {
                    Button(enabled = !state.busy, onClick = model::installBundledLinux) {
                        Text("Install included Linux packages")
                    }
                    Text("Included: ${bundledPackages.joinToString { it.component.folder }}", style = MaterialTheme.typography.bodySmall)
                }
                if (bundledPackages.none { it.component == LinuxProtonInstaller.Component.EAC })
                    Text("Proton EasyAntiCheat Runtime is a separate Steam tool. Install it with your Steam account below, or import a copy from a Linux Steam installation.",
                        style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                Button(enabled = !state.busy, onClick = {
                    if (graph.account.signedIn) model.installEacFromSteam() else nav.push(Screen.SignIn)
                }) { Text(if (graph.account.signedIn) "Install EAC runtime from Steam" else "Sign in to Steam to install EAC") }
                state.bundleProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinuxProtonInstaller.Component.entries.forEach { component ->
                        TextButton(enabled = !state.busy, onClick = {
                            pickingComponent = component.name
                            protonPicker.launch(arrayOf("*/*"))
                        }) { Text(if (component == LinuxProtonInstaller.Component.EAC) "Import EAC runtime .tar.gz" else "Import ${component.folder} .tar.gz") }
                    }
                }
                Text("Use complete upstream archives. Imports are stored privately and may need several GB of free space. The Linux display runs in a separate X11 app.",
                    style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                OutlinedTextField(protonDisplay, { protonDisplay = it }, label = { Text("Linux X11 DISPLAY") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !state.busy, onClick = model::installBox64) {
                        Text(if (RuntimeComponents.box64Available(context)) "Repair Android Box64" else "Install Android Box64 (4 KB download / 16 KB bundled)")
                    }
                    if (RuntimeComponents.box64Available(context))
                        TextButton(enabled = !state.busy, onClick = model::probeBox64) { Text("Test Box64 startup") }
                }
                state.box64Probe?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.secondary) }
                if (state.games.isEmpty() && !state.busy) Text("Refresh to load the Windows apps in your Epic library.", color = colors.secondary)
                for (game in state.games.filter { it.title.contains(search, true) || it.app.contains(search, true) }) {
                    val installed = model.installed(game)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(game.title, style = MaterialTheme.typography.titleMedium)
                            if (downloading == game.app) {
                                val p = progress
                                if (p != null && p.total > 0) {
                                    LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    Text("${(p.done * 100 / p.total).coerceIn(0, 100)}% · ${p.file}", style = MaterialTheme.typography.bodySmall)
                                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton(onClick = model::cancelDownload) { Text("Pause") }
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(enabled = downloading == null && !state.busy, onClick = { model.requestDownload(game) }) {
                                        Text(if (installed == null) "Download / resume" else "Verify / update")
                                    }
                                    if (installed != null) Button(enabled = !state.busy && downloading == null && graph.runtime.bundled(),
                                        onClick = { model.play(game, translator, cap, graphics, eacDiagnostics) }) { Text("Play") }
                                    if (installed != null) Button(enabled = !state.busy && downloading == null &&
                                        LinuxProton.staged(context, installed.optBoolean("eac")),
                                        onClick = { model.playProton(game, protonDisplay) }) { Text("Play with Linux Proton") }
                                    if (installed != null) TextButton(enabled = !state.busy,
                                        onClick = { model.refreshProtonStatus(game) }) { Text("Check Proton status") }
                                    if (installed != null && installed.optString("prereq").isNotBlank())
                                        TextButton(enabled = !state.busy && downloading == null && graph.runtime.bundled(),
                                            onClick = { model.prerequisites(game, translator) }) { Text("Run prerequisites") }
                                }
                                if (installed != null && !graph.runtime.bundled()) Text("Android Wine play requires the APK with the bundled Windows runtime.", style = MaterialTheme.typography.bodySmall)
                                if (state.protonStatusApp == game.app) state.protonStatus?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (installed?.optBoolean("eac") == true) Text(
                                "Easy Anti-Cheat detected. Linux Proton also needs its official EAC runtime and publisher support. Online acceptance is unverified; the Epic folder contains a launch report.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = colors.red); TextButton(onClick = model::clearError) { Text("Dismiss") } }
            Text("Sign-in tokens are encrypted using Android Keystore. Paused downloads retain verified files and checked chunk parts. An installed app is not a claim of Android or anti-cheat compatibility.",
                style = MaterialTheme.typography.bodySmall, color = colors.secondary)
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Records prerequisites without spoofing EAC or claiming an unsupported host ABI works. */
object EacCompatibility {
    fun detects(files: Collection<String>, launcher: String): Boolean =
        (files.asSequence() + sequenceOf(launcher)).any { path ->
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            name.contains("easyanticheat", true) || name.equals("start_protected_game.exe", true) ||
                name.equals("eac_launcher.exe", true)
        }
    fun report(app: String, executable: String, detected: Boolean, translator: String, deployment: Boolean,
               diagnostics: Boolean = false): JSONObject =
        JSONObject().put("schema", 1).put("app", app).put("launchExecutable", executable)
            .put("eacDetected", detected).put("x64Translator", "FEX ARM64EC").put("x86Translator", translator)
            .put("eosDeploymentConfigured", deployment).put("hostAbi", "Android arm64-v8a / bionic")
            .put("eacOnlineStatus", "unverified")
            .put("protonLinuxEacBridge", "not available in this ARM64 Android runtime")
            .put("loaderDiagnosticsEnabled", diagnostics)
            .put("launcherPolicy", "Use the manifest launch executable and arguments unchanged; never skip EAC")
            .put("remainingRequirement", "Verify the game's Proton EAC Linux runtime/bridge supports this host ABI; the Windows-code translator alone does not load x86-64 Linux libraries")
    fun write(folder: File, record: JSONObject) {
        folder.mkdirs()
        File(folder, "epic-launch-report.json").writeText(record.toString(2))
    }
    /** Keep only stage flags. Wine log lines may contain a one-use Epic exchange code. */
    fun capture(reportFile: File, wineLog: File) {
        runCatching {
            if (!reportFile.isFile) return
            val report = JSONObject(reportFile.readText())
            val text = if (wineLog.isFile) RandomAccessFile(wineLog, "r").use { input ->
                val length = input.length()
                val size = minOf(length, 4L * 1024 * 1024).toInt()
                input.seek(length - size)
                ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
            } else ""
            val lines = text.lineSequence().toList()
            val eac = lines.filter { it.contains("easyanticheat", ignoreCase = true) ||
                it.contains("start_protected_game", ignoreCase = true) }
            report.put("wineLogAvailable", wineLog.isFile)
                .put("wineLogTailBytes", text.toByteArray(Charsets.UTF_8).size)
                .put("eacWindowsModuleObserved", eac.any { it.contains("loaddll", true) && it.contains("loaded", true) })
                .put("eacErrorObserved", eac.any { it.contains("err:", true) || it.contains("error", true) })
                .put("linux64BridgeObserved", lines.any { it.contains("easyanticheat_x64.so", true) ||
                    it.contains("easyanticheat_x64.dll", true) })
                .put("diagnosticInterpretation", "Module load only confirms a Windows bootstrap stage; EAC acceptance and online play require a live match test")
            reportFile.writeText(report.toString(2))
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An opt-in, app-private Linux x86-64 Proton installation, separate from Android Wine. */
object LinuxProton {
    data class Plan(val command: List<String>, val environment: Map<String, String>, val workingDir: String,
                    val logFile: String, val title: String) {
        fun toJson() = JSONObject().put("command", JSONArray(command)).put("environment", JSONObject(environment))
            .put("workingDir", workingDir).put("logFile", logFile).put("title", title).toString()
        companion object {
            fun fromJson(text: String): Plan {
                val data = JSONObject(text)
                val array = data.getJSONArray("command")
                val env = data.getJSONObject("environment")
                return Plan((0 until array.length()).map(array::getString), env.keys().asSequence().associateWith(env::getString),
                    data.getString("workingDir"), data.getString("logFile"), data.getString("title"))
            }
        }
    }

    fun root(context: Context) = File(context.filesDir, "linux-proton")
    fun statusText(stateDir: File): String = runCatching {
        val file = File(stateDir, "sessions/proton/status.json")
        if (!file.isFile) return "No Proton session has started yet"
        val data = JSONObject(file.readText())
        when (data.optString("state")) {
            "running" -> "Proton process is running; check the X11 display"
            "exited" -> "Proton exited with code ${data.optInt("exitCode")}; see proton.log for details"
            "failed" -> "Proton could not start: ${data.optString("message", data.optString("reason"))}"
            else -> "Proton session status is unavailable"
        }
    }.getOrDefault("Proton session status could not be read")
    fun staged(context: Context, needsEac: Boolean): Boolean {
        val root = root(context)
        return RuntimeComponents.box64Available(context) && File(root, "rootfs/usr/bin/python3").isFile &&
            File(root, "proton/proton").isFile && File(root, "proton/files/bin/wine").isFile &&
            (!needsEac || File(root, "eac_runtime/v2/lib/x86_64-linux-gnu/easyanticheat.so").isFile ||
                File(root, "eac_runtime/v2/lib/x86_64-linux-gnu/easyanticheat_x64.so").isFile)
    }
    private fun requireInside(root: File, file: File): File {
        val base = root.canonicalFile
        val candidate = file.canonicalFile
        require(candidate.path.startsWith(base.path + File.separator)) { "Linux runtime path leaves app-private storage" }
        return candidate
    }
    private fun requireElf64X86(root: File, relative: String): File {
        val file = requireInside(root, File(root, relative))
        require(file.isFile && file.canExecute()) { "Missing executable Linux x86-64 component: $relative" }
        val header = ByteArray(20)
        file.inputStream().use { java.io.DataInputStream(it).readFully(header) }
        require(header.size == 20 && header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() && header[4] == 2.toByte() &&
            header[5] == 1.toByte() && ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(18).toInt() == 62) {
            "Expected an x86-64 Linux ELF: $relative"
        }
        return file
    }
    private fun library(root: File, name: String): File {
        val candidates = listOf("rootfs/lib/x86_64-linux-gnu/$name", "rootfs/usr/lib/x86_64-linux-gnu/$name", "rootfs/lib64/$name")
        return candidates.map { requireInside(root, File(root, it)) }.firstOrNull(File::isFile)
            ?: error("Missing x86-64 Linux library $name in rootfs")
    }
    fun prepare(context: Context, gameDir: File, executable: File, title: String, arguments: List<String>,
                display: String, needsEac: Boolean, stateDir: File = gameDir): Plan =
        prepare(root(context), RuntimeComponents.verifiedBox64(context), gameDir, executable, title, arguments, display, needsEac, stateDir)

    fun desktop(context: Context, display: String): Plan = desktop(root(context),
        RuntimeComponents.verifiedBox64(context), File(context.filesDir, "desktop-proton"), display)

    fun desktop(root: File, box64: File, stateDir: File, display: String): Plan =
        runtimePlan(root, box64, stateDir, stateDir, "Windows desktop", listOf("explorer.exe"), display, false)

    fun prepare(root: File, box64: File, gameDir: File, executable: File, title: String, arguments: List<String>,
                display: String, needsEac: Boolean, stateDir: File = gameDir): Plan {
        val work = executable.parentFile!!.canonicalFile
        val install = gameDir.canonicalFile
        require((work == install || work.path.startsWith(install.path + File.separator)) && executable.isFile) {
            "Game executable is outside its installation"
        }
        return runtimePlan(root, box64, stateDir, work, title, listOf(executable.absolutePath) + arguments, display, needsEac)
    }

    private fun runtimePlan(root: File, box64: File, stateDir: File, work: File, title: String,
                            target: List<String>, display: String, needsEac: Boolean): Plan {
        require(box64.isFile && box64.canExecute()) { "Install Box64 before using Linux Proton" }
        val python = requireElf64X86(root, "rootfs/usr/bin/python3")
        requireElf64X86(root, "proton/files/bin/wine")
        library(root, "libc.so.6")
        val script = requireInside(root, File(root, "proton/proton"))
        require(script.isFile) { "Stage a complete Proton distribution in linux-proton/proton" }
        val steam = File(root, "steam").apply { mkdirs() }
        val compat = File(stateDir, "sessions/proton/compatdata").apply { mkdirs() }
        val temp = File(stateDir, "sessions/proton/tmp").apply { mkdirs() }
        work.mkdirs()
        val x11 = display.trim()
        require(x11.isNotEmpty() && !x11.contains('\u0000')) { "Set an X11 DISPLAY for Linux Proton" }
        val eac = File(root, "eac_runtime")
        if (needsEac) {
            require(listOf("v2/lib/x86_64-linux-gnu/easyanticheat.so", "v2/lib/x86_64-linux-gnu/easyanticheat_x64.so")
                .any { requireInside(root, File(eac, it)).isFile }) {
                "Stage the official Proton EasyAntiCheat Runtime in linux-proton/eac_runtime"
            }
        }
        val paths = listOf("rootfs/lib/x86_64-linux-gnu", "rootfs/usr/lib/x86_64-linux-gnu", "rootfs/lib64",
            "rootfs/usr/lib64", "proton/files/lib64", "proton/files/lib", "proton/files/lib64/wine/x86_64-unix")
            .map { File(root, it).absolutePath }.joinToString(":")
        val env = mapOf(
            "HOME" to File(stateDir, "sessions/proton").absolutePath,
            "TMPDIR" to temp.absolutePath,
            "DISPLAY" to x11,
            "PATH" to listOf(File(root, "rootfs/usr/bin"), File(root, "rootfs/bin"), File(root, "proton/files/bin"))
                .joinToString(":") { it.absolutePath } + ":/system/bin",
            "BOX64_PATH" to listOf(File(root, "rootfs/usr/bin"), File(root, "rootfs/bin"), File(root, "proton/files/bin"))
                .joinToString(":") { it.absolutePath },
            "BOX64_LD_LIBRARY_PATH" to paths,
            "STEAM_COMPAT_DATA_PATH" to compat.absolutePath,
            "STEAM_COMPAT_CLIENT_INSTALL_PATH" to steam.absolutePath,
            "STEAM_COMPAT_TOOL_PATHS" to File(root, "proton").absolutePath,
            "PROTON_NO_ESYNC" to "1",
            "PROTON_NO_FSYNC" to "1",
            "WINEDEBUG" to "-all",
        ).toMutableMap()
        if (needsEac) env["PROTON_EAC_RUNTIME"] = eac.absolutePath
        return Plan(listOf(box64.absolutePath, python.absolutePath, script.absolutePath, "run") + target,
            env, work.absolutePath, File(stateDir, "sessions/proton/proton.log").absolutePath, title)
    }
}

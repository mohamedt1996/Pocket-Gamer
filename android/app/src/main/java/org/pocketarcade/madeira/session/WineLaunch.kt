// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// One Wine session: what the app asks for (LaunchSpec, passed in the intent) and
// what WineActivity runs in the :wine process (WineLaunch.prepare). The prefix
// is prepared here: drive mappings, graphics DLLs, the XInput bridge, Madeira
// Dock's host and the environment.

package org.pocketarcade.madeira.session

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.pocketarcade.madeira.NativeEnvironment
import org.pocketarcade.madeira.PortRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/** What one session runs. Serialised into the intent as JSON. */
data class LaunchSpec(
    /** The game folder: holds prefix/, tmp/ and wine.log. */
    val home: String,
    /** The Wine command line. */
    val command: String,
    /** The Unix working folder of the first process. */
    val workingDir: String,
    val title: String,
    /** "wine", "dxvk" or "vkd3d". */
    val graphics: String = "wine",
    val environment: Map<String, String> = emptyMap(),
    val dllOverrides: String = "",
    val touchControls: Boolean = true,
    val controlLayout: String? = null,
    val performance: Boolean = false,
    /** Gamepad buttons to keys. */
    val binds: Map<String, String> = emptyMap(),
    val stickMouse: Boolean = true,
    /** A Steam game started through Madeira Dock: its App ID, and the report file to follow. */
    val steamAppID: Int = 0,
    val dockReport: String? = null,
    val dockInstallerResults: String? = null,
    val diagnostics: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("home", home); put("command", command); put("workingDir", workingDir); put("title", title)
        put("graphics", graphics); put("environment", JSONObject(environment as Map<*, *>)); put("dllOverrides", dllOverrides)
        put("touchControls", touchControls); controlLayout?.let { put("controlLayout", it) }; put("performance", performance)
        put("binds", JSONObject(binds as Map<*, *>)); put("stickMouse", stickMouse); put("steamAppID", steamAppID)
        dockReport?.let { put("dockReport", it) }; dockInstallerResults?.let { put("dockInstallerResults", it) }
        put("diagnostics", diagnostics)
    }.toString()

    companion object {
        const val EXTRA = "pocketarcade.launch"

        fun fromJson(text: String): LaunchSpec {
            val o = JSONObject(text)
            fun map(name: String) = o.optJSONObject(name)?.let { m -> m.keys().asSequence().associateWith { m.optString(it) } } ?: emptyMap()
            return LaunchSpec(
                home = o.getString("home"), command = o.getString("command"), workingDir = o.optString("workingDir"),
                title = o.optString("title"), graphics = o.optString("graphics", "wine"), environment = map("environment"),
                dllOverrides = o.optString("dllOverrides"), touchControls = o.optBoolean("touchControls", true),
                controlLayout = o.optString("controlLayout", "").ifEmpty { null }, performance = o.optBoolean("performance"),
                binds = map("binds"), stickMouse = o.optBoolean("stickMouse", true), steamAppID = o.optInt("steamAppID"),
                dockReport = o.optString("dockReport", "").ifEmpty { null },
                dockInstallerResults = o.optString("dockInstallerResults", "").ifEmpty { null },
                diagnostics = o.optBoolean("diagnostics"),
            )
        }

        /** "Z:\..." for a Unix path, as the drive mapping WineLaunch makes guarantees. */
        fun windowsPath(file: File): String = "Z:" + file.absolutePath.replace('/', '\\')

        fun start(context: Context, spec: LaunchSpec) {
            val intent = Intent().setClassName(context, "org.winehq.wine.WineActivity").putExtra(EXTRA, spec.toJson())
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            SessionState.markStarting(context, spec)
            context.startActivity(intent)
        }
    }
}

/** Prepares and describes a session for WineActivity (Java). */
class WineLaunch private constructor(
    @JvmField val command: String,
    @JvmField val environment: HashMap<String, String>,
    @JvmField val ntdll: String,
    @JvmField val spec: LaunchSpec,
) {
    companion object {
        /** Called on WineActivity's loader thread: installs the runtime, prepares the prefix and the process environment. */
        @JvmStatic
        fun prepare(activity: Activity, intent: Intent): WineLaunch {
            val spec = intent.getStringExtra(LaunchSpec.EXTRA)?.let { LaunchSpec.fromJson(it) }
                ?: legacy(activity, intent).also { intent.putExtra(LaunchSpec.EXTRA, it.toJson()) }
            val runtime = PortRuntime(activity)
            runtime.installBundled()
            val env = environment(activity, runtime.root(), spec)
            NativeEnvironment.prepare(env["LD_LIBRARY_PATH"], spec.workingDir.ifEmpty { spec.home }, env["TMPDIR"])
            SessionState.markRunning(activity, spec)
            return WineLaunch(spec.command, env, File(runtime.root(), "lib/wine/aarch64-unix/ntdll.so").absolutePath, spec)
        }

        /** The developer interface's launches: gameId + wineCommand extras. */
        private fun legacy(context: Context, intent: Intent): LaunchSpec {
            val id = intent.getStringExtra("gameId") ?: throw IllegalArgumentException("No game to start")
            val store = org.pocketarcade.madeira.GameStore(context)
            val settings = store.settings(id)
            val exe = settings.optString("exe")
            return LaunchSpec(store.directory(id).absolutePath, intent.getStringExtra("wineCommand") ?: "explorer.exe",
                if (exe.isNotEmpty()) File(exe).parent ?: "" else store.directory(id).absolutePath, settings.optString("title"),
                graphics = settings.optString("graphics", "wine"))
        }

        private fun symlink(link: File, target: String) {
            val path = link.toPath()
            if (Files.isSymbolicLink(path)) {
                if (Files.readSymbolicLink(path).toString() == target) return
                Files.delete(path)
            } else if (link.exists()) return
            Files.createSymbolicLink(path, Paths.get(target))
        }

        /**
         * The prefix's drive letters, made before Wine's first start: C: is drive_c and Z: is the
         * Unix root, so Z:\data\... paths reach the app's files (Wine makes no mapping once
         * dosdevices exists).
         */
        fun prepareDrives(prefix: File) {
            File(prefix, "drive_c").mkdirs()
            val dos = File(prefix, "dosdevices").apply { mkdirs() }
            symlink(File(dos, "c:"), "../drive_c")
            symlink(File(dos, "z:"), "/")
        }

        /**
         * The registry a new prefix starts from, written before Wine's first start.
         *
         * system.reg must say `#arch=win64`: wineserver treats an existing system.reg without an
         * arch line as a 32-bit prefix, and a 64-bit Wine then refuses to start in it.
         * The Wine driver choice is read from HKCU (user.reg), not HKLM.
         */
        @JvmStatic
        fun seedRegistry(prefix: File) {
            prefix.mkdirs()
            val system = File(prefix, "system.reg")
            if (!system.exists()) {
                system.writeText(REGISTRY_HEADER + ";; All keys relative to \\\\Machine\n\n#arch=win64\n\n" +
                    "[Software\\\\Microsoft\\\\Wow64\\\\amd64]\n@=\"libarm64ecfex.dll\"\n\n" +
                    "[Software\\\\Microsoft\\\\Wow64\\\\x86]\n@=\"libwow64fex.dll\"\n\n")
            } else {
                // Prefixes seeded by earlier versions lack the arch line; Wine always writes one itself.
                val text = system.readText()
                if (!text.lineSequence().any { it.startsWith("#arch=") } && text.startsWith(REGISTRY_HEADER)) {
                    system.writeText(REGISTRY_HEADER + "\n#arch=win64\n" + text.removePrefix(REGISTRY_HEADER))
                }
            }
            val user = File(prefix, "user.reg")
            if (!user.exists()) {
                user.writeText(REGISTRY_HEADER + "\n#arch=win64\n\n" + DRIVERS_KEY)
            } else if (!user.readText().contains("[Software\\\\Wine\\\\Drivers]")) {
                user.appendText("\n" + DRIVERS_KEY)
            }
        }

        private const val REGISTRY_HEADER = "WINE REGISTRY Version 2\n"
        private const val DRIVERS_KEY = "[Software\\\\Wine\\\\Drivers]\n\"Audio\"=\"directaudio\"\n\"Graphics\"=\"android\"\n\n"

        /** Just enough environment for `wineserver -k` to find the prefix's server. */
        fun serverEnvironment(context: Context, root: File, spec: LaunchSpec): HashMap<String, String> = hashMapOf(
            "HOME" to spec.home,
            "TMPDIR" to File(spec.home, "tmp").absolutePath,
            "WINEPREFIX" to File(spec.home, "prefix").absolutePath,
            "LD_LIBRARY_PATH" to File(root, "lib").absolutePath + ":" + File(root, "lib/wine/aarch64-unix").absolutePath + ":" + context.applicationInfo.nativeLibraryDir,
        )

        private fun copyDlls(source: File, dest: File, names: Map<String, String>? = null) {
            dest.mkdirs()
            val files = source.listFiles { _, n -> n.endsWith(".dll") } ?: return
            for (f in files) {
                val targets = names?.get(f.name)?.split(',') ?: listOf(f.name)
                for (t in targets) {
                    val out = File(dest, t)
                    if (!out.isFile || out.length() != f.length()) f.copyTo(out, overwrite = true)
                }
            }
        }

        fun environment(context: Context, root: File, spec: LaunchSpec): HashMap<String, String> {
            val home = File(spec.home)
            val prefix = File(home, "prefix")
            val tmp = File(home, "tmp")
            prefix.mkdirs(); tmp.mkdirs()
            prepareDrives(prefix)
            seedRegistry(prefix)
            val system32 = File(prefix, "drive_c/windows/system32")
            val syswow64 = File(prefix, "drive_c/windows/syswow64")
            val env = HashMap<String, String>()
            env["HOME"] = home.absolutePath
            env["TMPDIR"] = tmp.absolutePath
            env["WINEPREFIX"] = prefix.absolutePath
            // Wine's loader finds ntdll.so next to itself. bin/wine is a plain copy once the runtime
            // is zipped (no symlinks), so every process Wine starts through it would fail with
            // "could not load ntdll.so". Children are exec'd through WINELOADER: use the real one.
            env["WINELOADER"] = loader(root).absolutePath
            env["WINESERVER"] = File(root, "bin/wineserver").absolutePath
            env["WINEDLLPATH"] = File(root, "lib/wine").absolutePath
            env["WINEDEBUGLOG"] = File(home, "wine.log").absolutePath
            env["WINEDEBUG"] = if (spec.diagnostics) "+timestamp,+pid,+seh,+loaddll,warn+all" else "+timestamp,+pid,+seh"
            env["LD_LIBRARY_PATH"] = File(root, "lib").absolutePath + ":" + File(root, "lib/wine/aarch64-unix").absolutePath + ":" + context.applicationInfo.nativeLibraryDir
            env["PATH"] = File(root, "bin").absolutePath + ":/system/bin"
            env["LC_ALL"] = "C.UTF-8"; env["LANG"] = "C.UTF-8"
            env["WINEESYNC"] = "0"; env["WINEFSYNC"] = "0"; env["WINENTSYNC"] = "0"
            // Wine looks for its Mono and Gecko installers in $XDG_CACHE_HOME/wine (WineMono).
            env["XDG_CACHE_HOME"] = File(context.filesDir, "wine-cache").absolutePath
            val overrides = ArrayList<String>()
            when (spec.graphics) {
                "dxvk", "vkd3d" -> {
                    copyDlls(File(root, "graphics/dxvk/x64"), system32)
                    copyDlls(File(root, "graphics/dxvk/x86"), syswow64)
                    overrides += "d3d8,d3d9,d3d10core,d3d11,dxgi=n,b"
                    if (spec.graphics == "vkd3d") {
                        copyDlls(File(root, "graphics/vkd3d/x64"), system32)
                        copyDlls(File(root, "graphics/vkd3d/x86"), syswow64)
                        overrides += "d3d12,d3d12core=n,b"
                    }
                }
                else -> overrides += "d3d8,d3d9,d3d10core,d3d11,dxgi,d3d12,d3d12core=b"
            }
            // The XInput bridge: games read the pad the app writes (touch controls and physical pads).
            val bridge = File(root, "addons/xinput")
            if (File(bridge, "x64").isDirectory) {
                val names = mapOf("xinput_pocket.dll" to XINPUT_NAMES)
                copyDlls(File(bridge, "x64"), system32, names)
                copyDlls(File(bridge, "x86"), syswow64, names)
                overrides += "xinput1_1,xinput1_2,xinput1_3,xinput1_4,xinput9_1_0=n,b"
                PadBridge.ensureFile(prefix)
            }
            // Madeira Dock's host runs from system32.
            val dock = File(root, "addons/dock/dockhost.exe")
            if (dock.isFile) {
                system32.mkdirs()
                val target = File(system32, "dockhost.exe")
                if (!target.isFile || target.length() != dock.length()) dock.copyTo(target, overwrite = true)
            }
            if (spec.dllOverrides.isNotBlank()) overrides += spec.dllOverrides.trim()
            env["WINEDLLOVERRIDES"] = overrides.joinToString(";")
            env["DXVK_LOG_PATH"] = home.absolutePath
            env["DXVK_STATE_CACHE_PATH"] = home.absolutePath
            env["VKD3D_SHADER_CACHE_PATH"] = home.absolutePath
            env["VKD3D_LOG_FILE"] = File(home, "vkd3d.log").absolutePath
            if (spec.performance) env["DXVK_HUD"] = "fps,frametimes,gpuload"
            for ((k, v) in spec.environment) env[k] = v
            return env
        }

        /** Wine's own loader, beside ntdll.so. */
        @JvmStatic
        fun loader(root: File) = File(root, "lib/wine/aarch64-unix/wine")

        const val XINPUT_NAMES = "xinput1_1.dll,xinput1_2.dll,xinput1_3.dll,xinput1_4.dll,xinput9_1_0.dll"
    }
}

/**
 * Which session runs, as files the app process and the :wine process both see: the app
 * checks after a session whether the game has ended, to reopen its Steam connection,
 * sync saves and refresh playtime.
 */
object SessionState {
    private fun file(context: Context) = File(context.filesDir, "session.json")

    fun markStarting(context: Context, spec: LaunchSpec) {
        try { file(context).writeText(JSONObject().apply { put("steamAppID", spec.steamAppID); put("title", spec.title); put("state", "starting"); put("at", System.currentTimeMillis()) }.toString()) } catch (_: Exception) {}
    }

    fun markRunning(context: Context, spec: LaunchSpec) {
        try { file(context).writeText(JSONObject().apply { put("steamAppID", spec.steamAppID); put("title", spec.title); put("state", "running"); put("pid", android.os.Process.myPid()); put("at", System.currentTimeMillis()) }.toString()) } catch (_: Exception) {}
    }

    /** Written by the :wine process just before it ends itself (stop, or a failed start). */
    @JvmStatic
    fun markEnded(context: Context) {
        try {
            val f = file(context)
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            o.put("state", "ended"); o.put("at", System.currentTimeMillis())
            f.writeText(o.toString())
        } catch (_: Exception) {}
    }

    class Info(val steamAppID: Int, val title: String, val pid: Int, val at: Long, val ended: Boolean)

    fun current(context: Context): Info? = try {
        val f = file(context)
        if (!f.isFile) null else JSONObject(f.readText()).let {
            Info(it.optInt("steamAppID"), it.optString("title"), it.optInt("pid"), it.optLong("at"), it.optString("state") == "ended")
        }
    } catch (_: Exception) { null }

    fun clear(context: Context) { file(context).delete() }

    /** Whether the :wine process lives. */
    fun wineProcessRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val name = context.packageName + ":wine"
        return am.runningAppProcesses?.any { it.processName == name } == true
    }

    /** Whether a game session is still going: recorded, not marked ended, and its process alive. */
    fun sessionRunning(context: Context): Boolean {
        val info = current(context) ?: return wineProcessRunning(context)
        return !info.ended && wineProcessRunning(context)
    }
}

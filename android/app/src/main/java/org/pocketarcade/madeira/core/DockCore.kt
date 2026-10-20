// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Madeira Dock's app-side contract, ported from MadeiraDock.swift,
// SteamRuntime.swift and DockInstallers.swift: Valve's client components
// (pinned packages from Valve's own update CDN), the one-use sign-in transfer,
// the host's numeric report, and a game's one-time installs. Pure JVM code.
//
// Dock (dockhost.exe, https://github.com/willfaust/madeira-dock) loads Valve's
// genuine Steam client inside Wine, signs in with the user's own refresh token
// and asks it to start an installed game with Valve's own LaunchApp. Valve's
// client makes every decision about sign-in, licences and launch.

package org.pocketarcade.madeira.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Inflater

class DockException(message: String) : Exception(message)

/** Valve's client components, checked against pinned sizes and SHA-256 sums. No Valve binary is bundled. */
object SteamRuntimeFiles {
    class Package(val file: String, val bytes: Long, val sha256: String)

    const val ORIGIN = "https://client-update.akamai.steamstatic.com/"
    val PACKAGES = listOf(
        Package("bins_win32.zip.23e34a6d4b10596a44561a5100dac5585d2517da", 59_544_006, "8b712b2a3412a9066b7725f4e1c5cef9a7ca5b187b6585a5b92d25d09df0ba62"),
        Package("bins_win64_win32.zip.f29d67dc38a4be027f1734802697c668621a6da1", 10_509_550, "345f6e4bdc19b27ae53bf752c21e2d894e0e899d94823222fb426e890d1226b5"),
        Package("steam_win32.zip.3e96965d109fc2d4cc14206b2fc4ec960a746ed3", 2_307_664, "1369615c795b60de822876b4dc4042186cf58d0dc8f63ea1371167f667e16925"),
    )
    /** The steamclient64.dll build the Dock host supports. */
    const val CLIENT_SHA256 = "71b391fe9f3e2006cbc81a5c75eef3eb4186012deabfdb2c8b7e8d4850ecf640"
    const val RELATIVE_ROOT = "Program Files (x86)/Steam"
    const val WINDOWS_ROOT = "C:\\Program Files (x86)\\Steam"
    val totalBytes: Long get() = PACKAGES.sumOf { it.bytes }

    fun validPath(name: String): Boolean {
        if (name.isEmpty() || name.toByteArray().size >= 240 || name.contains('\\') || name.contains(':') || name.any { it.code < 32 }) return false
        return name.split('/').none { it.isEmpty() || it == "." || it == ".." || it.endsWith(".") || it.endsWith(" ") }
    }

    /**
     * Unpacks a ZIP whose pinned SHA-256 was already checked. ZIP64, encryption, links,
     * duplicates and case-colliding names are refused; every entry's CRC is checked.
     */
    fun unpack(data: ByteArray, write: (String, ByteArray) -> Unit) {
        fun need(c: Boolean) { if (!c) throw DockException("Steam's components could not be verified. Try downloading them again.") }
        need(data.size >= 22 && data.size <= 64 shl 20)
        fun u16(p: Int) = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
        fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
        var end = -1
        var p = data.size - 22
        while (p >= maxOf(0, data.size - 65557)) {
            if (u32(p) == 0x06054b50L && p + 22 + u16(p + 20) == data.size) { end = p; break }
            p--
        }
        need(end >= 0)
        val count = u16(end + 10); val central = u32(end + 16).toInt(); val centralSize = u32(end + 12).toInt()
        need(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == count && count <= 256)
        need(central in 0..end && centralSize == end - central)
        var cursor = central
        var total = 0L
        val seen = HashSet<String>()
        repeat(count) {
            need(cursor + 46 <= end && u32(cursor) == 0x02014b50L)
            val flags = u16(cursor + 8); val method = u16(cursor + 10)
            val crc = u32(cursor + 16); val compressed = u32(cursor + 20).toInt(); val size = u32(cursor + 24).toInt()
            val nameLength = u16(cursor + 28); val local = u32(cursor + 42).toInt()
            val next = cursor + 46 + nameLength + u16(cursor + 30) + u16(cursor + 32)
            val kind = (u32(cursor + 38) shr 16).toInt() and 0xf000
            need(next <= end && flags and 1 == 0 && (method == 0 || method == 8))
            need(kind == 0 || kind == 0x8000 || kind == 0x4000)
            val raw = String(data, cursor + 46, nameLength, Charsets.UTF_8)
            val normalized = raw.replace('\\', '/')
            val directory = normalized.endsWith("/")
            val name = if (directory) normalized.dropLast(1) else normalized
            need(validPath(name) && seen.add(name.lowercase()))
            need(size in 0..(64 shl 20) && compressed >= 0 && local >= 0 && local + 30 <= central && u32(local) == 0x04034b50L)
            need(u16(local + 6) == flags && u16(local + 8) == method && u16(local + 26) == nameLength)
            val body = local + 30 + nameLength + u16(local + 28)
            need(body <= central && compressed <= central - body)
            need(data.copyOfRange(local + 30, local + 30 + nameLength).contentEquals(raw.toByteArray(Charsets.UTF_8)))
            if (flags and 8 == 0) need(u32(local + 18).toInt() == compressed && u32(local + 22).toInt() == size)
            total += size
            need(total <= 256L shl 20)
            if (directory) { need(size == 0 && compressed == 0); cursor = next; return@repeat }
            val decoded: ByteArray = if (method == 0) {
                need(compressed == size); data.copyOfRange(body, body + compressed)
            } else {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(data, body, compressed)
                    val out = ByteArray(size)
                    var n = 0
                    while (n < size) {
                        val k = inflater.inflate(out, n, size - n)
                        if (k == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                        n += k
                    }
                    need(n == size)
                    out
                } catch (e: java.util.zip.DataFormatException) { need(false); ByteArray(0) } finally { inflater.end() }
            }
            need(CRC32().apply { update(decoded) }.value == crc)
            write(name, decoded)
            cursor = next
        }
        need(cursor == end)
    }

    /**
     * Adds only the fixed client-discovery paths to a Wine registry file: never user IDs,
     * licences, login cache or tokens. A conflicting existing value fails closed.
     */
    fun registry(text: String, machine: Boolean): String {
        if (!text.startsWith("WINE REGISTRY Version 2")) throw DockException("Pocket Arcade could not prepare its Windows environment.")
        val root = WINDOWS_ROOT.replace("\\", "\\\\")
        val sections: List<Pair<String, List<Pair<String, String>>>> = if (machine) listOf(
            "Software\\\\Valve\\\\Steam" to listOf("InstallPath" to root),
            "Software\\\\Wow6432Node\\\\Valve\\\\Steam" to listOf("InstallPath" to root),
        ) else listOf(
            "Software\\\\Valve\\\\Steam" to listOf("SteamPath" to root, "SteamExe" to "$root\\\\steam.exe"),
            "Software\\\\Valve\\\\Steam\\\\ActiveProcess" to listOf("SteamClientDll" to "$root\\\\steamclient.dll", "SteamClientDll64" to "$root\\\\steamclient64.dll"),
        )
        val lines = text.split("\n").toMutableList()
        for ((key, values) in sections) {
            val header = "[$key]"
            val starts = lines.indices.filter { lines[it].lowercase().startsWith(header.lowercase()) }
            if (starts.size > 1) throw DockException("Existing Steam files need attention. They were kept.")
            if (starts.isNotEmpty()) {
                val start = starts[0]
                val stop = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("[") } ?: lines.size
                val additions = ArrayList<String>()
                for ((name, value) in values) {
                    val prefix = "\"$name\"="
                    val matches = (start + 1 until stop).map { lines[it] }.filter { it.lowercase().startsWith(prefix.lowercase()) }
                    val expected = "$prefix\"$value\""
                    if (!(matches.isEmpty() || matches == listOf(expected))) throw DockException("Existing Steam files need attention. They were kept.")
                    if (matches.isEmpty()) additions += expected
                }
                lines.addAll(stop, additions)
            } else {
                lines += listOf("", header) + values.map { "\"${it.first}\"=\"${it.second}\"" } + ""
            }
        }
        return lines.joinToString("\n")
    }
}

/** The one-use sign-in transfer (format MDOCK001) and the host's report. */
object DockHandoff {
    const val EXECUTABLE = "C:\\windows\\system32\\dockhost.exe"

    fun validAppID(v: Long) = v > 0 && v < 0xFFFFFFFFL

    /**
     * 8 magic bytes; LE u64 SteamID, u32 App ID, u16 account length, u16 token length; the
     * account name (1-64 bytes) and the token (1-8192 bytes). It carries a credential,
     * never a claim of ownership: Valve authenticates it in the guest.
     */
    fun envelope(account: String, token: String, steamID: Long, appID: Int): ByteArray {
        val name = account.toByteArray(Charsets.UTF_8); val secret = token.toByteArray(Charsets.UTF_8)
        val ok = name.size in 1..64 && secret.size in 1..8192 && name.all { it in 33..126 } &&
            secret.all { it in 65..90 || it in 97..122 || it in 48..57 || it.toInt() == 45 || it.toInt() == 46 || it.toInt() == 95 } &&
            (steamID ushr 56) == 1L && ((steamID ushr 52) and 15) == 1L && ((steamID ushr 32) and 0xfffff) == 1L &&
            (steamID and 0xffffffffL) != 0L && validAppID(appID.toLong() and 0xFFFFFFFFL)
        if (!ok) throw DockException("Your Steam sign-in cannot be handed to Dock. Sign in to Steam again.")
        val out = ByteArrayOutputStream()
        out.write("MDOCK001".toByteArray())
        fun le(v: Long, n: Int) { for (i in 0 until n) out.write(((v ushr (8 * i)) and 0xFF).toInt()) }
        le(steamID, 8); le(appID.toLong(), 4); le(name.size.toLong(), 2); le(secret.size.toLong(), 2)
        out.write(name); out.write(secret)
        return out.toByteArray()
    }

    /** The host's report: a bounded set of numeric fields, never arbitrary guest text. */
    class Report(val fields: Map<String, String>) {
        val result: Int? get() = fields["probe-result"]?.toIntOrNull()

        val failure: String? get() {
            val r = result ?: return null
            if (r == 0) return null
            return when (r) {
                30 -> if (fields["session-unsupported-client"] == "1" || fields["session-user-method-mismatch"] != null)
                    "Madeira Dock does not support this Steam client build. Dock only drives client builds it has verified."
                    else "Madeira Dock could not initialize the Steam session (code 30)."
                34 -> if (fields["session-authenticated-online"] == "1") "Steam signed in but did not confirm this game's license in time."
                    else "Steam did not finish signing in. Check the connection and try again."
                35 -> "Steam did not confirm a license for this game on the signed-in account."
                50 -> if (fields["session-offline-abi"] == "0") "There is no connection, and this Steam client build cannot be started offline by Madeira Dock. Connect to the internet and try again."
                    else "There is no connection, and Steam has nothing saved to sign this account in offline. Start a game once while online."
                51 -> "There is no connection, and Steam refused to sign in offline. Connect to the internet and start the game once."
                52 -> "Steam signed in offline, but what it has saved does not list this game. Start this game once while online."
                37 -> if (fields["session-native-handoff-app-mismatch"] == "1") "Madeira Dock received a sign-in transfer for a different launch. Close the session and try again."
                    else "Madeira Dock could not read the one-use Steam sign-in transfer. Steam has not checked the login yet."
                49 -> when (fields["ceg-result"]?.toIntOrNull()) {
                    -1 -> "Steam took too long to prepare this game's executable. Check the connection and try again."
                    0 -> "Steam did not start preparing this game's executable. Verify the game's files, then try again."
                    10 -> "Steam is busy with this game (updating or running). Try again in a moment."
                    -4 -> "Steam's Windows service manager could not be started, so Steam could not prepare this game's executable."
                    -5 -> "Steam's Windows service is not installed, so Steam could not prepare this game's executable."
                    else -> "Steam could not prepare this game's executable for your account (code ${fields["ceg-result"] ?: "?"})."
                }
                45, 48 -> {
                    val error = fields["launch-client-error"]?.toIntOrNull()
                    when {
                        fields["launch-option-missing"] != null || fields["launch-option-invalid"] != null -> "Steam rejected this game's launch option. Refresh the Steam library and try again."
                        error != null && error in 22..23 && fields["launch-config-wait"] != null -> "Steam did not finish loading this game's configuration after signing in. Wait a minute and start the game again."
                        error == 35 && fields["launch-session-wait"] != null -> "Steam still says this account is playing in another session. Close the game on your other device and start it again."
                        error != null -> launchRefusal(error, r == 48) ?: "Madeira Dock could not complete the Steam launch (code $r)."
                        else -> "Madeira Dock could not complete the Steam launch (code $r)."
                    }
                }
                else -> "Madeira Dock could not complete the Steam launch (code $r)."
            }
        }

        companion object {
            /** Valve's own launch refusal (EAppUpdateError) in words. */
            fun launchRefusal(error: Int, waited: Boolean): String? = when (error) {
                5 -> "Steam did not confirm a license for this game on the signed-in account."
                6, 21 -> "Steam could not reach its servers to start this game. Check the connection and try again."
                16 -> "Steam reports this game is already running. Close it, then try again."
                17, 19, 20 -> if (waited) "Steam could not finish installing content this game needs. Try again later."
                    else "Steam needs to install or update content this game depends on before it can start. Start the game again to let Madeira Dock wait for Steam."
                18 -> "Steam does not see this game as installed."
                28 -> "Steam could not find the game's executable."
                29 -> "Steam started this game's program, but it could not be loaded (invalid platform)."
                22, 23, 24 -> "Steam could not read this game's configuration. Try again."
                25 -> "Steam says this game is not released yet."
                26 -> "Steam says this game is not available in your region."
                35 -> "Steam says this account is playing in another session. Close the game on your other device and start it again."
                else -> null
            }
        }
    }

    private val ROUNDS = setOf("ml1820", "ml1830", "ml1860", "ml1870", "ml1970", "ml1990", "ml2000", "ml2011", "ml2015", "ml2016")

    /** Lines `[steam-host] <round> <field>=<number>`; a partial last line is ignored. */
    fun parseReport(data: ByteArray): Report {
        if (data.size > 32768) return Report(emptyMap())
        val text = String(data, Charsets.UTF_8).replace("\r\n", "\n")
        val fields = HashMap<String, String>()
        for (line in text.split("\n").dropLast(1)) {
            val parts = line.split(" ")
            if (parts.size != 3 || parts[0] != "[steam-host]" || parts[1] !in ROUNDS) continue
            val eq = parts[2].indexOf('=')
            if (eq <= 0) continue
            val key = parts[2].substring(0, eq); val value = parts[2].substring(eq + 1)
            if (key == "client-sha256") { if (value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) fields[key] = value }
            else value.toIntOrNull()?.let { fields[key] = it.toString() }
        }
        return Report(fields)
    }
}

// MARK: One-time installs (Steam install scripts)

data class InstallRun(val name: String, val machine: Boolean, val key: String, val value: Long)
data class InstallProcess(val run: InstallRun, val executable: String, val arguments: String)

/**
 * Valve's client runs a game's install scripts (redistributables) before a launch, a step
 * Dock's route skips: these programs run once in the Dock session before the host, from a
 * batch that reports each exit status, and are recorded in the registry afterwards.
 */
object InstallScripts {
    const val SCRIPT_NAME = "madeira-dock-installers.cmd"
    const val RESULT_NAME = "madeira-dock-installers.result"

    fun hive(path: String): Pair<Boolean, String>? {
        val parts = path.replace('/', '\\').split('\\').filter { it.isNotEmpty() }
        val first = parts.firstOrNull()?.uppercase() ?: return null
        val rest = parts.drop(1).joinToString("\\")
        return when (first) { "HKEY_LOCAL_MACHINE", "HKLM" -> true to rest; "HKEY_CURRENT_USER", "HKCU" -> false to rest; else -> null }
    }

    fun keys(run: InstallRun): List<String> {
        val lower = run.key.lowercase()
        if (!run.machine || !lower.startsWith("software\\") || lower.startsWith("software\\wow6432node\\")) return listOf(run.key)
        return listOf(run.key, "Software\\Wow6432Node\\" + run.key.substring("software\\".length))
    }

    /** Install scripts (.vdf with a "Run Process" section) in `folder` and one level below. */
    fun scripts(folder: File, depth: Int = 1): List<File> {
        val found = ArrayList<File>()
        for (item in folder.listFiles()?.sortedBy { it.path }?.take(500).orEmpty()) {
            if (item.name.startsWith(".") || java.nio.file.Files.isSymbolicLink(item.toPath())) continue
            if (item.isDirectory) { if (depth > 0) found += scripts(item, depth - 1) }
            else if (item.name.lowercase().endsWith(".vdf") && item.length() <= 1 shl 20 &&
                String(item.readBytes(), Charsets.UTF_8).contains("run process", ignoreCase = true)) found += item
        }
        return found
    }

    /** Every "Run Process" entry with its fields, read from the text (the key repeats per installer). */
    fun entries(script: ByteArray, appID: Int?): List<Pair<InstallRun, Map<String, String>>> {
        var b = script.take(1 shl 20).toByteArray()
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b = b.copyOfRange(3, b.size)
        var pos = 0
        fun token(): Pair<String, Boolean>? {
            while (pos < b.size) {
                val c = b[pos].toInt() and 0xFF
                if (c <= 32) { pos++; continue }
                if (c == 47 && pos + 1 < b.size && b[pos + 1].toInt() == 47) { while (pos < b.size && b[pos].toInt() != 10) pos++; continue }
                break
            }
            if (pos >= b.size) return null
            val first = b[pos].toInt() and 0xFF; pos++
            if (first == 123) return "{" to false
            if (first == 125) return "}" to false
            val v = ByteArrayOutputStream()
            if (first == 34) {
                while (pos < b.size) {
                    val c = b[pos].toInt() and 0xFF; pos++
                    if (c == 34) break
                    if (c == 92 && pos < b.size && (b[pos].toInt() == 34 || b[pos].toInt() == 92)) { v.write(b[pos].toInt()); pos++ } else v.write(c)
                }
                return String(v.toByteArray(), Charsets.UTF_8) to true
            }
            v.write(first)
            while (pos < b.size) { val c = b[pos].toInt() and 0xFF; if (c <= 32 || c == 123 || c == 125) break; v.write(c); pos++ }
            return String(v.toByteArray(), Charsets.UTF_8) to true
        }
        fun runKey(fields: Map<String, String>): String? {
            fields["hasrunkey"]?.let { return it }
            if (appID == null || appID <= 0 || fields.keys.none { it.startsWith("process ") }) return null
            return "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\$appID"
        }
        val result = ArrayList<Pair<InstallRun, Map<String, String>>>()
        val path = ArrayList<String>()
        var pending: String? = null
        var fields = HashMap<String, String>()
        var tokens = 0
        while (true) {
            val (text, quoted) = token() ?: break
            if (++tokens > 200_000 || path.size > 32) break
            if (!quoted && text == "{") {
                path += pending?.lowercase() ?: ""; pending = null
                if (path.size >= 2 && path[path.size - 2] == "run process") fields = HashMap()
            } else if (!quoted && text == "}") {
                if (path.size >= 2 && path[path.size - 2] == "run process") {
                    val name = path.last()
                    val key = runKey(fields)
                    val h = key?.let { hive(it) }
                    if (h != null && h.second.isNotEmpty()) {
                        val minimum = fields["minimumhasrunvalue"]?.trim()?.toLongOrNull() ?: 1
                        val run = InstallRun(name, h.first, h.second, maxOf(1, minimum))
                        if (result.none { it.first == run }) result += run to HashMap(fields)
                    }
                }
                if (path.isNotEmpty()) path.removeAt(path.size - 1)
                pending = null
            } else if (pending != null) {
                if (path.size >= 2 && path[path.size - 2] == "run process") fields[pending!!.lowercase()] = text
                pending = null
            } else pending = text
        }
        return result
    }

    fun expand(text: String, installDir: String, path: Boolean = true): String {
        var v = if (path) text.replace('/', '\\') else text
        v = v.replace("%INSTALLDIR%", installDir, ignoreCase = true)
        if (path) while (v.contains("\\\\")) v = v.replace("\\\\", "\\")
        return v
    }

    fun processes(script: ByteArray, installDir: String, appID: Int?): List<InstallProcess> {
        val out = ArrayList<InstallProcess>()
        for ((run, fields) in entries(script, appID)) {
            val numbers = fields.keys.mapNotNull { k -> if (k.startsWith("process ")) k.removePrefix("process ").trim().toIntOrNull() else null }.sorted()
            for (n in numbers.take(8)) {
                val raw = fields["process $n"] ?: continue
                if (raw.isEmpty() || raw.toByteArray().size > 1024) continue
                val exe = expand(raw, installDir)
                val lower = exe.lowercase()
                if (exe.length <= 3 || !exe.startsWith("C:\\") || exe.contains('%') || exe.contains('"') || exe.contains("..") ||
                    !(lower.endsWith(".exe") || lower.endsWith(".msi"))) continue
                val args = expand(fields["command $n"] ?: "", installDir, path = false).trim()
                if (args.toByteArray().size > 1024 || args.any { it in "\r\n&|><^%" }) continue
                val p = InstallProcess(run, exe, args)
                if (p !in out) out += p
            }
        }
        return out
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The value a run has in a Wine .reg text (highest of both views). */
    fun recorded(run: InstallRun, text: String): Long? {
        val lines = text.split("\n")
        val valueName = ("\"" + escape(run.name) + "\"=").lowercase()
        var best: Long? = null
        for (key in keys(run)) {
            val header = ("[" + escape(key) + "]").lowercase()
            val start = lines.indexOfFirst { it.lowercase().startsWith(header) }
            if (start < 0) continue
            var i = start + 1
            while (i < lines.size && !lines[i].startsWith("[")) {
                val line = lines[i].lowercase()
                if (line.startsWith(valueName)) {
                    val at = line.indexOf("=dword:")
                    if (at >= 0) line.substring(at + 7).trim().toLongOrNull(16)?.let { best = maxOf(best ?: 0, it) }
                }
                i++
            }
        }
        return best
    }

    fun marked(run: InstallRun, text: String) = (recorded(run, text) ?: 0) >= run.value

    /** The .reg text with every run's value at least its minimum, and how many values changed. */
    fun mark(runs: List<InstallRun>, text: String, now: Long): Pair<String, Int> {
        val lines = text.split("\n").toMutableList()
        var changed = 0
        for (run in runs) {
            val valueName = "\"" + escape(run.name) + "\"="
            val valueLine = valueName + String.format("dword:%08x", run.value)
            for (key in keys(run)) {
                val header = "[" + escape(key) + "]"
                val start = lines.indexOfFirst { it.lowercase().startsWith(header.lowercase()) }
                if (start >= 0) {
                    var end = start + 1
                    while (end < lines.size && !lines[end].startsWith("[")) end++
                    val index = (start + 1 until end).firstOrNull { lines[it].lowercase().startsWith(valueName.lowercase()) }
                    if (index != null) {
                        val at = lines[index].lowercase().indexOf("=dword:")
                        val current = if (at >= 0) lines[index].substring(at + 7).trim().toLongOrNull(16) else null
                        if (current != null && current >= run.value) continue
                        lines[index] = valueLine
                    } else {
                        var at = end
                        while (at > start + 1 && lines[at - 1].isBlank()) at--
                        lines.add(at, valueLine)
                    }
                } else {
                    while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
                    lines += listOf("", "$header $now", valueLine, "")
                }
                changed++
            }
        }
        return lines.joinToString("\n") to changed
    }

    fun label(run: InstallRun) = run.name.filter { it.isLetterOrDigit() || it in " .-_" }.take(64)

    /** The batch the session runs before the host; each start and exit status goes to `resultFile`. */
    fun batch(processes: List<InstallProcess>, resultFile: String, servicesExecutable: String?): String {
        val result = " >>\"" + resultFile.replace("\"", "") + "\""
        val lines = arrayListOf("@echo off", "rem One-time installs of a game, before the Dock host starts it",
            "echo begin ${processes.size}" + result.replace(" >>", " >"))
        if (servicesExecutable == null) lines += "echo services off$result"
        else {
            lines += "\"" + servicesExecutable.replace("\"", "") + "\" --start-services" + result
            lines += "if not \"%ERRORLEVEL%\"==\"0\" echo services failed$result"
        }
        processes.forEachIndexed { offset, p ->
            val index = offset + 1
            val quoted = "\"" + p.executable + "\""
            val command = if (p.executable.lowercase().endsWith(".msi"))
                "C:\\windows\\system32\\msiexec.exe /i $quoted" + (if (p.arguments.isEmpty()) "" else " " + p.arguments)
                else "call $quoted" + (if (p.arguments.isEmpty()) "" else " " + p.arguments)
            val text = label(p.run)
            lines += "echo start $index $text$result"
            lines += command
            lines += "echo exit $index %ERRORLEVEL%$result"
        }
        lines += "echo end$result"
        return lines.joinToString("\r\n") + "\r\n"
    }

    class Results(val total: Int, val started: Map<Int, String>, val exits: Map<Int, Int>, val ended: Boolean, val services: String?) {
        companion object { fun succeeded(status: Int) = status == 0 || status == 3010 || status == 1641 }
    }

    fun results(text: String): Results {
        var total = 0; val started = HashMap<Int, String>(); val exits = HashMap<Int, Int>(); var ended = false; var services: String? = null
        for (raw in text.lines().take(200)) {
            val parts = raw.trim().split(" ", limit = 3)
            when (parts.firstOrNull()) {
                "begin" -> total = minOf(parts.getOrNull(1)?.toIntOrNull() ?: 0, 64)
                "start" -> parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..64 }?.let { started[it] = parts.getOrNull(2)?.take(64) ?: "" }
                "exit" -> { val i = parts.getOrNull(1)?.toIntOrNull(); val s = parts.getOrNull(2)?.trim()?.toIntOrNull(); if (i != null && i in 1..64 && s != null) exits[i] = s }
                "services" -> parts.getOrNull(1)?.filter { it.isLetter() }?.take(16)?.takeIf { it.isNotEmpty() }?.let { services = it }
                "end" -> ended = true
            }
        }
        return Results(total, started, exits, ended, services)
    }
}

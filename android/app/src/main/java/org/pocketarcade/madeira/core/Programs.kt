// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 125hz (Madeira); Android port: Pocket Arcade contributors
// Madeira Converter Exception: see LICENSE-EXCEPTION.md
//
// Windows programs: what a PE file is (32/64-bit, which graphics APIs it imports)
// and which program a Steam game's launch configuration starts. Ports of
// Madeira's LibraryModel PE inspection (Library.swift), LibraryRendererBadge and
// SteamDirectStart (SteamGames.swift). Pure JVM code.

package org.pocketarcade.madeira.core

import org.pocketarcade.madeira.steam.LaunchOption
import java.io.File
import java.io.RandomAccessFile

object PeInspector {
    class Info(val bits: Int, val imports: List<String>)

    /** Machine and imports of a PE file, or null when it is not an x86/x64 program. */
    fun inspect(file: File): Info? = try {
        RandomAccessFile(file, "r").use { f ->
            fun read(offset: Long, count: Int): ByteArray {
                if (offset < 0 || offset >= f.length()) return ByteArray(0)
                val n = minOf(count.toLong(), f.length() - offset).toInt()
                val b = ByteArray(n); f.seek(offset); f.readFully(b); return b
            }
            fun u16(b: ByteArray, o: Int) = if (o + 2 <= b.size) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) else 0
            fun u32(b: ByteArray, o: Int): Long = if (o >= 0 && o + 4 <= b.size) (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)) else 0
            val dos = read(0, 64)
            if (dos.size < 64 || dos[0] != 'M'.code.toByte() || dos[1] != 'Z'.code.toByte()) return null
            val base = u32(dos, 60)
            if (base >= 16L shl 20) return null
            val header = read(base, 264)
            if (header.size < 264 || u32(header, 0) != 0x4550L) return null
            val machine = u16(header, 4)
            val bits = when (machine) { 0x14c -> 32; 0x8664 -> 64; else -> return null }
            val sections = u16(header, 6)
            val optSize = u16(header, 20)
            if (sections > 96 || optSize < 120) return Info(bits, emptyList())
            val magic = u16(header, 24)
            val pe64 = magic == 0x20b
            if (magic != 0x10b && !pe64) return Info(bits, emptyList())
            val imports = u32(header, if (pe64) 144 else 128)
            val delayed = if (optSize >= (if (pe64) 224 else 208)) u32(header, if (pe64) 240 else 224) else 0
            val table = read(base + 24 + optSize, sections * 40)
            fun fileOffset(rva: Long): Long? {
                if (table.size != sections * 40) return null
                for (i in 0 until sections) {
                    val va = u32(table, i * 40 + 12); val size = u32(table, i * 40 + 16)
                    if (rva >= va && rva - va < size) return u32(table, i * 40 + 20) + (rva - va)
                }
                return null
            }
            val names = ArrayList<String>()
            for ((rva, stride, nameField) in listOf(Triple(imports, 20, 12), Triple(delayed, 32, 4))) {
                if (rva == 0L) continue
                val start = fileOffset(rva) ?: continue
                for (i in 0 until 256) {
                    val d = read(start + i * stride, stride)
                    if (d.size != stride) break
                    var nameRVA = u32(d, nameField)
                    if (nameRVA == 0L) break
                    if (stride == 32 && (u32(d, 0) and 1L) == 0L) {
                        val imageBase = u32(header, 52)
                        if (pe64 || nameRVA < imageBase) continue
                        nameRVA -= imageBase
                    }
                    val off = fileOffset(nameRVA) ?: continue
                    val raw = read(off, 128)
                    val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
                    names += String(raw, 0, end, Charsets.ISO_8859_1).lowercase()
                }
            }
            Info(bits, names)
        }
    } catch (_: Exception) { null }

    fun apiNames(imports: Collection<String>): Set<String> = imports.mapNotNull {
        when (it) {
            "ddraw.dll" -> "DirectDraw"; "d3d8.dll" -> "D3D8"; "d3d9.dll" -> "D3D9"
            "d3d10.dll", "d3d10_1.dll" -> "D3D10"; "d3d11.dll" -> "D3D11"; "d3d12.dll" -> "D3D12"
            "opengl32.dll" -> "OpenGL"; "vulkan-1.dll" -> "Vulkan"
            else -> null
        }
    }.toSet()

    private val DLL_NAMES = listOf("ddraw.dll", "d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d11.dll", "d3d12.dll", "opengl32.dll", "vulkan-1.dll")

    /** DLL names a program loads at run time, found as text in bounded reads of its start and end. */
    fun dynamicAPIs(file: File, budget: IntArray): Set<String> {
        if (budget[0] <= 0) return emptySet()
        val result = HashSet<String>()
        try {
            RandomAccessFile(file, "r").use { f ->
                val length = f.length()
                val window = minOf(budget[0], 4 shl 20)
                for (offset in listOf(0L, if (length > window) length - window else 0L).distinct()) {
                    if (budget[0] <= 0) break
                    val n = minOf(window.toLong(), length - offset).toInt()
                    val bytes = ByteArray(n); f.seek(offset); f.readFully(bytes)
                    budget[0] -= n
                    for (i in bytes.indices) { val c = bytes[i].toInt(); if (c in 65..90) bytes[i] = (c + 32).toByte() }
                    val ascii = String(bytes, Charsets.ISO_8859_1)
                    for (name in DLL_NAMES) {
                        val wide = name.flatMap { listOf(it, '\u0000') }.joinToString("") + "\u0000\u0000"
                        if (ascii.contains(name + "\u0000") || ascii.contains(wide)) result += apiNames(listOf(name))
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    /**
     * Every graphics API a game's files name: the program's import graph through its own
     * DLLs, its siblings when it imports none (launchers), and run-time loads.
     */
    fun graphicsAPIs(executable: File): Set<String> {
        val folder = executable.parentFile ?: return emptySet()
        val siblings = folder.listFiles().orEmpty()
        val local = siblings.filter { it.name.lowercase().endsWith(".dll") }.associateBy { it.name.lowercase() }
        val budget = intArrayOf(32 shl 20)
        val pending = ArrayList<File>()
        pending += executable
        if (apiNames(inspect(executable)?.imports.orEmpty()).isEmpty()) {
            pending.addAll(0, siblings.filter { it.name.lowercase().endsWith(".exe") && it != executable }.sortedBy { it.path }.take(8))
        }
        val visited = HashSet<String>()
        val apis = HashSet<String>()
        while (pending.isNotEmpty() && visited.size < 64) {
            val f = pending.removeAt(pending.size - 1)
            if (!visited.add(f.path)) continue
            val imports = inspect(f)?.imports.orEmpty()
            apis += apiNames(imports)
            apis += dynamicAPIs(f, budget)
            for (n in imports) local[n]?.let { if (it.path !in visited) pending += it }
        }
        return apis
    }

    val BADGE_APIS = listOf("D3D12", "D3D11", "D3D10", "D3D9", "D3D8", "Vulkan", "OpenGL", "DirectDraw")

    /** The badge names an API only when the files name exactly one. */
    fun badge(apis: Set<String>): String? = BADGE_APIS.filter { it in apis }.singleOrNull()

    /** The graphics path that suits these APIs on Android: D3D12 -> VKD3D-Proton, D3D8-11 -> DXVK, else Wine's own. */
    fun recommendedGraphics(apis: Set<String>): String = when {
        "D3D12" in apis -> "vkd3d"
        apis.any { it in setOf("D3D8", "D3D9", "D3D10", "D3D11") } -> "dxvk"
        else -> "wine"
    }
}

/** "Start with: The game": the game's own program from Steam's launch configuration, without Steam. */
object DirectStart {
    data class Choice(val program: String, val arguments: String, val folder: String?, val launchIndex: Int? = null)

    private val OTHER_KINDS = setOf("server", "editor", "vr", "othervr", "openvroverlay", "osvr", "manual")

    fun relativePath(raw: String): String? {
        val text = raw.trim().replace('\\', '/')
        if (text.startsWith("/") || text.toByteArray().size > 512 || text.any { it.code < 0x20 || it in "<>:\"|?*" }) return null
        val parts = ArrayList<String>()
        for (p in text.split('/').filter { it.isNotEmpty() }) {
            if (p == ".") continue
            if (p == "..") return null
            parts += p
        }
        return parts.joinToString("/")
    }

    /** `relative` under `root`, matched exactly or else without case: its spelling on disk. */
    fun onDisk(relative: String, root: File, directory: Boolean): String? {
        var f = root
        val spelled = ArrayList<String>()
        for (part in relative.split('/').filter { it.isNotEmpty() }) {
            val names = f.list() ?: return null
            val name = names.firstOrNull { it == part } ?: names.firstOrNull { it.equals(part, true) } ?: return null
            f = File(f, name); spelled += name
        }
        if (!f.exists() || f.isDirectory != directory) return null
        val base = root.canonicalPath
        val target = f.canonicalPath
        if (!(if (target == base) directory else target.startsWith("$base/"))) return null
        return spelled.joinToString("/")
    }

    fun choose(options: List<LaunchOption>, root: File): Choice? {
        fun rank(o: LaunchOption): Int? {
            val type = o.type.lowercase()
            if (o.betaKey.isNotEmpty() || type in OTHER_KINDS) return null
            if (!(o.requiredDLC.isNullOrEmpty() || o.requiredDLC == "0")) return null
            if (o.oslist.isNotEmpty() && !o.oslist.lowercase().contains("windows")) return null
            val kind = if (type == "default") 0 else if (type.isEmpty() || type == "none") 1 else 2
            val arch = if (o.osarch == "64") 0 else if (o.osarch.isEmpty()) 1 else 2
            return kind * 3 + arch
        }
        val ranked = options.withIndex().mapNotNull { (i, o) -> rank(o)?.let { Triple(it, i, o) } }.sortedWith(compareBy({ it.first }, { it.second }))
        for ((_, _, o) in ranked) {
            val path = relativePath(o.executable)?.takeIf { it.isNotEmpty() && it.lowercase().endsWith(".exe") } ?: continue
            val program = onDisk(path, root, false) ?: continue
            var folder: String? = null
            if (o.workingDir.isNotBlank()) {
                val rel = relativePath(o.workingDir) ?: continue
                folder = if (rel.isEmpty()) "" else (onDisk(rel, root, true) ?: continue)
            }
            return Choice(program, o.arguments.trim(), folder, o.index)
        }
        return null
    }

    /** The install folder's .exe files up to six folders deep, at most 400, sorted. */
    fun programs(root: File): List<String> {
        val found = ArrayList<String>()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (depth > 6) return
            for (name in dir.list()?.sorted().orEmpty()) {
                if (name.startsWith(".") || found.size >= 400) continue
                val child = File(dir, name)
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) { if (!java.nio.file.Files.isSymbolicLink(child.toPath())) walk(child, rel, depth + 1) }
                else if (name.lowercase().endsWith(".exe")) found += rel
            }
        }
        walk(root, "", 0)
        return found.sortedBy { it.lowercase() }
    }

    fun program(picked: String?, options: List<LaunchOption>?, root: File): String? {
        if (!picked.isNullOrEmpty()) onDisk(picked, root, false)?.let { return it }
        options?.let { choose(it, root)?.let { c -> return c.program } }
        return programs(root).singleOrNull()
    }
}

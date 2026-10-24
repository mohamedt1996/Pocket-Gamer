// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.pocketarcade.madeira.steam.Zstd
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Optional existing Android launcher component. Does not rebuild or replace Wine/FEX. */
object RuntimeComponents {
    const val SOURCE = "https://raw.githubusercontent.com/utkarshdalal/GameNative/9c5f4121eb6512707eb467d4a70edb3ae8f08ae3/app/src/main/assets/wowbox64/wowbox64-0.4.2.tzst"
    const val ARCHIVE_SHA = "90e6fefea2c9189c2556d6e0661ec4325dfc41fa82413a44a2485ae223e38efd"
    const val DLL_SHA = "37f8dff29d71291c2715be9c82706e80f5a33223c0ed30bd3490311cf0596bde"
    const val BOX64_SOURCE = "https://raw.githubusercontent.com/utkarshdalal/GameNative/9c5f4121eb6512707eb467d4a70edb3ae8f08ae3/app/src/main/assets/box86_64/box64-0.4.2-bionic.tzst"
    const val BOX64_ARCHIVE_SHA = "fcd10730ff899bad829a5e91780d8aae6350a7e152069ff2c90e93922ecad192"
    const val BOX64_BINARY_SHA = "d0e7170fa6a8a2e2609e4e6a84b1ce19fbe39f843ae1e84d91e43e0a6f190883"
    const val BOX64_16K_ASSET = "runner/box64-android-arm64-16k.zst"
    const val BOX64_16K_ARCHIVE_SHA = "9968eb903442ffaad060657f928d2c6b9270e4d51773ee5cc29dc58cb2c5cfcb"
    const val BOX64_16K_BINARY_SHA = "99d5c29ce068b35f92bf8500cf6966d94dc5ef8fd6c9b0242f1a006bd55a0b4e"
    private fun file(context: Context) = File(context.filesDir, "runtime-components/wowbox64-0.4.2.dll")
    private fun box64File(context: Context) = File(context.filesDir, "runtime-components/box64-0.4.2")
    private fun box32File(context: Context) = File(context.filesDir, "runtime-components/box64-box32-16k")
    private const val BOX32_MANIFEST = "runner/box64-box32-android-arm64-16k.json"
    private fun hostPageSize(): Int = Os.sysconf(OsConstants._SC_PAGESIZE).also {
        require(it in 4096..65536) { "Android did not report a usable page size" }
    }.toInt()
    fun available(context: Context): Boolean = file(context).isFile
    fun box64Available(context: Context): Boolean = box32Available(context) || box64File(context).isFile
    fun box32Available(context: Context): Boolean = box32File(context).isFile
    fun box32Bundled(context: Context): Boolean = runCatching { context.assets.open(BOX32_MANIFEST).close(); true }.getOrDefault(false)
    fun verifiedLinuxBox(context: Context): File {
        if (!box32Available(context)) return verifiedBox64(context)
        val binary = box32File(context)
        require(binary.isFile && binary.canExecute()) { "Install Box64 with Box32 for Linux Proton" }
        val manifest = context.assets.open(BOX32_MANIFEST).bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getBoolean("box32") && manifest.getString("sourceCommit") ==
            "420eda69ff51ab5d5a7cbc3dee2e70433be3411d") { "Unexpected Box32 build" }
        val contents = binary.readBytes()
        require(sha(contents) == manifest.getString("binarySha256")) { "Repair the Linux Box64/Box32 component" }
        validateBox64(contents, hostPageSize())
        return binary
    }
    fun verifiedBox64(context: Context): File {
        if (box32Available(context)) return verifiedLinuxBox(context)
        val binary = box64File(context)
        require(binary.isFile && binary.canExecute()) { "Install Box64 before using Linux Proton" }
        val contents = binary.readBytes()
        require(sha(contents) == box64Sha(hostPageSize())) { "Repair the Box64 component before using Linux Proton" }
        validateBox64(contents, hostPageSize())
        return binary
    }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    suspend fun install(context: Context, http: EpicHttp) = withContext(Dispatchers.IO) {
        val compressed = http.get(Request.Builder().url(SOURCE).build(), 4 * 1024 * 1024)
        require(sha(compressed) == ARCHIVE_SHA) { "WoWBox64 archive checksum failed" }
        val archive = Zstd.decompress(compressed, 0, compressed.size, 8 * 1024 * 1024)
            ?: error("WoWBox64 archive could not be decoded")
        val dll = dllFromTar(archive)
        require(sha(dll) == DLL_SHA) { "WoWBox64 DLL checksum failed" }
        validateDll(dll)
        val dest = file(context).also { it.parentFile!!.mkdirs() }
        val temp = File(dest.parentFile, dest.name + ".tmp"); temp.writeBytes(dll)
        Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    suspend fun installBox64(context: Context, http: EpicHttp) = withContext(Dispatchers.IO) {
        if (box32Bundled(context)) {
            installBox32(context)
            return@withContext
        }
        val pageSize = hostPageSize()
        val binary = if (pageSize > 4096) {
            require(pageSize <= 16384) { "No Box64 build supports this Android page size" }
            val compressed = context.assets.open(BOX64_16K_ASSET).use { it.readBytes() }
            require(sha(compressed) == BOX64_16K_ARCHIVE_SHA) { "Box64 16 KB asset checksum failed" }
            Zstd.decompress(compressed, 0, compressed.size, 32 * 1024 * 1024)
                ?: error("Box64 16 KB asset could not be decoded")
        } else {
            val compressed = http.get(Request.Builder().url(BOX64_SOURCE).build(), 5 * 1024 * 1024)
            require(sha(compressed) == BOX64_ARCHIVE_SHA) { "Box64 archive checksum failed" }
            val archive = Zstd.decompress(compressed, 0, compressed.size, 32 * 1024 * 1024)
                ?: error("Box64 archive could not be decoded")
            tarEntry(archive, "./usr/bin/box64")
        }
        require(sha(binary) == box64Sha(pageSize)) { "Box64 binary checksum failed" }
        validateBox64(binary, pageSize)
        val dest = box64File(context).also { it.parentFile!!.mkdirs() }
        val temp = File(dest.parentFile, dest.name + ".tmp")
        temp.writeBytes(binary)
        check(temp.setExecutable(true, true)) { "Android could not make Box64 executable" }
        Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    private fun installBox32(context: Context) {
        val manifest = context.assets.open(BOX32_MANIFEST).bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getInt("schema") == 1 && manifest.getBoolean("box32") &&
            manifest.getString("sourceCommit") == "420eda69ff51ab5d5a7cbc3dee2e70433be3411d")
        val name = manifest.getString("asset")
        require(name == "box64-box32-android-arm64-16k.zst")
        val compressed = context.assets.open("runner/$name").use { it.readBytes() }
        require(sha(compressed) == manifest.getString("archiveSha256")) { "Box32 archive checksum failed" }
        val binary = Zstd.decompress(compressed, 0, compressed.size, 64 * 1024 * 1024)
            ?: error("Box32 archive could not be decoded")
        require(sha(binary) == manifest.getString("binarySha256")) { "Box32 binary checksum failed" }
        validateBox64(binary, hostPageSize())
        val dest = box32File(context).also { it.parentFile!!.mkdirs() }
        val temp = File(dest.parentFile, dest.name + ".tmp")
        temp.writeBytes(binary)
        check(temp.setExecutable(true, true)) { "Android could not make Box32 executable" }
        Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    suspend fun probeBox64(context: Context): String = withContext(Dispatchers.IO) {
        val binary = verifiedBox64(context)
        val process = ProcessBuilder(binary.absolutePath, "--version").redirectErrorStream(true).start()
        try {
            check(process.waitFor(10, TimeUnit.SECONDS)) { "Box64 startup timed out" }
            check(process.exitValue() == 0) { "Box64 could not start on this Android device" }
            "Box64 started on this device. Import Proton components and test a game to validate the Linux path."
        } finally { process.destroyForcibly() }
    }
    private fun box64Sha(pageSize: Int) = if (pageSize > 4096) BOX64_16K_BINARY_SHA else BOX64_BINARY_SHA
    fun configure(context: Context, prefix: File, choice: String) {
        require(choice in listOf("fex", "wowbox64")) { "Unknown x86 translator" }
        if (choice == "fex") return
        val dll = file(context).readBytes()
        require(sha(dll) == DLL_SHA) { "Install or repair the optional WoWBox64 component first" }
        validateDll(dll)
        val dest = File(prefix, "drive_c/windows/system32/wowbox64.dll").also { it.parentFile!!.mkdirs() }
        dest.writeBytes(dll)
        val registry = File(prefix, "system.reg")
        val updated = registry(registry.readText(), "wowbox64.dll")
        val temp = File(prefix, "system.reg.pocket-tmp"); temp.writeText(updated)
        Files.move(temp.toPath(), registry.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    fun registry(text: String, dll: String): String {
        require(dll == "wowbox64.dll")
        val header = "[Software\\\\Microsoft\\\\Wow64\\\\x86]"
        val lines = text.lines().toMutableList()
        val starts = lines.indices.filter { lines[it].startsWith(header, ignoreCase = true) }
        require(starts.size == 1) { "Unexpected Wine x86 translator registry; prefix kept" }
        val start = starts.single()
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("[") } ?: lines.size
        val keys = (start + 1 until end).filter { lines[it].startsWith("@=") }
        require(keys.size == 1 && lines[keys.single()] in listOf("@=\"libwow64fex.dll\"", "@=\"wowbox64.dll\"")) { "Unknown existing x86 translator; prefix kept" }
        lines[keys.single()] = "@=\"$dll\""
        return lines.joinToString("\n")
    }
    fun validateDll(bytes: ByteArray) {
        require(bytes.size >= 64 && bytes[0] == 77.toByte() && bytes[1] == 90.toByte())
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = b.getInt(60)
        require(offset >= 64 && offset.toLong() + 6 <= bytes.size && b.getInt(offset) == 0x4550 &&
            b.getShort(offset + 4).toInt() and 65535 == 0xaa64) { "WoWBox64 must be an ARM64 Windows DLL" }
    }
    fun dllFromTar(bytes: ByteArray): ByteArray = tarEntry(bytes, "wowbox64.dll")
    fun tarEntry(bytes: ByteArray, target: String): ByteArray {
        var offset = 0
        while (offset + 512 <= bytes.size) {
            val header = bytes.copyOfRange(offset, offset + 512)
            if (header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000')
            val length = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toIntOrNull(8) ?: error("Invalid WoWBox64 TAR")
            require(length >= 0 && offset.toLong() + 512 + length <= bytes.size)
            if (name == target && header[156].toInt() in listOf(0, 48))
                return bytes.copyOfRange(offset + 512, offset + 512 + length)
            offset += 512 + ((length + 511) / 512) * 512
        }
        error("Runtime archive is missing its expected executable")
    }
    fun validateBox64(bytes: ByteArray, pageSize: Int = 4096) {
        require(bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() && bytes[4] == 2.toByte() &&
            bytes[5] == 1.toByte()) { "Box64 must be a little-endian 64-bit ELF" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getShort(18).toInt() and 65535 == 183) { "Box64 must be ARM64" }
        val offset = b.getLong(32)
        val size = b.getShort(54).toInt() and 65535
        val count = b.getShort(56).toInt() and 65535
        require(size >= 56 && count in 1..128 && offset >= 64 && offset <= bytes.size.toLong() - size.toLong() * count) {
            "Invalid Box64 program headers"
        }
        val interpreter = (0 until count).firstNotNullOfOrNull { index ->
            val header = offset.toInt() + index * size
            if (b.getInt(header) != 3) null else {
                val start = b.getLong(header + 8)
                val length = b.getLong(header + 32)
                require(start >= 0 && length in 1..256 && start <= bytes.size - length) { "Invalid Box64 linker path" }
                String(bytes, start.toInt(), length.toInt(), Charsets.US_ASCII).trimEnd('\u0000')
            }
        }
        require(interpreter == "/system/bin/linker64") { "Box64 must use Android's ARM64 linker" }
        val loads = (0 until count).filter { b.getInt(offset.toInt() + it * size) == 1 }
        require(loads.isNotEmpty()) { "Box64 has no loadable segments" }
        for (index in loads) {
            val header = offset.toInt() + index * size
            val fileOffset = b.getLong(header + 8)
            val virtualAddress = b.getLong(header + 16)
            val alignment = b.getLong(header + 48)
            require(alignment >= pageSize && fileOffset % pageSize == virtualAddress % pageSize) {
                "Box64 ELF segments do not support the device's page size"
            }
        }
    }
}

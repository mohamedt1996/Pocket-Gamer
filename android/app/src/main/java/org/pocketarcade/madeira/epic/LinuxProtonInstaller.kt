// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Streams user-selected upstream tar.gz packages into isolated app-private folders. */
object LinuxProtonInstaller {
    enum class Component(val folder: String, val anchor: String, val marker: String) {
        ROOTFS("rootfs", "usr/bin", "usr/bin/python3"),
        PROTON("proton", "files/bin", "proton"),
        EAC("eac_runtime", "v2/lib/x86_64-linux-gnu", "v2/lib/x86_64-linux-gnu/easyanticheat.so");
    }
    private data class Link(val name: String, val target: String, val hard: Boolean)
    private const val MAX_EXPANDED = 12L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 250_000

    data class Bundled(val component: Component, val asset: String, val bytes: Long, val sha256: String)
    fun installed(context: Context, component: Component): Boolean {
        val folder = File(LinuxProton.root(context), component.folder)
        return when (component) {
            Component.ROOTFS -> File(folder, component.marker).isFile
            Component.PROTON -> File(folder, component.marker).isFile && File(folder, "files/bin/wine").isFile
            Component.EAC -> File(folder, component.marker).isFile ||
                File(folder, "v2/lib/x86_64-linux-gnu/easyanticheat_x64.so").isFile
        }
    }
    fun bundled(context: Context): List<Bundled> = runCatching {
        val manifest = context.assets.open("linux-proton/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getInt("schema") == 1)
        val entries = manifest.getJSONObject("components")
        Component.entries.mapNotNull { component ->
            val item = entries.optJSONObject(component.folder) ?: return@mapNotNull null
            val filename = item.getString("asset")
            val size = item.getLong("bytes")
            val checksum = item.getString("sha256")
            require(filename == "${component.folder}.tgz" && size in 1..(2L shl 30) &&
                checksum.matches(Regex("[0-9a-f]{64}"))) { "Invalid bundled Linux package manifest" }
            Bundled(component, "linux-proton/$filename", size, checksum)
        }
    }.getOrDefault(emptyList())

    suspend fun installBundled(context: Context, bundled: Bundled) {
        val job = currentCoroutineContext()
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.assets.open(bundled.asset).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                job.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= bundled.bytes) { "Bundled ${bundled.component.folder} size mismatch" }
                hash.update(buffer, 0, count)
            }
        }
        val checksum = hash.digest().joinToString("") { "%02x".format(it) }
        require(size == bundled.bytes && checksum == bundled.sha256) { "Bundled ${bundled.component.folder} checksum failed" }
        context.assets.open(bundled.asset).use {
            extract(it, LinuxProton.root(context), bundled.component) { job.ensureActive() }
        }
    }

    suspend fun install(context: Context, component: Component, uri: Uri) {
        val job = currentCoroutineContext()
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the selected archive")
        input.use { extract(it, LinuxProton.root(context), component) { job.ensureActive() } }
    }
    fun extract(source: InputStream, root: File, component: Component, checkCanceled: () -> Unit = {}) {
        root.mkdirs()
        val stage = Files.createTempDirectory(root.toPath(), ".${component.folder}-").toFile()
        val links = mutableListOf<Link>()
        try {
            var total = 0L
            var count = 0
            TarArchiveInputStream(GZIPInputStream(source)).use { tar ->
                while (true) {
                    checkCanceled()
                    val entry = tar.nextTarEntry ?: break
                    count++
                    require(count <= MAX_ENTRIES) { "Runtime archive has too many entries" }
                    val name = safeName(entry.name)
                    if (name.isEmpty()) continue
                    val target = File(stage, name)
                    require(target.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "Archive path escapes staging folder" }
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink || entry.isLink -> links.add(Link(name, entry.linkName, entry.isLink))
                        entry.isFile -> {
                            require(entry.size >= 0 && entry.size <= MAX_EXPANDED - total) { "Runtime archive exceeds size limit" }
                            total += entry.size
                            target.parentFile!!.mkdirs()
                            target.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    checkCanceled()
                                    val count = tar.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
                        }
                        else -> error("Unsupported runtime archive entry")
                    }
                }
            }
            val candidate = if (File(stage, component.anchor).exists()) stage else {
                val choices = stage.listFiles()?.filter { File(it, component.anchor).exists() }.orEmpty()
                require(choices.size == 1 && choices.single().isDirectory) { "Archive does not contain a complete ${component.folder} folder" }
                choices.single()
            }
            val candidatePath = candidate.toPath().toAbsolutePath().normalize()
            for (link in links) {
                val linkPath = File(stage, link.name).toPath().toAbsolutePath().normalize()
                require(linkPath.startsWith(candidatePath)) { "Archive link is outside its component" }
                val dest = linkPath
                val target = if (link.target.startsWith('/')) candidatePath.resolve(link.target.removePrefix("/"))
                    else if (link.hard) stage.toPath().toAbsolutePath().resolve(link.target)
                    else dest.parent.resolve(link.target)
                val resolved = target.normalize()
                require(resolved.startsWith(candidatePath)) { "Archive link escapes its component" }
                Files.createDirectories(dest.parent)
                if (link.hard) {
                    require(Files.isRegularFile(resolved)) { "Archive hard link target is missing" }
                    Files.createLink(dest, resolved)
                } else Files.createSymbolicLink(dest, dest.parent.relativize(resolved))
            }
            if (component != Component.EAC)
                require(File(candidate, component.marker).isFile) { "Archive is missing ${component.marker}" }
            if (component == Component.PROTON)
                require(File(candidate, "files/bin/wine").isFile) { "Archive is missing Proton's Wine binary" }
            if (component == Component.EAC)
                require(File(candidate, "v2/lib/x86_64-linux-gnu/easyanticheat.so").isFile ||
                    File(candidate, "v2/lib/x86_64-linux-gnu/easyanticheat_x64.so").isFile) { "Archive is missing the EAC bridge" }
            checkCanceled()
            val dest = File(root, component.folder)
            val backup = File(root, ".${component.folder}-old")
            deleteTree(backup.toPath())
            if (dest.exists()) Files.move(dest.toPath(), backup.toPath())
            try { Files.move(candidate.toPath(), dest.toPath()) }
            catch (e: Exception) {
                if (backup.exists()) Files.move(backup.toPath(), dest.toPath())
                throw e
            }
            deleteTree(backup.toPath())
        } finally { deleteTree(stage.toPath()) }
    }
    private fun safeName(name: String): String {
        require(name.length <= 1024 && !name.contains('\u0000') && !name.startsWith('/')) { "Unsafe runtime archive path" }
        val normalized = name.removePrefix("./").trimEnd('/')
        require(normalized.split('/').none { it == ".." || it == "" && normalized.isNotEmpty() }) { "Unsafe runtime archive path" }
        return normalized
    }
    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}

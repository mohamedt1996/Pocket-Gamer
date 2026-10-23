// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.epic

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class LinuxProtonInstallerTest {
    private fun archive(block: (TarArchiveOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { gzip -> TarArchiveOutputStream(gzip).use(block) }
        return bytes.toByteArray()
    }
    private fun file(tar: TarArchiveOutputStream, name: String, contents: String) {
        val value = contents.toByteArray()
        val entry = TarArchiveEntry(name).apply { size = value.size.toLong(); mode = 493 }
        tar.putArchiveEntry(entry); tar.write(value); tar.closeArchiveEntry()
    }
    @Test fun importsCompleteProtonAndKeepsRelativeLinks() {
        val root = Files.createTempDirectory("linux-proton-import").toFile()
        try {
            val input = archive { tar ->
                file(tar, "GE-Proton/proton", "#!/usr/bin/env python3")
                file(tar, "GE-Proton/files/bin/wine", "wine")
                val link = TarArchiveEntry("GE-Proton/files/bin/wine64", TarConstants.LF_SYMLINK).apply { linkName = "wine" }
                tar.putArchiveEntry(link); tar.closeArchiveEntry()
            }
            LinuxProtonInstaller.extract(ByteArrayInputStream(input), root, LinuxProtonInstaller.Component.PROTON)
            assertTrue(root.resolve("proton/proton").isFile)
            assertEquals("wine", Files.readSymbolicLink(root.resolve("proton/files/bin/wine64").toPath()).toString())
        } finally { root.deleteRecursively() }
    }
    @Test fun traversalArchiveCannotReplaceExistingRuntime() {
        val root = Files.createTempDirectory("linux-proton-import").toFile()
        try {
            val old = root.resolve("proton/proton").apply { parentFile!!.mkdirs(); writeText("old") }
            val input = archive { tar -> file(tar, "../escape", "bad") }
            assertThrows(IllegalArgumentException::class.java) {
                LinuxProtonInstaller.extract(ByteArrayInputStream(input), root, LinuxProtonInstaller.Component.PROTON)
            }
            assertEquals("old", old.readText())
            assertTrue(!root.parentFile!!.resolve("escape").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun bundledUpstreamArchivesExtractOnHostWhenPresent() {
        val source = File(System.getenv("LINUX_PROTON_REAL_ARCHIVES") ?: "")
        assumeTrue(System.getenv("LINUX_PROTON_REAL_ARCHIVES") != null &&
            File(source, "proton.tgz").isFile && File(source, "rootfs.tgz").isFile)
        val root = Files.createTempDirectory("linux-proton-real").toFile()
        try {
            listOf(LinuxProtonInstaller.Component.ROOTFS, LinuxProtonInstaller.Component.PROTON).forEach { component ->
                File(source, "${component.folder}.tgz").inputStream().use {
                    LinuxProtonInstaller.extract(it, root, component)
                }
            }
            assertTrue(File(root, "rootfs/usr/bin/python3").isFile)
            assertTrue(File(root, "rootfs/usr/lib/x86_64-linux-gnu/libc.so.6").isFile)
            assertTrue(File(root, "proton/proton").isFile)
            assertTrue(File(root, "proton/files/bin/wine").isFile)
        } finally { root.deleteRecursively() }
    }
}

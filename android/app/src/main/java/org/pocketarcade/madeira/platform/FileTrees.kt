// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// Deleting folders that may contain a Wine prefix. A prefix holds symbolic links
// out of itself (dosdevices/z: points at "/"), and Kotlin's File.deleteRecursively
// follows links into directories. These helpers delete a link, never what it points to.

package org.pocketarcade.madeira.platform

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object FileTrees {
    /** Deletes [root] and everything under it without following symbolic links. True when nothing is left. */
    @JvmStatic
    fun delete(root: File): Boolean {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            // walkFileTree does not follow links unless asked: a link is visited as a file and removed itself.
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                }
            })
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            false
        }
    }
}

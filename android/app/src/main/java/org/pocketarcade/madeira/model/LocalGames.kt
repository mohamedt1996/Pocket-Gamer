// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// The library's "Other games": programs added by hand (an EXE, an installer or
// a game ZIP), each in its own Wine prefix (GameStore), with the same per-game
// settings as Steam games.

package org.pocketarcade.madeira.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pocketarcade.madeira.GameStore
import org.pocketarcade.madeira.core.PeInspector
import java.io.File

data class LocalGame(
    val id: String,
    val title: String,
    val exe: String,
    val cover: String?,
    val config: GameConfig,
) {
    val folder: File get() = File(exe).parentFile ?: File(exe)
}

class LocalGames(private val context: Context) {
    private val store = GameStore(context)
    private val _games = MutableStateFlow<List<LocalGame>>(emptyList())
    val games: StateFlow<List<LocalGame>> = _games

    fun directory(id: String): File = store.directory(id)

    suspend fun refresh() {
        val list = withContext(Dispatchers.IO) {
            try {
                store.list().filter { !it.optBoolean("hidden", false) }.map { o -> from(o) }
            } catch (_: Exception) { emptyList() }
        }
        _games.value = list
    }

    private fun from(o: JSONObject) = LocalGame(o.optString("id"), o.optString("title"), o.optString("exe"),
        o.optString("cover", "").ifEmpty { null }, GameConfig.from(o.optJSONObject("config")).let { c ->
            if (c.graphics == "auto" && o.has("graphics") && !o.has("config")) c.copy(graphics = o.optString("graphics", "auto")) else c
        })

    fun game(id: String) = _games.value.firstOrNull { it.id == id }

    /** Adds an EXE, MSI or game ZIP; returns the new game's id. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val id = store.importGame(uri)
        val o = store.settings(id)
        o.put("config", GameConfig(added = System.currentTimeMillis()).toJson())
        store.save(id, o)
        inspect(id)
        id
    }

    /** Bitness and graphics APIs of the game's program, for its pills and the "auto" graphics choice. */
    suspend fun inspect(id: String) = withContext(Dispatchers.IO) {
        val o = store.settings(id)
        val exe = File(o.optString("exe"))
        if (!exe.isFile) return@withContext
        val info = PeInspector.inspect(exe)
        val apis = PeInspector.graphicsAPIs(exe)
        val size = exe.parentFile?.walkTopDown()?.take(200_000)?.filter { it.isFile }?.sumOf { it.length() } ?: 0
        val config = GameConfig.from(o.optJSONObject("config")).copy(bits = info?.bits ?: 0, apis = apis.sorted(), sizeBytes = size)
        o.put("config", config.toJson())
        store.save(id, o)
    }

    suspend fun update(id: String, change: (LocalGame) -> LocalGame) = withContext(Dispatchers.IO) {
        val current = game(id) ?: return@withContext
        val next = change(current)
        val o = store.settings(id)
        o.put("title", next.title); o.put("exe", next.exe)
        if (next.cover != null) o.put("cover", next.cover) else o.remove("cover")
        o.put("config", next.config.toJson())
        o.put("graphics", next.config.resolvedGraphics())
        store.save(id, o)
        refresh()
    }

    /** The game's .exe files, for the Program picker. */
    fun programs(id: String): List<File> {
        val root = File(store.directory(id), "files")
        return root.walkTopDown().maxDepth(8).filter { it.isFile && it.name.lowercase().endsWith(".exe") }.take(400).sortedBy { it.path.lowercase() }.toList()
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        // The game folder holds its Wine prefix, whose z: drive links to "/".
        org.pocketarcade.madeira.platform.FileTrees.delete(store.directory(id))
        refresh()
    }

    /** Copies a picked image as the game's cover. */
    suspend fun setCover(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val dest = File(store.directory(id), "cover.img")
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
        update(id) { it.copy(cover = dest.absolutePath) }
    }

    fun store(): GameStore = store
}

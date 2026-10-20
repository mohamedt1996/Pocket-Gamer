// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// App-wide settings and per-game settings, kept in SharedPreferences and small
// JSON files. Both the app process and the :wine process read them.

package org.pocketarcade.madeira.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))
    private fun str(key: String, default: String) = MutableStateFlow(prefs.getString(key, default) ?: default)

    private val _layout = str("layout", "cards")
    private val _sort = str("sort", "played")
    private val _liquidMetal = bool("liquidMetal", true)
    private val _ambient = bool("ambient", true)
    private val _steamCloud = bool("steamCloud", true)
    private val _cloudAutomatic = bool("cloudAutomatic", true)
    private val _cloudPlayCheck = bool("cloudPlayCheck", true)
    private val _diagnostics = bool("diagnostics", false)
    private val _steamCollapsed = bool("steamCollapsed", false)
    private val _notInstalledCollapsed = bool("notInstalledCollapsed", false)
    private val _otherCollapsed = bool("otherCollapsed", false)
    private val _theme = str("theme", "system")

    val layout: StateFlow<String> = _layout
    val sort: StateFlow<String> = _sort
    val liquidMetal: StateFlow<Boolean> = _liquidMetal
    val ambient: StateFlow<Boolean> = _ambient
    val steamCloud: StateFlow<Boolean> = _steamCloud
    val cloudAutomatic: StateFlow<Boolean> = _cloudAutomatic
    val cloudPlayCheck: StateFlow<Boolean> = _cloudPlayCheck
    val diagnostics: StateFlow<Boolean> = _diagnostics
    val steamCollapsed: StateFlow<Boolean> = _steamCollapsed
    val notInstalledCollapsed: StateFlow<Boolean> = _notInstalledCollapsed
    val otherCollapsed: StateFlow<Boolean> = _otherCollapsed
    /** "system", "dark" or "light". */
    val theme: StateFlow<String> = _theme

    private fun put(key: String, flow: MutableStateFlow<Boolean>, v: Boolean) { flow.value = v; prefs.edit().putBoolean(key, v).apply() }
    private fun put(key: String, flow: MutableStateFlow<String>, v: String) { flow.value = v; prefs.edit().putString(key, v).apply() }

    fun setLayout(v: String) = put("layout", _layout, v)
    fun setSort(v: String) = put("sort", _sort, v)
    fun setLiquidMetal(v: Boolean) = put("liquidMetal", _liquidMetal, v)
    fun setAmbient(v: Boolean) = put("ambient", _ambient, v)
    fun setSteamCloud(v: Boolean) = put("steamCloud", _steamCloud, v)
    fun setCloudAutomatic(v: Boolean) = put("cloudAutomatic", _cloudAutomatic, v)
    fun setCloudPlayCheck(v: Boolean) = put("cloudPlayCheck", _cloudPlayCheck, v)
    fun setDiagnostics(v: Boolean) = put("diagnostics", _diagnostics, v)
    fun setSteamCollapsed(v: Boolean) = put("steamCollapsed", _steamCollapsed, v)
    fun setNotInstalledCollapsed(v: Boolean) = put("notInstalledCollapsed", _notInstalledCollapsed, v)
    fun setOtherCollapsed(v: Boolean) = put("otherCollapsed", _otherCollapsed, v)
    fun setTheme(v: String) = put("theme", _theme, v)

    /** The setup revision this device last finished or skipped (Onboarding). */
    var onboardingRevision: Int
        get() = prefs.getInt("onboardingRevision", 0)
        set(v) { prefs.edit().putInt("onboardingRevision", v).apply() }
}

/** Settings of one game in the library (a Steam game by App ID, or a game added by hand). */
data class GameConfig(
    /** "auto", "wine", "dxvk" or "vkd3d". */
    val graphics: String = "auto",
    /** Steam games: "dock" (Valve's client through Madeira Dock) or "game" (the program itself). */
    val startWith: String = "dock",
    /** Steam games started directly: the program, relative to the install folder. */
    val program: String? = null,
    val arguments: String = "",
    val touchControls: Boolean = true,
    val controlLayout: String? = null,
    val performance: Boolean = false,
    /** Extra environment for Wine, "NAME=value" per line. */
    val environment: String = "",
    /** Extra DLL overrides, Wine's WINEDLLOVERRIDES syntax. */
    val dllOverrides: String = "",
    val lastPlayed: Long = 0,
    val added: Long = 0,
    /** Detected graphics APIs of the program ("D3D11", ...). */
    val apis: List<String> = emptyList(),
    val bits: Int = 0,
    val sizeBytes: Long = 0,
    /** Gamepad buttons to keys: "A" -> Android keycode name. */
    val binds: Map<String, String> = emptyMap(),
    val stickMouse: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("graphics", graphics); put("startWith", startWith); program?.let { put("program", it) }
        put("arguments", arguments); put("touchControls", touchControls); controlLayout?.let { put("controlLayout", it) }
        put("performance", performance); put("environment", environment); put("dllOverrides", dllOverrides)
        put("lastPlayed", lastPlayed); put("added", added); put("apis", JSONArray(apis)); put("bits", bits); put("sizeBytes", sizeBytes)
        put("binds", JSONObject(binds as Map<*, *>)); put("stickMouse", stickMouse)
    }

    /** The graphics path a launch uses: the choice, or for "auto" the one the program's APIs suit. */
    fun resolvedGraphics(): String = if (graphics != "auto") graphics else org.pocketarcade.madeira.core.PeInspector.recommendedGraphics(apis.toSet())

    companion object {
        fun from(o: JSONObject?): GameConfig {
            if (o == null) return GameConfig()
            val apis = o.optJSONArray("apis")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val binds = o.optJSONObject("binds")?.let { b -> b.keys().asSequence().associateWith { b.optString(it) } } ?: emptyMap()
            return GameConfig(
                graphics = o.optString("graphics", "auto").let { if (it in setOf("auto", "wine", "dxvk", "vkd3d")) it else "auto" },
                startWith = o.optString("startWith", "dock"),
                program = o.optString("program", "").ifEmpty { null },
                arguments = o.optString("arguments", ""),
                touchControls = o.optBoolean("touchControls", true),
                controlLayout = o.optString("controlLayout", "").ifEmpty { null },
                performance = o.optBoolean("performance", false),
                environment = o.optString("environment", ""),
                dllOverrides = o.optString("dllOverrides", ""),
                lastPlayed = o.optLong("lastPlayed", 0), added = o.optLong("added", 0),
                apis = apis, bits = o.optInt("bits", 0), sizeBytes = o.optLong("sizeBytes", 0),
                binds = binds, stickMouse = o.optBoolean("stickMouse", true),
            )
        }

        /** "NAME=value" lines into a map; names are letters, digits and underscores. */
        fun parseEnvironment(text: String): Map<String, String> = text.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val eq = t.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = t.substring(0, eq).trim()
            if (!name.all { it.isLetterOrDigit() || it == '_' }) return@mapNotNull null
            name to t.substring(eq + 1).trim()
        }.toMap()
    }
}

/** Per-game settings of Steam games, one JSON file per App ID. */
class SteamGameConfigs(context: Context) {
    private val dir = File(context.filesDir, "steam-games").apply { mkdirs() }
    private val cache = HashMap<Int, GameConfig>()
    private val _changes = MutableStateFlow(0)
    /** Bumped on every save, so screens can recompose. */
    val changes: StateFlow<Int> = _changes

    @Synchronized fun get(appID: Int): GameConfig = cache.getOrPut(appID) {
        val f = File(dir, "$appID.json")
        GameConfig.from(if (f.isFile) try { JSONObject(f.readText()) } catch (_: Exception) { null } else null)
    }

    @Synchronized fun has(appID: Int) = File(dir, "$appID.json").isFile

    @Synchronized fun save(appID: Int, config: GameConfig) {
        cache[appID] = config
        val f = File(dir, "$appID.json"); val tmp = File(dir, "$appID.json.tmp")
        tmp.writeText(config.toJson().toString(2))
        tmp.renameTo(f)
        _changes.value++
    }

    @Synchronized fun update(appID: Int, change: (GameConfig) -> GameConfig) = save(appID, change(get(appID)))

    @Synchronized fun remove(appID: Int) { cache.remove(appID); File(dir, "$appID.json").delete(); _changes.value++ }
}

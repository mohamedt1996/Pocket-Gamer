// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// The phone's GPU, as its own Vulkan driver reports it, and which of the app's Direct3D
// translation layers that driver can run (gpu_caps.c checks the pinned DXVK and
// VKD3D-Proton requirements). Probed once per app version and driver, then cached.

package org.pocketarcade.madeira.platform

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

object GpuCaps {
    data class Info(
        val vulkan: Boolean,
        val name: String = "",
        val api: String = "",
        val driver: String = "",
        val driverInfo: String = "",
        /** BC (DXT) textures: every Direct3D game uses them; phone drivers often lack them. */
        val bc: Boolean = false,
        /** DXVK (Direct3D 8-11) can create a device. */
        val dxvk: Boolean = false,
        /** VKD3D-Proton (Direct3D 12) can create a device. */
        val vkd3d: Boolean = false,
        /** Wine's own Direct3D on its Vulkan renderer (decodes BC textures itself when needed). */
        val wined3d: Boolean = false,
        val dxvkMissing: List<String> = emptyList(),
        val vkd3dMissing: List<String> = emptyList(),
        val error: String? = null,
    ) {
        /** "Adreno (TM) 740 · Vulkan 1.3.128 · Qualcomm proprietary". */
        fun summary(): String = if (!vulkan) "No Vulkan driver" + (error?.let { " ($it)" } ?: "")
            else listOf(name, "Vulkan $api", driver.ifBlank { null }).filterNotNull().joinToString(" · ")
    }

    @Volatile var current: Info? = null
        private set

    @JvmStatic private external fun nativeProbe(): String

    fun parse(json: String): Info {
        val o = JSONObject(json)
        fun list(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return Info(
            vulkan = o.optBoolean("vulkan", false), name = o.optString("name"), api = o.optString("api"),
            driver = o.optString("driver"), driverInfo = o.optString("driverInfo"), bc = o.optBoolean("bc"),
            dxvk = o.optBoolean("dxvk"), vkd3d = o.optBoolean("vkd3d"), wined3d = o.optBoolean("wined3d"),
            dxvkMissing = list("dxvkMissing"), vkd3dMissing = list("vkd3dMissing"),
            error = o.optString("error").ifBlank { null },
        )
    }

    /** The cached result, or a fresh probe when the app or the system (and so the driver) changed. */
    fun load(context: Context): Info? {
        current?.let { return it }
        val stamp = "${appVersion(context)}|${Build.FINGERPRINT}"
        val cache = File(context.filesDir, "gpu-caps.json")
        val info = runCatching {
            val saved = cache.takeIf { it.isFile }?.readText()?.let { JSONObject(it) }
            if (saved != null && saved.optString("stamp") == stamp) parse(saved.getString("caps"))
            else {
                System.loadLibrary("madeira_probe")
                val json = nativeProbe()
                cache.writeText(JSONObject().put("stamp", stamp).put("caps", json).toString())
                parse(json)
            }
        }.getOrNull()
        current = info
        return info
    }

    private fun appVersion(context: Context): Long = runCatching {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }.getOrDefault(0)
}

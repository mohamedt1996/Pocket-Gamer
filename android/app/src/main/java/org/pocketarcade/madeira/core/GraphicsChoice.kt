// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// Fitting a game's graphics path to the phone's GPU: DXVK and VKD3D-Proton need features many
// phone drivers lack (most visibly BC textures), so "Auto" steps down to what the driver can run
// instead of letting the game fail at device creation.

package org.pocketarcade.madeira.core

import org.pocketarcade.madeira.platform.GpuCaps

object GraphicsChoice {
    /**
     * WANTED is "wine", "dxvk" or "vkd3d" (DXVK + VKD3D-Proton). Returns the closest path this GPU
     * can run: VKD3D-Proton -> DXVK with Wine's own Direct3D 12 -> Wine's Direct3D (Vulkan
     * renderer, Direct3D 12 through Wine's vkd3d). An unknown GPU keeps WANTED.
     */
    fun fit(wanted: String, gpu: GpuCaps.Info?): String {
        if (gpu == null || !gpu.vulkan) return wanted
        return when (wanted) {
            "vkd3d" -> when { gpu.vkd3d -> "vkd3d"; gpu.dxvk -> "dxvk"; else -> "wine" }
            "dxvk" -> if (gpu.dxvk) "dxvk" else "wine"
            else -> wanted
        }
    }

    /** One sentence on why FIT stepped down from WANTED, or null when it did not. */
    fun reason(wanted: String, fit: String, gpu: GpuCaps.Info?): String? {
        if (gpu == null || wanted == fit) return null
        val missing = (if (wanted == "vkd3d" && !gpu.vkd3d) gpu.vkd3dMissing else gpu.dxvkMissing).take(4).joinToString(", ")
        val layer = if (wanted == "vkd3d") "VKD3D-Proton" else "DXVK"
        return "This GPU's driver cannot run $layer (missing: $missing), so this game uses ${if (fit == "dxvk") "DXVK" else "Wine's own Direct3D"} instead."
    }
}

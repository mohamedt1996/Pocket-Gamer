// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors
//
// CPU and heat: the frame cap and the FEX translation mode of a launch, as the
// environment DXVK, VKD3D-Proton and FEX read. Pure functions, unit tested.

package org.pocketarcade.madeira.core

object Performance {
    /** Frame cap choices: "auto", a number of frames per second, or "off". */
    val FRAME_CAPS = listOf("auto", "30", "40", "45", "60", "off")

    /** CPU translation modes: "compatible", "balanced" (FEX's defaults) or "fast". */
    val CPU_MODES = listOf("compatible", "balanced", "fast")

    /** Frames per second for a choice; 0 means no cap. "auto" is 60, or 30 when Android's
     *  game mode is "battery". */
    fun frameCap(choice: String, batteryMode: Boolean): Int = when (choice) {
        "off" -> 0
        "auto" -> if (batteryMode) 30 else 60
        else -> choice.toIntOrNull()?.takeIf { it in 10..240 } ?: 60
    }

    /**
     * The environment for a launch.
     *
     * Frame cap: every frame not drawn is CPU and GPU time not spent, which is the biggest
     * single lever on heat. DXVK reads it from DXVK_CONFIG (Direct3D 8–11), VKD3D-Proton from
     * VKD3D_FRAME_RATE (Direct3D 12).
     *
     * Shader compilation: DXVK compiles on as many threads as the phone has cores, which
     * heats every core at once while loading. Three threads keep that in check.
     *
     * CPU modes (FEX options, read from FEX_<OPTION> by its Windows modules):
     * - balanced: FEX's defaults (x86 memory ordering kept, half barriers for unaligned access).
     * - compatible: also orders vector and memcpy/memset accesses, for games that glitch or
     *   crash in "balanced". Slower.
     * - fast: no x86 memory-ordering emulation and reduced x87 precision. Clearly less CPU
     *   work, but multithreaded games can crash or misbehave; for games that run well.
     */
    fun environment(frameCap: Int, cpuMode: String): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        val dxvk = mutableListOf("dxvk.numCompilerThreads = 3")
        if (frameCap > 0) {
            dxvk += "dxgi.maxFrameRate = $frameCap"
            dxvk += "d3d9.maxFrameRate = $frameCap"
            env["VKD3D_FRAME_RATE"] = frameCap.toString()
        }
        env["DXVK_CONFIG"] = dxvk.joinToString("; ")
        when (cpuMode) {
            "compatible" -> {
                env["FEX_VECTORTSOENABLED"] = "1"
                env["FEX_MEMCPYSETTSOENABLED"] = "1"
            }
            "fast" -> {
                env["FEX_TSOENABLED"] = "0"
                env["FEX_X87REDUCEDPRECISION"] = "1"
            }
        }
        return env
    }
}

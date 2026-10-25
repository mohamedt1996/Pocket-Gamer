// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors

package org.pocketarcade.madeira.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketarcade.madeira.model.GameConfig
import org.json.JSONObject

class PerformanceTest {
    @Test fun autoCapFollowsBatteryMode() {
        assertEquals(60, Performance.frameCap("auto", batteryMode = false))
        assertEquals(30, Performance.frameCap("auto", batteryMode = true))
    }

    @Test fun systemPowerSaverLowersOnlyAutomaticCap() {
        assertEquals(30, Performance.frameCap("auto", batteryMode = false, powerSaveMode = true))
        assertEquals(30, Performance.frameCap("auto", batteryMode = true, powerSaveMode = true))
        assertEquals(48, Performance.frameCap("48", batteryMode = false, powerSaveMode = true))
        assertEquals(0, Performance.frameCap("off", batteryMode = true, powerSaveMode = true))
    }

    @Test fun tabletCapSurvivesSettingsAndReachesBothRenderers() {
        val config = GameConfig.from(GameConfig(frameCap = "48").toJson())
        assertEquals("48", config.frameCap)
        val cap = Performance.frameCap(config.frameCap, batteryMode = false)
        val env = Performance.environment(cap, config.cpuMode)
        assertEquals("48", env["VKD3D_FRAME_RATE"])
        assertTrue(env.getValue("DXVK_CONFIG").contains("dxgi.maxFrameRate = 48"))
        assertTrue(env.getValue("DXVK_CONFIG").contains("d3d9.maxFrameRate = 48"))
    }

    @Test fun explicitCapsAndOff() {
        assertEquals(40, Performance.frameCap("40", batteryMode = true))
        assertEquals(0, Performance.frameCap("off", batteryMode = true))
        assertEquals(60, Performance.frameCap("garbage", batteryMode = false))
        assertEquals(60, Performance.frameCap("5", batteryMode = false))
    }

    @Test fun capReachesDxvkAndVkd3d() {
        val env = Performance.environment(45, "balanced")
        assertEquals("45", env["VKD3D_FRAME_RATE"])
        val dxvk = env.getValue("DXVK_CONFIG")
        assertTrue(dxvk.contains("dxgi.maxFrameRate = 45"))
        assertTrue(dxvk.contains("d3d9.maxFrameRate = 45"))
        assertTrue(dxvk.contains("dxvk.numCompilerThreads = 3"))
        assertFalse(env.keys.any { it.startsWith("FEX_") })
    }

    @Test fun noCapLeavesLimitersOut() {
        val env = Performance.environment(0, "balanced")
        assertFalse(env.containsKey("VKD3D_FRAME_RATE"))
        assertFalse(env.getValue("DXVK_CONFIG").contains("maxFrameRate"))
    }

    @Test fun cpuModesSetFexOptions() {
        val fast = Performance.environment(60, "fast")
        assertEquals("0", fast["FEX_TSOENABLED"]); assertEquals("1", fast["FEX_X87REDUCEDPRECISION"])
        val compatible = Performance.environment(60, "compatible")
        assertEquals("1", compatible["FEX_VECTORTSOENABLED"]); assertEquals("1", compatible["FEX_MEMCPYSETTSOENABLED"])
    }

    @Test fun gameConfigKeepsChoicesAndRejectsUnknown() {
        val c = GameConfig.from(GameConfig(frameCap = "30", cpuMode = "fast").toJson())
        assertEquals("30", c.frameCap); assertEquals("fast", c.cpuMode)
        val bad = GameConfig.from(JSONObject().put("frameCap", "999").put("cpuMode", "turbo"))
        assertEquals("auto", bad.frameCap); assertEquals("balanced", bad.cpuMode)
    }
}

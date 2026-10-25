// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Pocket Arcade contributors

package org.pocketarcade.madeira.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketarcade.madeira.platform.GpuCaps

class GraphicsChoiceTest {
    private val full = GpuCaps.Info(vulkan = true, name = "Turnip", dxvk = true, vkd3d = true, wined3d = true, bc = true)
    private val noD3d12 = full.copy(vkd3d = false, vkd3dMissing = listOf("transform feedback"))
    private val noBc = GpuCaps.Info(vulkan = true, name = "Mali", wined3d = true, dxvkMissing = listOf("BC texture compression"),
        vkd3dMissing = listOf("BC texture compression"))

    @Test fun keepsWhatTheGpuRuns() {
        assertEquals("vkd3d", GraphicsChoice.fit("vkd3d", full))
        assertEquals("dxvk", GraphicsChoice.fit("dxvk", full))
        assertEquals("wine", GraphicsChoice.fit("wine", noBc))
        assertNull(GraphicsChoice.reason("dxvk", "dxvk", full))
    }

    @Test fun stepsDownToWhatTheDriverCanRun() {
        assertEquals("dxvk", GraphicsChoice.fit("vkd3d", noD3d12))
        assertEquals("wine", GraphicsChoice.fit("vkd3d", noBc))
        assertEquals("wine", GraphicsChoice.fit("dxvk", noBc))
        assertTrue(GraphicsChoice.reason("dxvk", "wine", noBc)!!.contains("BC texture compression"))
    }

    @Test fun unknownGpuKeepsTheChoice() {
        assertEquals("vkd3d", GraphicsChoice.fit("vkd3d", null))
        assertEquals("dxvk", GraphicsChoice.fit("dxvk", GpuCaps.Info(vulkan = false)))
    }
}

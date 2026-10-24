// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketarcade.madeira.platform.GpuCaps

class PocketDockTest {
    @Test fun selectsWineD3dWhenTabletDriverCannotRunDxvk() {
        val adreno = GpuCaps.Info(vulkan = true, name = "Adreno 732", api = "1.3.128",
            dxvk = false, dxvkMissing = listOf("BC texture compression"))
        assertEquals("1", PocketDock.graphicsEnvironment(adreno)["PROTON_USE_WINED3D"])
        assertTrue(PocketDock.graphicsEnvironment(adreno.copy(dxvk = true)).isEmpty())
        assertTrue(PocketDock.graphicsEnvironment(adreno, "dxvk").isEmpty())
        assertEquals("1", PocketDock.graphicsEnvironment(adreno.copy(dxvk = true), "wine")["PROTON_USE_WINED3D"])
    }
}

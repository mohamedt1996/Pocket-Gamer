// SPDX-License-Identifier: GPL-3.0-or-later
package org.pocketarcade.madeira.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketDockTest {
    @Test fun onlyExplicitWineChoiceDisablesGuestDxvk() {
        assertTrue(PocketDock.graphicsEnvironment().isEmpty())
        assertTrue(PocketDock.graphicsEnvironment("dxvk").isEmpty())
        assertEquals("1", PocketDock.graphicsEnvironment("wine")["PROTON_USE_WINED3D"])
    }
}

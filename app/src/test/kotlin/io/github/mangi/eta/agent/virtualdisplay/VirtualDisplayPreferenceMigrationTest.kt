package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VirtualDisplayPreferenceMigrationTest {
    @Test
    fun legacyEnabledAndDisabledArePreservedWithoutInventingAValueForNewInstall() {
        assertEquals(true, VirtualDisplayPreferenceMigration.valueToMigrate(null, true))
        assertEquals(false, VirtualDisplayPreferenceMigration.valueToMigrate(null, false))
        assertNull(VirtualDisplayPreferenceMigration.valueToMigrate(null, null))
    }

    @Test
    fun explicitNewSettingWinsAcrossRepeatedOrInterruptedMigration() {
        for (current in listOf(true, false)) {
            for (legacy in listOf(true, false, null)) {
                assertNull(VirtualDisplayPreferenceMigration.valueToMigrate(current, legacy))
            }
        }
    }
}

package com.dd3boh.xenowave.viewmodels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreCoordinatorTest {
    @Test
    fun `only one backup or restore can own the coordinator`() {
        assertTrue(BackupRestoreCoordinator.tryAcquire())
        assertFalse(BackupRestoreCoordinator.tryAcquire())
        BackupRestoreCoordinator.release()
        assertTrue(BackupRestoreCoordinator.tryAcquire())
        BackupRestoreCoordinator.release()
    }
}

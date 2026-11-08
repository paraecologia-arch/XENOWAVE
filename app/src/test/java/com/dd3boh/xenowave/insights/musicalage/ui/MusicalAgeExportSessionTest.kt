package com.dd3boh.xenowave.insights.musicalage.ui

import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeExportOperation
import com.dd3boh.xenowave.insights.musicalage.ui.visual.MusicalAgeTestFixtures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicalAgeExportSessionTest {
    @Test
    fun `a late completion from A cannot clear the state of B`() {
        val session = MusicalAgeExportSession()
        val operationA = operation("operation-a")
        val activeA = session.begin(operationA)
        assertSame(operationA, activeA!!.operation)
        // A second export cannot start while A owns the session.
        assertNull(session.begin(operation("operation-b")))

        session.cancelActive()
        assertTrue(operationA.isCancelled())
        assertTrue(session.finish(activeA))

        val operationB = operation("operation-b")
        val activeB = session.begin(operationB)
        assertSame(operationB, activeB!!.operation)
        // A's old finally must not release B.
        assertFalse(session.finish(activeA))
        assertSame(activeB, session.active)
    }

    @Test
    fun `only the active operation is cancelled`() {
        val session = MusicalAgeExportSession()
        val operation = operation("operation-a")
        val active = session.begin(operation)!!
        session.cancelActive()

        assertTrue(operation.isCancelled())
        assertTrue(session.finish(active))
        // Calling cancel after completion has no operation to affect.
        session.cancelActive()
        assertFalse(operation.isActive())
    }

    private fun operation(id: String): MusicalAgeExportOperation =
        MusicalAgeExportOperation(
            token = MusicalAgeExportToken(
                result = MusicalAgeTestFixtures.readyResult(),
                revision = 1L,
            ),
            validity = { true },
            id = id,
        )
}

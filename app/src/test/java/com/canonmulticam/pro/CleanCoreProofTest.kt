package com.canonmulticam.pro

import com.canonmulticam.pro.core.CameraTelemetry
import com.canonmulticam.pro.core.ConnectionPhase
import com.canonmulticam.pro.core.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CleanCoreProofTest {
    @Test
    fun initialTelemetryIsIdleAndSafe() {
        val state = CameraTelemetry()
        assertEquals(ConnectionPhase.IDLE, state.phase)
        assertEquals(TransportMode.NONE, state.transport)
        assertFalse(state.isRecording)
    }
}

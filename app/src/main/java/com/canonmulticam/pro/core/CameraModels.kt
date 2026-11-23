package com.canonmulticam.pro.core

import android.graphics.Bitmap

enum class TransportMode {
    NONE,
    USB_PTP,
    WIFI_BOUND
}

enum class ConnectionPhase {
    IDLE,
    SCANNING,
    AWAITING_PERMISSION,
    HANDSHAKING,
    STREAMING,
    ERROR
}

data class CameraTelemetry(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val transport: TransportMode = TransportMode.NONE,
    val cameraName: String = "STANDBY",
    val isRecording: Boolean = false,
    val fps: Int = 0,
    val frame: Bitmap? = null,
    val logs: List<String> = emptyList()
)

package com.canonmulticam.pro.transport

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val PTP_TYPE_COMMAND = 1
private const val PTP_TYPE_DATA = 2
private const val PTP_TYPE_RESPONSE = 3

private const val PTP_RESP_OK = 0x2001
private const val PTP_RESP_SESSION_ALREADY_OPEN = 0x201E

private const val PTP_OP_OPEN_SESSION = 0x1002
private const val PTP_OP_EOS_SET_PROP = 0x9110
private const val PTP_OP_EOS_REMOTE_MODE = 0x9114
private const val PTP_OP_EOS_EVENT_MODE = 0x9115
private const val PTP_OP_EOS_GET_EVENT = 0x9116
private const val PTP_OP_EOS_GET_VIEWFINDER = 0x9153
private const val PTP_OP_EOS_TOUCH_AF = 0x915B

private const val EOS_PROP_EVF_OUTPUT = 0xD1B0
private const val EOS_PROP_EVF_MODE = 0xD1B1
private const val EOS_PROP_MOVIE_RECORD = 0xD1B8

private const val USB_RECIP_ENDPOINT = 0x02
private const val USB_REQ_CLEAR_FEATURE = 0x01
private const val USB_FEATURE_ENDPOINT_HALT = 0x00

private const val USBFS_MAX_CHUNK = 16384
private const val HANDSHAKE_TIMEOUT_MS = 2000
private const val STREAM_TIMEOUT_MS = 800
private const val INTERRUPT_TIMEOUT_MS = 15
private const val SETTLE_DELAY_MS = 120L
private const val MAX_PACKET_READS = 64
private const val MIN_JPEG_BYTES = 512
private const val JPEG_SCAN_LIMIT = 64
private const val EVENT_DRAIN_INTERVAL = 5

/**
 * Deterministic PTP State Machine for Canon R6 II / R5C over USB.
 * Enforces Android usbfs 16KB chunk reads and falls back to UsbRequest async queue
 * if synchronous bulkTransfer IN returns -1 on older kernels.
 */
class CanonUsbStateMachine(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val onLog: (String) -> Unit
) {
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkOut: UsbEndpoint? = null
    private var bulkIn: UsbEndpoint? = null
    private var interruptIn: UsbEndpoint? = null
    private var transactionId = 1
    private var pollCounter = 0
    private var useAsyncInFallback = false

    private val packetScratch = ByteArray(USBFS_MAX_CHUNK)
    private val interruptScratch = ByteArray(64)
    private val reusableFrameBuf = ByteArray(2 * 1024 * 1024)

    fun connect(): Boolean {
        val candidates = PtpFrameCodec.collectCandidateInterfaces(device)
        if (candidates.isEmpty()) {
            onLog("ERROR: No Bulk IN/OUT interfaces found on USB device.")
            return false
        }

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            onLog("ERROR: UsbManager.openDevice() returned null.")
            return false
        }
        connection = conn

        for (bundle in candidates) {
            if (tryHandshakeOnInterface(conn, bundle)) return true
            runCatching { conn.releaseInterface(bundle.iface) }
        }

        close()
        return false
    }

    private fun tryHandshakeOnInterface(
        conn: UsbDeviceConnection,
        bundle: EndpointBundle
    ): Boolean {
        if (!conn.claimInterface(bundle.iface, true)) {
            onLog("Skip Iface#${bundle.iface.id}: claimInterface denied.")
            return false
        }
        runCatching { conn.setInterface(bundle.iface) }

        usbInterface = bundle.iface
        bulkOut = bundle.outEp
        bulkIn = bundle.inEp
        interruptIn = bundle.intEp
        useAsyncInFallback = false

        val outAddr = "0x" + bundle.outEp.address.toString(16)
        val inAddr = "0x" + bundle.inEp.address.toString(16)
        onLog(
            "Testing Iface#${bundle.iface.id} (Class=${bundle.iface.interfaceClass}) " +
                "OUT=$outAddr IN=$inAddr MaxPkt=${bundle.outEp.maxPacketSize}"
        )

        PtpFrameCodec.clearHalts(conn, bundle)
        SystemClock.sleep(SETTLE_DELAY_MS)

        val openResp = executeCommand(PTP_OP_OPEN_SESSION, intArrayOf(1), HANDSHAKE_TIMEOUT_MS)
        onLog("Iface#${bundle.iface.id} OpenSession -> 0x${openResp.toString(16)}")
        if (openResp != PTP_RESP_OK && openResp != PTP_RESP_SESSION_ALREADY_OPEN) {
            return false
        }

        val remoteResp = executeCommand(PTP_OP_EOS_REMOTE_MODE, intArrayOf(1), HANDSHAKE_TIMEOUT_MS)
        val eventResp = executeCommand(PTP_OP_EOS_EVENT_MODE, intArrayOf(1), HANDSHAKE_TIMEOUT_MS)
        onLog("EOS Remote=0x${remoteResp.toString(16)}, Event=0x${eventResp.toString(16)}")

        drainCameraEvents()
        setEosProperty(EOS_PROP_EVF_MODE, 1)
        val evfOutResp = setEosProperty(EOS_PROP_EVF_OUTPUT, 3)
        onLog("EOS LiveView Output -> 0x${evfOutResp.toString(16)}")
        return true
    }

    private fun readBulkChunk(
        conn: UsbDeviceConnection,
        input: UsbEndpoint,
        target: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): Int {
        val safeLen = length.coerceAtMost(USBFS_MAX_CHUNK)
        if (!useAsyncInFallback) {
            val syncRead = if (offset == 0) {
                conn.bulkTransfer(input, target, safeLen, timeoutMs)
            } else {
                conn.bulkTransfer(input, target, offset, safeLen, timeoutMs)
            }
            if (syncRead >= 0) return syncRead
            onLog("Sync bulk IN returned $syncRead; switching to UsbRequest queue...")
            useAsyncInFallback = true
        }
        return readViaUsbRequest(conn, input, target, offset, safeLen)
    }

    private fun readViaUsbRequest(
        conn: UsbDeviceConnection,
        input: UsbEndpoint,
        target: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        val req = UsbRequest()
        if (!req.initialize(conn, input)) return -1
        return try {
            val directBuf = ByteBuffer.allocateDirect(length)
            @Suppress("DEPRECATION")
            if (!req.queue(directBuf, length)) return -1
            if (conn.requestWait() == null) return -1
            val bytesRead = directBuf.position()
            if (bytesRead > 0) {
                directBuf.flip()
                directBuf.get(target, offset, bytesRead)
            }
            bytesRead
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { req.close() }
        }
    }

    fun nextFrame(): Bitmap? {
        val conn = connection ?: return null
        val out = bulkOut ?: return null
        val input = bulkIn ?: return null

        pollCounter++
        if (pollCounter % EVENT_DRAIN_INTERVAL == 0) {
            interruptIn?.let { conn.bulkTransfer(it, interruptScratch, interruptScratch.size, INTERRUPT_TIMEOUT_MS) }
            drainCameraEvents()
        }

        val cmd = PtpFrameCodec.buildContainer(
            PTP_TYPE_COMMAND,
            PTP_OP_EOS_GET_VIEWFINDER,
            transactionId++,
            intArrayOf(0x00100000)
        )
        if (conn.bulkTransfer(out, cmd, cmd.size, STREAM_TIMEOUT_MS) != cmd.size) return null

        val frameLen = readDataPayload(conn, input)
        if (frameLen <= MIN_JPEG_BYTES) return null

        val jpegStart = PtpFrameCodec.findJpegStart(reusableFrameBuf, frameLen)
        if (jpegStart < 0) return null
        return BitmapFactory.decodeByteArray(reusableFrameBuf, jpegStart, frameLen - jpegStart)
    }

    fun setRecording(record: Boolean): Boolean {
        val value = if (record) 4 else 0
        val resp = setEosProperty(EOS_PROP_MOVIE_RECORD, value)
        onLog("EOS MovieRecord(${if (record) "START" else "STOP"}) -> 0x${resp.toString(16)}")
        drainCameraEvents()
        return resp == PTP_RESP_OK
    }

    fun triggerTouchAf(normX: Float, normY: Float) {
        val x = (normX.coerceIn(0f, 1f) * 10000).toInt()
        val y = (normY.coerceIn(0f, 1f) * 10000).toInt()
        val resp = executeCommand(PTP_OP_EOS_TOUCH_AF, intArrayOf(x, y), STREAM_TIMEOUT_MS)
        onLog("TouchAF($x, $y) -> 0x${resp.toString(16)}")
    }

    private fun drainCameraEvents() {
        val conn = connection ?: return
        val out = bulkOut ?: return
        val input = bulkIn ?: return
        val cmd = PtpFrameCodec.buildContainer(PTP_TYPE_COMMAND, PTP_OP_EOS_GET_EVENT, transactionId++, intArrayOf())
        if (conn.bulkTransfer(out, cmd, cmd.size, STREAM_TIMEOUT_MS) == cmd.size) {
            readDataPayload(conn, input)
        }
    }

    private fun readDataPayload(conn: UsbDeviceConnection, input: UsbEndpoint): Int {
        var payloadSize = 0
        var reads = 0
        while (reads < MAX_PACKET_READS) {
            reads++
            val n = readBulkChunk(conn, input, packetScratch, 0, packetScratch.size, STREAM_TIMEOUT_MS)
            if (n < 12) return 0
            val hdr = ByteBuffer.wrap(packetScratch, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
            val totalLen = hdr.getInt(0)
            val type = hdr.getShort(4).toInt() and 0xFFFF

            if (type == PTP_TYPE_DATA && totalLen > 12) {
                val len = totalLen - 12
                if (len <= reusableFrameBuf.size && copyRemainingBytes(conn, input, n, len)) {
                    payloadSize = len
                }
            } else if (type == PTP_TYPE_RESPONSE) {
                break
            }
        }
        return payloadSize
    }

    private fun copyRemainingBytes(
        conn: UsbDeviceConnection,
        input: UsbEndpoint,
        initialRead: Int,
        payloadLen: Int
    ): Boolean {
        var copied = (initialRead - 12).coerceAtMost(payloadLen)
        if (copied > 0) System.arraycopy(packetScratch, 12, reusableFrameBuf, 0, copied)
        while (copied < payloadLen) {
            val r = readBulkChunk(
                conn,
                input,
                reusableFrameBuf,
                copied,
                payloadLen - copied,
                STREAM_TIMEOUT_MS
            )
            if (r <= 0) return false
            copied += r
        }
        return true
    }

    private fun setEosProperty(propCode: Int, value: Int): Int {
        val conn = connection ?: return -1
        val out = bulkOut ?: return -1
        val id = transactionId++
        val cmd = PtpFrameCodec.buildContainer(PTP_TYPE_COMMAND, PTP_OP_EOS_SET_PROP, id, intArrayOf())
        if (conn.bulkTransfer(out, cmd, cmd.size, STREAM_TIMEOUT_MS) != cmd.size) return -1

        val data = PtpFrameCodec.buildPropertyPayload(id, propCode, value)
        if (conn.bulkTransfer(out, data, data.size, STREAM_TIMEOUT_MS) != data.size) return -1
        return waitResponseCode(STREAM_TIMEOUT_MS)
    }

    private fun executeCommand(opCode: Int, params: IntArray, timeoutMs: Int): Int {
        val conn = connection ?: return -1
        val out = bulkOut ?: return -1
        val cmd = PtpFrameCodec.buildContainer(PTP_TYPE_COMMAND, opCode, transactionId++, params)
        val sent = conn.bulkTransfer(out, cmd, cmd.size, timeoutMs)
        if (sent != cmd.size) {
            onLog("OUT fail op=0x${opCode.toString(16)} sent=$sent/${cmd.size}")
            return -1
        }
        return waitResponseCode(timeoutMs)
    }

    private fun waitResponseCode(timeoutMs: Int): Int {
        val conn = connection ?: return -1
        val input = bulkIn ?: return -1
        var guard = 0
        while (guard < MAX_PACKET_READS) {
            guard++
            val n = readBulkChunk(conn, input, packetScratch, 0, packetScratch.size, timeoutMs)
            if (n < 12) {
                onLog("IN short/fail n=$n")
                return -1
            }
            val hdr = ByteBuffer.wrap(packetScratch, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
            val type = hdr.getShort(4).toInt() and 0xFFFF
            val code = hdr.getShort(6).toInt() and 0xFFFF
            if (type == PTP_TYPE_RESPONSE) return code
        }
        return -1
    }

    fun close() {
        runCatching { usbInterface?.let { connection?.releaseInterface(it) } }
        runCatching { connection?.close() }
        connection = null
    }
}

internal data class EndpointBundle(
    val iface: UsbInterface,
    val outEp: UsbEndpoint,
    val inEp: UsbEndpoint,
    val intEp: UsbEndpoint?
)

internal object PtpFrameCodec {
    fun buildContainer(type: Int, code: Int, id: Int, params: IntArray): ByteArray {
        val len = 12 + params.size * 4
        return ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(len)
            putShort(type.toShort())
            putShort(code.toShort())
            putInt(id)
            for (p in params) putInt(p)
        }.array()
    }

    fun buildPropertyPayload(id: Int, propCode: Int, value: Int): ByteArray =
        ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(20)
            putShort(PTP_TYPE_DATA.toShort())
            putShort(PTP_OP_EOS_SET_PROP.toShort())
            putInt(id)
            putInt(propCode)
            putInt(value)
        }.array()

    fun clearHalts(conn: UsbDeviceConnection, bundle: EndpointBundle) {
        clearSingleHalt(conn, bundle.outEp)
        clearSingleHalt(conn, bundle.inEp)
        bundle.intEp?.let { clearSingleHalt(conn, it) }
    }

    private fun clearSingleHalt(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        conn.controlTransfer(
            USB_RECIP_ENDPOINT,
            USB_REQ_CLEAR_FEATURE,
            USB_FEATURE_ENDPOINT_HALT,
            ep.address,
            null,
            0,
            300
        )
    }

    fun collectCandidateInterfaces(device: UsbDevice): List<EndpointBundle> {
        val list = mutableListOf<EndpointBundle>()
        for (i in 0 until device.interfaceCount) {
            val bundle = scanInterface(device.getInterface(i)) ?: continue
            list.add(bundle)
        }
        return list.sortedBy { if (it.iface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) 0 else 1 }
    }

    private fun scanInterface(iface: UsbInterface): EndpointBundle? {
        var outEp: UsbEndpoint? = null
        var inEp: UsbEndpoint? = null
        var intEp: UsbEndpoint? = null
        for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
            } else if (
                ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                ep.direction == UsbConstants.USB_DIR_IN
            ) {
                intEp = ep
            }
        }
        return if (outEp != null && inEp != null) EndpointBundle(iface, outEp, inEp, intEp) else null
    }

    fun findJpegStart(buf: ByteArray, validLen: Int): Int {
        val limit = (validLen - 1).coerceAtMost(JPEG_SCAN_LIMIT)
        for (i in 0 until limit) {
            if ((buf[i].toInt() and 0xFF) == 0xFF && (buf[i + 1].toInt() and 0xFF) == 0xD8) {
                return i
            }
        }
        return -1
    }
}

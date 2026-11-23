package com.canonmulticam.pro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.canonmulticam.pro.core.CameraTelemetry
import com.canonmulticam.pro.core.ConnectionPhase
import com.canonmulticam.pro.core.TransportMode
import com.canonmulticam.pro.core.UsbSerialExecutor
import com.canonmulticam.pro.transport.CanonUsbStateMachine
import com.canonmulticam.pro.transport.WifiNetworkBinder
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ACTION_USB_PERMISSION = "com.canonmulticam.pro.USB_PERMISSION"
private const val CANON_VENDOR_ID = 0x04A9
private const val REC_DEBOUNCE_MS = 500L
private const val MAX_LOGS = 60
private const val MAX_STREAM_FAILURES = 25

private val ObsidianBlack = Color(0xFF111111)
private val PanelSurface = Color(0xFF181818)
private val WarmIvory = Color(0xFFF5F3EE)
private val SoftStone = Color(0xFFB8B5AE)
private val MutedChampagne = Color(0xFFA58B62)
private val TallyRed = Color(0xFFD92D20)
private val OnlineGreen = Color(0xFF12B76A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = ObsidianBlack) {
                    CleanCinemaMonitorScreen()
                }
            }
        }
    }
}

@Composable
private fun CleanCinemaMonitorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    val usbExecutor = remember { UsbSerialExecutor() }
    val wifiBinder = remember { WifiNetworkBinder(context) }

    var telemetry by remember { mutableStateOf(CameraTelemetry()) }
    var showGrid by remember { mutableStateOf(true) }
    var showLogDrawer by remember { mutableStateOf(true) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var lastRecClickMs by remember { mutableLongStateOf(0L) }
    var machine by remember { mutableStateOf<CanonUsbStateMachine?>(null) }

    fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val updated = (listOf("[$ts] $msg") + telemetry.logs).take(MAX_LOGS)
        telemetry = telemetry.copy(logs = updated)
    }

    fun startUsbPipeline() {
        val devices = usbManager.deviceList.values.toList()
        appendLog("Serial USB Scan: ${devices.size} device(s)")
        if (devices.isEmpty()) {
            telemetry = telemetry.copy(
                phase = ConnectionPhase.ERROR,
                cameraName = "NO USB DEVICE"
            )
            appendLog("Check: Camera USB -> Photo Import/Remote Control & Wi-Fi -> Disable")
            return
        }

        val target = devices.firstOrNull { it.vendorId == CANON_VENDOR_ID } ?: devices.first()
        val vid = "0x" + target.vendorId.toString(16).uppercase(Locale.US)
        val pid = "0x" + target.productId.toString(16).uppercase(Locale.US)
        appendLog("Found: ${target.productName ?: "USB"} (VID=$vid PID=$pid)")

        if (!usbManager.hasPermission(target)) {
            telemetry = telemetry.copy(phase = ConnectionPhase.AWAITING_PERMISSION)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(target, pi)
            return
        }

        telemetry = telemetry.copy(phase = ConnectionPhase.HANDSHAKING)
        scope.launch {
            val nextMachine = CanonUsbStateMachine(usbManager, target, ::appendLog)
            val connected = withContext(usbExecutor.dispatcher) { nextMachine.connect() }
            if (connected) {
                machine?.close()
                machine = nextMachine
                telemetry = telemetry.copy(
                    phase = ConnectionPhase.STREAMING,
                    transport = TransportMode.USB_PTP,
                    cameraName = target.productName ?: "CANON R6 II"
                )
                showLogDrawer = false
            } else {
                withContext(usbExecutor.dispatcher) { nextMachine.close() }
                telemetry = telemetry.copy(
                    phase = ConnectionPhase.ERROR,
                    cameraName = "HANDSHAKE FAIL"
                )
            }
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    appendLog("Permission granted=$granted")
                    if (granted) startUsbPipeline()
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            wifiBinder.unbind()
            machine?.close()
            usbExecutor.shutdown()
        }
    }

    LaunchedEffect(Unit) { startUsbPipeline() }

    LaunchedEffect(telemetry.phase, machine) {
        val activeMachine = machine ?: return@LaunchedEffect
        if (telemetry.phase != ConnectionPhase.STREAMING) return@LaunchedEffect
        withContext(usbExecutor.dispatcher) {
            var failures = 0
            var frameCount = 0
            var windowStart = SystemClock.elapsedRealtime()
            while (isActive && telemetry.phase == ConnectionPhase.STREAMING) {
                val bmp = activeMachine.nextFrame()
                if (bmp != null) {
                    failures = 0
                    frameCount++
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - windowStart
                    val computedFps = if (elapsed >= 1000L) {
                        val rate = ((frameCount * 1000L) / elapsed).toInt()
                        frameCount = 0
                        windowStart = now
                        rate
                    } else {
                        telemetry.fps
                    }
                    telemetry = telemetry.copy(frame = bmp, fps = computedFps)
                } else {
                    failures++
                    if (failures >= MAX_STREAM_FAILURES) {
                        telemetry = telemetry.copy(
                            phase = ConnectionPhase.ERROR,
                            isRecording = false,
                            cameraName = "DISCONNECTED"
                        )
                        appendLog("USB stream lost after $MAX_STREAM_FAILURES failed reads.")
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBlack)
            .padding(10.dp)
    ) {
        CleanTopHud(
            telemetry = telemetry,
            showLogDrawer = showLogDrawer,
            onToggleLog = { showLogDrawer = !showLogDrawer }
        )

        Spacer(modifier = Modifier.height(8.dp))

        CleanViewport(
            telemetry = telemetry,
            showGrid = showGrid,
            focusPoint = focusPoint,
            onTapFocus = { norm ->
                focusPoint = norm
                val active = machine ?: return@CleanViewport
                scope.launch {
                    withContext(usbExecutor.dispatcher) {
                        active.triggerTouchAf(norm.x, norm.y)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        if (showLogDrawer) {
            Spacer(modifier = Modifier.height(8.dp))
            CleanLogDrawer(
                logs = telemetry.logs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(135.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        CleanBottomDock(
            telemetry = telemetry,
            showGrid = showGrid,
            onUsbLink = { startUsbPipeline() },
            onWifiBind = {
                wifiBinder.bindWifi { ok, msg ->
                    appendLog(msg)
                    if (ok) {
                        telemetry = telemetry.copy(transport = TransportMode.WIFI_BOUND)
                    }
                }
            },
            onToggleGrid = { showGrid = !showGrid },
            onRecordClick = {
                val now = SystemClock.elapsedRealtime()
                if (now - lastRecClickMs < REC_DEBOUNCE_MS) return@CleanBottomDock
                lastRecClickMs = now
                val active = machine ?: return@CleanBottomDock
                val targetRec = !telemetry.isRecording
                scope.launch {
                    val ok = withContext(usbExecutor.dispatcher) { active.setRecording(targetRec) }
                    if (ok) telemetry = telemetry.copy(isRecording = targetRec)
                }
            }
        )
    }
}

@Composable
private fun CleanTopHud(
    telemetry: CameraTelemetry,
    showLogDrawer: Boolean,
    onToggleLog: () -> Unit
) {
    val isLive = telemetry.phase == ConnectionPhase.STREAMING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PanelSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (isLive) OnlineGreen else MutedChampagne)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${telemetry.cameraName.uppercase(Locale.US)}  |  ${telemetry.fps} FPS",
                color = WarmIvory,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (telemetry.isRecording) {
                Text(
                    text = "● REC",
                    color = TallyRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
            Text(
                text = if (showLogDrawer) "HIDE LOG" else "DIAG LOG",
                color = MutedChampagne,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, MutedChampagne.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .clickable { onToggleLog() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun CleanViewport(
    telemetry: CameraTelemetry,
    showGrid: Boolean,
    focusPoint: Offset?,
    onTapFocus: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (telemetry.isRecording) TallyRed else SoftStone.copy(alpha = 0.25f)
    val borderWidth = if (telemetry.isRecording) 3.dp else 1.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0 && size.height > 0) {
                        val nx = (offset.x / size.width).coerceIn(0f, 1f)
                        val ny = (offset.y / size.height).coerceIn(0f, 1f)
                        onTapFocus(Offset(nx, ny))
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = telemetry.frame
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Camera Feed",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "CLEAN CORE — ${telemetry.phase.name}",
                    color = WarmIvory,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Dedicated USB Serial Thread + Event Drain",
                    color = SoftStone,
                    fontSize = 12.sp
                )
            }
        }

        if (showGrid) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val thirdW = size.width / 3f
                val thirdH = size.height / 3f
                val gridColor = WarmIvory.copy(alpha = 0.22f)
                drawLine(gridColor, Offset(thirdW, 0f), Offset(thirdW, size.height), 1f)
                drawLine(gridColor, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), 1f)
                drawLine(gridColor, Offset(0f, thirdH), Offset(size.width, thirdH), 1f)
                drawLine(gridColor, Offset(0f, thirdH * 2f), Offset(size.width, thirdH * 2f), 1f)

                val fp = focusPoint
                if (fp != null) {
                    drawCircle(
                        color = MutedChampagne,
                        radius = 28f,
                        center = Offset(fp.x * size.width, fp.y * size.height),
                        style = Stroke(width = 2f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanLogDrawer(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PanelSurface)
            .border(1.dp, SoftStone.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        logs.forEach { entry ->
            Text(
                text = entry,
                color = SoftStone,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
private fun CleanBottomDock(
    telemetry: CameraTelemetry,
    showGrid: Boolean,
    onUsbLink: () -> Unit,
    onWifiBind: () -> Unit,
    onToggleGrid: () -> Unit,
    onRecordClick: () -> Unit
) {
    val isLive = telemetry.phase == ConnectionPhase.STREAMING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PanelSurface)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onUsbLink,
                border = BorderStroke(1.dp, MutedChampagne),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WarmIvory),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(text = "USB", fontSize = 11.sp)
            }

            OutlinedButton(
                onClick = onWifiBind,
                border = BorderStroke(1.dp, SoftStone.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WarmIvory),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(text = "WIFI BIND", fontSize = 11.sp)
            }
        }

        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .border(
                    2.dp,
                    if (isLive) WarmIvory else SoftStone.copy(alpha = 0.3f),
                    CircleShape
                )
                .clickable(enabled = isLive) { onRecordClick() }
                .padding(5.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (telemetry.isRecording) 22.dp else 38.dp)
                    .clip(if (telemetry.isRecording) RoundedCornerShape(4.dp) else CircleShape)
                    .background(if (isLive) TallyRed else TallyRed.copy(alpha = 0.3f))
            )
        }

        OutlinedButton(
            onClick = onToggleGrid,
            border = BorderStroke(
                1.dp,
                if (showGrid) MutedChampagne else SoftStone.copy(alpha = 0.4f)
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (showGrid) MutedChampagne else SoftStone
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(text = "GRID", fontSize = 11.sp)
        }
    }
}

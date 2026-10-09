package com.example.cutebotandroidsample

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var activeGatt: BluetoothGatt? by mutableStateOf(null)
    private var connectionStatus by mutableStateOf("Disconnected")

    // Line follower using the robot's own sensors (line sensors + distance sensor)
    private var followStatus by mutableStateOf("Off")
    private var following by mutableStateOf(false)
    private val follower = SensorLineFollower(
        gattProvider = { activeGatt },
        onStatus = { msg -> runOnUiThread { followStatus = msg } },
        onFinished = { runOnUiThread { following = false } }
    )

    private fun startFollowing() {
        follower.start()
        following = true
    }

    private fun stopFollowing() {
        follower.stop()
        following = false
    }

    private fun toggleFollow() {
        if (following) stopFollowing() else startFollowing()
    }

    override fun onDestroy() {
        super.onDestroy()
        follower.stop()
    }

    // MAC address of the robot you are connecting to
    var deviceAddress by mutableStateOf("C1:CA:09:FC:A1:30")

    // Live Sensor Telemetry State (Updated via CutebotController.onTelemetry)
    private var telemetryDistance by mutableStateOf("--")
    private var telemetryLine by mutableStateOf("--")
    private var telemetryCompass by mutableStateOf("--")
    private var telemetryAccel by mutableStateOf("--")
    private var telemetryLight by mutableStateOf("--")
    private var telemetryTemp by mutableStateOf("--")
    private var telemetryPing by mutableStateOf("--")

    // The BLE Callback to handle connection state and delegate sensor notifications to the API
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionStatus = "Connected! Discovering services..."
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionStatus = "Disconnected"
                activeGatt = null
                CutebotController.resetBuffer()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connectionStatus = "Ready to Drive"
                activeGatt = gatt
                // Automatically subscribe to TX characteristic indications/notifications for telemetry
                val subResult = CutebotController.enableNotifications(gatt, true)
                android.util.Log.d("BLE", "enableNotifications result: $subResult")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            android.util.Log.d("BLE", "onDescriptorWrite for ${descriptor.uuid}: status=$status (0=GATT_SUCCESS)")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            CutebotController.handleNotification(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { CutebotController.handleNotification(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup telemetry listener from CutebotController API
        CutebotController.onTelemetry = { telemetry ->
            follower.onTelemetry(telemetry)
            runOnUiThread {
                when (telemetry) {
                    is CutebotTelemetry.Distance -> telemetryDistance = "${telemetry.cm} cm"
                    is CutebotTelemetry.LineTracker -> telemetryLine = telemetry.description
                    is CutebotTelemetry.Compass -> telemetryCompass = "${telemetry.degrees}°"
                    is CutebotTelemetry.Acceleration -> telemetryAccel = "${telemetry.x}, ${telemetry.y}, ${telemetry.z}"
                    is CutebotTelemetry.LightLevel -> telemetryLight = "${telemetry.level}"
                    is CutebotTelemetry.Temperature -> telemetryTemp = "${telemetry.celsius}°C"
                    is CutebotTelemetry.Pong -> telemetryPing = "PONG"
                    is CutebotTelemetry.Raw -> {}
                }
            }
        }

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* Permissions callback */ }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            )
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RobotControllerScreen()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToRobot(address: String) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val device = adapter.getRemoteDevice(address)

        connectionStatus = "Connecting..."
        device.connectGatt(this, false, gattCallback)
    }

    @Composable
    fun RobotControllerScreen() {
        var driveSpeed by remember { mutableFloatStateOf(60f) }
        var avoidBlocks by remember { mutableStateOf(true) }
        val isConnected = activeGatt != null

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Cutebot Robot Controller",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Status: $connectionStatus",
                color = if (isConnected) Color(0xFF2E7D32) else Color(0xFFC62828),
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Connection Card
            OutlinedTextField(
                value = deviceAddress,
                onValueChange = { deviceAddress = it },
                label = { Text("Robot MAC Address") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { connectToRobot(deviceAddress) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnected) "Reconnect to Robot" else "Connect to Robot")
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Speed Slider
            Text(
                text = "Speed: ${driveSpeed.toInt()}%",
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = driveSpeed,
                onValueChange = { driveSpeed = it },
                valueRange = 20f..100f,
                steps = 7,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Line follower (robot sensors)
            SectionHeader(title = "Purple Road Follower (robot sensors)")
            Button(
                onClick = {
                    follower.baseSpeed = driveSpeed.toInt()
                    follower.avoidBlocks = avoidBlocks
                    toggleFollow()
                },
                enabled = isConnected,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (following) Color.Red else Color(0xFF6A1B9A)
                ),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (following) "STOP following" else "Start following purple line") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = avoidBlocks,
                    onCheckedChange = { avoidBlocks = it; follower.avoidBlocks = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Avoid blocks (distance sensor)")
            }
            Text("Put the robot in the middle of the road (between the white lines) before pressing Start.", fontSize = 12.sp)
            Text(followStatus)

            Spacer(modifier = Modifier.height(12.dp))

            // D-Pad Drive Controls
            Text("Movement Controls", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(8.dp))
            GridControls(speed = driveSpeed.toInt())

            Spacer(modifier = Modifier.height(24.dp))

            // Lighting & Signals
            SectionHeader(title = "Headlights & Underglow")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { activeGatt?.let { CutebotController.setHeadlights(it, 255, 0, 0) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Red Both") }

                Button(
                    onClick = { activeGatt?.let { CutebotController.setLeftHeadlight(it, 255, 165, 0) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) { Text("Turn L") }

                Button(
                    onClick = { activeGatt?.let { CutebotController.setRightHeadlight(it, 255, 165, 0) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) { Text("Turn R") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { activeGatt?.let { CutebotController.setUnderglow(it, 0, 255, 0) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) { Text("Underglow Green") }

                Button(
                    onClick = { activeGatt?.let { CutebotController.turnLightsOff(it) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) { Text("Lights Off") }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Audio & Horn
            SectionHeader(title = "Audio & Horn")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { activeGatt?.let { CutebotController.playHorn(it) } }
                ) { Text("Horn") }

                Button(
                    onClick = { activeGatt?.let { CutebotController.playBeep(it) } }
                ) { Text("Beep") }

                Button(
                    onClick = { activeGatt?.let { CutebotController.playTone(it, 1000, 300) } }
                ) { Text("Tone 1kHz") }

                Button(
                    onClick = { activeGatt?.let { CutebotController.stopSound(it) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) { Text("Quiet") }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // micro:bit 5x5 LED Display
            SectionHeader(title = "micro:bit 5x5 LED Display")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { activeGatt?.let { CutebotController.displayText(it, "NEXT") } }) {
                    Text("Text 'NEXT'")
                }
                Button(onClick = { activeGatt?.let { CutebotController.displayIcon(it, "HEART") } }) {
                    Text("Heart")
                }
                Button(onClick = { activeGatt?.let { CutebotController.displayIcon(it, "SKULL") } }) {
                    Text("Skull")
                }
                Button(onClick = { activeGatt?.let { CutebotController.clearDisplay(it) } }) {
                    Text("Clear")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sensor Telemetry & Diagnostics
            SectionHeader(title = "Sensor Telemetry & Diagnostics")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { activeGatt?.let { CutebotController.requestDistance(it) } }) {
                    Text("?Dist")
                }
                Button(onClick = { activeGatt?.let { CutebotController.requestLineStatus(it) } }) {
                    Text("?Line")
                }
                Button(onClick = { activeGatt?.let { CutebotController.requestCompass(it) } }) {
                    Text("?Heading")
                }
                Button(onClick = { activeGatt?.let { CutebotController.ping(it) } }) {
                    Text("Ping")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { activeGatt?.let { CutebotController.requestAcceleration(it) } }) {
                    Text("?Accel")
                }
                Button(onClick = { activeGatt?.let { CutebotController.requestLightLevel(it) } }) {
                    Text("?Light")
                }
                Button(onClick = { activeGatt?.let { CutebotController.requestTemperature(it) } }) {
                    Text("?Temp")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Telemetry Readout Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Live Telemetry Readings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    TelemetryRow(label = "Distance:", value = telemetryDistance)
                    TelemetryRow(label = "Line Tracker:", value = telemetryLine)
                    TelemetryRow(label = "Compass Heading:", value = telemetryCompass)
                    TelemetryRow(label = "Accelerometer (X,Y,Z):", value = telemetryAccel)
                    TelemetryRow(label = "Ambient Light:", value = telemetryLight)
                    TelemetryRow(label = "Temperature:", value = telemetryTemp)
                    TelemetryRow(label = "Ping / Pong:", value = telemetryPing)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    @Composable
    fun SectionHeader(title: String) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
    }

    @Composable
    fun TelemetryRow(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    fun GridControls(speed: Int) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(
                onClick = { if (following) stopFollowing(); activeGatt?.let { CutebotController.moveForward(it, speed) } },
                modifier = Modifier.size(76.dp)
            ) { Text("W") }

            Row(
                modifier = Modifier.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { if (following) stopFollowing(); activeGatt?.let { CutebotController.turnLeft(it, speed) } },
                    modifier = Modifier.size(76.dp)
                ) { Text("A") }

                Button(
                    onClick = {
                        if (following) stopFollowing()
                        activeGatt?.let { CutebotController.stop(it) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    modifier = Modifier.size(76.dp)
                ) { Text("STOP") }

                Button(
                    onClick = { if (following) stopFollowing(); activeGatt?.let { CutebotController.turnRight(it, speed) } },
                    modifier = Modifier.size(76.dp)
                ) { Text("D") }
            }

            Button(
                onClick = { if (following) stopFollowing(); activeGatt?.let { CutebotController.moveBackward(it, speed) } },
                modifier = Modifier.size(76.dp)
            ) { Text("S") }
        }
    }
}

/**
 * Road follower using the ROBOT's own sensors (no phone camera).
 *
 * The track is a wide purple road with a white line on each side. The two infrared sensors only say
 * "dark" or "not dark" for left and right. Instead of guessing how purple reads, the app LEARNS it:
 * when you press Start the robot (standing still in the middle of the road) watches what both
 * sensors report and treats that as "centered". If only ONE sensor changes (it reached the white
 * border or the black floor), the robot is drifting toward that side, so it steers away.
 * The baseline keeps updating whenever both sensors agree for a while, because the road colour
 * changes from blue to pink along the track.
 *
 * The ultrasonic sensor slows the robot down and turns it a bit when something is close ahead.
 * One BLE command is sent per 50 ms tick, so writes never collide.
 */
class SensorLineFollower(
    private val gattProvider: () -> BluetoothGatt?,
    private val onStatus: (String) -> Unit = {},
    private val onFinished: () -> Unit = {}
) {
    @Volatile var baseSpeed = 40          // forward speed (motors stall below ~25)
    @Volatile var avoidBlocks = true      // slow down / turn away from obstacles ahead

    private data class Cmd(val left: Int, val right: Int, val note: String)

    private val lock = Any()
    @Volatile private var running = false
    private var scheduler: ScheduledExecutorService? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var lineCode = -1
    @Volatile private var distanceCm = -1
    @Volatile private var lastReplyMs = 0L

    private var tickCount = 0
    private var queryIndex = 0
    private var baseline: Boolean? = null  // true = road reads "dark", false = road reads "bright"
    private var agreeTicks = 0
    private var calibTicks = 0
    private var bothTicks = 0
    private var lastSteer = 1              // 1 = last steered right, -1 = left
    private var sideTicks = 0
    private var obstacleActive = false
    private var obstacleTicks = 0
    private var lastCmd: Cmd? = null
    private var lastCmdMs = 0L

    fun onTelemetry(t: CutebotTelemetry) {
        when (t) {
            is CutebotTelemetry.LineTracker -> {
                lineCode = t.code
                lastReplyMs = System.currentTimeMillis()
            }
            is CutebotTelemetry.Distance -> distanceCm = t.cm
            else -> {}
        }
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        lineCode = -1
        distanceCm = -1
        lastReplyMs = System.currentTimeMillis()
        tickCount = 0; queryIndex = 0; baseline = null; agreeTicks = 0; calibTicks = 0
        bothTicks = 0; sideTicks = 0; obstacleActive = false; obstacleTicks = 0
        lastCmd = null; lastCmdMs = 0L
        val s = Executors.newSingleThreadScheduledExecutor()
        scheduler = s
        s.scheduleWithFixedDelay({
            try {
                tick()
            } catch (e: Exception) {
                android.util.Log.e("LineFollower", "tick failed", e)
            }
        }, 0, 50, TimeUnit.MILLISECONDS)
        onStatus("Calibrating: keep the robot in the middle of the road")
    }

    fun stop() {
        synchronized(lock) {
            running = false
            gattProvider()?.let { CutebotController.stop(it) }
        }
        scheduler?.shutdown()
        scheduler = null
        handler.postDelayed({ if (!running) gattProvider()?.let { CutebotController.stop(it) } }, 120)
        handler.postDelayed({ if (!running) gattProvider()?.let { CutebotController.stop(it) } }, 300)
        onStatus("Stopped")
    }

    private fun haltItself(message: String) {
        stop()
        onStatus(message)
        onFinished()
    }

    private fun tick() {
        if (!running) return
        tickCount++
        val now = System.currentTimeMillis()

        if (now - lastReplyMs > 1500) {
            haltItself("No sensor data: stopped")
            return
        }

        val cmd = decide()
        if (cmd.left == 0 && cmd.right == 0 && cmd.note.startsWith("STOP")) {
            haltItself(cmd.note)
            return
        }

        val prev = lastCmd
        val changed = prev == null || cmd.left != prev.left || cmd.right != prev.right
        if (changed || now - lastCmdMs > 300) {
            send { g -> CutebotController.setMotorSpeeds(g, cmd.left, cmd.right) }
            lastCmd = cmd
            lastCmdMs = now
        } else {
            val askDistance = avoidBlocks && (queryIndex % 3 == 2)
            queryIndex++
            send { g ->
                if (askDistance) CutebotController.requestDistance(g)
                else CutebotController.requestLineStatus(g)
            }
        }

        if (tickCount % 5 == 0) {
            val b = baseline
            val bs = if (b == null) "?" else if (b) "dark" else "bright"
            onStatus("line=$lineCode road=$bs dist=${distanceCm}cm  ${cmd.note}")
        }
    }

    private fun decide(): Cmd {
        val code = lineCode
        if (code < 0) return Cmd(0, 0, "waiting for sensor")
        val leftOn = code == 2 || code == 3     // left sensor sees "dark"
        val rightOn = code == 1 || code == 3    // right sensor sees "dark"

        // Learn what "centered on the road" looks like: both sensors agree for ~0.4 s
        if (leftOn == rightOn) {
            agreeTicks++
            if (agreeTicks >= 8) baseline = leftOn
        } else {
            agreeTicks = 0
        }

        val base = baseline
        if (base == null) {
            calibTicks++
            if (calibTicks > 80) return Cmd(0, 0, "STOP: put the robot in the middle of the road and start again")
            return Cmd(0, 0, "calibrating: keep robot centered")
        }

        // Obstacle ahead: slow down, and when very close turn away a bit
        if (avoidBlocks) {
            val d = distanceCm
            if (d in 3..15 || (obstacleActive && d in 3..22)) {
                obstacleActive = true
                obstacleTicks++
                if (obstacleTicks > 60) return Cmd(0, 0, "STOP: blocked for 3 s")
                return if (lastSteer >= 0) Cmd(30, -30, "obstacle: turning right")
                else Cmd(-30, 30, "obstacle: turning left")
            }
        }
        obstacleActive = false
        obstacleTicks = 0
        val slow = avoidBlocks && distanceCm in 3..30
        val b = maxOf(25, (baseSpeed * (if (slow) 0.65f else 1f)).roundToInt())

        // Which sensors differ from the "centered" reading?
        val leftDiff = leftOn != base
        val rightDiff = rightOn != base

        if (leftDiff && rightDiff) {
            bothTicks++
            sideTicks = 0
            // both changed: crossing a white line / checkered strip, keep straight for a moment
            if (bothTicks > 40) return Cmd(0, 0, "STOP: off the road")
            return Cmd(b, b, "crossing a line")
        }
        bothTicks = 0

        if (leftDiff) {          // left sensor reached the border -> steer right
            sideTicks = if (lastSteer == 1) sideTicks + 1 else 1
            lastSteer = 1
            val inner = if (sideTicks > 6) -30 else 0
            return Cmd(b, inner, "near left edge: steering right")
        }
        if (rightDiff) {         // right sensor reached the border -> steer left
            sideTicks = if (lastSteer == -1) sideTicks + 1 else 1
            lastSteer = -1
            val inner = if (sideTicks > 6) -30 else 0
            return Cmd(inner, b, "near right edge: steering left")
        }
        sideTicks = 0
        return Cmd(b, b, "centered")
    }

    private fun send(action: (BluetoothGatt) -> Unit) {
        synchronized(lock) {
            if (!running) return
            val g = gattProvider() ?: return
            action(g)
        }
    }
}

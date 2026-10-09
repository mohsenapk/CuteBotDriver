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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import android.graphics.Color as AColor
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var activeGatt: BluetoothGatt? by mutableStateOf(null)
    private var connectionStatus by mutableStateOf("Disconnected")

    // Purple line follower (uses the phone camera)
    private var followStatus by mutableStateOf("Off")
    private var following by mutableStateOf(false)
    private val follower = PurpleLineFollower({ activeGatt }) { msg ->
        runOnUiThread { followStatus = msg }
    }
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startFollowing() else followStatus = "Camera permission denied"
    }

    private fun startFollowing() {
        follower.start(this)
        following = true
    }

    private fun stopFollowing() {
        follower.stop()
        following = false
    }

    private fun toggleFollow() {
        if (following) {
            stopFollowing()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startFollowing()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
        var avoidRed by remember { mutableStateOf(true) }
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

            // Purple line follower
            SectionHeader(title = "Purple Line Follower (phone camera)")
            Button(
                onClick = {
                    follower.baseSpeed = driveSpeed.toInt()
                    follower.avoidRed = avoidRed
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
                    checked = avoidRed,
                    onCheckedChange = { avoidRed = it; follower.avoidRed = it }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Avoid red blocks")
            }
            Button(
                onClick = { follower.calibrateOnPurple() },
                enabled = following,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Calibrate (purple line at bottom-center)") }
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
                onClick = { activeGatt?.let { CutebotController.moveForward(it, speed) } },
                modifier = Modifier.size(76.dp)
            ) { Text("W") }

            Row(
                modifier = Modifier.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { activeGatt?.let { CutebotController.turnLeft(it, speed) } },
                    modifier = Modifier.size(76.dp)
                ) { Text("A") }

                Button(
                    onClick = { activeGatt?.let { CutebotController.stop(it) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    modifier = Modifier.size(76.dp)
                ) { Text("STOP") }

                Button(
                    onClick = { activeGatt?.let { CutebotController.turnRight(it, speed) } },
                    modifier = Modifier.size(76.dp)
                ) { Text("D") }
            }

            Button(
                onClick = { activeGatt?.let { CutebotController.moveBackward(it, speed) } },
                modifier = Modifier.size(76.dp)
            ) { Text("S") }
        }
    }
}

/**
 * Real-time purple-line follower using the PHONE camera (the Cutebot itself has no camera).
 * Mount the phone on the robot, back camera looking forward/down at the floor.
 *
 * Each frame: find purple pixels (HSV) in the lower part of the image, compute where the
 * line is (left/right), and send differential motor speeds (MS,left,right) over BLE.
 * White pixels (guardrail) on one side push the robot away from that side.
 */
class PurpleLineFollower(
    private val gattProvider: () -> BluetoothGatt?,
    private val onStatus: (String) -> Unit = {}
) : ImageAnalysis.Analyzer {

    // ---- Tuning knobs (change these to adjust behaviour) ----
    @Volatile var baseSpeed = 40          // forward speed (motors stall below ~25)
    @Volatile var steerGain = 40f         // how hard to turn toward the line
    @Volatile var derivGain = 12f         // damping, reduces wobble
    @Volatile var hueMin = 255f           // purple hue range in degrees (0..360)
    @Volatile var hueMax = 305f
    @Volatile var satMin = 0.25f          // min colour saturation (ignore grey/white)
    @Volatile var valMin = 0.20f          // min brightness (ignore shadows)
    @Volatile var avoidRed = true         // steer away from red obstacle blocks
    @Volatile var avoidWhite = true       // push away from white guardrail; set false if floor is white

    @Volatile private var running = false
    @Volatile private var calibrateNext = false
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor = Executors.newSingleThreadExecutor()

    private var prevError = 0f
    private var lastError = 0f
    private var lostFrames = 0
    private var lastSendMs = 0L
    private val hsv = FloatArray(3)

    /** Start camera + following. Camera permission must already be granted. */
    fun start(activity: ComponentActivity) {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(320, 240),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, this)
            provider.unbindAll()
            provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            prevError = 0f
            lastError = 0f
            lostFrames = 0
            running = true
            onStatus("Following: looking for purple line")
        }, ContextCompat.getMainExecutor(activity))
    }

    fun stop() {
        running = false
        gattProvider()?.let { CutebotController.stop(it) }
        cameraProvider?.unbindAll()
        onStatus("Stopped")
    }

    /** Learn the purple colour from the centre of the lower image area (put the line there first). */
    fun calibrateOnPurple() {
        calibrateNext = true
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (running) process(image)
        } catch (e: Exception) {
            android.util.Log.e("LineFollower", "analyze failed", e)
        } finally {
            image.close()
        }
    }

    private fun process(image: ImageProxy) {
        val w = image.width
        val h = image.height
        val rot = image.imageInfo.rotationDegrees
        val sideways = rot == 90 || rot == 270
        val rw = if (sideways) h else w   // upright image size
        val rh = if (sideways) w else h
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride

        // Read pixel at upright coordinates (rx, ry) into hsv[]
        fun readHsv(rx: Int, ry: Int) {
            val sx: Int
            val sy: Int
            when (rot) {
                90 -> { sx = ry; sy = h - 1 - rx }
                180 -> { sx = w - 1 - rx; sy = h - 1 - ry }
                270 -> { sx = w - 1 - ry; sy = rx }
                else -> { sx = rx; sy = ry }
            }
            val i = sy * rowStride + sx * pixStride
            val r = buf.get(i).toInt() and 0xFF
            val g = buf.get(i + 1).toInt() and 0xFF
            val b = buf.get(i + 2).toInt() and 0xFF
            AColor.RGBToHSV(r, g, b, hsv)
        }

        val top = (rh * 0.45f).toInt()   // only look at the lower 55% (the floor ahead)

        if (calibrateNext) {
            calibrateNext = false
            var hs = 0f; var ss = 0f; var vs = 0f; var n = 0
            val cx = rw / 2
            val cy = (top + rh) / 2
            for (dy in -6..6 step 2) for (dx in -6..6 step 2) {
                readHsv(cx + dx, cy + dy)
                hs += hsv[0]; ss += hsv[1]; vs += hsv[2]; n++
            }
            val hAvg = hs / n
            hueMin = hAvg - 25f
            hueMax = hAvg + 25f
            satMin = maxOf(0.15f, ss / n * 0.5f)
            valMin = maxOf(0.10f, vs / n * 0.5f)
            onStatus("Calibrated: hue ${hAvg.roundToInt()}")
        }

        var count = 0
        var sumX = 0f
        var wSum = 0f
        var total = 0
        var whiteL = 0
        var whiteR = 0
        var redCount = 0
        var redSumX = 0f
        val half = rw / 2
        val hMin = hueMin; val hMax = hueMax; val sMin = satMin; val vMin = valMin

        var y = top
        while (y < rh) {
            val rowWeight = 1f + (y - top).toFloat() / (rh - top)   // nearer rows count more
            var x = 0
            while (x < rw) {
                readHsv(x, y)
                total++
                val hue = hsv[0]; val s = hsv[1]; val v = hsv[2]
                if (hue in hMin..hMax && s >= sMin && v >= vMin) {
                    count++
                    sumX += x * rowWeight
                    wSum += rowWeight
                } else if ((hue < 15f || hue > 345f) && s > 0.50f && v > 0.30f) {
                    redCount++                 // red obstacle block
                    redSumX += x
                } else if (s < 0.15f && v > 0.80f) {
                    if (x < half) whiteL++ else whiteR++
                }
                x += 4
            }
            y += 4
        }

        val frac = if (total > 0) count.toFloat() / total else 0f
        if (frac < 0.01f || wSum == 0f) {
            handleLost()
            return
        }
        lostFrames = 0

        // error: -1 (line far left) .. +1 (line far right)
        val cxLine = sumX / wSum
        var error = (cxLine - rw / 2f) / (rw / 2f)
        if (avoidWhite && total > 0) {
            val wl = whiteL / (total / 2f)
            val wr = whiteR / (total / 2f)
            if (wl > 0.35f) error += (wl - 0.35f)   // white on left -> steer right
            if (wr > 0.35f) error -= (wr - 0.35f)   // white on right -> steer left
        }

        // Red blocks: turn a bit away from them and slow down
        var speedFactor = 1f
        if (avoidRed && total > 0 && redCount > 0.02f * total) {
            val redFrac = redCount.toFloat() / total
            val side = (redSumX / redCount - rw / 2f) / (rw / 2f)   // -1 left .. +1 right
            val push = (redFrac * 10f).coerceIn(0.2f, 0.8f)         // bigger/closer block = stronger turn
            error += if (side >= 0f) -push else push
            speedFactor = 0.7f
        }
        error = error.coerceIn(-1f, 1f)

        val d = error - prevError
        prevError = error
        lastError = error

        val now = System.currentTimeMillis()
        if (now - lastSendMs < 80) return        // throttle BLE to ~12 commands/sec
        lastSendMs = now

        val speed = baseSpeed * speedFactor * (1f - 0.4f * abs(error))   // slow down in sharp turns
        val turn = steerGain * error + derivGain * d
        send(speed + turn, speed - turn)          // line on right -> left wheel faster -> turn right
        val redNote = if (speedFactor < 1f) "  RED block" else ""
        onStatus("purple ${(frac * 100).roundToInt()}%  error ${"%.2f".format(error)}$redNote")
    }

    private fun handleLost() {
        lostFrames++
        if (lostFrames < 3) return                // ignore brief dropouts, keep last command
        val now = System.currentTimeMillis()
        if (now - lastSendMs < 80) return
        lastSendMs = now
        if (lostFrames < 40) {
            val dir = if (lastError >= 0f) 1 else -1   // spin toward where the line was last seen
            send(30f * dir, -30f * dir)
            onStatus("Line lost: searching")
        } else {
            gattProvider()?.let { CutebotController.stop(it) }
            onStatus("Line lost: stopped")
        }
    }

    private fun send(left: Float, right: Float) {
        val g = gattProvider() ?: return
        CutebotController.setMotorSpeeds(g, deadband(left), deadband(right))
    }

    // Motors whine without moving below ~25, so jump over that dead zone.
    private fun deadband(v: Float): Int {
        val i = v.roundToInt().coerceIn(-100, 100)
        return when {
            i in 1..24 -> 25
            i in -24..-1 -> -25
            else -> i
        }
    }
}

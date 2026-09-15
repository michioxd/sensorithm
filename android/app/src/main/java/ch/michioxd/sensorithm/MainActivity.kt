package ch.michioxd.sensorithm

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle

class MainActivity : AppCompatActivity() {

    private lateinit var connectionManager: ConnectionManager
    private lateinit var configRepository: ConfigRepository
    private lateinit var cameraController: CameraController
    private lateinit var stateSynchronizer: ClientStateSynchronizer
    private lateinit var telemetryMonitor: DeviceTelemetryMonitor
    private lateinit var initialConfig: AppConfig
    private val sensorProcessor = SensorProcessor()

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ZoneOverlayView
    private lateinit var tvWatermark: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var cbAutoReconnect: CheckBox
    private lateinit var cbAutoConnectOnStartup: CheckBox
    private lateinit var btnConnect: Button
    private lateinit var btnRecalibrate: Button
    private lateinit var btnChangeCamera: Button
    private lateinit var btnChangeRes: Button
    private lateinit var btnChangeFps: Button
    private lateinit var btnToggleFlash: Button
    private lateinit var btnRestartCamera: Button
    private lateinit var controlsLayout: android.widget.LinearLayout

    private lateinit var sbSizeX: SeekBar
    private lateinit var sbSizeY: SeekBar
    private lateinit var sbSpacing: SeekBar
    private lateinit var sbAngle: SeekBar
    private lateinit var sbExposure: SeekBar
    private lateinit var sbThreshold: SeekBar

    private lateinit var tvSizeXValue: TextView
    private lateinit var tvSizeYValue: TextView
    private lateinit var tvSpacingValue: TextView
    private lateinit var tvAngleValue: TextView
    private lateinit var tvExposureValue: TextView
    private lateinit var tvThresholdValue: TextView

    private var backPressedTime: Long = 0
    private var applyingRemoteSettings = false
    private var currentTorchState: CameraController.TorchUiState = CameraController.TorchUiState.Unavailable
    private var latestFps = 0f
    private var latestBatteryTemperatureCelsius: Float? = null
    private val cameraRecoveryHandler = Handler(Looper.getMainLooper())
    private var cameraRestartAttempts = 0
    private var cameraRecoveryActive = false
    private var cameraRecoveryExhausted = false
    private var cameraStartPending = false
    private var cameraStartPosted = false
    private val cameraRecoveryRunnable = Runnable(::attemptAutomaticCameraRestart)
    private val cameraRecoveryVerificationRunnable = Runnable {
        if (cameraRecoveryActive) scheduleAutomaticCameraRestart()
    }
    private val cameraStartRunnable = Runnable {
        cameraStartPosted = false
        if (!cameraStartPending ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            isFinishing || isDestroyed
        ) {
            return@Runnable
        }

        cameraStartPending = false
        if (!cameraController.start()) {
            handleCameraInterrupted("Could not start camera")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        configRepository = ConfigRepository(this)
        
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = System.currentTimeMillis()
                if (t - backPressedTime > 2000) {
                    backPressedTime = t
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                } else {
                    finish()
                }
            }
        })

        connectionManager = ConnectionManager(
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown",
            autoReconnectEnabled = { cbAutoReconnect.isChecked },
            onStateChanged = { state ->
                renderConnectionState(state)
                if (state is ConnectionState.Connected) {
                    sensorProcessor.resetOutput()
                    saveConfigs()
                    stateSynchronizer.publishLocalSettings(currentSyncedSettings())
                    publishTorchState()
                    publishTelemetry()
                }
            },
            onConnectionTimeout = {
                Toast.makeText(this, "Connection timeout", Toast.LENGTH_SHORT).show()
            },
            onMessage = ::handleServerMessage,
            onProtocolError = { message -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show() },
        )

        initViews()
        cameraController = CameraController(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            sensorProcessor = sensorProcessor,
            onFrameGeometryChanged = { updateZones() },
            onFpsChanged = { fps ->
                latestFps = fps
                renderCameraMetrics()
            },
            onSensorMask = connectionManager::sendMask,
            onSensorMaskForDisplay = overlayView::updateActiveMask,
            onTorchChanged = ::handleTorchState,
            onPreviewCaptured = ::handlePreviewCaptured,
            onCameraError = { exception ->
                android.util.Log.e("Sensorithm", "Camera operation failed", exception)
                handleCameraInterrupted(exception.message ?: "Camera operation failed")
            },
            onCameraInterrupted = ::handleCameraInterrupted,
            onCameraOperational = ::handleCameraOperational,
        )
        stateSynchronizer = ClientStateSynchronizer(
            send = connectionManager::sendMessage,
            applyRemote = ::applyRemoteSettings,
        )
        telemetryMonitor = DeviceTelemetryMonitor(this, ::handleTelemetryChanged)
        telemetryMonitor.start()
        setupListeners()

        cameraController.initialize(initialConfig.camera) { cameraAvailable ->
            if (cameraAvailable) {
                continueStartup()
            } else {
                showCameraRequiredDialog()
            }
        }
    }

    private fun continueStartup() {
        if (cbAutoConnectOnStartup.isChecked) connect()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun showCameraRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Camera required")
            .setMessage("This device does not have a camera. This application requires a camera to operate.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> finishAffinity() }
            .show()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvWatermark = findViewById(R.id.tvWatermark)
        tvFps = findViewById(R.id.tvFps)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        cbAutoReconnect = findViewById(R.id.cbAutoReconnect)
        cbAutoConnectOnStartup = findViewById(R.id.cbAutoConnectOnStartup)
        btnConnect = findViewById(R.id.btnConnect)
        btnRecalibrate = findViewById(R.id.btnRecalibrate)
        btnChangeCamera = findViewById(R.id.btnChangeCamera)
        btnChangeRes = findViewById(R.id.btnChangeRes)
        btnChangeFps = findViewById(R.id.btnChangeFps)
        btnToggleFlash = findViewById(R.id.btnToggleFlash)
        btnRestartCamera = findViewById(R.id.btnRestartCamera)
        controlsLayout = findViewById(R.id.controlsLayout)

        sbSizeX = findViewById(R.id.sbSizeX)
        sbSizeY = findViewById(R.id.sbSizeY)
        sbSpacing = findViewById(R.id.sbSpacing)
        sbAngle = findViewById(R.id.sbAngle)
        sbExposure = findViewById(R.id.sbExposure)
        sbThreshold = findViewById(R.id.sbThreshold)

        tvSizeXValue = findViewById(R.id.tvSizeXValue)
        tvSizeYValue = findViewById(R.id.tvSizeYValue)
        tvSpacingValue = findViewById(R.id.tvSpacingValue)
        tvAngleValue = findViewById(R.id.tvAngleValue)
        tvExposureValue = findViewById(R.id.tvExposureValue)
        tvThresholdValue = findViewById(R.id.tvThresholdValue)
        
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        tvWatermark.text = "sensorithm v$appVersion"
        
        loadConfigs()
        updateLabels()
        tvExposureValue.text = "${sbExposure.progress}"
        tvThresholdValue.text = "${sbThreshold.progress}"

        overlayView.onOffsetChanged = {
            updateZones()
        }
        overlayView.onOffsetChangeFinished = {
            stateSynchronizer.publishLocalSettings(currentSyncedSettings())
            saveConfigs()
        }
        
        overlayView.onCameraBoundsChanged = { left, top, right, bottom ->
            val parentWidth = overlayView.width
            val parentHeight = overlayView.height
            val rightOffset = parentWidth - right
            val bottomOffset = parentHeight - bottom
            
            tvWatermark.translationX = -rightOffset.toFloat()
            tvWatermark.translationY = -bottomOffset.toFloat()

            val canvasWidth = right - left
            val canvasHeight = bottom - top
            val referenceDim = Math.min(parentWidth, parentHeight).toFloat()
            val currentMinDim = Math.min(canvasWidth, canvasHeight).toFloat()
            
            if (referenceDim > 0) {
                val scale = currentMinDim / referenceDim
                tvWatermark.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f * scale)
            }
        }
    }

    private fun connect() {
        val ip = etIp.text.toString()
        val port = etPort.text.toString().toIntOrNull() ?: CONNECT_FALLBACK_PORT
        saveConfigs()
        connectionManager.connect(ip, port)
    }
    
    private fun disconnect() {
        connectionManager.disconnect()
    }

    private fun renderConnectionState(state: ConnectionState) {
        when (state) {
            ConnectionState.Disconnected -> {
                tvConnectionStatus.setText(R.string.disconnected)
                tvConnectionStatus.setTextColor(Color.RED)
                btnConnect.isEnabled = true
                btnConnect.text = "Connect"
            }
            ConnectionState.Connecting -> {
                tvConnectionStatus.text = "Connecting..."
                tvConnectionStatus.setTextColor(Color.YELLOW)
                btnConnect.isEnabled = true
                btnConnect.text = "Cancel"
            }
            is ConnectionState.Connected -> {
                tvConnectionStatus.text = "Connected - v${state.serverVersion}"
                tvConnectionStatus.setTextColor(Color.GREEN)
                btnConnect.isEnabled = true
                btnConnect.text = "Disconnect"
            }
            is ConnectionState.Rejected -> {
                tvConnectionStatus.text = state.message
                tvConnectionStatus.setTextColor(Color.RED)
                btnConnect.isEnabled = true
                btnConnect.text = "Connect"
                AlertDialog.Builder(this)
                    .setTitle("Connection rejected")
                    .setMessage(state.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (connectionManager.state is ConnectionState.Connected ||
                connectionManager.state == ConnectionState.Connecting
            ) {
                cbAutoReconnect.isChecked = false
                saveConfigs()
                disconnect()
            } else {
                connect()
            }
        }
        
        cbAutoReconnect.setOnCheckedChangeListener { _, _ -> saveConfigs() }
        cbAutoConnectOnStartup.setOnCheckedChangeListener { _, _ -> saveConfigs() }
        btnRecalibrate.setOnClickListener {
            sensorProcessor.recalibrate()
        }
        
        btnChangeCamera.setOnClickListener {
            showCameraSelectionDialog()
        }
        
        btnChangeRes.setOnClickListener {
            showResolutionSelectionDialog()
        }
        
        btnChangeFps.setOnClickListener {
            showFpsSelectionDialog()
        }

        btnToggleFlash.setOnClickListener {
            cameraController.toggleTorch()
        }

        btnRestartCamera.setOnClickListener {
            cameraController.restart()
        }

        val seekBarListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
                if (!applyingRemoteSettings) updateZones()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveConfigs()
                stateSynchronizer.publishLocalSettings(currentSyncedSettings())
            }
        }

        sbSizeX.setOnSeekBarChangeListener(seekBarListener)
        sbSizeY.setOnSeekBarChangeListener(seekBarListener)
        sbSpacing.setOnSeekBarChangeListener(seekBarListener)
        sbAngle.setOnSeekBarChangeListener(seekBarListener)
        
        sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvExposureValue.text = "$progress"
                if (!applyingRemoteSettings) cameraController.setExposure(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveConfigs()
                stateSynchronizer.publishLocalSettings(currentSyncedSettings())
            }
        })
        
        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvThresholdValue.text = "$progress"
                if (!applyingRemoteSettings) sensorProcessor.setThreshold(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveConfigs()
                stateSynchronizer.publishLocalSettings(currentSyncedSettings())
            }
        })

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (controlsLayout.visibility == View.VISIBLE) {
                    controlsLayout.visibility = View.GONE
                } else {
                    controlsLayout.visibility = View.VISIBLE
                }
                return true
            }
        })
        overlayView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        tvWatermark.setOnClickListener {
            val appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
            AlertDialog.Builder(this)
                .setTitle("About sensorithm")
                .setMessage("sensorithm by michioxd\nVersion: v$appVersion\nLicense: GPLv3\nGitHub: https://github.com/michioxd/sensorithm")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Source Code") { _, _ ->
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/michioxd/sensorithm"))
                    startActivity(intent)
                }
                .show()
        }
    }

    private fun loadConfigs() {
        val config = configRepository.load().also { initialConfig = it }
        etIp.setText(config.serverAddress)
        etPort.setText(config.serverPort.toString())
        cbAutoReconnect.isChecked = config.autoReconnect
        cbAutoConnectOnStartup.isChecked = config.autoConnectOnStartup
        
        sbSizeX.progress = config.zones.sizePercentX
        sbSizeY.progress = config.zones.sizePercentY
        sbSpacing.progress = config.zones.spacingPercent
        sbAngle.progress = config.zones.angleDegrees
        sbExposure.progress = config.exposure
        sbThreshold.progress = config.zones.threshold
        overlayView.offsetX = config.zoneOffsetX.coerceIn(0f, 1f)
        overlayView.offsetY = config.zoneOffsetY.coerceIn(0f, 1f)

    }
    
    private fun saveConfigs() {
        configRepository.save(
            AppConfig(
                serverAddress = etIp.text.toString(),
                serverPort = etPort.text.toString().toIntOrNull() ?: AppConfig.DEFAULT_SERVER_PORT,
                autoReconnect = cbAutoReconnect.isChecked,
                autoConnectOnStartup = cbAutoConnectOnStartup.isChecked,
                zones = currentZoneSettings(),
                exposure = sbExposure.progress,
                zoneOffsetX = overlayView.offsetX,
                zoneOffsetY = overlayView.offsetY,
                camera = cameraController.config(),
            ),
        )
    }

    private fun updateLabels() {
        tvSizeXValue.text = "${sbSizeX.progress}%"
        tvSizeYValue.text = "${sbSizeY.progress}%"
        tvSpacingValue.text = "${sbSpacing.progress}%"
        tvAngleValue.text = "${sbAngle.progress}"
    }

    private fun updateZones() {
        val frame = cameraController.frameGeometry ?: return

        val settings = currentZoneSettings()
        val layout = SensorZoneLayoutCalculator.calculate(
            frame,
            settings,
            overlayView.offsetX,
            overlayView.offsetY,
        )
        overlayView.updateLayout(frame.previewWidth, frame.previewHeight, layout, settings.angleDegrees)
        sensorProcessor.configureZones(layout.nativeConfig)
    }

    private fun currentZoneSettings() = ZoneSettings(
        sizePercentX = sbSizeX.progress,
        sizePercentY = sbSizeY.progress,
        spacingPercent = sbSpacing.progress,
        angleDegrees = sbAngle.progress,
        threshold = sbThreshold.progress,
    )

    private fun currentSyncedSettings() = SyncedSettings(
        sizeX = sbSizeX.progress,
        sizeY = sbSizeY.progress,
        spacing = sbSpacing.progress,
        angle = sbAngle.progress,
        exposure = sbExposure.progress,
        threshold = sbThreshold.progress,
        offsetX = overlayView.offsetX,
        offsetY = overlayView.offsetY,
    )

    private fun applyRemoteSettings(settings: SyncedSettings) {
        applyingRemoteSettings = true
        try {
            sbSizeX.progress = settings.sizeX
            sbSizeY.progress = settings.sizeY
            sbSpacing.progress = settings.spacing
            sbAngle.progress = settings.angle
            sbExposure.progress = settings.exposure
            sbThreshold.progress = settings.threshold
            overlayView.offsetX = settings.offsetX
            overlayView.offsetY = settings.offsetY
        } finally {
            applyingRemoteSettings = false
        }
        updateLabels()
        cameraController.setExposure(settings.exposure)
        sensorProcessor.setThreshold(settings.threshold.toFloat())
        updateZones()
        saveConfigs()
    }

    private fun handleServerMessage(message: ServerMessage) {
        when (message) {
            ServerMessage.Recalibrate -> {
                sensorProcessor.recalibrate()
                Toast.makeText(this, "Recalibrated by server", Toast.LENGTH_SHORT).show()
            }
            is ServerMessage.Settings -> {
                if (!stateSynchronizer.applyRemoteSettings(message.settings)) {
                    connectionManager.sendMessage(
                        ClientMessage.Error("settings", null, "Received invalid settings"),
                    )
                }
            }
            is ServerMessage.SetTorch -> {
                if (!cameraController.setTorch(message.enabled)) {
                    connectionManager.sendMessage(
                        ClientMessage.Error("torch", null, "Flash is unavailable"),
                    )
                }
            }
            ServerMessage.RestartCamera -> {
                if (!cameraController.restart()) {
                    connectionManager.sendMessage(
                        ClientMessage.Error("camera_restart", null, "Camera restart failed"),
                    )
                }
            }
            is ServerMessage.RequestPreview -> {
                if (!cameraController.requestPreview(message.requestId)) {
                    connectionManager.sendMessage(
                        ClientMessage.Error(
                            "preview",
                            message.requestId,
                            "Camera is unavailable or another preview is pending",
                        ),
                    )
                }
            }
        }
    }

    private fun handlePreviewCaptured(result: CameraController.PreviewCaptureResult) {
        when (result) {
            is CameraController.PreviewCaptureResult.Success -> connectionManager.sendMessage(
                ClientMessage.Preview(
                    requestId = result.requestId,
                    width = result.preview.width,
                    height = result.preview.height,
                    jpegBytes = result.preview.jpegBytes,
                ),
            )
            is CameraController.PreviewCaptureResult.Failure -> connectionManager.sendMessage(
                ClientMessage.Error("preview", result.requestId, result.message),
            )
        }
    }

    private fun showCameraSelectionDialog() {
        CameraDialogHelper.showCameraSelectionDialog(this, cameraController.provider, cameraController.camera?.cameraInfo) { info ->
            cameraController.selectCamera(info)
            saveConfigs()
        }
    }
    
    private fun showResolutionSelectionDialog() {
        CameraDialogHelper.showResolutionSelectionDialog(this, cameraController.camera, cameraController.resolution) { size ->
            cameraController.selectResolution(size)
            saveConfigs()
        }
    }
    
    private fun showFpsSelectionDialog() {
        CameraDialogHelper.showFpsSelectionDialog(this, cameraController.camera, cameraController.fpsRange) { range ->
            cameraController.selectFps(range)
            saveConfigs()
        }
    }

    private fun startCamera() {
        cameraController.setExposure(sbExposure.progress)
        sensorProcessor.setThreshold(sbThreshold.progress.toFloat())
        cameraStartPending = true
        scheduleCameraStart()
    }

    private fun scheduleCameraStart() {
        if (!cameraStartPending || cameraStartPosted ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            return
        }

        cameraStartPosted = true
        previewView.post(cameraStartRunnable)
    }

    private fun handleCameraInterrupted(message: String) {
        android.util.Log.w("Sensorithm", message)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            cameraStartPending = true
            return
        }
        if (cameraRecoveryExhausted) {
            return
        }
        if (!cameraRecoveryActive) {
            cameraRecoveryActive = true
            cameraRestartAttempts = 0
        }
        cameraRecoveryHandler.removeCallbacks(cameraRecoveryVerificationRunnable)
        scheduleAutomaticCameraRestart()
    }

    private fun scheduleAutomaticCameraRestart() {
        if (!cameraRecoveryActive || cameraRecoveryExhausted) return
        cameraRecoveryHandler.removeCallbacks(cameraRecoveryRunnable)
        if (cameraRestartAttempts >= MAX_AUTOMATIC_CAMERA_RESTARTS) {
            cameraRecoveryActive = false
            cameraRecoveryExhausted = true
            Toast.makeText(
                this,
                "Could not restart camera after $MAX_AUTOMATIC_CAMERA_RESTARTS attempts",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val delay = if (cameraRestartAttempts == 0) 0L else CAMERA_RESTART_DELAY_MILLIS
        cameraRecoveryHandler.postDelayed(cameraRecoveryRunnable, delay)
    }

    private fun attemptAutomaticCameraRestart() {
        if (!cameraRecoveryActive || cameraRecoveryExhausted) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            cameraRecoveryActive = false
            cameraRestartAttempts = 0
            return
        }

        cameraRestartAttempts++
        Toast.makeText(
            this,
            "Camera disconnected. Restarting ($cameraRestartAttempts/$MAX_AUTOMATIC_CAMERA_RESTARTS)...",
            Toast.LENGTH_SHORT,
        ).show()

        cameraRecoveryHandler.removeCallbacks(cameraRecoveryVerificationRunnable)
        if (cameraController.restart()) {
            cameraRecoveryHandler.removeCallbacks(cameraRecoveryRunnable)
            cameraRecoveryHandler.postDelayed(
                cameraRecoveryVerificationRunnable,
                CAMERA_RECOVERY_VERIFICATION_MILLIS,
            )
        } else {
            scheduleAutomaticCameraRestart()
        }
    }

    private fun handleCameraOperational() {
        val recovered = cameraRecoveryActive || cameraRecoveryExhausted || cameraRestartAttempts > 0
        cameraStartPending = false
        cameraRecoveryHandler.removeCallbacks(cameraRecoveryRunnable)
        cameraRecoveryHandler.removeCallbacks(cameraRecoveryVerificationRunnable)
        cameraRecoveryActive = false
        cameraRecoveryExhausted = false
        cameraRestartAttempts = 0
        if (recovered) {
            Toast.makeText(this, "Camera restarted successfully", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        scheduleCameraStart()
    }

    private fun renderTorchState(state: CameraController.TorchUiState) {
        when (state) {
            CameraController.TorchUiState.Unavailable -> {
                btnToggleFlash.isEnabled = false
                btnToggleFlash.text = "No Flash"
            }
            is CameraController.TorchUiState.Available -> {
                btnToggleFlash.isEnabled = true
                btnToggleFlash.text = if (state.enabled) "Flash ON" else "Flash"
            }
        }
    }

    private fun handleTorchState(state: CameraController.TorchUiState) {
        currentTorchState = state
        renderTorchState(state)
        publishTorchState()
    }

    private fun publishTorchState() {
        connectionManager.sendMessage(
            when (val state = currentTorchState) {
                CameraController.TorchUiState.Unavailable -> ClientMessage.TorchState(false, false)
                is CameraController.TorchUiState.Available -> ClientMessage.TorchState(true, state.enabled)
            },
        )
    }

    private fun handleTelemetryChanged(snapshot: DeviceTelemetrySnapshot) {
        latestBatteryTemperatureCelsius = snapshot.batteryTemperatureCelsius
        renderCameraMetrics()
        publishTelemetry()
    }

    private fun publishTelemetry() {
        val telemetry = telemetryMonitor.current ?: return
        connectionManager.sendMessage(
            ClientMessage.Telemetry(
                batteryPercent = telemetry.batteryPercent,
                temperatureCelsius = telemetry.batteryTemperatureCelsius,
                charging = telemetry.isCharging,
            ),
        )
    }

    private fun renderCameraMetrics() {
        tvFps.text = latestBatteryTemperatureCelsius?.let { temperature ->
            String.format("%.1f FPS  %.1f °C", latestFps, temperature)
        } ?: String.format("%.1f FPS  -- °C", latestFps)
    }

    override fun onDestroy() {
        super.onDestroy()
        previewView.removeCallbacks(cameraStartRunnable)
        cameraStartPending = false
        cameraStartPosted = false
        cameraRecoveryHandler.removeCallbacks(cameraRecoveryRunnable)
        cameraRecoveryHandler.removeCallbacks(cameraRecoveryVerificationRunnable)
        telemetryMonitor.close()
        connectionManager.close()
        cameraController.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val CONNECT_FALLBACK_PORT = 8080
        private const val MAX_AUTOMATIC_CAMERA_RESTARTS = 3
        private const val CAMERA_RESTART_DELAY_MILLIS = 1_000L
        private const val CAMERA_RECOVERY_VERIFICATION_MILLIS = 3_000L
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

package ch.michioxd.sensorithm

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var connectionManager: ConnectionManager
    private lateinit var configRepository: ConfigRepository
    private lateinit var cameraController: CameraController
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
                }
            },
            onConnectionTimeout = {
                Toast.makeText(this, "Connection timeout", Toast.LENGTH_SHORT).show()
            },
            onRecalibrate = {
                sensorProcessor.recalibrate()
                Toast.makeText(this, "Recalibrated by server", Toast.LENGTH_SHORT).show()
            },
        )

        initViews()
        cameraController = CameraController(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            sensorProcessor = sensorProcessor,
            onFrameGeometryChanged = { updateZones() },
            onFpsChanged = { fps -> tvFps.text = String.format("%.1f FPS", fps) },
            onSensorMask = connectionManager::sendMask,
            onSensorMaskForDisplay = overlayView::updateActiveMask,
            onTorchChanged = ::renderTorchState,
            onCameraError = { exception -> android.util.Log.e("Sensorithm", "Camera operation failed", exception) },
        )
        cameraController.restoreConfig(initialConfig.camera)
        setupListeners()
        
        if (cbAutoConnectOnStartup.isChecked) {
            connect()
        }
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
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
                btnConnect.isEnabled = false
                btnConnect.text = "Connecting..."
            }
            is ConnectionState.Connected -> {
                tvConnectionStatus.text = "Connected - v${state.serverVersion}"
                tvConnectionStatus.setTextColor(Color.GREEN)
                btnConnect.isEnabled = true
                btnConnect.text = "Disconnect"
            }
        }
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (connectionManager.state != ConnectionState.Disconnected) {
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
                updateZones()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveConfigs()
            }
        }

        sbSizeX.setOnSeekBarChangeListener(seekBarListener)
        sbSizeY.setOnSeekBarChangeListener(seekBarListener)
        sbSpacing.setOnSeekBarChangeListener(seekBarListener)
        sbAngle.setOnSeekBarChangeListener(seekBarListener)
        
        sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvExposureValue.text = "$progress"
                cameraController.setExposure(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveConfigs()
            }
        })
        
        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvThresholdValue.text = "$progress"
                val threshold = progress.toFloat()
                sensorProcessor.setThreshold(threshold)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveConfigs()
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
        cameraController.start(initialConfig.camera)
    }

    private fun renderTorchState(state: CameraController.TorchUiState) {
        when (state) {
            CameraController.TorchUiState.Unavailable -> {
                btnToggleFlash.isEnabled = false
                btnToggleFlash.text = "No Flash"
            }
            is CameraController.TorchUiState.Available -> {
                btnToggleFlash.isEnabled = true
                btnToggleFlash.text = if (state.enabled) "Flash: ON" else "Flash"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.close()
        cameraController.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val CONNECT_FALLBACK_PORT = 8080
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

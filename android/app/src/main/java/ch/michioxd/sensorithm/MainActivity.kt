package ch.michioxd.sensorithm

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.Size
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
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var client: Client
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: ZoneOverlayView
    private lateinit var tvWatermark: TextView
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

    private var previewWidth = 0
    private var previewHeight = 0
    private var rawWidth = 0
    private var rawHeight = 0
    private var imageRotation = 0
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCamera: Camera? = null
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentResolution: Size? = null
    private var currentFpsRange: android.util.Range<Int>? = null

    private var isConnected = false
    private var isConnecting = false
    private val connectionHandler = Handler(Looper.getMainLooper())
    private val connectionTimeoutRunnable = Runnable {
        if (isConnecting && !isConnected) {
            Toast.makeText(this@MainActivity, "Connection timeout", Toast.LENGTH_SHORT).show()
            disconnect()
        }
    }
    
    private val reconnectRunnable = Runnable {
        if (cbAutoReconnect.isChecked && !isConnected && !isConnecting) {
            connect()
        }
    }
    
    private var backPressedTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        
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

        client = Client(
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown",
            onConnected = { serverVersion ->
                runOnUiThread {
                    isConnected = true
                    isConnecting = false
                    connectionHandler.removeCallbacks(connectionTimeoutRunnable)
                    tvConnectionStatus.text = "Connected - v$serverVersion"
                    tvConnectionStatus.setTextColor(Color.GREEN)
                    btnConnect.isEnabled = true
                    btnConnect.text = "Disconnect"
                    saveConfigs()
                }
            },
            onDisconnected = { e ->
                runOnUiThread {
                    isConnected = false
                    isConnecting = false
                    tvConnectionStatus.setText(R.string.disconnected)
                    tvConnectionStatus.setTextColor(Color.RED)
                    btnConnect.isEnabled = true
                    btnConnect.text = "Connect"
                    if (cbAutoReconnect.isChecked) {
                        connectionHandler.postDelayed(reconnectRunnable, 3000) // retry after 3 seconds
                    }
                }
            },
            onRecalibrate = {
                SensorithmJNI.recalibrate()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Recalibrated by server", Toast.LENGTH_SHORT).show()
                }
            }
        )

        initViews()
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
    }

    private fun connect() {
        if (isConnected || isConnecting) return
        
        val ip = etIp.text.toString()
        val port = etPort.text.toString().toIntOrNull() ?: 8080
        
        isConnecting = true
        btnConnect.isEnabled = false
        btnConnect.text = "Connecting..."
        tvConnectionStatus.text = "Connecting..."
        tvConnectionStatus.setTextColor(Color.YELLOW)
        
        saveConfigs()
        client.connect(ip, port)
        
        connectionHandler.removeCallbacks(connectionTimeoutRunnable)
        connectionHandler.postDelayed(connectionTimeoutRunnable, 10000)
    }
    
    private fun disconnect() {
        connectionHandler.removeCallbacks(connectionTimeoutRunnable)
        connectionHandler.removeCallbacks(reconnectRunnable)
        client.disconnect()
        isConnected = false
        isConnecting = false
        tvConnectionStatus.setText(R.string.disconnected)
        tvConnectionStatus.setTextColor(Color.RED)
        btnConnect.isEnabled = true
        btnConnect.text = "Connect"
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (isConnected || isConnecting) {
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
            SensorithmJNI.recalibrate()
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
                currentCamera?.cameraInfo?.exposureState?.let { exposureState ->
                    val range = exposureState.exposureCompensationRange
                    val index = range.lower + ((progress / 100f) * (range.upper - range.lower)).toInt()
                    currentCamera?.cameraControl?.setExposureCompensationIndex(index)
                }
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
                SensorithmJNI.setThreshold(-1, threshold)
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
        etIp.setText(Config.getString(this, "ip", "127.0.0.1"))
        etPort.setText(Config.getInt(this, "port", 4420).toString())
        cbAutoReconnect.isChecked = Config.getBoolean(this, "autoReconnect", false)
        cbAutoConnectOnStartup.isChecked = Config.getBoolean(this, "autoConnectOnStartup", false)
        
        sbSizeX.progress = Config.getInt(this, "sizeX", 15)
        sbSizeY.progress = Config.getInt(this, "sizeY", 5)
        sbSpacing.progress = Config.getInt(this, "spacing", 10)
        sbAngle.progress = Config.getInt(this, "angle", 180)
        sbExposure.progress = Config.getInt(this, "exposure", 50)
        sbThreshold.progress = Config.getInt(this, "threshold", 30)

        val savedCameraId = Config.getString(this, "cameraId", "")
        if (savedCameraId.isNotEmpty()) {
            currentCameraSelector = CameraSelector.Builder().addCameraFilter { cameras ->
                val matching = cameras.filter { camInfo ->
                    try {
                        androidx.camera.camera2.interop.Camera2CameraInfo.from(camInfo).cameraId == savedCameraId
                    } catch (e: Exception) {
                        false
                    }
                }
                if (matching.isNotEmpty()) matching else cameras
            }.build()
        }

        val resW = Config.getInt(this, "resW", 0)
        val resH = Config.getInt(this, "resH", 0)
        if (resW > 0 && resH > 0) {
            currentResolution = Size(resW, resH)
        }

        val fpsL = Config.getInt(this, "fpsL", 0)
        val fpsU = Config.getInt(this, "fpsU", 0)
        if (fpsL > 0 && fpsU > 0) {
            currentFpsRange = android.util.Range(fpsL, fpsU)
        }
    }
    
    private fun saveConfigs() {
        Config.saveString(this, "ip", etIp.text.toString())
        Config.saveInt(this, "port", etPort.text.toString().toIntOrNull() ?: 4420)
        Config.saveBoolean(this, "autoReconnect", cbAutoReconnect.isChecked)
        Config.saveBoolean(this, "autoConnectOnStartup", cbAutoConnectOnStartup.isChecked)
        
        Config.saveInt(this, "sizeX", sbSizeX.progress)
        Config.saveInt(this, "sizeY", sbSizeY.progress)
        Config.saveInt(this, "spacing", sbSpacing.progress)
        Config.saveInt(this, "angle", sbAngle.progress)
        Config.saveInt(this, "exposure", sbExposure.progress)
        Config.saveInt(this, "threshold", sbThreshold.progress)

        currentCamera?.cameraInfo?.let { camInfo ->
            try {
                val cameraId = androidx.camera.camera2.interop.Camera2CameraInfo.from(camInfo).cameraId
                Config.saveString(this, "cameraId", cameraId)
            } catch (e: Exception) {}
        }
        currentResolution?.let {
            Config.saveInt(this, "resW", it.width)
            Config.saveInt(this, "resH", it.height)
        } ?: run {
            Config.saveInt(this, "resW", 0)
            Config.saveInt(this, "resH", 0)
        }
        currentFpsRange?.let {
            Config.saveInt(this, "fpsL", it.lower)
            Config.saveInt(this, "fpsU", it.upper)
        } ?: run {
            Config.saveInt(this, "fpsL", 0)
            Config.saveInt(this, "fpsU", 0)
        }
    }

    private fun updateLabels() {
        tvSizeXValue.text = "${sbSizeX.progress}%"
        tvSizeYValue.text = "${sbSizeY.progress}%"
        tvSpacingValue.text = "${sbSpacing.progress}%"
        tvAngleValue.text = "${sbAngle.progress}"
    }

    private fun updateZones() {
        if (previewWidth == 0 || previewHeight == 0) return

        val sensorSizeX = (sbSizeX.progress / 100f * previewWidth).toInt().coerceAtLeast(1)
        val sensorSizeY = (sbSizeY.progress / 100f * previewHeight).toInt().coerceAtLeast(1)
        val distance = (sbSpacing.progress / 100f * previewHeight).toInt()
        val threshold = sbThreshold.progress.toFloat()
        
        val angle = sbAngle.progress
        
        val pixelOffsetX = ((overlayView.offsetX - 0.5f) * previewWidth).toInt()
        val pixelOffsetY = ((overlayView.offsetY - 0.5f) * previewHeight).toInt()

        overlayView.updateParams(previewWidth, previewHeight, pixelOffsetX, pixelOffsetY, sensorSizeX, sensorSizeY, distance, angle)

        val configs = IntArray(24)
        val rawSizeX = if (imageRotation == 90 || imageRotation == 270) sensorSizeY else sensorSizeX
        val rawSizeY = if (imageRotation == 90 || imageRotation == 270) sensorSizeX else sensorSizeY
        
        val rad = Math.toRadians((angle - 180).toDouble())
        val dx = Math.sin(rad).toFloat()
        val dy = Math.cos(rad).toFloat()

        for (i in 0 until 6) {
            val cx = (previewWidth / 2f + pixelOffsetX + (i - 2.5f) * distance * dx).toInt()
            val cy = (previewHeight / 2f + pixelOffsetY + (i - 2.5f) * distance * dy).toInt()
            
            val rawX: Int
            val rawY: Int
            when (imageRotation) {
                90 -> {
                    rawX = cy
                    rawY = rawHeight - 1 - cx
                }
                180 -> {
                    rawX = rawWidth - 1 - cx
                    rawY = rawHeight - 1 - cy
                }
                270 -> {
                    rawX = rawWidth - 1 - cy
                    rawY = cx
                }
                else -> {
                    rawX = cx
                    rawY = cy
                }
            }
            
            configs[i * 4 + 0] = rawX
            configs[i * 4 + 1] = rawY
            configs[i * 4 + 2] = rawSizeX
            configs[i * 4 + 3] = rawSizeY
        }
        
        SensorithmJNI.setAirConfig(configs)
    }

    private fun showCameraSelectionDialog() {
        CameraDialogHelper.showCameraSelectionDialog(this, cameraProvider, currentCamera?.cameraInfo) { info ->
            currentCameraSelector = CameraSelector.Builder().addCameraFilter { it.filter { camInfo -> camInfo == info } }.build()
            currentResolution = null
            currentFpsRange = null
            saveConfigs()
            bindCamera()
        }
    }
    
    private fun showResolutionSelectionDialog() {
        CameraDialogHelper.showResolutionSelectionDialog(this, currentCamera, currentResolution) { size ->
            currentResolution = size
            saveConfigs()
            bindCamera()
        }
    }
    
    private fun showFpsSelectionDialog() {
        CameraDialogHelper.showFpsSelectionDialog(this, currentCamera, currentFpsRange) { range ->
            currentFpsRange = range
            saveConfigs()
            bindCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        var resSelector: ResolutionSelector? = null
        currentResolution?.let {
            resSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(it, ResolutionStrategy.FALLBACK_RULE_NONE))
                .build()
        }

        val previewBuilder = Preview.Builder()
        resSelector?.let { previewBuilder.setResolutionSelector(it) }
        currentFpsRange?.let {
            val extender = androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
            extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            // high fps
            extender.setCaptureRequestTemplate(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD)
        }
        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        resSelector?.let { analysisBuilder.setResolutionSelector(it) }
        currentFpsRange?.let {
            val extender = androidx.camera.camera2.interop.Camera2Interop.Extender(analysisBuilder)
            extender.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
            extender.setCaptureRequestTemplate(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD)
        }
        val imageAnalysis = analysisBuilder.build().also {
            it.setAnalyzer(analyzerExecutor) { imageProxy ->
                val rotation = imageProxy.imageInfo.rotationDegrees
                val isSwapped = rotation == 90 || rotation == 270
                val rotatedWidth = if (isSwapped) imageProxy.height else imageProxy.width
                val rotatedHeight = if (isSwapped) imageProxy.width else imageProxy.height

                if (previewWidth != rotatedWidth || previewHeight != rotatedHeight || imageRotation != rotation) {
                    previewWidth = rotatedWidth
                    previewHeight = rotatedHeight
                    rawWidth = imageProxy.width
                    rawHeight = imageProxy.height
                    imageRotation = rotation
                    updateZones()
                }

                val yPlane = imageProxy.planes[0]
                val buffer = yPlane.buffer
                val rowStride = yPlane.rowStride

                if (buffer.isDirect) {
                    val mask = SensorithmJNI.processFrame(buffer, imageProxy.width, imageProxy.height, rowStride)
                    if (mask != (-1).toByte()) {
                        client.sendMask(mask)
                        runOnUiThread {
                            overlayView.updateActiveMask(mask)
                        }
                    }
                }
                imageProxy.close()
            }
        }

        try {
            currentCamera = provider.bindToLifecycle(this, currentCameraSelector, preview, imageAnalysis)
            
            currentCamera?.cameraInfo?.exposureState?.let { exposureState ->
                val range = exposureState.exposureCompensationRange
                val index = range.lower + ((sbExposure.progress / 100f) * (range.upper - range.lower)).toInt()
                currentCamera?.cameraControl?.setExposureCompensationIndex(index)
            }
            SensorithmJNI.setThreshold(-1, sbThreshold.progress.toFloat())
        } catch (exc: Exception) {
            Log.e("Sensorithm", "Use case binding failed", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.disconnect()
        analyzerExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
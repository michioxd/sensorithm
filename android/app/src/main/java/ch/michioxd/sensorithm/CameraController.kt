package ch.michioxd.sensorithm

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("RestrictedApi", "UnsafeOptInUsageError")
class CameraController(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val sensorProcessor: SensorProcessor,
    private val onFrameGeometryChanged: (FrameGeometry) -> Unit,
    private val onFpsChanged: (Float) -> Unit,
    private val onSensorMask: (Byte) -> Unit,
    private val onSensorMaskForDisplay: (Byte) -> Unit,
    private val onTorchChanged: (TorchUiState) -> Unit,
    private val onPreviewCaptured: (PreviewCaptureResult) -> Unit,
    private val onCameraError: (Exception) -> Unit,
    private val onCameraInterrupted: (String) -> Unit,
    private val onCameraOperational: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var currentCamera: Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var selectedCameraId = ""
    private var currentResolution: Size? = null
    private var currentFpsRange: Range<Int>? = null
    private var torchSource: androidx.lifecycle.LiveData<Int>? = null
    private var cameraStateSource: androidx.lifecycle.LiveData<CameraState>? = null
    private val torchObserver = Observer<Int> { state ->
        onTorchChanged(TorchUiState.Available(state == TorchState.ON))
    }
    private val cameraStateObserver = Observer<CameraState> { state ->
        val error = state.error ?: return@Observer
        if (!closed && !cameraInterruptionReported) {
            cameraInterruptionReported = true
            onCameraInterrupted("Camera stopped unexpectedly (error ${error.code})")
        }
    }

    @Volatile
    var frameGeometry: FrameGeometry? = null
        private set

    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var exposureProgress = AppConfig.DEFAULT_EXPOSURE
    @Volatile
    private var closed = false
    @Volatile
    private var configuredGeometry: FrameGeometry? = null
    @Volatile
    private var awaitingFirstFrame = false
    private var cameraInterruptionReported = false
    private val pendingPreviewRequest = AtomicReference<String?>(null)

    val provider: ProcessCameraProvider?
        get() = cameraProvider

    val camera: Camera?
        get() = currentCamera

    val resolution: Size?
        get() = currentResolution

    val fpsRange: Range<Int>?
        get() = currentFpsRange

    fun initialize(config: CameraConfig, onAvailabilityChecked: (Boolean) -> Unit) {
        closed = false
        restoreConfig(config)

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            if (closed) return@addListener
            try {
                cameraProvider = providerFuture.get()
                val provider = cameraProvider
                val cameras = provider?.availableCameraInfos.orEmpty()
                if (provider != null && cameras.isNotEmpty() && !provider.hasCamera(cameraSelector)) {
                    val fallbackCamera = cameras.first()
                    selectedCameraId = cameraId(fallbackCamera)
                    cameraSelector = CameraSelector.Builder()
                        .addCameraFilter { available -> available.filter { it == fallbackCamera } }
                        .build()
                }
                onAvailabilityChecked(cameras.isNotEmpty())
            } catch (exception: Exception) {
                onCameraError(exception)
                onAvailabilityChecked(false)
            }
        }, mainExecutor)
    }

    fun start() = bind()

    fun restoreConfig(config: CameraConfig) {
        selectedCameraId = config.cameraId
        cameraSelector = selectorFor(config.cameraId)
        currentResolution = config.resolutionOrNull()
        currentFpsRange = config.fpsRangeOrNull()
    }

    fun selectCamera(cameraInfo: CameraInfo) {
        selectedCameraId = cameraId(cameraInfo)
        cameraSelector = CameraSelector.Builder()
            .addCameraFilter { cameras -> cameras.filter { it == cameraInfo } }
            .build()
        currentResolution = null
        currentFpsRange = null
        bind()
    }

    fun selectResolution(size: Size) {
        currentResolution = size
        bind()
    }

    fun selectFps(range: Range<Int>) {
        currentFpsRange = range
        bind()
    }

    fun restart(): Boolean = bind()

    fun requestPreview(requestId: String): Boolean {
        if (closed || currentCamera == null) return false
        return pendingPreviewRequest.compareAndSet(null, requestId)
    }

    fun toggleTorch() {
        val camera = currentCamera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) return
        setTorch(camera.cameraInfo.torchState.value != TorchState.ON)
    }

    fun setTorch(enabled: Boolean): Boolean {
        val camera = currentCamera ?: return false
        if (!camera.cameraInfo.hasFlashUnit()) {
            onTorchChanged(TorchUiState.Unavailable)
            return false
        }
        camera.cameraControl.enableTorch(enabled)
        return true
    }

    fun setExposure(progress: Int) {
        exposureProgress = progress
        val camera = currentCamera ?: return
        val range = camera.cameraInfo.exposureState.exposureCompensationRange
        val index = range.lower + ((progress / 100f) * (range.upper - range.lower)).toInt()
        camera.cameraControl.setExposureCompensationIndex(index)
    }

    fun config(): CameraConfig {
        val boundCameraId = currentCamera?.cameraInfo?.let(::cameraId).orEmpty()
        return CameraConfig(
            cameraId = boundCameraId.ifEmpty { selectedCameraId },
            resolutionWidth = currentResolution?.width ?: 0,
            resolutionHeight = currentResolution?.height ?: 0,
            fpsLower = currentFpsRange?.lower ?: 0,
            fpsUpper = currentFpsRange?.upper ?: 0,
        )
    }

    fun close() {
        closed = true
        torchSource?.removeObserver(torchObserver)
        torchSource = null
        cameraStateSource?.removeObserver(cameraStateObserver)
        cameraStateSource = null
        cameraProvider?.unbindAll()
        analyzerExecutor.shutdown()
        currentCamera = null
        awaitingFirstFrame = false
        cameraProvider = null
        pendingPreviewRequest.set(null)
    }

    private fun bind(): Boolean {
        if (closed) return false
        val provider = cameraProvider ?: return false
        provider.unbindAll()
        currentCamera = null
        torchSource?.removeObserver(torchObserver)
        torchSource = null
        cameraStateSource?.removeObserver(cameraStateObserver)
        cameraStateSource = null
        awaitingFirstFrame = false
        cameraInterruptionReported = false

        val resolutionSelector = currentResolution?.let {
            ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(it, ResolutionStrategy.FALLBACK_RULE_NONE))
                .build()
        }
        val previewBuilder = Preview.Builder()
        resolutionSelector?.let(previewBuilder::setResolutionSelector)
        currentFpsRange?.let { range ->
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                .setCaptureRequestTemplate(CameraDevice.TEMPLATE_RECORD)
        }
        val preview = previewBuilder.build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        resolutionSelector?.let(analysisBuilder::setResolutionSelector)
        currentFpsRange?.let { range ->
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                .setCaptureRequestTemplate(CameraDevice.TEMPLATE_RECORD)
        }
        val analysis = analysisBuilder.build()
            .also { it.setAnalyzer(analyzerExecutor, ::analyzeFrame) }

        try {
            currentCamera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
            selectedCameraId = currentCamera?.cameraInfo?.let(::cameraId).orEmpty().ifEmpty { selectedCameraId }
            observeCameraState()
            observeTorch()
            setExposure(exposureProgress)
            awaitingFirstFrame = true
            return true
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Use case binding failed", exception)
            onCameraError(exception)
            return false
        }
    }

    private fun analyzeFrame(image: androidx.camera.core.ImageProxy) {
        try {
            if (awaitingFirstFrame) {
                awaitingFirstFrame = false
                mainExecutor.execute {
                    if (!closed) onCameraOperational()
                }
            }
            updateFps()
            val rotation = image.imageInfo.rotationDegrees
            val swapsAxes = rotation == 90 || rotation == 270
            val geometry = FrameGeometry(
                previewWidth = if (swapsAxes) image.height else image.width,
                previewHeight = if (swapsAxes) image.width else image.height,
                rawWidth = image.width,
                rawHeight = image.height,
                rotationDegrees = rotation,
            )
            if (frameGeometry != geometry) {
                frameGeometry = geometry
                configuredGeometry = null
                mainExecutor.execute {
                    if (!closed) {
                        onFrameGeometryChanged(geometry)
                        configuredGeometry = geometry
                    }
                }
            }

            val yPlane = image.planes[0]
            if (configuredGeometry == geometry && yPlane.buffer.isDirect) {
                sensorProcessor.processFrame(yPlane.buffer, image.width, image.height, yPlane.rowStride)?.let { mask ->
                    onSensorMask(mask)
                    mainExecutor.execute {
                        if (!closed) onSensorMaskForDisplay(mask)
                    }
                }
            }
            pendingPreviewRequest.getAndSet(null)?.let { requestId ->
                try {
                    onPreviewCaptured(PreviewCaptureResult.Success(requestId, PreviewEncoder.encode(image)))
                } catch (exception: Exception) {
                    onPreviewCaptured(
                        PreviewCaptureResult.Failure(
                            requestId,
                            exception.message ?: "Preview capture failed",
                        ),
                    )
                }
            }
        } finally {
            image.close()
        }
    }

    private fun updateFps() {
        val now = System.currentTimeMillis()
        frameCount++
        if (now - lastFpsTimestamp >= FPS_UPDATE_INTERVAL_MILLIS) {
            val fps = frameCount * 1000f / (now - lastFpsTimestamp)
            frameCount = 0
            lastFpsTimestamp = now
            mainExecutor.execute {
                if (!closed) onFpsChanged(fps)
            }
        }
    }

    private fun observeTorch() {
        val cameraInfo = currentCamera?.cameraInfo
        if (cameraInfo?.hasFlashUnit() != true) {
            onTorchChanged(TorchUiState.Unavailable)
            return
        }
        torchSource = cameraInfo.torchState.also {
            it.observe(lifecycleOwner, torchObserver)
        }
    }

    private fun observeCameraState() {
        cameraStateSource = currentCamera?.cameraInfo?.cameraState?.also {
            it.observe(lifecycleOwner, cameraStateObserver)
        }
    }

    private fun selectorFor(cameraId: String): CameraSelector {
        if (cameraId.isEmpty()) return CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder().addCameraFilter { cameras ->
            cameras.filter { cameraId(it) == cameraId }.ifEmpty { cameras }
        }.build()
    }

    private fun cameraId(cameraInfo: CameraInfo): String = try {
        Camera2CameraInfo.from(cameraInfo).cameraId
    } catch (_: Exception) {
        ""
    }

    private fun CameraConfig.resolutionOrNull(): Size? =
        if (resolutionWidth > 0 && resolutionHeight > 0) Size(resolutionWidth, resolutionHeight) else null

    private fun CameraConfig.fpsRangeOrNull(): Range<Int>? =
        if (fpsLower > 0 && fpsUpper > 0) Range(fpsLower, fpsUpper) else null

    sealed interface TorchUiState {
        data object Unavailable : TorchUiState
        data class Available(val enabled: Boolean) : TorchUiState
    }

    sealed interface PreviewCaptureResult {
        data class Success(val requestId: String, val preview: EncodedPreview) : PreviewCaptureResult
        data class Failure(val requestId: String, val message: String) : PreviewCaptureResult
    }

    private companion object {
        const val LOG_TAG = "sensorithm"
        const val FPS_UPDATE_INTERVAL_MILLIS = 500L
    }
}

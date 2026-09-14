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
    private val onCameraError: (Exception) -> Unit,
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
    private val torchObserver = Observer<Int> { state ->
        onTorchChanged(TorchUiState.Available(state == TorchState.ON))
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

    val provider: ProcessCameraProvider?
        get() = cameraProvider

    val camera: Camera?
        get() = currentCamera

    val resolution: Size?
        get() = currentResolution

    val fpsRange: Range<Int>?
        get() = currentFpsRange

    fun start(config: CameraConfig) {
        closed = false
        restoreConfig(config)

        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            if (closed) return@addListener
            try {
                cameraProvider = providerFuture.get()
                bind()
            } catch (exception: Exception) {
                onCameraError(exception)
            }
        }, mainExecutor)
    }

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

    fun restart() = bind()

    fun toggleTorch() {
        val camera = currentCamera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) return
        camera.cameraControl.enableTorch(camera.cameraInfo.torchState.value != TorchState.ON)
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
        cameraProvider?.unbindAll()
        analyzerExecutor.shutdown()
        currentCamera = null
        cameraProvider = null
    }

    private fun bind() {
        if (closed) return
        val provider = cameraProvider ?: return
        provider.unbindAll()
        torchSource?.removeObserver(torchObserver)
        torchSource = null

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
            observeTorch()
            setExposure(exposureProgress)
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Use case binding failed", exception)
            onCameraError(exception)
        }
    }

    private fun analyzeFrame(image: androidx.camera.core.ImageProxy) {
        try {
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

    private companion object {
        const val LOG_TAG = "Sensorithm"
        const val FPS_UPDATE_INTERVAL_MILLIS = 500L
    }
}

package ch.michioxd.sensorithm

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

object CameraDialogHelper {

    fun showCameraSelectionDialog(
        context: Context,
        cameraProvider: ProcessCameraProvider?,
        currentCameraInfo: CameraInfo?,
        onSelected: (CameraInfo) -> Unit
    ) {
        val provider = cameraProvider ?: return
        val cameras = provider.availableCameraInfos
        
        var selectedIndex = -1
        
        val cameraNames = cameras.mapIndexed { index, info -> 
            if (info == currentCameraInfo) {
                selectedIndex = index
            }
            val facing = when (info.lensFacing) {
                CameraSelector.LENS_FACING_BACK -> "Back"
                CameraSelector.LENS_FACING_FRONT -> "Front"
                else -> "External"
            }
            var id = "Unknown"
            try {
                id = Camera2CameraInfo.from(info).cameraId
            } catch (e: Exception) { }
            
            "Camera $id ($facing)"
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Select Camera")
            .setSingleChoiceItems(cameraNames, selectedIndex) { dialog, which ->
                onSelected(cameras[which])
                dialog.dismiss()
            }
            .show()
    }

    fun showResolutionSelectionDialog(
        context: Context,
        currentCamera: Camera?,
        currentResolution: Size?,
        onSelected: (Size) -> Unit
    ) {
        val camInfo = currentCamera?.cameraInfo
        if (camInfo == null) {
            Toast.makeText(context, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val cameraId = Camera2CameraInfo.from(camInfo).cameraId
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: arrayOf()
            
            val uniqueSizes = sizes.distinctBy { it.width * it.height }.sortedByDescending { it.width * it.height }
            
            if (uniqueSizes.isEmpty()) {
                Toast.makeText(context, "No resolutions found", Toast.LENGTH_SHORT).show()
                return
            }
            
            var selectedIndex = -1
            val resNames = uniqueSizes.mapIndexed { index, size -> 
                if (size == currentResolution) {
                    selectedIndex = index
                }
                "${size.width} x ${size.height}"
            }.toTypedArray()
            
            AlertDialog.Builder(context)
                .setTitle("Select Resolution")
                .setSingleChoiceItems(resNames, selectedIndex) { dialog, which ->
                    onSelected(uniqueSizes[which])
                    dialog.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Log.e("Sensorithm", "Failed to get resolutions", e)
            Toast.makeText(context, "Failed to get resolutions from camera", Toast.LENGTH_SHORT).show()
        }
    }

    fun showFpsSelectionDialog(
        context: Context,
        currentCamera: Camera?,
        currentFpsRange: Range<Int>?,
        onSelected: (Range<Int>) -> Unit
    ) {
        val camInfo = currentCamera?.cameraInfo
        if (camInfo == null) {
            Toast.makeText(context, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val cameraId = Camera2CameraInfo.from(camInfo).cameraId
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf()
            
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val highSpeedRanges = try {
                map?.highSpeedVideoFpsRanges ?: arrayOf()
            } catch (e: Exception) {
                arrayOf()
            }
            
            val combinedRanges = fpsRanges.toList() + highSpeedRanges.toList()
            val uniqueFps = combinedRanges.distinctBy { "${it.lower}-${it.upper}" }.sortedByDescending { it.upper }
            
            if (uniqueFps.isEmpty()) {
                Toast.makeText(context, "No FPS ranges found", Toast.LENGTH_SHORT).show()
                return
            }
            
            var selectedIndex = -1
            val fpsNames = uniqueFps.mapIndexed { index, range -> 
                if (range == currentFpsRange) {
                    selectedIndex = index
                }
                "${range.lower} - ${range.upper} FPS"
            }.toTypedArray()
            
            AlertDialog.Builder(context)
                .setTitle("Select FPS Range")
                .setSingleChoiceItems(fpsNames, selectedIndex) { dialog, which ->
                    onSelected(uniqueFps[which])
                    dialog.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Log.e("Sensorithm", "Failed to get FPS ranges", e)
            Toast.makeText(context, "Failed to get FPS from camera", Toast.LENGTH_SHORT).show()
        }
    }
}

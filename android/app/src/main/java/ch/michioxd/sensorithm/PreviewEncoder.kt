package ch.michioxd.sensorithm

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class EncodedPreview(val jpegBytes: ByteArray, val width: Int, val height: Int)

object PreviewEncoder {
    private const val MAX_DIMENSION = 640
    private const val JPEG_QUALITY = 70

    fun encode(image: ImageProxy): EncodedPreview {
        require(image.planes.size >= 3) { "Camera frame is not YUV_420_888" }
        val scale = (MAX_DIMENSION.toFloat() / max(image.width, image.height)).coerceAtMost(1f)
        val outputWidth = (image.width * scale).roundToInt().coerceAtLeast(1)
        val outputHeight = (image.height * scale).roundToInt().coerceAtLeast(1)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val yStart = yBuffer.position()
        val uStart = uBuffer.position()
        val vStart = vBuffer.position()
        val pixels = IntArray(outputWidth * outputHeight)

        for (outputY in 0 until outputHeight) {
            val sourceY = (outputY * image.height / outputHeight).coerceAtMost(image.height - 1)
            for (outputX in 0 until outputWidth) {
                val sourceX = (outputX * image.width / outputWidth).coerceAtMost(image.width - 1)
                val y = yBuffer.get(
                    yStart + sourceY * yPlane.rowStride + sourceX * yPlane.pixelStride,
                ).toInt() and 0xff
                val chromaX = sourceX / 2
                val chromaY = sourceY / 2
                val u = (uBuffer.get(
                    uStart + chromaY * uPlane.rowStride + chromaX * uPlane.pixelStride,
                ).toInt() and 0xff) - 128
                val v = (vBuffer.get(
                    vStart + chromaY * vPlane.rowStride + chromaX * vPlane.pixelStride,
                ).toInt() and 0xff) - 128
                val luminance = (y - 16).coerceAtLeast(0)
                val red = ((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255)
                pixels[outputY * outputWidth + outputX] =
                    (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        val raw = Bitmap.createBitmap(pixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val rotated = if (image.imageInfo.rotationDegrees == 0) {
            raw
        } else {
            Bitmap.createBitmap(
                raw,
                0,
                0,
                raw.width,
                raw.height,
                Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) },
                true,
            ).also { raw.recycle() }
        }
        return try {
            val output = ByteArrayOutputStream()
            check(rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "JPEG compression failed"
            }
            EncodedPreview(output.toByteArray(), rotated.width, rotated.height)
        } finally {
            rotated.recycle()
        }
    }
}

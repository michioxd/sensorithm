package ch.michioxd.sensorithm

import kotlin.math.cos
import kotlin.math.sin

data class ZoneSettings(
    val sizePercentX: Int = DEFAULT_SIZE_PERCENT_X,
    val sizePercentY: Int = DEFAULT_SIZE_PERCENT_Y,
    val spacingPercent: Int = DEFAULT_SPACING_PERCENT,
    val angleDegrees: Int = DEFAULT_ANGLE_DEGREES,
    val threshold: Int = DEFAULT_THRESHOLD,
) {
    companion object {
        const val DEFAULT_SIZE_PERCENT_X = 15
        const val DEFAULT_SIZE_PERCENT_Y = 5
        const val DEFAULT_SPACING_PERCENT = 10
        const val DEFAULT_ANGLE_DEGREES = 180
        const val DEFAULT_THRESHOLD = 30
    }
}

data class FrameGeometry(
    val previewWidth: Int,
    val previewHeight: Int,
    val rawWidth: Int,
    val rawHeight: Int,
    val rotationDegrees: Int,
)

data class ZoneLayout(
    val sensorWidth: Int,
    val sensorHeight: Int,
    val spacing: Int,
    val pixelOffsetX: Int,
    val pixelOffsetY: Int,
    val centers: List<ZoneCenter>,
    val nativeConfig: IntArray,
)

data class ZoneCenter(val x: Int, val y: Int)

object SensorZoneLayoutCalculator {
    const val SENSOR_COUNT = 6
    private const val VALUES_PER_SENSOR = 4
    private const val CENTER_INDEX = 2.5f

    fun calculate(
        frame: FrameGeometry,
        settings: ZoneSettings,
        normalizedOffsetX: Float,
        normalizedOffsetY: Float,
    ): ZoneLayout {
        require(frame.previewWidth > 0 && frame.previewHeight > 0)

        val sensorWidth = (settings.sizePercentX / 100f * frame.previewWidth).toInt().coerceAtLeast(1)
        val sensorHeight = (settings.sizePercentY / 100f * frame.previewHeight).toInt().coerceAtLeast(1)
        val spacing = (settings.spacingPercent / 100f * frame.previewHeight).toInt()
        val pixelOffsetX = ((normalizedOffsetX - 0.5f) * frame.previewWidth).toInt()
        val pixelOffsetY = ((normalizedOffsetY - 0.5f) * frame.previewHeight).toInt()
        val nativeConfig = IntArray(SENSOR_COUNT * VALUES_PER_SENSOR)
        val centers = ArrayList<ZoneCenter>(SENSOR_COUNT)

        val swapsAxes = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
        val rawSensorWidth = if (swapsAxes) sensorHeight else sensorWidth
        val rawSensorHeight = if (swapsAxes) sensorWidth else sensorHeight
        val radians = Math.toRadians((settings.angleDegrees - 180).toDouble())
        val directionX = sin(radians).toFloat()
        val directionY = cos(radians).toFloat()

        for (index in 0 until SENSOR_COUNT) {
            val previewX = (frame.previewWidth / 2f + pixelOffsetX +
                (index - CENTER_INDEX) * spacing * directionX).toInt()
            val previewY = (frame.previewHeight / 2f + pixelOffsetY +
                (index - CENTER_INDEX) * spacing * directionY).toInt()
            centers += ZoneCenter(previewX, previewY)
            val (rawX, rawY) = previewToRaw(previewX, previewY, frame)
            val base = index * VALUES_PER_SENSOR
            nativeConfig[base] = rawX
            nativeConfig[base + 1] = rawY
            nativeConfig[base + 2] = rawSensorWidth
            nativeConfig[base + 3] = rawSensorHeight
        }

        return ZoneLayout(sensorWidth, sensorHeight, spacing, pixelOffsetX, pixelOffsetY, centers, nativeConfig)
    }

    private fun previewToRaw(x: Int, y: Int, frame: FrameGeometry): Pair<Int, Int> =
        when (frame.rotationDegrees) {
            90 -> y to frame.rawHeight - 1 - x
            180 -> frame.rawWidth - 1 - x to frame.rawHeight - 1 - y
            270 -> frame.rawWidth - 1 - y to x
            else -> x to y
        }
}

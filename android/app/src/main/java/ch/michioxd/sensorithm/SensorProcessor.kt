package ch.michioxd.sensorithm

import java.nio.ByteBuffer

interface SensorEngine {
    fun configureZones(config: IntArray)
    fun setThreshold(threshold: Float)
    fun recalibrate()
    fun processFrame(yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int): Byte
}

object JniSensorEngine : SensorEngine {
    override fun configureZones(config: IntArray) = SensorithmJNI.setAirConfig(config)
    override fun setThreshold(threshold: Float) = SensorithmJNI.setThreshold(ALL_SENSORS, threshold)
    override fun recalibrate() = SensorithmJNI.recalibrate()
    override fun processFrame(yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int): Byte =
        SensorithmJNI.processFrame(yPlane, width, height, rowStride)

    private const val ALL_SENSORS = -1
}

class SensorProcessor(private val engine: SensorEngine = JniSensorEngine) {
    private val nativeLock = Any()

    @Volatile
    private var lastOutputMask: Byte = INVALID_MASK

    fun configureZones(config: IntArray) {
        synchronized(nativeLock) {
            engine.configureZones(config)
        }
    }

    fun setThreshold(threshold: Float) {
        synchronized(nativeLock) {
            engine.setThreshold(threshold)
        }
    }

    fun recalibrate() {
        synchronized(nativeLock) {
            engine.recalibrate()
        }
    }

    fun resetOutput() {
        lastOutputMask = INVALID_MASK
    }

    fun processFrame(yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int): Byte? {
        val mask = synchronized(nativeLock) {
            engine.processFrame(yPlane, width, height, rowStride)
        }
        if (mask == INVALID_MASK || mask == lastOutputMask) return null
        lastOutputMask = mask
        return mask
    }

    private companion object {
        const val INVALID_MASK: Byte = -1
    }
}

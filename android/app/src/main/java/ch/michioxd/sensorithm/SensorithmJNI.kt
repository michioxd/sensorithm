package ch.michioxd.sensorithm

import java.nio.ByteBuffer

object SensorithmJNI {
    init {
        System.loadLibrary("sensorithm")
    }

    /**
     * Set the parameters for the 6 AIR sensors.
     * @param configs Array of 24 ints: [x0, y0, w0, h0, x1, y1, w1, h1...]
     */
    external fun setAirConfig(configs: IntArray)

    external fun setThreshold(sensorIndex: Int, threshold: Float)
    external fun recalibrate()
    
    /**
     * Process a YUV Y-plane frame.
     * @return a single byte bitmask where lowest 6 bits represent active state of 6 zones.
     */
    external fun processFrame(yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int): Byte
}

package ch.michioxd.sensorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class SensorProcessorTest {
    @Test
    fun `suppresses calibration and duplicate masks until reset`() {
        val engine = FakeSensorEngine(ArrayDeque(listOf((-1).toByte(), 3, 3, 4)))
        val processor = SensorProcessor(engine)
        val buffer = ByteBuffer.allocateDirect(1)

        assertNull(processor.processFrame(buffer, 1, 1, 1))
        assertEquals(3.toByte(), processor.processFrame(buffer, 1, 1, 1))
        assertNull(processor.processFrame(buffer, 1, 1, 1))
        assertEquals(4.toByte(), processor.processFrame(buffer, 1, 1, 1))
        processor.resetOutput()
        engine.results.add(4)
        assertEquals(4.toByte(), processor.processFrame(buffer, 1, 1, 1))
    }

    private class FakeSensorEngine(val results: ArrayDeque<Byte>) : SensorEngine {
        override fun configureZones(config: IntArray) = Unit
        override fun setThreshold(threshold: Float) = Unit
        override fun recalibrate() = Unit
        override fun processFrame(yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int) =
            results.removeFirst()
    }
}

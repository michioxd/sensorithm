package ch.michioxd.sensorithm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorZoneLayoutCalculatorTest {
    private val settings = ZoneSettings(20, 10, 10, 180, 30)

    @Test
    fun `calculates the existing six sensor ordering and sizes`() {
        val result = SensorZoneLayoutCalculator.calculate(
            FrameGeometry(200, 100, 200, 100, 0), settings, 0.5f, 0.5f,
        )

        assertEquals(40, result.sensorWidth)
        assertEquals(10, result.sensorHeight)
        assertEquals(10, result.spacing)
        assertArrayEquals(
            intArrayOf(
                100, 25, 40, 10, 100, 35, 40, 10, 100, 45, 40, 10,
                100, 55, 40, 10, 100, 65, 40, 10, 100, 75, 40, 10,
            ),
            result.nativeConfig,
        )
    }

    @Test
    fun `maps preview coordinates into a ninety degree raw frame`() {
        val result = SensorZoneLayoutCalculator.calculate(
            FrameGeometry(100, 200, 200, 100, 90), settings, 0.5f, 0.5f,
        )

        assertArrayEquals(intArrayOf(50, 49, 20, 20), result.nativeConfig.copyOfRange(0, 4))
        assertArrayEquals(intArrayOf(150, 49, 20, 20), result.nativeConfig.copyOfRange(20, 24))
    }

    @Test
    fun `maps preview coordinates into a two hundred seventy degree raw frame`() {
        val result = SensorZoneLayoutCalculator.calculate(
            FrameGeometry(100, 200, 200, 100, 270), settings, 0.5f, 0.5f,
        )

        assertArrayEquals(intArrayOf(149, 50, 20, 20), result.nativeConfig.copyOfRange(0, 4))
        assertArrayEquals(intArrayOf(49, 50, 20, 20), result.nativeConfig.copyOfRange(20, 24))
    }
}

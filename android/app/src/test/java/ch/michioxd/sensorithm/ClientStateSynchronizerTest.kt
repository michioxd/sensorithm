package ch.michioxd.sensorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientStateSynchronizerTest {
    private val validSettings = SyncedSettings(
        sizeX = 15,
        sizeY = 5,
        spacing = 10,
        angle = 180,
        exposure = 10,
        threshold = 30,
        offsetX = 0.5f,
        offsetY = 0.5f,
    )

    @Test
    fun localChangesAreSentOnce() {
        val sent = mutableListOf<ClientMessage>()
        val synchronizer = ClientStateSynchronizer(sent::add) {}

        synchronizer.publishLocalSettings(validSettings)

        assertEquals(listOf(ClientMessage.Settings(validSettings)), sent)
    }

    @Test
    fun remoteChangesAreAppliedWithoutBeingSentBack() {
        val sent = mutableListOf<ClientMessage>()
        val applied = mutableListOf<SyncedSettings>()
        val synchronizer = ClientStateSynchronizer(sent::add, applied::add)

        assertTrue(synchronizer.applyRemoteSettings(validSettings))

        assertEquals(listOf(validSettings), applied)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun invalidRemoteChangesAreRejected() {
        val applied = mutableListOf<SyncedSettings>()
        val synchronizer = ClientStateSynchronizer({}, applied::add)

        assertFalse(synchronizer.applyRemoteSettings(validSettings.copy(offsetX = -0.1f)))

        assertTrue(applied.isEmpty())
    }
}

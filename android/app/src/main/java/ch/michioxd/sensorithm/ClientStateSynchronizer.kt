package ch.michioxd.sensorithm

class ClientStateSynchronizer(
    private val send: (ClientMessage) -> Unit,
    private val applyRemote: (SyncedSettings) -> Unit,
) {
    fun publishLocalSettings(settings: SyncedSettings) {
        if (settings.isValid()) send(ClientMessage.Settings(settings))
    }

    fun applyRemoteSettings(settings: SyncedSettings): Boolean {
        if (!settings.isValid()) return false
        applyRemote(settings)
        return true
    }
}

package ch.michioxd.sensorithm

import android.os.Handler
import android.os.Looper

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val serverVersion: String) : ConnectionState
}

class ConnectionManager(
    appVersion: String,
    private val autoReconnectEnabled: () -> Boolean,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onConnectionTimeout: () -> Unit,
    private val onRecalibrate: () -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    @Volatile
    private var closed = false

    var state: ConnectionState = ConnectionState.Disconnected
        private set

    private var lastAddress = AppConfig.DEFAULT_SERVER_ADDRESS
    private var lastPort = AppConfig.DEFAULT_SERVER_PORT

    private val client = Client(
        appVersion = appVersion,
        onConnected = { serverVersion ->
            mainHandler.post {
                if (closed) return@post
                cancelTimeout()
                updateState(ConnectionState.Connected(serverVersion))
            }
        },
        onDisconnected = {
            mainHandler.post {
                if (closed) return@post
                updateState(ConnectionState.Disconnected)
                scheduleReconnectIfEnabled()
            }
        },
        onRecalibrate = {
            mainHandler.post {
                if (!closed) onRecalibrate()
            }
        },
    )

    private val timeoutRunnable = Runnable {
        if (state == ConnectionState.Connecting) {
            onConnectionTimeout()
            disconnect()
        }
    }

    private val reconnectRunnable = Runnable {
        if (autoReconnectEnabled() && state == ConnectionState.Disconnected) {
            connect(lastAddress, lastPort)
        }
    }

    fun connect(address: String, port: Int) {
        if (closed) return
        if (state != ConnectionState.Disconnected) return
        lastAddress = address
        lastPort = port
        updateState(ConnectionState.Connecting)
        client.connect(address, port)
        cancelTimeout()
        mainHandler.postDelayed(timeoutRunnable, CONNECTION_TIMEOUT_MILLIS)
    }

    fun disconnect() {
        cancelScheduledWork()
        client.disconnect()
        updateState(ConnectionState.Disconnected)
    }

    fun sendMask(mask: Byte) = client.sendMask(mask)

    fun close() {
        closed = true
        cancelScheduledWork()
        client.disconnect()
        state = ConnectionState.Disconnected
    }

    private fun scheduleReconnectIfEnabled() {
        mainHandler.removeCallbacks(reconnectRunnable)
        if (autoReconnectEnabled()) {
            mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MILLIS)
        }
    }

    private fun cancelTimeout() = mainHandler.removeCallbacks(timeoutRunnable)

    private fun cancelScheduledWork() {
        cancelTimeout()
        mainHandler.removeCallbacks(reconnectRunnable)
    }

    private fun updateState(newState: ConnectionState) {
        state = newState
        onStateChanged(newState)
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MILLIS = 10_000L
        const val RECONNECT_DELAY_MILLIS = 3_000L
    }
}

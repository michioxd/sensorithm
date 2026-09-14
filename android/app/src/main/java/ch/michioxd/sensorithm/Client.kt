package ch.michioxd.sensorithm

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class Client(
    private val appVersion: String,
    private val onConnected: (String) -> Unit,
    private val onRejected: (String) -> Unit,
    private val onDisconnected: (Exception?) -> Unit,
    private val onMessage: (ServerMessage) -> Unit,
    private val onProtocolError: (String) -> Unit,
) {
    private val generation = AtomicLong()
    private val lifecycleLock = Any()
    private val writeLock = Any()
    private val messageExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var outputStream: OutputStream? = null
    @Volatile
    private var connectionThread: Thread? = null

    fun connect(ip: String, port: Int) {
        disconnect()
        val connectionGeneration = generation.get()

        connectionThread = thread(name = "sensorithm-client") {
            var connected = false
            var rejected = false
            var failure: Exception? = null
            var ownedSocket: Socket? = null
            try {
                val newSocket = Socket().apply {
                    tcpNoDelay = true
                    soTimeout = HANDSHAKE_TIMEOUT_MILLIS
                }
                ownedSocket = newSocket
                synchronized(lifecycleLock) {
                    if (!isCurrent(connectionGeneration)) return@thread
                    socket = newSocket
                }
                newSocket.connect(InetSocketAddress(ip, port))
                val out = newSocket.getOutputStream()
                val reader = newSocket.getInputStream().bufferedReader(Charsets.UTF_8)

                val info = JSONObject().apply {
                    put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("os", "Android ${Build.VERSION.RELEASE}")
                    put("app", appVersion)
                }
                synchronized(writeLock) {
                    out.write((info.toString() + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }

                val helloLine = reader.readLine() ?: throw IOException("Server closed during handshake")
                val hello = ClientProtocol.parseHello(helloLine)
                if (!hello.accepted) {
                    rejected = true
                    onRejected(hello.message ?: DEFAULT_REJECTION_MESSAGE)
                    return@thread
                }
                if (!isCurrent(connectionGeneration)) return@thread

                newSocket.soTimeout = 0
                outputStream = out
                connected = true
                onConnected(hello.version)
                Log.d(LOG_TAG, "Connected to $ip:$port")

                while (isCurrent(connectionGeneration) && !Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    if (line.trim() == LEGACY_RECALIBRATE_COMMAND) {
                        onMessage(ServerMessage.Recalibrate)
                        continue
                    }
                    try {
                        onMessage(ClientProtocol.parseServerMessage(line))
                    } catch (exception: Exception) {
                        Log.e(LOG_TAG, "Ignored malformed server command", exception)
                        onProtocolError(exception.message ?: "Malformed server command")
                    }
                }
            } catch (exception: Exception) {
                failure = exception
                Log.e(LOG_TAG, "Connection failed or interrupted", exception)
            } finally {
                try {
                    ownedSocket?.close()
                } catch (_: Exception) {
                }
                synchronized(lifecycleLock) {
                    if (socket === ownedSocket) {
                        socket = null
                        outputStream = null
                    }
                }
                if (isCurrent(connectionGeneration)) {
                    connectionThread = null
                    if (!rejected) onDisconnected(failure.takeIf { !connected } ?: failure)
                }
            }
        }
    }

    fun sendMask(mask: Byte) {
        send { output ->
            output.write(byteArrayOf(mask))
            output.flush()
        }
    }

    fun sendMessage(message: ClientMessage) {
        messageExecutor.execute {
            send { output -> ClientProtocol.writeClientMessage(output, message) }
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        connectionThread?.interrupt()
        connectionThread = null
        synchronized(lifecycleLock) {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            outputStream = null
        }
    }

    private fun send(write: (OutputStream) -> Unit) {
        val output = outputStream ?: return
        try {
            synchronized(writeLock) {
                if (output === outputStream) write(output)
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Send failed", exception)
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun isCurrent(connectionGeneration: Long) = generation.get() == connectionGeneration

    private companion object {
        const val LOG_TAG = "sensorithmClient"
        const val HANDSHAKE_TIMEOUT_MILLIS = 3_000
        const val LEGACY_RECALIBRATE_COMMAND = "RECALIBRATE"
        const val DEFAULT_REJECTION_MESSAGE =
            "Another client is already connected. You cannot connect right now."
    }
}

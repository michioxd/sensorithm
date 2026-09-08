package ch.michioxd.sensorithm

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

class Client(
    private val appVersion: String,
    private val onConnected: (String) -> Unit,
    private val onDisconnected: (Exception?) -> Unit,
    private val onRecalibrate: () -> Unit
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var connectionThread: Thread? = null

    fun connect(ip: String, port: Int) {
        disconnect()
        
        connectionThread = thread {
            try {
                val newSocket = Socket(ip, port).apply {
                    tcpNoDelay = true
                    soTimeout = 3000
                }
                socket = newSocket
                val out = newSocket.getOutputStream()
                val input = newSocket.getInputStream()

                val info = JSONObject().apply {
                    put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("os", "Android ${Build.VERSION.RELEASE}")
                    put("app", appVersion)
                }
                
                out.write((info.toString() + "\n").toByteArray(Charsets.UTF_8))
                out.flush()

                val reader = input.bufferedReader(Charsets.UTF_8)
                val serverLine = reader.readLine()
                var serverVersion = "Unknown"
                if (serverLine != null) {
                    try {
                        val serverInfo = JSONObject(serverLine)
                        serverVersion = serverInfo.optString("version", "Unknown")
                    } catch (e: Exception) {
                        Log.e("SensorithmClient", "Failed to parse server info", e)
                    }
                }
                
                newSocket.soTimeout = 0

                outputStream = out

                onConnected(serverVersion)
                Log.d("SensorithmClient", "Connected to $ip:$port")

                while (!Thread.currentThread().isInterrupted) {
                    val cmd = reader.readLine()
                    if (cmd == null) {
                        break
                    }
                    if (cmd.trim() == "RECALIBRATE") {
                        onRecalibrate()
                    }
                }
            } catch (e: Exception) {
                if (socket != null && !socket!!.isClosed) {
                    onDisconnected(e)
                    Log.e("SensorithmClient", "Connection failed or interrupted", e)
                }
            }
        }
    }

    fun sendMask(mask: Byte) {
        if (outputStream != null) {
            try {
                outputStream?.write(byteArrayOf(mask))
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e("SensorithmClient", "Send failed", e)
                disconnect()
                onDisconnected(e)
            }
        }
    }

    fun disconnect() {
        connectionThread?.interrupt()
        connectionThread = null
        try {
            outputStream?.close()
        } catch (e: Exception) {}
        try {
            socket?.close()
        } catch (e: Exception) {}
        outputStream = null
        socket = null
    }
}

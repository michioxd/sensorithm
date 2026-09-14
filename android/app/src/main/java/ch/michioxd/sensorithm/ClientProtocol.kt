package ch.michioxd.sensorithm

import android.util.Base64
import org.json.JSONObject
import java.io.OutputStream

data class ServerHello(val version: String, val accepted: Boolean, val message: String?)

data class SyncedSettings(
    val sizeX: Int,
    val sizeY: Int,
    val spacing: Int,
    val angle: Int,
    val exposure: Int,
    val threshold: Int,
    val offsetX: Float,
    val offsetY: Float,
) {
    fun isValid(): Boolean =
        sizeX in 0..100 && sizeY in 0..50 && spacing in 0..100 && angle in 0..360 &&
            exposure in 0..100 && threshold in 0..255 && offsetX in 0f..1f && offsetY in 0f..1f
}

sealed interface ServerMessage {
    data object Recalibrate : ServerMessage
    data class Settings(val settings: SyncedSettings) : ServerMessage
    data class SetTorch(val enabled: Boolean) : ServerMessage
    data object RestartCamera : ServerMessage
    data class RequestPreview(val requestId: String) : ServerMessage
}

sealed interface ClientMessage {
    data class Settings(val settings: SyncedSettings) : ClientMessage
    data class TorchState(val available: Boolean, val enabled: Boolean) : ClientMessage
    data class Telemetry(
        val batteryPercent: Int,
        val temperatureCelsius: Float?,
        val charging: Boolean,
    ) : ClientMessage
    data class Preview(val requestId: String, val width: Int, val height: Int, val jpegBytes: ByteArray) : ClientMessage
    data class Error(val operation: String, val requestId: String?, val message: String) : ClientMessage
}

object ClientProtocol {
    private const val CONTROL_FRAME_PREFIX = 0xff

    fun parseHello(line: String): ServerHello {
        val json = JSONObject(line)
        return ServerHello(
            version = json.optString("version", "Unknown"),
            accepted = json.optBoolean("accepted", true),
            message = json.optString("message").takeIf { it.isNotEmpty() },
        )
    }

    fun parseServerMessage(line: String): ServerMessage {
        val json = JSONObject(line)
        return when (json.getString("type")) {
            "recalibrate" -> ServerMessage.Recalibrate
            "settings" -> ServerMessage.Settings(parseSettings(json.getJSONObject("settings")))
            "set_torch" -> ServerMessage.SetTorch(json.getBoolean("enabled"))
            "restart_camera" -> ServerMessage.RestartCamera
            "request_preview" -> ServerMessage.RequestPreview(json.getString("request_id"))
            else -> throw ProtocolException("Unsupported server message type")
        }
    }

    fun writeClientMessage(output: OutputStream, message: ClientMessage) {
        val payload = encodeClientMessage(message).toString().toByteArray(Charsets.UTF_8)
        output.write(CONTROL_FRAME_PREFIX)
        output.write(byteArrayOf(
            (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte(),
        ))
        output.write(payload)
        output.flush()
    }

    private fun encodeClientMessage(message: ClientMessage): JSONObject = when (message) {
        is ClientMessage.Settings -> JSONObject().put("type", "settings").put("settings", encodeSettings(message.settings))
        is ClientMessage.TorchState -> JSONObject().put("type", "torch_state")
            .put("available", message.available).put("enabled", message.enabled)
        is ClientMessage.Telemetry -> JSONObject().put("type", "telemetry")
            .put("battery_percent", message.batteryPercent)
            .put("temperature_celsius", message.temperatureCelsius ?: JSONObject.NULL)
            .put("charging", message.charging)
        is ClientMessage.Preview -> JSONObject().put("type", "preview")
            .put("request_id", message.requestId).put("width", message.width).put("height", message.height)
            .put("image_base64", Base64.encodeToString(message.jpegBytes, Base64.NO_WRAP))
        is ClientMessage.Error -> JSONObject().put("type", "error").put("operation", message.operation)
            .put("request_id", message.requestId).put("message", message.message)
    }

    private fun encodeSettings(settings: SyncedSettings) = JSONObject()
        .put("size_x", settings.sizeX).put("size_y", settings.sizeY)
        .put("spacing", settings.spacing).put("angle", settings.angle)
        .put("exposure", settings.exposure).put("threshold", settings.threshold)
        .put("offset_x", settings.offsetX.toDouble()).put("offset_y", settings.offsetY.toDouble())

    private fun parseSettings(json: JSONObject) = SyncedSettings(
        sizeX = json.getInt("size_x"), sizeY = json.getInt("size_y"),
        spacing = json.getInt("spacing"), angle = json.getInt("angle"),
        exposure = json.getInt("exposure"), threshold = json.getInt("threshold"),
        offsetX = json.getDouble("offset_x").toFloat(), offsetY = json.getDouble("offset_y").toFloat(),
    ).also {
        if (!it.isValid()) throw ProtocolException("Settings are outside the supported range")
    }
}

class ProtocolException(message: String) : Exception(message)

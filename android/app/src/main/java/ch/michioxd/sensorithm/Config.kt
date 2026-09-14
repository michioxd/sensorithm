package ch.michioxd.sensorithm

import android.content.Context
import android.content.SharedPreferences

data class AppConfig(
    val serverAddress: String = DEFAULT_SERVER_ADDRESS,
    val serverPort: Int = DEFAULT_SERVER_PORT,
    val autoReconnect: Boolean = false,
    val autoConnectOnStartup: Boolean = false,
    val zones: ZoneSettings = ZoneSettings(),
    val exposure: Int = DEFAULT_EXPOSURE,
    val zoneOffsetX: Float = DEFAULT_ZONE_OFFSET,
    val zoneOffsetY: Float = DEFAULT_ZONE_OFFSET,
    val camera: CameraConfig = CameraConfig(),
) {
    companion object {
        const val DEFAULT_SERVER_ADDRESS = "127.0.0.1"
        const val DEFAULT_SERVER_PORT = 4420
        const val DEFAULT_EXPOSURE = 10
        const val DEFAULT_ZONE_OFFSET = 0.5f
    }
}

data class CameraConfig(
    val cameraId: String = "",
    val resolutionWidth: Int = 0,
    val resolutionHeight: Int = 0,
    val fpsLower: Int = 0,
    val fpsUpper: Int = 0,
)

class ConfigRepository(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): AppConfig = AppConfig(
        serverAddress = preferences.getString(KEY_IP, AppConfig.DEFAULT_SERVER_ADDRESS)
            ?: AppConfig.DEFAULT_SERVER_ADDRESS,
        serverPort = preferences.getInt(KEY_PORT, AppConfig.DEFAULT_SERVER_PORT),
        autoReconnect = preferences.getBoolean(KEY_AUTO_RECONNECT, false),
        autoConnectOnStartup = preferences.getBoolean(KEY_AUTO_CONNECT_ON_STARTUP, false),
        zones = ZoneSettings(
            sizePercentX = preferences.getInt(KEY_SIZE_X, ZoneSettings.DEFAULT_SIZE_PERCENT_X),
            sizePercentY = preferences.getInt(KEY_SIZE_Y, ZoneSettings.DEFAULT_SIZE_PERCENT_Y),
            spacingPercent = preferences.getInt(KEY_SPACING, ZoneSettings.DEFAULT_SPACING_PERCENT),
            angleDegrees = preferences.getInt(KEY_ANGLE, ZoneSettings.DEFAULT_ANGLE_DEGREES),
            threshold = preferences.getInt(KEY_THRESHOLD, ZoneSettings.DEFAULT_THRESHOLD),
        ),
        exposure = preferences.getInt(KEY_EXPOSURE, AppConfig.DEFAULT_EXPOSURE),
        zoneOffsetX = preferences.getFloat(KEY_ZONE_OFFSET_X, AppConfig.DEFAULT_ZONE_OFFSET),
        zoneOffsetY = preferences.getFloat(KEY_ZONE_OFFSET_Y, AppConfig.DEFAULT_ZONE_OFFSET),
        camera = CameraConfig(
            cameraId = preferences.getString(KEY_CAMERA_ID, "") ?: "",
            resolutionWidth = preferences.getInt(KEY_RESOLUTION_WIDTH, 0),
            resolutionHeight = preferences.getInt(KEY_RESOLUTION_HEIGHT, 0),
            fpsLower = preferences.getInt(KEY_FPS_LOWER, 0),
            fpsUpper = preferences.getInt(KEY_FPS_UPPER, 0),
        ),
    )

    fun save(config: AppConfig) {
        preferences.edit()
            .putString(KEY_IP, config.serverAddress)
            .putInt(KEY_PORT, config.serverPort)
            .putBoolean(KEY_AUTO_RECONNECT, config.autoReconnect)
            .putBoolean(KEY_AUTO_CONNECT_ON_STARTUP, config.autoConnectOnStartup)
            .putInt(KEY_SIZE_X, config.zones.sizePercentX)
            .putInt(KEY_SIZE_Y, config.zones.sizePercentY)
            .putInt(KEY_SPACING, config.zones.spacingPercent)
            .putInt(KEY_ANGLE, config.zones.angleDegrees)
            .putInt(KEY_EXPOSURE, config.exposure)
            .putInt(KEY_THRESHOLD, config.zones.threshold)
            .putFloat(KEY_ZONE_OFFSET_X, config.zoneOffsetX)
            .putFloat(KEY_ZONE_OFFSET_Y, config.zoneOffsetY)
            .putString(KEY_CAMERA_ID, config.camera.cameraId)
            .putInt(KEY_RESOLUTION_WIDTH, config.camera.resolutionWidth)
            .putInt(KEY_RESOLUTION_HEIGHT, config.camera.resolutionHeight)
            .putInt(KEY_FPS_LOWER, config.camera.fpsLower)
            .putInt(KEY_FPS_UPPER, config.camera.fpsUpper)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "SensorithmPrefs"
        const val KEY_IP = "ip"
        const val KEY_PORT = "port"
        const val KEY_AUTO_RECONNECT = "autoReconnect"
        const val KEY_AUTO_CONNECT_ON_STARTUP = "autoConnectOnStartup"
        const val KEY_SIZE_X = "sizeX"
        const val KEY_SIZE_Y = "sizeY"
        const val KEY_SPACING = "spacing"
        const val KEY_ANGLE = "angle"
        const val KEY_EXPOSURE = "exposure"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_ZONE_OFFSET_X = "zoneOffsetX"
        const val KEY_ZONE_OFFSET_Y = "zoneOffsetY"
        const val KEY_CAMERA_ID = "cameraId"
        const val KEY_RESOLUTION_WIDTH = "resW"
        const val KEY_RESOLUTION_HEIGHT = "resH"
        const val KEY_FPS_LOWER = "fpsL"
        const val KEY_FPS_UPPER = "fpsU"
    }
}

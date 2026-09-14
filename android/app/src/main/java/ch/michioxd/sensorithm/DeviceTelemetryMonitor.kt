package ch.michioxd.sensorithm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

data class DeviceTelemetrySnapshot(
    val batteryPercent: Int,
    val batteryTemperatureCelsius: Float?,
    val isCharging: Boolean,
)

/** Event-driven battery telemetry. No polling thread is used. */
class DeviceTelemetryMonitor(
    context: Context,
    private val onChanged: (DeviceTelemetrySnapshot) -> Unit,
) {
    private val appContext = context.applicationContext
    private var registered = false

    var current: DeviceTelemetrySnapshot? = null
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::updateFromIntent)
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
        stickyIntent?.let(::updateFromIntent)
    }

    fun close() {
        if (!registered) return
        appContext.unregisterReceiver(receiver)
        registered = false
    }

    private fun updateFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val snapshot = DeviceTelemetrySnapshot(
            batteryPercent = (level * 100 / scale).coerceIn(0, 100),
            batteryTemperatureCelsius = temperatureTenths
                .takeUnless { it == Int.MIN_VALUE }
                ?.div(10f),
            isCharging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL,
        )
        if (snapshot == current) return
        current = snapshot
        onChanged(snapshot)
    }
}

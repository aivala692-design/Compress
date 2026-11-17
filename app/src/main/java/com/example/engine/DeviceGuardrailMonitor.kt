package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.example.model.GuardrailState
import com.example.model.ThermalLevel

class DeviceGuardrailMonitor(private val context: Context) {

  companion object {
    const val LOW_STORAGE_THRESHOLD_BYTES: Long = 500L * 1024L * 1024L // 500 MB
    const val LOW_BATTERY_PERCENT: Int = 10
  }

  fun sample(requiredBytes: Long = LOW_STORAGE_THRESHOLD_BYTES): GuardrailState {
    val availableBytes = queryAvailableStorageBytes()
    val isLowStorage = availableBytes < maxOf(LOW_STORAGE_THRESHOLD_BYTES, requiredBytes)

    val batteryStatus: Intent? =
      runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      }.getOrNull()

    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

    val batteryPct =
      if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100
    val isCharging =
      status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val isLowBattery = !isCharging && batteryPct <= LOW_BATTERY_PERCENT

    val thermalLevel = queryThermalLevel()

    return GuardrailState(
      availableStorageBytes = availableBytes,
      isLowStorage = isLowStorage,
      batteryPercent = batteryPct,
      isCharging = isCharging,
      isLowBattery = isLowBattery,
      thermalLevel = thermalLevel,
    )
  }

  fun queryAvailableStorageBytes(): Long {
    return runCatching {
      val stat = StatFs(context.cacheDir.absolutePath)
      stat.availableBytes
    }.getOrDefault(0L)
  }

  private fun queryThermalLevel(): ThermalLevel {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalLevel.NORMAL
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return ThermalLevel.NORMAL
    return when (pm.currentThermalStatus) {
      PowerManager.THERMAL_STATUS_SEVERE,
      PowerManager.THERMAL_STATUS_CRITICAL,
      PowerManager.THERMAL_STATUS_EMERGENCY,
      PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SEVERE
      PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.ELEVATED
      else -> ThermalLevel.NORMAL
    }
  }
}

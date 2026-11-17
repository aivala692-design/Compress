package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.CompressionPreset
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import com.example.model.VideoCompressionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "compressx_prefs")

class PreferencesRepository(private val context: Context) {

  private object Keys {
    val PRESET = stringPreferencesKey("preset")
    val RESOLUTION = stringPreferencesKey("resolution")
    val CODEC = stringPreferencesKey("codec")
    val CUSTOM_BITRATE_KBPS = intPreferencesKey("custom_bitrate_kbps")
    val OUTPUT_TREE_URI = stringPreferencesKey("output_tree_uri")
    val OUTPUT_FOLDER_NAME = stringPreferencesKey("output_folder_name")
    val THERMAL_PROTECTION = booleanPreferencesKey("thermal_protection")
    val PAUSE_LOW_BATTERY = booleanPreferencesKey("pause_low_battery")
    val AUTO_RESUME_RESTART = booleanPreferencesKey("auto_resume_restart")
    val PREFER_HARDWARE = booleanPreferencesKey("prefer_hardware")
  }

  val configFlow: Flow<VideoCompressionConfig> =
    context.dataStore.data.map { prefs ->
      VideoCompressionConfig(
        preset =
          prefs[Keys.PRESET]?.let { runCatching { CompressionPreset.valueOf(it) }.getOrNull() }
            ?: CompressionPreset.BALANCED,
        resolution =
          prefs[Keys.RESOLUTION]?.let { runCatching { TargetResolution.valueOf(it) }.getOrNull() }
            ?: TargetResolution.FHD_1080P,
        codec =
          prefs[Keys.CODEC]?.let { runCatching { VideoCodec.valueOf(it) }.getOrNull() }
            ?: VideoCodec.H264,
        customBitrateKbps = prefs[Keys.CUSTOM_BITRATE_KBPS] ?: 3500,
        outputTreeUri = prefs[Keys.OUTPUT_TREE_URI],
        outputFolderName = prefs[Keys.OUTPUT_FOLDER_NAME] ?: "Movies/CompressX Studio",
        thermalProtectionEnabled = prefs[Keys.THERMAL_PROTECTION] ?: true,
        pauseOnLowBattery = prefs[Keys.PAUSE_LOW_BATTERY] ?: true,
        autoResumeAfterRestart = prefs[Keys.AUTO_RESUME_RESTART] ?: true,
        preferHardwareAcceleration = prefs[Keys.PREFER_HARDWARE] ?: true,
      )
    }

  suspend fun updatePreset(preset: CompressionPreset) {
    context.dataStore.edit { prefs ->
      prefs[Keys.PRESET] = preset.name
      if (preset != CompressionPreset.CUSTOM) {
        prefs[Keys.RESOLUTION] = preset.defaultShortSide.name
      }
    }
  }

  suspend fun updateResolution(resolution: TargetResolution) {
    context.dataStore.edit { prefs -> prefs[Keys.RESOLUTION] = resolution.name }
  }

  suspend fun updateCodec(codec: VideoCodec) {
    context.dataStore.edit { prefs -> prefs[Keys.CODEC] = codec.name }
  }

  suspend fun updateCustomBitrateKbps(kbps: Int) {
    context.dataStore.edit { prefs ->
      prefs[Keys.CUSTOM_BITRATE_KBPS] = kbps.coerceIn(250, 50_000)
    }
  }

  suspend fun updateOutputFolder(treeUri: String?, folderName: String) {
    context.dataStore.edit { prefs ->
      if (treeUri == null) {
        prefs.remove(Keys.OUTPUT_TREE_URI)
      } else {
        prefs[Keys.OUTPUT_TREE_URI] = treeUri
      }
      prefs[Keys.OUTPUT_FOLDER_NAME] = folderName
    }
  }

  suspend fun updateThermalProtection(enabled: Boolean) {
    context.dataStore.edit { prefs -> prefs[Keys.THERMAL_PROTECTION] = enabled }
  }

  suspend fun updatePauseOnLowBattery(enabled: Boolean) {
    context.dataStore.edit { prefs -> prefs[Keys.PAUSE_LOW_BATTERY] = enabled }
  }

  suspend fun updateAutoResumeAfterRestart(enabled: Boolean) {
    context.dataStore.edit { prefs -> prefs[Keys.AUTO_RESUME_RESTART] = enabled }
  }

  suspend fun updatePreferHardware(enabled: Boolean) {
    context.dataStore.edit { prefs -> prefs[Keys.PREFER_HARDWARE] = enabled }
  }
}

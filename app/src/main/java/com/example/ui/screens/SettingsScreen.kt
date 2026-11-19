package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.CompressionPreset
import com.example.model.OutputMode
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import com.example.ui.StudioUiState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BlueSecondary
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  uiState: StudioUiState,
  onSelectPreset: (CompressionPreset) -> Unit,
  onSelectResolution: (TargetResolution) -> Unit,
  onSelectCodec: (VideoCodec) -> Unit,
  onChangeBitrateKbps: (Int) -> Unit,
  onSelectOutputMode: (OutputMode) -> Unit,
  onPickOutputFolder: () -> Unit,
  onResetOutputFolder: () -> Unit,
  onTogglePreferHardware: (Boolean) -> Unit,
  onToggleThermalProtection: (Boolean) -> Unit,
  onTogglePauseLowBattery: (Boolean) -> Unit,
  onToggleAutoResume: (Boolean) -> Unit,
  isBatteryUnrestricted: Boolean = false,
  onOpenBatterySettings: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val config = uiState.config
  val capabilities = uiState.capabilities
  var bitrateText by remember(config.customBitrateKbps) {
    mutableStateOf(config.customBitrateKbps.toString())
  }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        Column {
          Text(
            text = "Studio Encoding Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "100% offline • Zero tracking • Device MediaCodec adaptive",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
          )
        }
      }

      // Preset Selection
      item {
        SettingsCard(title = "Default Compression Preset") {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CompressionPreset.entries.forEach { preset ->
              val selected = config.preset == preset
              FilterChip(
                selected = selected,
                onClick = { onSelectPreset(preset) },
                label = { Text(preset.label) },
                colors =
                  FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                    selectedLabelColor = CyanPrimary,
                    containerColor = StudioSurfaceHighlight,
                  ),
                modifier = Modifier.testTag("settings_preset_${preset.name}"),
              )
            }
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = config.preset.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
          )
        }
      }

      // Resolution Options
      item {
        SettingsCard(title = "Target Resolution (Aspect Ratio Preserved)") {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TargetResolution.entries.forEach { res ->
              val selected = config.resolution == res
              FilterChip(
                selected = selected,
                onClick = { onSelectResolution(res) },
                label = { Text(res.label) },
                colors =
                  FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                    selectedLabelColor = CyanPrimary,
                    containerColor = StudioSurfaceHighlight,
                  ),
                modifier = Modifier.testTag("settings_resolution_${res.name}"),
              )
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Portrait, landscape, and square videos automatically preserve aspect ratio without upscaling.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
          )
        }
      }

      // Custom Bitrate in kbps
      item {
        SettingsCard(title = "Custom Video Bitrate (kbps)") {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Slider(
              value = config.customBitrateKbps.toFloat().coerceIn(300f, 25000f),
              onValueChange = { raw ->
                val rounded = ((raw / 100f).roundToInt() * 100).coerceIn(300, 25000)
                bitrateText = rounded.toString()
                onChangeBitrateKbps(rounded)
              },
              valueRange = 300f..25000f,
              colors =
                SliderDefaults.colors(
                  thumbColor = CyanPrimary,
                  activeTrackColor = CyanPrimary,
                ),
              modifier = Modifier.weight(1f).testTag("bitrate_slider"),
            )

            OutlinedTextField(
              value = bitrateText,
              onValueChange = { input ->
                val digits = input.filter { it.isDigit() }.take(5)
                bitrateText = digits
                val parsed = digits.toIntOrNull()
                if (parsed != null && parsed in 250..50000) {
                  onChangeBitrateKbps(parsed)
                }
              },
              label = { Text("kbps") },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.width(108.dp).testTag("bitrate_input"),
            )
          }
          Text(
            text =
              if (config.preset == CompressionPreset.CUSTOM) {
                "Active target: ${config.customBitrateKbps} kbps video + 128 kbps AAC audio"
              } else {
                "Adjusting slider or resolution automatically switches preset to Custom"
              },
            style = MaterialTheme.typography.labelSmall,
            color = CyanPrimary,
          )
        }
      }

      // Codec Selection based on Device Support
      item {
        SettingsCard(title = "Output Video Codec (Device Verified)") {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoCodec.entries.forEach { codec ->
              val supported = capabilities?.supportsCodec(codec) ?: (codec == VideoCodec.H264)
              val hasHw =
                when (codec) {
                  VideoCodec.H264 -> capabilities?.h264HardwareSupported == true
                  VideoCodec.H265 -> capabilities?.h265HardwareSupported == true
                }
              val selected = config.codec == codec
              Surface(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = supported) { onSelectCodec(codec) }
                    .testTag("codec_option_${codec.name}"),
                color = if (selected) Color(0xFF0A333D) else StudioSurfaceHighlight,
                shape = RoundedCornerShape(12.dp),
                border =
                  androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) CyanPrimary else StudioBorder,
                  ),
              ) {
                Row(
                  modifier = Modifier.padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = codec.label,
                      style = MaterialTheme.typography.titleMedium,
                      color = if (supported) MaterialTheme.colorScheme.onSurface else TextMuted,
                    )
                    Text(
                      text =
                        when {
                          !supported -> "Unavailable on this device"
                          hasHw -> "Hardware-accelerated + software fallback"
                          else -> "Software MediaCodec fallback only"
                        },
                      style = MaterialTheme.typography.bodyMedium,
                      color = if (hasHw) EmeraldSuccess else AmberWarning,
                    )
                  }
                  Icon(
                    imageVector = if (hasHw) Icons.Default.Bolt else Icons.Default.Memory,
                    contentDescription = null,
                    tint = if (selected) CyanPrimary else TextSecondary,
                  )
                }
              }
            }
          }
        }
      }

      // Output Storage Mode (Replace Original vs Keep Original)
      item {
        SettingsCard(title = "Output File Mode") {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputMode.entries.forEach { mode ->
              val selected =
                when (mode) {
                  OutputMode.REPLACE_ORIGINAL -> config.isReplaceOriginalActive
                  OutputMode.KEEP_ORIGINAL -> !config.isReplaceOriginalActive
                }
              val needsConsent =
                mode == OutputMode.REPLACE_ORIGINAL && !config.replaceOriginalConsentGranted
              Surface(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectOutputMode(mode) }
                    .testTag("settings_output_mode_${mode.name}"),
                color = if (selected) Color(0xFF0A333D) else StudioSurfaceHighlight,
                shape = RoundedCornerShape(12.dp),
                border =
                  androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) CyanPrimary else StudioBorder,
                  ),
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text = mode.label,
                      style = MaterialTheme.typography.titleMedium,
                      color = if (selected) CyanPrimary else MaterialTheme.colorScheme.onSurface,
                      modifier = Modifier.weight(1f),
                    )
                    Text(
                      text =
                        when {
                          selected -> "ACTIVE"
                          needsConsent -> "ENABLE"
                          else -> "SELECT"
                        },
                      style = MaterialTheme.typography.labelSmall,
                      color = if (selected) EmeraldSuccess else CyanPrimary,
                    )
                  }
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = mode.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                  )
                }
              }
            }
          }
        }
      }

      // Output Folder Selection
      item {
        SettingsCard(title = "Output Directory (Keep Original / Copy Fallback)") {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.FolderOpen,
              contentDescription = null,
              tint = BlueSecondary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = config.outputFolderName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text =
                  if (config.isReplaceOriginalActive) {
                    "Used when you tap Save Copy instead of replacing original"
                  } else {
                    "Originals are preserved untouched (_compressed.mp4)"
                  },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
              )
            }
            if (config.outputTreeUri != null) {
              IconButton(
                onClick = onResetOutputFolder,
                modifier = Modifier.testTag("reset_output_folder_button"),
              ) {
                Icon(
                  imageVector = Icons.Default.RestartAlt,
                  contentDescription = "Reset to default folder",
                  tint = TextSecondary,
                )
              }
            }
            Text(
              text = "Choose",
              style = MaterialTheme.typography.labelLarge,
              color = CyanPrimary,
              modifier =
                Modifier
                  .clip(RoundedCornerShape(8.dp))
                  .clickable(onClick = onPickOutputFolder)
                  .padding(horizontal = 12.dp, vertical = 12.dp)
                  .testTag("settings_pick_folder_button"),
            )
          }
        }
      }

      // Thermal, Battery & Recovery Guardrails
      item {
        SettingsCard(title = "Safety, Thermal & Process Recovery") {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleRow(
              title = "Hardware Acceleration Priority",
              subtitle = "Use device HW MediaCodec with automatic SW fallback",
              checked = config.preferHardwareAcceleration,
              onCheckedChange = onTogglePreferHardware,
              testTag = "toggle_prefer_hw",
            )
            ToggleRow(
              title = "Thermal Overheat Guardrail",
              subtitle = "Sequential 1-at-a-time queue + thermal cooldown pause",
              checked = config.thermalProtectionEnabled,
              onCheckedChange = onToggleThermalProtection,
              testTag = "toggle_thermal",
            )
            ToggleRow(
              title = "Pause on Low Battery (≤10%)",
              subtitle = "Protect device battery during long offline batches",
              checked = config.pauseOnLowBattery,
              onCheckedChange = onTogglePauseLowBattery,
              testTag = "toggle_low_battery",
            )
            ToggleRow(
              title = "Auto-Resume After Restart",
              subtitle = "Recover pending queue automatically after process death",
              checked = config.autoResumeAfterRestart,
              onCheckedChange = onToggleAutoResume,
              testTag = "toggle_auto_resume",
            )
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .clickable(onClick = onOpenBatterySettings)
                  .padding(vertical = 6.dp)
                  .testTag("battery_optimization_settings_row"),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                  text = "Background Battery Execution",
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text =
                    if (isBatteryUnrestricted) {
                      "Unrestricted — screen-off batches protected from OEM sleep"
                    } else {
                      "Recommended: set CompressX Studio to Unrestricted in Android battery settings"
                    },
                  style = MaterialTheme.typography.bodyMedium,
                  color = if (isBatteryUnrestricted) EmeraldSuccess else AmberWarning,
                )
              }
              Text(
                text = if (isBatteryUnrestricted) "Active" else "Configure",
                style = MaterialTheme.typography.labelLarge,
                color = if (isBatteryUnrestricted) EmeraldSuccess else CyanPrimary,
              )
            }
          }
        }
      }

      // Detected Runtime MediaCodec Matrix
      if (capabilities != null) {
        item {
          SettingsCard(title = "Detected Device Video Encoders (${capabilities.encoders.size})") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              capabilities.encoders.take(10).forEach { enc ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = enc.codecName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                  )
                  Text(
                    text =
                      "${enc.mimeType.removePrefix("video/").uppercase()} • " +
                        if (enc.isHardwareAccelerated) "HW" else "SW",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enc.isHardwareAccelerated) EmeraldSuccess else AmberWarning,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SettingsCard(
  title: String,
  content: @Composable () -> Unit,
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
    modifier =
      Modifier
        .fillMaxWidth()
        .border(1.dp, StudioBorder, RoundedCornerShape(18.dp)),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(10.dp))
      content()
    }
  }
}

@Composable
private fun ToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors =
        SwitchDefaults.colors(
          checkedThumbColor = Color(0xFF02171B),
          checkedTrackColor = CyanPrimary,
        ),
      modifier = Modifier.testTag(testTag),
    )
  }
}

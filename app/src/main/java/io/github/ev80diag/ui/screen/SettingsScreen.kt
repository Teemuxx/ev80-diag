package io.github.ev80diag.ui.screen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import io.github.ev80diag.R
import io.github.ev80diag.settings.AppSettings
import io.github.ev80diag.settings.FullCanLogging
import io.github.ev80diag.settings.LanguageChoice
import io.github.ev80diag.settings.NumberSpec
import io.github.ev80diag.settings.RecordingMode
import io.github.ev80diag.settings.ThemeChoice

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    var language by remember { mutableStateOf(settings.language) }
    var theme by remember { mutableStateOf(settings.theme) }
    var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
    var openCharging by remember { mutableStateOf(settings.openChargingOnStart) }
    var mode by remember { mutableStateOf(settings.recordingMode) }
    var fullCan by remember { mutableStateOf(settings.fullCanLogging) }
    var retention by remember { mutableIntStateOf(settings.driveRawRetentionDays) }
    var spread by remember { mutableIntStateOf(settings.spreadWarnMv) }
    var lowCell by remember { mutableIntStateOf(settings.lowCellMv) }
    var insulation by remember { mutableIntStateOf(settings.insulationLowKOhm) }
    var cold by remember { mutableIntStateOf(settings.coldBatteryC) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Section(R.string.section_general)
        Choice(R.string.setting_language, language,
            listOf(LanguageChoice.SYSTEM to R.string.lang_system, LanguageChoice.EN to R.string.lang_en, LanguageChoice.DE to R.string.lang_de)) {
            language = it; settings.language = it
            AppCompatDelegate.setApplicationLocales(when (it) {
                LanguageChoice.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
                LanguageChoice.EN -> LocaleListCompat.forLanguageTags("en")
                LanguageChoice.DE -> LocaleListCompat.forLanguageTags("de")
            })
        }
        Choice(R.string.setting_theme, theme,
            listOf(ThemeChoice.DARK to R.string.theme_dark, ThemeChoice.LIGHT to R.string.theme_light, ThemeChoice.SYSTEM to R.string.theme_system)) {
            theme = it; settings.theme = it; (ctx as? Activity)?.recreate()
        }
        SwitchRow(R.string.setting_keep_screen_on, keepOn) { keepOn = it; settings.keepScreenOn = it }
        SwitchRow(R.string.setting_open_charging, openCharging) { openCharging = it; settings.openChargingOnStart = it }

        Section(R.string.section_recording)
        Choice(R.string.setting_recording_mode, mode,
            listOf(RecordingMode.AUTOMATIC to R.string.mode_automatic, RecordingMode.WHILE_OPEN to R.string.mode_while_open)) {
            mode = it; settings.recordingMode = it
        }
        if (mode == RecordingMode.AUTOMATIC) {
            Text(stringResource(R.string.mode_automatic_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }) { Text(stringResource(R.string.setting_allow_background)) }
            }
        }
        Choice(R.string.setting_full_can, fullCan,
            listOf(FullCanLogging.CHARGING_ONLY to R.string.full_charging_only, FullCanLogging.ALWAYS to R.string.full_always)) {
            fullCan = it; settings.fullCanLogging = it
        }
        Text(stringResource(R.string.full_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumberRow(R.string.setting_retention, retention, AppSettings.DRIVE_RETENTION_DAYS) { retention = it; settings.driveRawRetentionDays = it }

        Section(R.string.section_warnings)
        NumberRow(R.string.setting_spread, spread, AppSettings.SPREAD_WARN_MV) { spread = it; settings.spreadWarnMv = it }
        NumberRow(R.string.setting_low_cell, lowCell, AppSettings.LOW_CELL_MV) { lowCell = it; settings.lowCellMv = it }
        NumberRow(R.string.setting_insulation, insulation, AppSettings.INSULATION_LOW_KOHM) { insulation = it; settings.insulationLowKOhm = it }
        NumberRow(R.string.setting_cold, cold, AppSettings.COLD_BATTERY_C) { cold = it; settings.coldBatteryC = it }
    }
}

@Composable
private fun Section(titleRes: Int) =
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

@Composable
private fun SwitchRow(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(labelRes), Modifier.weight(1f))
        Switch(checked, onChange)
    }

@Composable
private fun <T> Choice(labelRes: Int, selected: T, options: List<Pair<T, Int>>, onSelect: (T) -> Unit) {
    Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
    Column(Modifier.selectableGroup()) {
        options.forEach { (value, textRes) ->
            Row(Modifier.fillMaxWidth().selectable(selected == value, role = Role.RadioButton) { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == value, onClick = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(textRes))
            }
        }
    }
}

/**
 * A number typed in directly. A valid value is saved as it is typed; an invalid one is marked with the allowed
 * range and dropped when the field is left. "Default" restores the spec value.
 */
@Composable
private fun NumberRow(labelRes: Int, value: Int, spec: NumberSpec, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = spec.parse(text) != null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(labelRes), Modifier.weight(1f))
        OutlinedTextField(text, { t ->
                text = t.filter { it.isDigit() || it == '-' }.take(5)
                spec.parse(text)?.let { if (it != value) onChange(it) }
            },
            Modifier.width(96.dp).onFocusChanged { if (!it.isFocused && !valid) text = value.toString() },
            singleLine = true, isError = !valid,
            supportingText = if (valid) null else { { Text("${spec.range.first}…${spec.range.last}") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
        TextButton(onClick = { onChange(spec.default) }, enabled = value != spec.default) { Text(stringResource(R.string.btn_default)) }
    }
}

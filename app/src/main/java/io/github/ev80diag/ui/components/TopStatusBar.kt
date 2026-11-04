package io.github.ev80diag.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ev80diag.BuildConfig
import io.github.ev80diag.R
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.pipeline.RecorderState
import io.github.ev80diag.session.PowerState
import io.github.ev80diag.ui.theme.Blue
import io.github.ev80diag.ui.theme.StatusCritical
import io.github.ev80diag.ui.theme.StatusGood
import io.github.ev80diag.ui.theme.StatusWarning
import io.github.ev80diag.warnings.Level
import io.github.ev80diag.ui.theme.TextMutedDark

private data class Chip(val icon: ImageVector, val textRes: Int, val color: Color)

private fun chipFor(s: RecorderState): Chip = when {
    s.link == LinkStatus.REFUSED -> Chip(Icons.Filled.Error, R.string.link_refused, StatusCritical)
    s.link == LinkStatus.LOST -> Chip(Icons.Filled.Error, R.string.link_lost, StatusCritical)
    s.link == LinkStatus.CONNECTING -> Chip(Icons.Filled.PowerSettingsNew, R.string.link_connecting, TextMutedDark)
    s.link == LinkStatus.DISCONNECTED -> Chip(Icons.Filled.PowerSettingsNew, R.string.link_disconnected, TextMutedDark)
    else -> when (s.van.power) {
        PowerState.OFF -> Chip(Icons.Filled.PowerSettingsNew, R.string.van_state_off, TextMutedDark)
        PowerState.ON -> Chip(Icons.Filled.Power, R.string.van_state_on, Blue)
        PowerState.READY -> Chip(Icons.Filled.Speed, R.string.van_state_ready, StatusGood)
        PowerState.PLUGGED -> Chip(Icons.Filled.Power, R.string.van_state_plugged, Blue)
        PowerState.CHARGING_AC -> Chip(Icons.Filled.BatteryChargingFull, R.string.van_state_charging_ac, Blue)
        PowerState.CHARGING_DC -> Chip(Icons.Filled.BatteryChargingFull, R.string.van_state_charging_dc, Blue)
        PowerState.CHARGE_COMPLETE -> Chip(Icons.Filled.CheckCircle, R.string.van_state_complete, StatusGood)
        PowerState.STOPPED_BY_BMS -> Chip(Icons.Filled.Error, R.string.van_state_stopped, StatusCritical)
    }
}

/** Van name, adapter, state chip (always icon + text, never colour alone) and the app version. */
@Composable
fun TopStatusBar(state: RecorderState, vanName: String, modifier: Modifier = Modifier, gear: String? = null,
                 warning: Pair<Level, Int?> = Level.OK to null, onVanClick: () -> Unit = {}) {
    val base = chipFor(state)
    val (level, warnRes) = warning
    val chip = when {
        warnRes == null || level == Level.OK -> base
        level == Level.RED -> base.copy(icon = Icons.Filled.Error, color = StatusCritical)
        else -> base.copy(icon = Icons.Filled.Warning, color = StatusWarning)
    }
    val text = buildString {
        append(stringResource(chip.textRes))
        if (gear != null && state.van.power == PowerState.READY && state.link == LinkStatus.CONNECTED) append(" · ").append(gear)
        if (warnRes != null && level != Level.OK) append(" · ").append(stringResource(warnRes))
    }
    // The app draws edge to edge: keep clear of Android's status bar (and the camera cutout in landscape)
    Row(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(listOfNotNull(vanName, state.adapterName).joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clickable(onClick = onVanClick))
        Surface(shape = RoundedCornerShape(10.dp), color = Color.Transparent, border = BorderStroke(1.dp, chip.color)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(chip.icon, contentDescription = null, tint = chip.color, modifier = Modifier.padding(end = 4.dp))
                Text(text, fontSize = 11.sp, color = chip.color)
            }
        }
        Text("v" + BuildConfig.VERSION_NAME, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

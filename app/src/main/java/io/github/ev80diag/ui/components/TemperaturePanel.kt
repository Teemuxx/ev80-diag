package io.github.ev80diag.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ev80diag.R
import io.github.ev80diag.ui.screen.ChargingUi
import io.github.ev80diag.warnings.Level

private val ColdColor = Color(0xFF86B6EF)
private val WarmColor = Color(0xFF0D366B)

/** Battery temperature: average, min/max, all 18 sensors on a one-hue scale (light = colder), and the winter hint. */
@Composable
fun TemperaturePanel(ui: ChargingUi, modifier: Modifier = Modifier) {
    if (ui.tempAvg == null && ui.temps == null) return
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(stringResource(R.string.charge_battery_temp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        Text(ui.tempAvg?.let { "%.0f °C".format(it) } ?: "–", fontSize = 24.sp, style = MaterialTheme.typography.titleLarge)
                        if (ui.tempMin != null && ui.tempMax != null) Text(
                            "  " + stringResource(R.string.charge_temp_minmax, "%.0f".format(ui.tempMin), "%.0f".format(ui.tempMax)),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                StatusChip(ui.coldLevel, okRes = R.string.charge_temp_ok, warnRes = R.string.charge_temp_cold, cold = true)
            }
            ui.temps?.takeIf { it.isNotEmpty() }?.let { temps ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    temps.forEach { Sensor(it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.charge_sensors, temps.size), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.charge_sensors_hint), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.coldLevel != Level.OK) Text(stringResource(R.string.charge_temp_cold_hint), fontSize = 12.sp)
        }
    }
}

@Composable
private fun RowScope.Sensor(celsius: Int) {
    val f = ((celsius + 5) / 35f).coerceIn(0f, 1f)           // -5 °C (light) .. 30 °C (dark)
    Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(2.dp)).background(lerp(ColdColor, WarmColor, f)))
}

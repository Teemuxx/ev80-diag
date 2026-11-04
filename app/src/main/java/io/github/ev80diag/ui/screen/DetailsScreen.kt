package io.github.ev80diag.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ev80diag.R
import io.github.ev80diag.pipeline.LinkStatus
import io.github.ev80diag.session.PowerState
import io.github.ev80diag.signals.SignalFormat
import io.github.ev80diag.signals.SignalGroup
import io.github.ev80diag.signals.SignalStatus
import io.github.ev80diag.ui.charts.TimeSeriesChart
import io.github.ev80diag.ui.theme.Blue

/** Details (spec §5.6): every verified and probable signal, grouped, with search, raw hex and a short history. */
@Composable
fun DetailsScreen(modifier: Modifier = Modifier, vm: DetailsViewModel = viewModel()) {
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var hex by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<DetailRow?>(null) }
    val stale = st.link != LinkStatus.CONNECTED || st.van.power == PowerState.OFF
    val groups = DetailRows.build(st, query, { ctx.getString(it.nameRes) })

    Column(modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val focus = LocalFocusManager.current
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(stringResource(R.string.details_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
            FilterChip(hex, { hex = !hex }, label = { Text(stringResource(R.string.details_hex)) })
        }
        if (stale) Text(stringResource(R.string.details_stale), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (group, rows) ->
                if (rows.isEmpty()) return@forEach
                item(key = group.name) {
                    Text(stringResource(groupRes(group)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(rows, key = { it.def.key }) { r ->
                    Column(Modifier.fillMaxWidth().clickable { open = r }.padding(vertical = 6.dp).alpha(if (stale) 0.5f else 1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(r.def.nameRes), Modifier.weight(1f))
                            Text(r.value?.let { SignalFormat.text(ctx, r.def.key, it) } ?: "–", fontSize = 16.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.def.sourceLabel(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(statusRes(r.def.status)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (hex && r.rawHex != null) Text(r.rawHex, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    open?.let { r ->
        val pts = vm.history.series(r.def.key)
        AlertDialog(onDismissRequest = { open = null },
            title = { Text(stringResource(r.def.nameRes)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.def.explanationRes?.let { Text(stringResource(it)) }
                    Text("${r.def.sourceLabel()} · ${stringResource(statusRes(r.def.status))}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (pts.size >= 2) {
                        val lo = pts.minOf { it.second }; val hi = pts.maxOf { it.second }
                        val pad = maxOf((hi - lo) * 0.1, 0.5)
                        TimeSeriesChart(stringResource(R.string.details_last_10min, r.def.unit), pts, pts.first().first, pts.last().first,
                            lo - pad, hi + pad, listOf(lo to "%.1f".format(lo), hi to "%.1f".format(hi)), Blue, height = 140.dp)
                    } else Text(stringResource(R.string.details_no_history), fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton({ open = null }) { Text(stringResource(android.R.string.ok)) } })
    }
}

private fun groupRes(g: SignalGroup) = when (g) {
    SignalGroup.BATTERY -> R.string.tile_group_battery; SignalGroup.CHARGING -> R.string.tile_group_charging
    SignalGroup.DRIVING -> R.string.tile_group_driving; SignalGroup.VEHICLE -> R.string.details_group_vehicle
}

private fun statusRes(s: SignalStatus) = when (s) {
    SignalStatus.VERIFIED -> R.string.details_verified; SignalStatus.PROBABLE -> R.string.details_probable; SignalStatus.UNKNOWN -> R.string.details_unknown
}

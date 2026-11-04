package io.github.ev80diag.ui.screen

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ev80diag.R
import io.github.ev80diag.ui.components.ValueTile
import io.github.ev80diag.ui.tiles.TileGroup
import io.github.ev80diag.ui.tiles.TileKind
import io.github.ev80diag.ui.tiles.TileSpec
import io.github.ev80diag.ui.tiles.TileValues

/** Tiles (spec §5.3): 8 user-chosen values, 4 × 2 in portrait and 2 × 4 in landscape. Long-press to change one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TilesScreen(modifier: Modifier = Modifier, vm: TilesViewModel = viewModel()) {
    val ctx by vm.context.collectAsState()
    val layout by vm.layout.collectAsState()
    val c = ctx ?: return
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val perRow = if (landscape) 4 else 2
    var picking by remember { mutableStateOf<Int?>(null) }
    var periodFor by remember { mutableStateOf<Int?>(null) }

    Column(modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StaleLine(c.live)
        layout.withIndex().chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (slot, spec) ->
                    val t = TileValues.of(spec, slot, c)
                    ValueTile(stringResource(spec.kind.labelRes), t.value,
                        t.sub?.let { s -> if (s.arg != null) stringResource(s.res, s.arg) else stringResource(s.res) },
                        Modifier.weight(1f).fillMaxHeight(), dimmed = c.live.stale, valueSize = 30.sp,
                        onLongClick = { picking = slot })
                }
            }
        }
        Text(stringResource(R.string.tiles_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    picking?.let { slot ->
        ModalBottomSheet(onDismissRequest = { picking = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.tiles_pick_title), style = MaterialTheme.typography.titleMedium)
                TileGroup.entries.forEach { g ->
                    Text(stringResource(g.labelRes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    TileKind.entries.filter { it.group == g }.forEach { k ->
                        Row(Modifier.fillMaxWidth().clickable {
                            picking = null
                            if (k == TileKind.CONS_PERIOD) periodFor = slot else vm.set(slot, TileSpec(k))
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(k.labelRes), Modifier.weight(1f))
                            if (layout[slot].kind == k) Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    }
                }
            }
        }
    }

    periodFor?.let { slot ->
        val state = rememberDateRangePickerState()
        DatePickerDialog(onDismissRequest = { periodFor = null },
            confirmButton = {
                TextButton(enabled = state.selectedStartDateMillis != null, onClick = {
                    val from = state.selectedStartDateMillis!!
                    // the end day counts in full
                    val to = (state.selectedEndDateMillis ?: from) + 24 * 3_600_000L
                    vm.set(slot, TileSpec(TileKind.CONS_PERIOD, from, to)); periodFor = null
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton({ periodFor = null }) { Text(stringResource(android.R.string.cancel)) } }) {
            DateRangePicker(state, Modifier.weight(1f))
        }
    }
}

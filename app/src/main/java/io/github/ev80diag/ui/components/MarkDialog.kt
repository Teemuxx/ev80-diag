package io.github.ev80diag.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ev80diag.R
import io.github.ev80diag.data.store.Ev80Database
import io.github.ev80diag.profile.ActiveProfile
import io.github.ev80diag.service.Recorder
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** "Mark a moment": a note saved with the exact time into the open session (spec §5.9), plus the van's last marks. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun MarkDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val recent by remember {
        ActiveProfile.flow(ctx).flatMapLatest { p -> if (p == null) flowOf(emptyList()) else Ev80Database.get(ctx).marks().recent(p.id, 5) }
    }.collectAsState(emptyList())
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mark_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, placeholder = { Text(stringResource(R.string.mark_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (recent.isNotEmpty()) {
                    Text(stringResource(R.string.mark_recent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    recent.forEach { m -> Text("${time.format(Date(m.tsMs))}  ${m.text}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton({ Recorder.mark(ctx, text.trim()); onDismiss() }, enabled = text.isNotBlank()) { Text(stringResource(R.string.btn_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

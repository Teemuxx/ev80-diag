package io.github.ev80diag.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ev80diag.R
import io.github.ev80diag.data.store.ProfileEntity
import io.github.ev80diag.pipeline.ProfileEvent
import io.github.ev80diag.profile.Evidence
import io.github.ev80diag.service.Recorder

/** Van recognition in the UI: a note with Undo after an automatic switch, a question when it is unclear. */
@Composable
fun ProfileEventUi(event: ProfileEvent?, vans: List<ProfileEntity>, activeId: Long?, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    var handledSeq by rememberSaveable { mutableIntStateOf(0) }
    fun nameOf(id: Long) = vans.firstOrNull { it.id == id }?.name ?: "?"

    if (event is ProfileEvent.Switched) {
        val text = stringResource(R.string.van_switched, nameOf(event.to),
            stringResource(if (event.by == Evidence.ODOMETER) R.string.evidence_odometer else R.string.evidence_counter))
        val undo = stringResource(R.string.undo)
        LaunchedEffect(event.seq) {
            if (event.seq == handledSeq) return@LaunchedEffect
            handledSeq = event.seq
            val r = snackbar.showSnackbar(text, actionLabel = undo, withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) Recorder.chooseProfile(ctx, event.from)
        }
    }
    if (event is ProfileEvent.Ask && event.seq != handledSeq) {
        fun choose(id: Long) { handledSeq = event.seq; Recorder.chooseProfile(ctx, id) }
        AlertDialog(
            onDismissRequest = { activeId?.let(::choose) },     // keep the van recording now
            title = { Text(stringResource(R.string.van_ask_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.van_ask_text, activeId?.let(::nameOf) ?: "?"))
                    event.candidates.forEach { id -> OutlinedButton({ choose(id) }, Modifier.fillMaxWidth()) { Text(nameOf(id)) } }
                }
            },
            confirmButton = {},
        )
    }
}

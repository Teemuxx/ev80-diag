package io.github.ev80diag.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One labelled value (Live and Tiles). [dimmed]: the value is old (van asleep or not connected). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ValueTile(label: String, value: String, sub: String?, modifier: Modifier = Modifier, dimmed: Boolean = false,
              valueSize: TextUnit = 26.sp, onLongClick: (() -> Unit)? = null) {
    val click = if (onLongClick != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick) else Modifier
    Card(modifier.then(click), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).alpha(if (dimmed) 0.45f else 1f)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontSize = fittedValueSize(valueSize.value, value.length).sp, maxLines = 1, softWrap = false,
                color = MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Long values ("3.271 / 3.302") shrink so they fit one line of a half-width tile; never below 16 sp. */
internal fun fittedValueSize(base: Float, length: Int): Float =
    if (length <= FIT_CHARS) base else maxOf(16f, base * FIT_CHARS / length)

private const val FIT_CHARS = 9

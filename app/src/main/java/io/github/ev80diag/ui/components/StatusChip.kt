package io.github.ev80diag.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ev80diag.R
import io.github.ev80diag.ui.theme.StatusCritical
import io.github.ev80diag.ui.theme.StatusGood
import io.github.ev80diag.ui.theme.StatusWarning
import io.github.ev80diag.warnings.Level

/** Warning level shown as icon + text + colour, never colour alone. [warnRes]/[critRes] default to the generic words. */
@Composable
fun StatusChip(level: Level, okRes: Int = R.string.ins_good, warnRes: Int = R.string.ins_low, critRes: Int = warnRes, cold: Boolean = false) {
    val (icon, color, text) = when (level) {
        Level.OK -> Triple(Icons.Filled.CheckCircle, StatusGood, okRes)
        Level.AMBER -> Triple(if (cold) Icons.Filled.AcUnit else Icons.Filled.Warning, StatusWarning, warnRes)
        Level.RED -> Triple(Icons.Filled.Error, StatusCritical, critRes)
    }
    Surface(shape = RoundedCornerShape(10.dp), color = Color.Transparent, border = BorderStroke(1.dp, color)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(end = 4.dp))
            Text(stringResource(text), fontSize = 11.sp, color = color)
        }
    }
}

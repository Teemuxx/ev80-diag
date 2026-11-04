package io.github.ev80diag.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.ev80diag.R

/** Stands in for a screen that a later task of the stage-1 plan fills in. */
@Composable
fun PlaceholderScreen(title: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$title: ${stringResource(R.string.placeholder_screen)}")
    }
}

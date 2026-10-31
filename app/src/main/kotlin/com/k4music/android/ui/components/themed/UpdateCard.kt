package com.k4music.android.ui.components.themed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.k4music.android.utils.AppUpdate
import com.k4music.android.utils.medium
import com.k4music.android.utils.toast
import com.k4music.core.ui.LocalAppearance
import kotlinx.coroutines.launch

/** Home: shown only when a newer K4 Audio exists; checks quietly (at most every six hours) while Home is on screen. */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val release by AppUpdate.available.collectAsState()
    LaunchedEffect(Unit) { runCatching { AppUpdate.refresh() } }
    val found = release ?: return
    val (_, typography) = LocalAppearance.current
    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("K4 Audio ${found.version} is available (you have ${AppUpdate.currentVersion}).", style = typography.xs.medium)
        SecondaryTextButton(text = "Download update", onClick = { AppUpdate.open(context, found) })
    }
}

/** Settings: a button that checks now and says what it found. */
@Composable
fun CheckUpdateButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val release by AppUpdate.available.collectAsState()
    SecondaryTextButton(
        text = release?.let { "Update ${it.version} available: download" } ?: "Check for updates",
        modifier = modifier,
        onClick = {
            val known = release
            if (known != null) {
                AppUpdate.open(context, known)
            } else {
                scope.launch {
                    runCatching { AppUpdate.refresh(force = true) }
                        .onSuccess { found ->
                            if (found != null) AppUpdate.open(context, found)
                            else context.toast("You have the latest version (${AppUpdate.currentVersion})")
                        }
                        .onFailure { context.toast("Could not check for updates. Are you online?") }
                }
            }
        }
    )
}

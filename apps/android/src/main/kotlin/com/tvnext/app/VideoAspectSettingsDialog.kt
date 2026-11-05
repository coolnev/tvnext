package com.tvnext.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.player.AndroidVideoAspectSettings
import com.tvnext.app.core.player.VideoAspectMode

/** Overall Android video output DAR; individual channels can override this. */
@Composable
internal fun VideoAspectSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AndroidVideoAspectSettings.get(context)) }
    var error by remember { mutableStateOf(false) }
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(initialFocus) {
        withFrameNanos { }
        runCatching { initialFocus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = { Text("Формат видео") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Общая настройка для каналов и фильмов. Для отдельного канала можно выбрать другой формат в редакторе.")
                VideoAspectMode.entries.filter { it != VideoAspectMode.INHERIT }.forEachIndexed { index, aspect ->
                    OutlinedButton(
                        onClick = {
                            error = !AndroidVideoAspectSettings.set(context, aspect.token)
                            if (!error) selected = aspect
                        },
                        modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                    ) { Text((if (selected == aspect) "✓ " else "") + aspect.displayName) }
                }
                if (error) Text("Не удалось сохранить настройку")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

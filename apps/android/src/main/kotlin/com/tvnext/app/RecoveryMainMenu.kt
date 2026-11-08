package com.tvnext.app

import com.tvnext.app.core.ui.component.TvActionButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.tvnext.app.core.app.tab.AppTab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/** Accessible with MENU on the TV remote or «Главное меню» on Home. */
@Composable
internal fun RecoveryMainMenu(
    onNavigate: (RecoveryScreen) -> Unit,
    onSelectTab: (AppTab) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(initialFocus) {
        withFrameNanos { }
        runCatching { initialFocus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Главное меню TVNext") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Разделы TVNext")
                listOf(
                    AppTab.HOME to "Главная",
                    AppTab.CHANNELS to "Каналы",
                    AppTab.TV_GUIDE to "Телепрограмма",
                    AppTab.MEDIA_LIBRARY to "Медиатека",
                    AppTab.SEARCH to "Поиск",
                    AppTab.SETTINGS to "Настройки",
                ).forEachIndexed { index, (tab, title) ->
                    TvActionButton(
                        onClick = { onSelectTab(tab) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .then(if (index == 0) Modifier.focusRequester(initialFocus) else Modifier),
                    ) { Text(title) }
                }
                Text("Инструменты")
                TvActionButton(onClick = { onNavigate(RecoveryScreen.CHANNEL_EDITOR) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Редактор каналов") }
                TvActionButton(onClick = { onNavigate(RecoveryScreen.RECORDINGS) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Мои записи") }
                TvActionButton(onClick = { onNavigate(RecoveryScreen.PROFILES) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Профили") }
                TvActionButton(onClick = { onNavigate(RecoveryScreen.RECORDING_SETTINGS) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Запись и расписание") }
                TvActionButton(onClick = { onNavigate(RecoveryScreen.VIDEO_ASPECT) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Формат видео") }
                TvActionButton(onClick = { onNavigate(RecoveryScreen.BACKUP) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Резервная копия профилей") }
            }
        },
        confirmButton = { TvActionButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

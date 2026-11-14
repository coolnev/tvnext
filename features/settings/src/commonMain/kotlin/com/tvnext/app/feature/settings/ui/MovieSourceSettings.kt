package com.tvnext.app.feature.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.i18n.uiText

/** Both providers expose the same controls, sizing and credential status. */
@Composable
fun MovieSourceSettings(
    name: String,
    enabled: Boolean,
    keyConfigured: Boolean,
    testing: Boolean,
    compact: Boolean,
    onEnabled: (Boolean) -> Unit,
    onEditKey: () -> Unit,
    onTest: () -> Unit,
    onClearCache: () -> Unit,
    message: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        @Composable fun row(label: String, value: String = "", isToggle: Boolean = false, action: () -> Unit) {
            TvCardButton(onClick = action,
                modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 38.dp else 49.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(uiText(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    if (isToggle) Switch(checked = enabled, onCheckedChange = null, modifier = Modifier.height(28.dp))
                    else if (value.isNotBlank()) Text(value, textAlign = TextAlign.End,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        row("Использовать источник", isToggle = true) { onEnabled(!enabled) }
        row("API-ключ", uiText(if (keyConfigured) "Ключ задан" else "Ключ не задан"), action = onEditKey)
        row(if (testing) "Проверка соединения…" else "Проверить соединение") { if (!testing) onTest() }
        row("Очистить кэш", action = onClearCache)
        message?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

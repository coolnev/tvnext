package com.tvnext.app.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Android actions share a quiet outline and a compact corner rather than pill shapes. */
@Composable
fun TvActionButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val foreground = MaterialTheme.colorScheme.onSurface
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled,
        shape = RoundedCornerShape(4.dp), interactionSource = interaction,
        border = BorderStroke(if (focused) 1.5.dp else .5.dp,
            if (focused) MaterialTheme.colorScheme.primary else foreground.copy(alpha = if (enabled) .25f else .10f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent,
            contentColor = foreground, disabledContainerColor = Color.Transparent,
            disabledContentColor = foreground.copy(alpha = .38f)),
        contentPadding = contentPadding, content = content)
}

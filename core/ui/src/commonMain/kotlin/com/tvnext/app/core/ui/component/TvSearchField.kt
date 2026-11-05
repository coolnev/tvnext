package com.tvnext.app.core.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.tvnext.app.core.common.platform.isAndroidPlatform
import com.tvnext.app.core.ui.theme.TVNextDimens

@Composable
fun TvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Поиск",
    onClear: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isTv = isAndroidPlatform()
    val fieldFocusRequester = remember { FocusRequester() }

    // Android TV: D-pad focus moving onto (or through) this field must NOT pop
    // the on-screen keyboard by itself — that used to happen because a plain
    // TextField shows the IME the instant it gains focus, regardless of HOW it
    // was focused. We split "field is D-pad-focused" (just a highlight) from
    // "field is being edited" (keyboard visible, typing enabled): editing only
    // starts on an explicit OK/Enter press, and always ends — closing the
    // keyboard — as soon as focus leaves the field for ANY reason (D-pad
    // Up/Down/Back move, tab switch, …). Previously nothing closed the
    // keyboard on focus loss, so it could stay open over whatever the user
    // focused next.
    var isEditing by remember { mutableStateOf(!isTv) }

    TextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = isTv && !isEditing,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(fieldFocusRequester)
            .onFocusChanged { focusState ->
                onFocusChanged(focusState.isFocused)
                if (!focusState.isFocused) {
                    if (isTv) isEditing = false
                    keyboardController?.hide()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Escape -> {
                        if (value.isNotEmpty()) onClear()
                        isEditing = false
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        true
                    }
                    isTv && !isEditing &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                        // First OK/Enter on a D-pad-focused field enters edit
                        // mode and opens the keyboard (it does not type "OK").
                        isEditing = true
                        keyboardController?.show()
                        true
                    }
                    isTv && isEditing && event.key == Key.Back -> {
                        // Back while editing only closes the keyboard / exits
                        // edit mode; it does not leave the screen. A second
                        // Back (now not editing) is free to bubble up normally.
                        isEditing = false
                        keyboardController?.hide()
                        true
                    }
                    else -> false
                }
            },
        placeholder = {
            Text(
                text = placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        singleLine = true,
        shape = RoundedCornerShape(TVNextDimens.CardCornerRadius),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

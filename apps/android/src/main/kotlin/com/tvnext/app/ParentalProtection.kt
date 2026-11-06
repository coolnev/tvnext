package com.tvnext.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.tvnext.app.core.iptv.domain.ParentalChannelGate
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom

/** PIN is salted and hashed on-device. Unlock is memory-only and never persisted. */
internal class ParentalProtection(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("parental_access_v1", Context.MODE_PRIVATE)
    init { ParentalChannelGate.setEnabled(enabled) }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) {
            prefs.edit().putBoolean("enabled", value).apply()
            ParentalChannelGate.setEnabled(value)
            if (!value) lock()
        }
    val unlocked: Boolean get() = ParentalChannelGate.session.value.unlocked
    fun unlock() { ParentalChannelGate.unlock() }
    fun lock() { ParentalChannelGate.lock() }

    fun verify(pin: String): Boolean {
        if (pin.length !in 4..8 || !pin.all(Char::isDigit)) return false
        val salt = prefs.getString("salt", null)
        val expected = prefs.getString("hash", null)
        if (salt == null || expected == null) return pin == "1234"
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), expected.toByteArray())
    }
    fun changePin(old: String, replacement: String): Boolean {
        if (!verify(old) || replacement.length !in 4..8 || !replacement.all(Char::isDigit)) return false
        val random = ByteArray(16).also(SecureRandom()::nextBytes)
        val salt = random.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val updated = hash(salt, replacement)
        prefs.edit().putString("salt", salt).putString("hash", updated).apply()
        return true
    }
    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("TVNext:$salt:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Use the Android numeric password IME, including its Done action. */
@Composable
internal fun ParentalPinEntryDialog(
    title: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
    var incorrect by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(180)
        focus.requestFocus()
        keyboard?.show()
    }
    fun submit() {
        if (onSubmit(pin)) {
            keyboard?.hide()
        } else {
            incorrect = true
            pin = ""
            focus.requestFocus()
            keyboard?.show()
        }
    }
    AlertDialog(
        modifier = Modifier.widthIn(max = 340.dp),
        onDismissRequest = { keyboard?.hide(); onCancel() },
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Введите PIN-код (4–8 цифр)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { new -> pin = new.filter(Char::isDigit).take(8); incorrect = false },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pin.length >= 4) submit() }),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (incorrect) Text("Неверный PIN-код", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = pin.length >= 4) { Text("Открыть") } },
        dismissButton = { TextButton(onClick = { keyboard?.hide(); onCancel() }) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
internal fun ParentalPinSettingsDialog(
    protection: ParentalProtection,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    var old by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Изменить PIN-код") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = old, onValueChange = { old = it.filter(Char::isDigit).take(8) },
                    label = { Text("Текущий PIN") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = current, onValueChange = { current = it.filter(Char::isDigit).take(8) },
                    label = { Text("Новый PIN (4–8 цифр)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation())
                if (error) Text("Проверьте текущий PIN и новый код", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (protection.changePin(old, current)) { protection.lock(); onChanged(); onClose() }
                else error = true
            }, enabled = old.length >= 4 && current.length >= 4) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

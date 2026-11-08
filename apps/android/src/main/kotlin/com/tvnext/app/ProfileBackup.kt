package com.tvnext.app

import com.tvnext.app.core.ui.i18n.uiText

import com.tvnext.app.core.ui.component.TvActionButton
import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Local profile-only backup. Playlist URLs, PIN, API keys, saved media and
 * recording files are deliberately NOT part of this export.
 */
internal class ProfileBackup(private val context: Context) {
    private val store = LocalRecoveryStore(context)
    private val data = context.getSharedPreferences("tvnext_profile_personal_v1", Context.MODE_PRIVATE)

    fun exportJson(): String {
        val payload = JSONObject()
            .put("format", "tvnext-profile-backup")
            .put("version", 1)
            .put("selected", store.activeProfileId())
        val profiles = JSONArray()
        store.profiles().forEach { profiles.put(JSONObject().put("id", it.id).put("name", it.name)) }
        payload.put("profiles", profiles)
        val personal = JSONObject()
        for ((key, value) in data.all) {
            if (safeKey(key) && (
                (value is String && value.length <= 1_000_000) ||
                (key.endsWith(".hide_duplicates") && value is Boolean)
            )) personal.put(key, value)
        }
        payload.put("personal", personal)
        return payload.toString(2)
    }

    fun validate(text: String): ParsedBackup {
        require(text.length <= 5_000_000) { uiText("Файл резервной копии слишком большой") }
        val root = JSONObject(text)
        require(root.optString("format") == "tvnext-profile-backup" && root.optInt("version") == 1) {
            uiText("Неподдерживаемая резервная копия")
        }
        val items = root.getJSONArray("profiles")
        require(items.length() in 1..8) { uiText("Допустимо от 1 до 8 профилей") }
        val profiles = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            LocalProfile(item.getString("id"), item.getString("name"))
        }
        val ids = profiles.map { it.id }.toSet()
        require(ids.size == profiles.size && "main" in ids &&
            profiles.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it.name.isNotBlank() && it.name.length <= 40 }) {
            uiText("Неверные идентификаторы или названия профилей")
        }
        val selected = root.getString("selected")
        require(selected in ids) { uiText("Выбранный профиль отсутствует") }
        val personal = root.getJSONObject("personal")
        require(personal.length() <= 400) { uiText("Слишком много параметров") }
        val values = mutableMapOf<String, Any>()
        val names = personal.keys()
        while (names.hasNext()) {
            val name = names.next()
            require(safeKey(name) && name.split('.').getOrNull(1) in ids) {
                uiText("Неверный параметр профиля")
            }
            val value = personal.get(name)
            require((value is String && value.length <= 1_000_000) ||
                (name.endsWith(".hide_duplicates") && value is Boolean)) { "Неверный тип или размер параметра" }
            values[name] = value
        }
        return ParsedBackup(profiles, selected, values)
    }

    /** Validate fully before modifying any stored profile data. */
    fun restore(parsed: ParsedBackup): Boolean {
        val previous = exportJson()
        val old = validate(previous)
        if (!store.restoreProfiles(parsed.profiles, parsed.selected)) return false
        if (replacePersonal(parsed.personal)) return true
        // Roll back profile list in case SharedPreferences could not persist.
        store.restoreProfiles(old.profiles, old.selected)
        replacePersonal(old.personal)
        return false
    }

    private fun replacePersonal(values: Map<String, Any>): Boolean {
        val edit = data.edit()
        data.all.keys.filter(::safeKey).forEach(edit::remove)
        values.forEach { (name, value) ->
            when (value) {
                is String -> edit.putString(name, value)
                is Boolean -> edit.putBoolean(name, value)
            }
        }
        return edit.commit()
    }

    private fun safeKey(key: String): Boolean {
        if (!key.startsWith("profile.")) return false
        val fields = setOf("channel_favorites", "media_favorites", "channel_edits", "hide_duplicates",
            "channel_order", "history", "last_channel", "theme", "accent", "language",
            "channel_playback", "archive_positions", "vod_positions")
        return key.split('.').size == 3 && key.split('.')[1].matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
            key.split('.')[2] in fields
    }
}

internal data class ParsedBackup(
    val profiles: List<LocalProfile>,
    val selected: String,
    val personal: Map<String, Any>,
)

@Composable
internal fun ProfileBackupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember(context) { ProfileBackup(context) }
    var message by remember { mutableStateOf("") }
    var importCandidate by remember { mutableStateOf<ParsedBackup?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(backup.exportJson())
                    } ?: throw IOException(uiText("Не удалось открыть файл"))
                    uiText("Резервная копия сохранена")
                }.getOrElse { uiText("Ошибка сохранения: {0}", it.message ?: it.javaClass.simpleName) }
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        val text = it.readText()
                        backup.validate(text)
                    } ?: throw IOException(uiText("Не удалось прочитать файл"))
                }
            }
            parsed.onSuccess { importCandidate = it; message = "" }
                .onFailure { message = uiText("Ошибка проверки: {0}", it.message ?: it.javaClass.simpleName) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText("Резервная копия профилей")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max=480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(uiText("Сохраняются профили, избранное, история, оформление, редактор каналов ") +
                    uiText("и позиции просмотра. ") +
                    uiText("Адреса источников, API-ключи, PIN и сами видеозаписи не экспортируются."))
                TvActionButton(onClick = { export.launch("TVNext_profiles.json") },
                    modifier = Modifier.fillMaxWidth()) { Text(uiText("Сохранить резервную копию")) }
                TvActionButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.fillMaxWidth()) { Text(uiText("Восстановить из файла")) }
                if (message.isNotBlank()) Text(message)
            }
        },
        confirmButton = { TvActionButton(onClick = onDismiss) { Text(uiText("Закрыть")) } },
    )
    if (importCandidate != null) {
        AlertDialog(
            onDismissRequest = { importCandidate = null },
            title = { Text(uiText("Заменить профили?")) },
            text = { Text(uiText("Данные избранного, истории и редактора будут заменены содержимым копии. ") +
                uiText("Записи и расписания останутся на устройстве. Восстановление отклоняется, если ") +
                uiText("оно сделает чужие записи недоступными.")) },
            confirmButton = { TvActionButton(onClick = {
                val parsed = importCandidate
                importCandidate = null
                if (parsed != null) {
                    message = if (backup.restore(parsed)) {
                        (context as? Activity)?.recreate()
                        uiText("Профили восстановлены")
                    } else uiText("Не удалось восстановить профили (проверьте связанные записи)")
                }
            }) { Text(uiText("Восстановить")) } },
            dismissButton = { TvActionButton(onClick = { importCandidate = null }) { Text(uiText("Отмена")) } },
        )
    }
}

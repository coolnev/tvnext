package com.tvnext.app.feature.media.ui

import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.tvnext.app.core.ui.component.TvActionButton
import com.tvnext.app.core.ui.i18n.uiText

@Composable
internal fun MediaResumeDialog(component:MediaComponent,request:MediaResumeRequest?) {
    request ?: return
    val seconds=(request.positionMs/1000).coerceAtLeast(0)
    val time="${seconds/3600}:${(seconds/60%60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}"
    AlertDialog(onDismissRequest={component.dispatch(MediaIntent.ResumeCancelled)},
        title={Text(uiText("Продолжить просмотр?"))},
        text={Text(uiText("{0} · сохранено на {1}",request.title,time))},
        confirmButton={TvActionButton(onClick={component.dispatch(MediaIntent.ResumeChosen(true))}){Text(uiText("Продолжить"))}},
        dismissButton={TvActionButton(onClick={component.dispatch(MediaIntent.ResumeChosen(false))}){Text(uiText("С начала"))}})
}

package com.tvnext.app

import com.tvnext.app.core.common.UiLanguage
import com.tvnext.app.core.ui.i18n.UiLocale
import com.tvnext.app.core.ui.i18n.uiText

/** Additional mouse-control labels; shared/Android translations stay untouched. */
internal fun desktopText(source:String):String {
    val labels=when(source) {
        "Выберите раздел слева"->"Choose a section on the left" to "在左侧选择设置类别"
        "В эфир"->"Back to live" to "返回直播"
        "Перемотка"->"Seek" to "跳转进度"
        "Громкость"->"Volume" to "音量"
        else->return uiText(source)
    }
    return when(UiLocale.language){UiLanguage.ENGLISH->labels.first;UiLanguage.CHINESE->labels.second;else->source}
}

package com.tvnext.app

import com.tvnext.app.core.common.UiLanguage
import com.tvnext.app.core.ui.i18n.UiLocale
import com.tvnext.app.core.ui.i18n.uiText

/** Additional mouse-control labels; shared/Android translations stay untouched. */
internal fun desktopText(source:String):String {
    val labels=when(source) {
        "Выберите раздел слева"->"Choose a section on the left" to "在左侧选择设置类别"
        "Добавить в избранное"->"Add to favorites" to "添加到收藏"
        "Удалить из избранного"->"Remove from favorites" to "取消收藏"
        "Декодирование и вывод видео"->"Video decoding and output" to "视频解码与输出"
        "Аппаратный — GPU"->"Hardware — GPU" to "硬件解码与 GPU 输出"
        "Аппаратный — совместимый"->"Hardware — compatible" to "硬件解码兼容模式"
        "Совместимый режим сохраняет аппаратное декодирование и вывод через память."->"Compatible mode keeps hardware decoding with memory output." to "兼容模式使用硬件解码并通过内存输出。"
        "Совместимый вывод"->"Compatible output" to "兼容输出"
        "Программный вывод"->"Software output" to "软件输出"
        "В эфир"->"Back to live" to "返回直播"
        "Перемотка"->"Seek" to "跳转进度"
        "Громкость"->"Volume" to "音量"
        else->return uiText(source)
    }
    return when(UiLocale.language){UiLanguage.ENGLISH->labels.first;UiLanguage.CHINESE->labels.second;else->source}
}

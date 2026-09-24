package com.nox.offline.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Короткие сообщения для пользователя из фоновых частей приложения:
 * «перенос в папку не удался», «видео импортировано». Интерфейс показывает
 * их баннером; если экрана нет — сообщение просто теряется, а факт
 * остаётся в журнале диагностики.
 */
object AppEvents {
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices: SharedFlow<String> = _notices

    fun notice(text: String) {
        _notices.tryEmit(text)
    }
}

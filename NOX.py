# coding: utf-8
"""
NOX — офлайн-медиатека для iPhone (Pythonista 3).

Точка входа. Вся логика живёт в соседних модулях:

    nox_core.py       пути, state.json, медиатека, форматтеры
    nox_download.py   yt-dlp resolve, HTTP-загрузка, очередь, обложки
    nox_player.py     AVPlayer и позиция просмотра
    nox_ui.py         тема, стекло, экраны, NoxApp

Рядом лежат данные проекта:

    yt_dlp/           локальный пакет загрузчика
    NoxMedia/         медиатека
    NOX_Data/         state.json и диагностика

Все пути по-прежнему считаются от папки с этим файлом: путей контейнера
Pythonista в коде нет. Импортируйте NOX.py в Pythonista и нажмите Run.
"""

import os
import sys

# Модули NOX лежат рядом с этим файлом. Кладём их папку в sys.path, чтобы
# импорт работал независимо от того, откуда Pythonista запустил скрипт.
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import ui

import nox_debug
from nox_ui import NoxApp


def main():
    # Чёрный ящик поднимаем первым: если запуск сорвётся дальше, в
    # NOX_Data останется и номер сессии, и полная трассировка.
    nox_debug.start_session()
    nox_debug.install_exception_hooks()
    try:
        w, h = ui.get_screen_size()
    except Exception:
        w, h = 390.0, 844.0
    app = NoxApp(frame=(0, 0, w, h))
    try:
        app.present('fullscreen', hide_title_bar=True, animated=False,
                    orientations=['portrait'])
    except Exception:
        app.present('fullscreen', hide_title_bar=True)
    app.reload_all()
    app.start_autorefresh()


if __name__ == '__main__':
    main()

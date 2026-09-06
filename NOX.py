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

О показе окна. present() — не мгновенная операция: UIKit ведёт переход, и
пока он идёт, второй показ невозможен. Pythonista это выражает так:

    ValueError: View is already being presented or animation is in progress

Появляется это в самой обычной ситуации: нажали Run, окно ещё уезжает
после прошлого запуска, а новый скрипт уже просит своё. Повторить present
немедленно нельзя — переход за эти микросекунды не закончится, и вторая
попытка упадёт ровно так же. Ждать тоже нельзя: занят главный поток, а на
нём и живёт анимация, которую мы ждём. Поэтому здесь одна попытка на такт
и отложенная следующая через ui.delay, с ограниченным числом заходов.
"""

import os
import sys
import types

# Модули NOX лежат рядом с этим файлом. Кладём их папку в sys.path, чтобы
# импорт работал независимо от того, откуда Pythonista запустил скрипт.
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import ui

import nox_debug
from nox_ui import NoxApp


# Пауза между попытками показать окно. Меньше — не успеет закончиться
# переход, ради которого мы и ждём; больше — заметная задержка запуска.
PRESENT_RETRY_DELAY = 0.3

# Сколько всего отложенных попыток разрешено. Цепочка ui.delay обязана
# кончаться: незавершимый переход — это сбой, а не повод крутиться вечно.
PRESENT_MAX_RETRIES = 8

# Признаки ровно того исключения, которое мы умеем переждать. Всё
# остальное — настоящая ошибка, и она должна идти наверх как есть.
_PRESENT_BUSY_MARKERS = ('already being presented',
                         'animation is in progress')

# Имя служебного модуля, в котором запуск оставляет ссылку на своё окно.
# Pythonista держит sys.modules между запусками скрипта, поэтому следующий
# Run видит окно предыдущего и не устраивает второй показ поверх него.
_LAUNCH_STATE = '_nox_launcher_state'


def _launch_state():
    """
    Память запуска, живущая столько же, сколько процесс Pythonista.

    Обычные глобальные переменные этого файла для такого не годятся: при
    повторном Run NOX.py выполняется заново, и всё в нём начинается с
    чистого листа. Модуль в sys.modules переживает перезапуск скрипта, а
    вместе с ним и ссылка на окно прошлого запуска. Ничего приватного из
    UIKit здесь нет и не нужно.
    """
    mod = sys.modules.get(_LAUNCH_STATE)
    if mod is None or not hasattr(mod, 'app'):
        mod = types.ModuleType(_LAUNCH_STATE)
        mod.app = None
        sys.modules[_LAUNCH_STATE] = mod
    return mod


def _event(kind, **fields):
    """
    Запись в чёрный ящик. Своих исключений наружу не выпускает: запуск
    приложения не должен зависеть от того, удалось ли записать о нём
    строчку. Настоящие ошибки показа при этом не трогаются — они идут
    своим путём, через sys.excepthook.
    """
    try:
        nox_debug.event(kind, **fields)
    except Exception:
        pass


def _on_screen(view):
    """Показано ли окно прямо сейчас. Отсутствие свойства — не показано."""
    try:
        return bool(view.on_screen)
    except Exception:
        return False


def _presentation_busy(exc):
    """Та самая занятость перехода — и ничего другого."""
    text = str(exc).lower()
    return any(mark in text for mark in _PRESENT_BUSY_MARKERS)


def _release_previous(app):
    """
    Убрать окно прошлого запуска, если оно ещё висит.

    Повторный Run в Pythonista не выгружает процесс: прошлое окно может
    быть ещё на экране. Показывать поверх него второе — то самое, чего мы
    и добиваемся не делать. Закрытие проходит штатным путём NoxApp:
    очередь сохраняется, экрану возвращается право гаснуть.
    """
    state = _launch_state()
    prev = state.app
    state.app = app
    if prev is None or prev is app or not _on_screen(prev):
        return
    _event('ui-present-close-previous')
    try:
        prev.close()
    except Exception as e:
        # Закрыть не удалось — это диагностика, а не причина не запускать
        # NOX: показ ниже переживает и незакрытое окно, просто дольше.
        _event('ui-present-close-error', exc=repr(e))


def _start_once(app):
    """
    Наполнить и оживить окно ровно один раз.

    Показ может состояться не с первой попытки, и точку «окно на экране»
    проходит либо первая попытка, либо отложенная. Второй reload_all и
    второй autorefresh нам при этом не нужны ни разу.
    """
    if getattr(app, '_nox_launcher_started', False):
        return
    app._nox_launcher_started = True
    app.reload_all()
    app.start_autorefresh()


def present_app(app, attempt=0):
    """
    Показать окно. Ровно один present за вызов — и никогда второй подряд.

    Ветка занятости перехода не считается ошибкой: следующая попытка
    уезжает в ui.delay, чтобы главный поток успел довести анимацию. Любое
    другое исключение — настоящее, и оно уходит наверх нетронутым.
    """
    if _on_screen(app):
        # Окно уже показано: запуск состоялся, показывать нечего.
        _event('ui-present-already-visible', attempt=attempt)
        _start_once(app)
        return
    if attempt == 0:
        _event('ui-present-start')
    try:
        app.present('fullscreen', hide_title_bar=True, animated=False,
                    orientations=['portrait'])
    except ValueError as exc:
        if not _presentation_busy(exc):
            raise                       # чужая ошибка — не наша ветка
        if _on_screen(app):
            # Переход всё-таки завершился нашим окном.
            _event('ui-present-already-visible', attempt=attempt)
            _start_once(app)
            return
        if attempt >= PRESENT_MAX_RETRIES:
            # Попытки кончились. Наружу ValueError не выпускаем: launcher
            # обязан закончиться записью в журнал, а не падением.
            _event('ui-present-failed', attempt=attempt, exc=str(exc))
            return
        _event('ui-present-busy', attempt=attempt, exc=str(exc))
        _event('ui-present-retry', attempt=attempt + 1,
               delay=PRESENT_RETRY_DELAY)
        ui.delay(lambda: present_app(app, attempt + 1), PRESENT_RETRY_DELAY)
        return
    _event('ui-present-success', attempt=attempt)
    _start_once(app)


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
    _release_previous(app)
    present_app(app)


if __name__ == '__main__':
    main()

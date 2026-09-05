# coding: utf-8
"""
NOX - офлайн-медиатека для iPhone (Pythonista 3).

Один файл. Импортируйте в Pythonista и нажмите Run.

Архитектура (одна, рабочая):

    NOX (Pythonista)
        -> локальный пакет yt_dlp рядом с NOX.py
        -> yt_dlp.YoutubeDL в отдельном потоке
        -> NoxMedia
        -> NOX видит файлы напрямую

Всё происходит внутри NOX: ни a-Shell, ни Ярлыков, ни выхода из приложения.

Все пути считаются от самого NOX.py, никаких путей контейнера в коде нет:

    Файлы -> На iPhone -> NOX          <- PROJECT_DIR (папка с NOX.py)
        NOX.py
        NOX_icon.png                   <- необязательно
        yt_dlp/                        <- локальный пакет загрузчика
        NoxMedia/                      <- медиатека
        NOX_Data/state.json            <- настройки

Загрузка идёт, пока NOX открыт: усыпить Pythonista может сама iOS.
"""

import os
import io
import re
import sys
import json
import time
import shutil
import threading
import urllib.request
import urllib.error

import ui
import console

try:
    import clipboard
except ImportError:                                    # pragma: no cover
    clipboard = None


# =====================================================================
#  ТЕМА
# =====================================================================

APP_NAME = 'NOX'
APP_VERSION = '1.0'

BG          = '#04050b'
BG_DEEP     = '#020307'
NAV_BG      = '#070a13'
CARD        = '#0a0d17'
CARD_2      = '#0d111d'
CARD_3      = '#111524'
FIELD       = '#080b14'

BORDER      = '#182140'
BORDER_2    = '#22305a'
BORDER_HI   = '#3a44a0'

ACCENT      = '#7b7bf5'
ACCENT_2    = '#a5a2ff'
ACCENT_DEEP = '#5b57e0'
ACCENT_SOFT = '#8d8bff'

TXT         = '#ffffff'
TXT_2       = '#9aa2ba'
TXT_3       = '#697089'
TXT_4       = '#4b5268'
ERR_TXT     = '#ff6b81'

F_BOLD      = '<system-bold>'
F_REG       = '<system>'

PAD         = 20.0
NAV_H       = 60.0

# Как часто интерфейс перечитывает состояние загрузки. progress_hook
# дёргается очень часто, поэтому UI обновляется не чаще ~5 раз в секунду.
UI_REFRESH = 0.22
IDLE_REFRESH = 3.0

# Досмотрено, если до конца осталось меньше этого; продолжать предлагаем,
# только если посмотрено больше WATCH_MIN_START.
WATCH_DONE_TAIL = 20.0
WATCH_MIN_START = 10.0
HISTORY_LIMIT = 200
# Ключ сохранённой очереди в state.json. Именно v2: в старом ключе 'jobs'
# лежал формат прежней архитектуры, и читать его нельзя.
JOBS_KEY = 'download_jobs_v2'
# Сколько карточка держится с отметкой «Готово» перед уходом из очереди.
DONE_LINGER = 0.8

# Через сколько секунд в ST_PREPARING карточка начинает показывать
# диагностику этапа. Ничего не лечит — только показывает, где встали.
DIAG_AFTER = 2.0

# Контрольный переключатель A/B. False — рабочий путь ровно как в
# победившей версии: extract_info -> finished, и ничего больше.
# True — метаданные и обложка выполняются ПОСЛЕ finished, в отдельном
# потоке ExtrasManager, когда поток загрузки уже мёртв.
ENABLE_EXTRAS = True

SORT_OPTIONS = [
    ('new', 'Сначала новые'),
    ('old', 'Сначала старые'),
    ('title', 'По названию'),
    ('size', 'По размеру'),
    ('duration', 'По длительности'),
]
QUALITY_FILTERS = [
    ('all', 'Все'),
    ('360', '360p'),
    ('480', '480p'),
    ('720', '720p'),
    ('1080', '1080p+'),
    ('max', 'MAX/4K'),
]

VIDEO_EXT   = ('.mp4', '.mov', '.m4v', '.mkv', '.webm')
# В карточки отдаём только JPEG/PNG. WEBP может лежать рядом от прежних
# загрузок — он удаляется вместе с видео, но в UI не передаётся.
IMAGE_EXT   = ('.jpg', '.jpeg', '.png')
IMAGE_EXT_ALL = ('.jpg', '.jpeg', '.png', '.webp')
TEMP_EXT    = ('.part', '.ytdl')

QUALITIES = [
    ('360', '360p', 'низкое'),
    ('480', '480p', 'среднее'),
    ('720', '720p', 'высокое'),
    ('MAX', 'MAX',  'максимум'),
]

DIRECT_LADDER = ['url2160', 'url1440', 'url1080', 'url720',
                 'url480', 'url360', 'url240', 'url144']


# =====================================================================
#  МЕЛКИЕ УТИЛИТЫ
# =====================================================================

def parse_hex(h):
    """'#rrggbb' -> (r, g, b) в 0..1."""
    h = h.lstrip('#')
    if len(h) == 3:
        h = ''.join(c * 2 for c in h)
    try:
        r = int(h[0:2], 16) / 255.0
        g = int(h[2:4], 16) / 255.0
        b = int(h[4:6], 16) / 255.0
    except Exception:
        return (1.0, 1.0, 1.0)
    return (r, g, b)


def rgba(h, a):
    r, g, b = parse_hex(h)
    return (r, g, b, a)


def mix(h1, h2, t):
    a = parse_hex(h1)
    b = parse_hex(h2)
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


def redraw(view):
    try:
        view.set_needs_display()
    except Exception:
        pass


def _noop():
    """Пустой callable для completion-блока ui.animate."""
    pass


def animate(func, duration=0.2, delay=0.0, completion=None):
    """
    Безопасная обёртка над ui.animate.

    ПРИЧИНА БАГА 'NoneType' object is not callable:
    раньше сюда передавался completion=None, и это None уходило
    четвёртым позиционным аргументом в ui.animate(). UIKit вызывает
    completion-блок ПОСЛЕ окончания анимации, уже вне нашего try/except,
    и вызов None() падал отдельным traceback'ом на каждое нажатие.

    Теперь в ui.animate всегда уходит настоящий callable, а сам ui.animate
    вызывается только с той сигнатурой, которую поддерживает устройство.
    """
    if not callable(func):
        return
    cb = completion if callable(completion) else _noop
    variants = (
        lambda: ui.animate(func, duration, delay, cb),
        lambda: ui.animate(func, duration, delay),
        lambda: ui.animate(func, duration),
    )
    for variant in variants:
        try:
            variant()
            return
        except TypeError:
            continue
        except Exception:
            break
    # Анимация недоступна — применяем изменение мгновенно.
    try:
        func()
    except Exception:
        pass
    if callable(completion):
        try:
            completion()
        except Exception:
            pass


def spaced(text, gap=' '):
    """Имитация letter-spacing: 'NOX' -> 'N O X'."""
    return gap.join(list(text))


def fmt_size(n):
    try:
        n = float(n)
    except Exception:
        return ''
    if n >= 1024.0 ** 3:
        return '%.1f GB' % (n / 1024.0 ** 3)
    if n >= 1024.0 ** 2:
        return '%.0f MB' % (n / 1024.0 ** 2)
    if n >= 1024.0:
        return '%.0f KB' % (n / 1024.0)
    return '%d B' % int(n)


def fmt_duration(sec):
    try:
        sec = int(float(sec))
    except Exception:
        return ''
    if sec <= 0:
        return ''
    h = sec // 3600
    m = (sec % 3600) // 60
    s = sec % 60
    if h:
        return '%d ч %d мин' % (h, m)
    if m:
        return '%d мин' % m
    return '%d с' % s


def fmt_speed(bps):
    """Реальная скорость из progress_hook. Нет данных — пустая строка."""
    try:
        v = float(bps)
    except Exception:
        return ''
    if v <= 0:
        return ''
    if v >= 1024.0 ** 3:
        return '%.1f GB/s' % (v / 1024.0 ** 3)
    if v >= 1024.0 ** 2:
        return '%.1f MB/s' % (v / 1024.0 ** 2)
    if v >= 1024.0:
        return '%.0f KB/s' % (v / 1024.0)
    return '%d B/s' % int(v)


def fmt_eta(seconds):
    """Реальный остаток из progress_hook: 00:38 или 1:02:03."""
    try:
        s = int(float(seconds))
    except Exception:
        return ''
    if s < 0:
        return ''
    h = s // 3600
    m = (s % 3600) // 60
    sec = s % 60
    if h:
        return '%d:%02d:%02d' % (h, m, sec)
    return '%02d:%02d' % (m, sec)


def safe_name(text, limit=40):
    text = (text or '').strip()
    if len(text) > limit:
        return text[:limit - 1].rstrip() + '…'
    return text


def make_label(text='', font=(F_REG, 14), color=TXT, align=ui.ALIGN_LEFT,
               lines=1, frame=None):
    """ВАЖНО: ui.Label нельзя наследовать в Pythonista, поэтому фабрика."""
    lb = ui.Label()
    lb.text = text
    lb.font = font
    lb.text_color = color
    lb.alignment = align
    lb.number_of_lines = lines
    lb.background_color = 'clear'
    try:
        lb.line_break_mode = ui.LB_WORD_WRAP if lines != 1 else ui.LB_TRUNCATING_TAIL
    except Exception:
        pass
    if frame:
        lb.frame = frame
    return lb


def deactivate_tree(view):
    """Рекурсивно зовёт stop() у всего, что умеет останавливаться."""
    stop = getattr(view, 'stop', None)
    if callable(stop):
        try:
            stop()
        except Exception:
            pass
    for sub in list(getattr(view, 'subviews', ()) or ()):
        deactivate_tree(sub)


def card_view(frame, bg=CARD, radius=16, border=BORDER, border_w=1):
    v = ui.View(frame=frame)
    v.background_color = bg
    v.corner_radius = radius
    v.border_width = border_w
    v.border_color = border
    return v


# =====================================================================
#  ИКОНКИ (векторные, без внешних ресурсов)
# =====================================================================

class Icon(ui.View):
    """Все иконки рисуются вручную в нормализованных координатах 0..1."""

    def __init__(self, name='film', color=TXT, line=1.7, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.icon_name = name
        self.icon_color = color
        self.line = line
        self.background_color = 'clear'
        self.user_interaction_enabled = False

    def set_icon(self, name=None, color=None):
        if name is not None:
            self.icon_name = name
        if color is not None:
            self.icon_color = color
        redraw(self)

    # -- helpers ---------------------------------------------------
    def _geom(self):
        s = min(self.width, self.height)
        ox = (self.width - s) / 2.0
        oy = (self.height - s) / 2.0
        return s, ox, oy

    def _stroke(self, p, color=None, w=None):
        p.line_width = w if w else self.line
        try:
            p.line_cap_style = 1
            p.line_join_style = 1
        except Exception:
            pass
        ui.set_color(color or self.icon_color)
        p.stroke()

    def _fill(self, p, color=None):
        ui.set_color(color or self.icon_color)
        p.fill()

    def _poly(self, pts, s, ox, oy, close=False):
        p = ui.Path()
        first = True
        for (a, b) in pts:
            x = ox + a * s
            y = oy + b * s
            if first:
                p.move_to(x, y)
                first = False
            else:
                p.line_to(x, y)
        if close:
            p.close()
        return p

    def _oval(self, x, y, w, h, s, ox, oy):
        return ui.Path.oval(ox + x * s, oy + y * s, w * s, h * s)

    def _rrect(self, x, y, w, h, r, s, ox, oy):
        return ui.Path.rounded_rect(ox + x * s, oy + y * s, w * s, h * s, r * s)

    # -- draw ------------------------------------------------------
    def draw(self):
        s, ox, oy = self._geom()
        if s <= 1:
            return
        n = self.icon_name
        fn = getattr(self, '_i_' + str(n), None)
        if not callable(fn):
            fn = self._i_film
        try:
            fn(s, ox, oy)
        except Exception:
            pass

    def _i_search(self, s, ox, oy):
        self._stroke(self._oval(0.10, 0.10, 0.58, 0.58, s, ox, oy))
        self._stroke(self._poly([(0.62, 0.62), (0.92, 0.92)], s, ox, oy))

    def _i_tune(self, s, ox, oy):
        for i, (y, kx) in enumerate([(0.24, 0.68), (0.5, 0.34), (0.76, 0.6)]):
            self._stroke(self._poly([(0.08, y), (0.92, y)], s, ox, oy), w=self.line * 0.85)
            self._fill(self._oval(kx - 0.09, y - 0.09, 0.18, 0.18, s, ox, oy), BG)
            self._stroke(self._oval(kx - 0.09, y - 0.09, 0.18, 0.18, s, ox, oy), w=self.line * 0.85)

    def _i_play(self, s, ox, oy):
        self._fill(self._poly([(0.30, 0.18), (0.84, 0.5), (0.30, 0.82)], s, ox, oy, True))

    def _i_play_circle(self, s, ox, oy):
        self._stroke(self._oval(0.06, 0.06, 0.88, 0.88, s, ox, oy))
        self._fill(self._poly([(0.41, 0.32), (0.68, 0.5), (0.41, 0.68)], s, ox, oy, True))

    def _i_check(self, s, ox, oy):
        self._stroke(self._poly([(0.22, 0.52), (0.42, 0.72), (0.78, 0.29)], s, ox, oy))

    def _i_check_badge(self, s, ox, oy):
        self._fill(self._oval(0.0, 0.0, 1.0, 1.0, s, ox, oy), self.icon_color)
        p = self._poly([(0.28, 0.52), (0.44, 0.68), (0.73, 0.34)], s, ox, oy)
        self._stroke(p, '#ffffff', max(1.2, s * 0.11))

    def _i_download(self, s, ox, oy):
        self._stroke(self._poly([(0.5, 0.08), (0.5, 0.62)], s, ox, oy))
        self._stroke(self._poly([(0.27, 0.41), (0.5, 0.64), (0.73, 0.41)], s, ox, oy))
        self._stroke(self._poly([(0.12, 0.78), (0.12, 0.92), (0.88, 0.92), (0.88, 0.78)],
                                s, ox, oy))

    def _i_home(self, s, ox, oy):
        pts = [(0.5, 0.06), (0.96, 0.45), (0.84, 0.45), (0.84, 0.93),
               (0.60, 0.93), (0.60, 0.62), (0.40, 0.62), (0.40, 0.93),
               (0.16, 0.93), (0.16, 0.45), (0.04, 0.45)]
        self._fill(self._poly(pts, s, ox, oy, True))

    def _i_gear(self, s, ox, oy):
        import math
        cx, cy, r = 0.5, 0.5, 0.30
        for i in range(8):
            a = math.pi * 2 * i / 8.0
            x1 = cx + math.cos(a) * (r - 0.02)
            y1 = cy + math.sin(a) * (r - 0.02)
            x2 = cx + math.cos(a) * (r + 0.16)
            y2 = cy + math.sin(a) * (r + 0.16)
            self._stroke(self._poly([(x1, y1), (x2, y2)], s, ox, oy), w=self.line * 1.5)
        self._stroke(self._oval(cx - r, cy - r, r * 2, r * 2, s, ox, oy))
        self._stroke(self._oval(cx - 0.13, cy - 0.13, 0.26, 0.26, s, ox, oy),
                     w=self.line * 0.9)

    def _i_link(self, s, ox, oy):
        self._stroke(self._oval(0.02, 0.28, 0.52, 0.44, s, ox, oy))
        self._stroke(self._oval(0.46, 0.28, 0.52, 0.44, s, ox, oy))

    def _i_clip(self, s, ox, oy):
        self._stroke(self._rrect(0.16, 0.14, 0.68, 0.78, 0.14, s, ox, oy))
        self._fill(self._rrect(0.33, 0.03, 0.34, 0.20, 0.07, s, ox, oy))

    def _i_pause(self, s, ox, oy):
        self._fill(self._rrect(0.28, 0.20, 0.14, 0.60, 0.06, s, ox, oy))
        self._fill(self._rrect(0.58, 0.20, 0.14, 0.60, 0.06, s, ox, oy))

    def _i_clock(self, s, ox, oy):
        self._stroke(self._oval(0.06, 0.06, 0.88, 0.88, s, ox, oy))
        self._stroke(self._poly([(0.5, 0.28), (0.5, 0.52), (0.70, 0.62)], s, ox, oy),
                     w=self.line * 0.9)

    def _i_ring(self, s, ox, oy):
        self._stroke(self._oval(0.08, 0.08, 0.84, 0.84, s, ox, oy),
                     rgba(ACCENT, 0.25))
        try:
            import math
            p = ui.Path.arc(ox + 0.5 * s, oy + 0.5 * s, 0.42 * s,
                            -math.pi / 2.0, math.pi * 0.25)
            self._stroke(p, self.icon_color)
        except Exception:
            pass

    def _i_chevron(self, s, ox, oy):
        self._stroke(self._poly([(0.36, 0.18), (0.66, 0.5), (0.36, 0.82)], s, ox, oy),
                     w=self.line * 0.95)

    def _i_dots(self, s, ox, oy):
        for y in (0.16, 0.5, 0.84):
            self._fill(self._oval(0.5 - 0.085, y - 0.085, 0.17, 0.17, s, ox, oy))

    def _i_dots_h(self, s, ox, oy):
        for x in (0.14, 0.5, 0.86):
            self._fill(self._oval(x - 0.085, 0.5 - 0.085, 0.17, 0.17, s, ox, oy))

    def _i_drive(self, s, ox, oy):
        self._stroke(self._rrect(0.06, 0.22, 0.88, 0.56, 0.16, s, ox, oy))
        self._fill(self._oval(0.68, 0.42, 0.16, 0.16, s, ox, oy))
        self._stroke(self._poly([(0.22, 0.40), (0.22, 0.60)], s, ox, oy),
                     w=self.line * 0.9)

    def _i_film(self, s, ox, oy):
        self._stroke(self._rrect(0.06, 0.20, 0.88, 0.60, 0.14, s, ox, oy))
        self._fill(self._poly([(0.42, 0.36), (0.66, 0.5), (0.42, 0.64)], s, ox, oy, True))

    def _i_folder(self, s, ox, oy):
        p = self._poly([(0.06, 0.82), (0.06, 0.22), (0.40, 0.22), (0.48, 0.34),
                        (0.94, 0.34), (0.94, 0.82)], s, ox, oy, True)
        self._stroke(p)

    def _i_info(self, s, ox, oy):
        self._stroke(self._oval(0.08, 0.08, 0.84, 0.84, s, ox, oy), w=self.line * 0.9)
        self._fill(self._oval(0.44, 0.24, 0.12, 0.12, s, ox, oy))
        self._fill(self._rrect(0.44, 0.42, 0.12, 0.34, 0.06, s, ox, oy))

    def _i_refresh(self, s, ox, oy):
        try:
            import math
            p = ui.Path.arc(ox + 0.5 * s, oy + 0.5 * s, 0.36 * s,
                            math.pi * 0.15, math.pi * 1.75)
            self._stroke(p)
        except Exception:
            self._stroke(self._oval(0.14, 0.14, 0.72, 0.72, s, ox, oy))
        self._fill(self._poly([(0.78, 0.10), (0.96, 0.30), (0.68, 0.34)], s, ox, oy, True))

    def _i_close(self, s, ox, oy):
        self._stroke(self._poly([(0.22, 0.22), (0.78, 0.78)], s, ox, oy))
        self._stroke(self._poly([(0.78, 0.22), (0.22, 0.78)], s, ox, oy))

    def _i_trash(self, s, ox, oy):
        self._stroke(self._poly([(0.14, 0.24), (0.86, 0.24)], s, ox, oy))
        self._stroke(self._rrect(0.22, 0.24, 0.56, 0.68, 0.12, s, ox, oy))
        self._stroke(self._poly([(0.38, 0.24), (0.38, 0.12), (0.62, 0.12), (0.62, 0.24)],
                                s, ox, oy))


# =====================================================================
#  БАЗОВЫЕ КОМПОНЕНТЫ
# =====================================================================

class Tappable(ui.View):
    """Кнопка-контейнер с press-анимацией scale 0.96 -> 1.0."""

    action = None
    press_scale = 0.96

    def __init__(self, action=None, press_scale=0.96, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.action = action
        self.press_scale = press_scale
        self._pressed = False
        self._start = (0.0, 0.0)
        self.multitouch_enabled = False

    def _scale(self, k):
        try:
            self.transform = ui.Transform.scale(k, k)
        except Exception:
            pass

    def touch_began(self, touch):
        self._pressed = True
        self._start = touch.location
        animate(lambda: self._scale(self.press_scale), 0.09)

    def touch_moved(self, touch):
        if not self._pressed:
            return
        x, y = touch.location
        sx, sy = self._start
        moved = abs(x - sx) > 12 or abs(y - sy) > 12
        inside = (-14 <= x <= self.width + 14) and (-14 <= y <= self.height + 14)
        if moved or not inside:
            self._pressed = False
            animate(lambda: self._scale(1.0), 0.12)

    def touch_ended(self, touch):
        was = self._pressed
        self._pressed = False
        animate(lambda: self._scale(1.0), 0.14)
        if not was:
            return
        x, y = touch.location
        sx, sy = self._start
        if abs(x - sx) > 12 or abs(y - sy) > 12:
            return
        if not (0 <= x <= self.width and 0 <= y <= self.height):
            return
        handler = self.action
        if not callable(handler):
            return                      # кнопка без обработчика молчит
        try:
            handler(self)
        except Exception as e:
            nox_error(str(e))


class GradientView(ui.View):
    """Горизонтальный градиент (для главной кнопки «Скачать»)."""

    def __init__(self, c1=ACCENT_DEEP, c2=ACCENT_2, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.c1 = c1
        self.c2 = c2
        self.background_color = 'clear'
        self.user_interaction_enabled = False

    def draw(self):
        w = self.width
        h = self.height
        if w <= 0 or h <= 0:
            return
        steps = 48
        step_w = w / float(steps)
        for i in range(steps):
            t = i / float(steps - 1)
            ui.set_color(mix(self.c1, self.c2, t))
            ui.fill_rect(i * step_w, 0, step_w + 1.0, h)


class GlowView(ui.View):
    """Мягкое свечение — концентрические круги с малой альфой."""

    def __init__(self, color=ACCENT, strength=0.16, rings=7, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.color = color
        self.strength = strength
        self.rings = rings
        self.background_color = 'clear'
        self.user_interaction_enabled = False

    def draw(self):
        w, h = self.width, self.height
        if w <= 0 or h <= 0:
            return
        cx, cy = w / 2.0, h / 2.0
        rmax = min(w, h) / 2.0
        for i in range(self.rings, 0, -1):
            t = i / float(self.rings)
            r = rmax * t
            a = self.strength * (1.0 - t) ** 1.6
            ui.set_color(rgba(self.color, a))
            ui.Path.oval(cx - r, cy - r, r * 2, r * 2).fill()


class OrbView(ui.View):
    """Фиолетовый «шар» статуса из шапки."""

    def __init__(self, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.user_interaction_enabled = False

    def draw(self):
        w, h = self.width, self.height
        s = min(w, h)
        cx, cy = w / 2.0, h / 2.0
        for i in range(8, 0, -1):
            t = i / 8.0
            r = s / 2.0 * t
            ui.set_color(rgba(ACCENT, 0.10 * (1.0 - t) + 0.02))
            ui.Path.oval(cx - r, cy - r, r * 2, r * 2).fill()
        r = s * 0.30
        ui.set_color(ACCENT_DEEP)
        ui.Path.oval(cx - r, cy - r, r * 2, r * 2).fill()
        r2 = s * 0.20
        ui.set_color(rgba(ACCENT_2, 0.85))
        ui.Path.oval(cx - r2 * 1.1, cy - r2 * 1.35, r2 * 2, r2 * 2).fill()


class ProgressBar(ui.View):
    """
    Пассивная полоса прогресса. value=None -> неопределённый режим (бегунок).

    Собственного таймера у неё НЕТ и быть не должно: раньше каждая полоса
    заводила свою цепочку ui.delay, которая переживала удаление карточки
    (проверка superview не спасала — полоса оставалась дочерней у уже
    выброшенной card) и продолжала дёргать set_needs_display на мёртвых
    view. Фазу теперь задаёт единственный таймер NoxApp._tick.
    """

    def __init__(self, value=None, track=BORDER, fill=ACCENT, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.user_interaction_enabled = False
        self.value = value
        self.track = track
        self.fill_color = fill
        self._phase = 0.0
        self._detached = False

    def set_value(self, v):
        if self._detached:
            return
        self.value = v
        redraw(self)

    def set_phase(self, phase):
        """Только запомнить фазу и перерисоваться. Ничего не планирует."""
        if self._detached:
            return
        try:
            self._phase = float(phase) % 1.0
        except Exception:
            self._phase = 0.0
        redraw(self)

    def stop(self):
        """Полосу сняли с экрана: дальше она молчит, даже если её позовут."""
        self._detached = True

    def draw(self):
        w, h = self.width, self.height
        if w <= 0 or h <= 0:
            return
        r = h / 2.0
        ui.set_color(rgba(self.track, 0.85))
        ui.Path.rounded_rect(0, 0, w, h, r).fill()
        if self.value is None:
            seg = max(28.0, w * 0.28)
            x = (w + seg) * self._phase - seg
            x = max(0.0, min(w - 1.0, x))
            seg = min(seg, w - x)
            ui.set_color(rgba(self.fill_color, 0.9))
            ui.Path.rounded_rect(x, 0, max(4.0, seg), h, r).fill()
        else:
            v = max(0.0, min(1.0, float(self.value)))
            if v > 0:
                ui.set_color(self.fill_color)
                ui.Path.rounded_rect(0, 0, max(h, w * v), h, r).fill()


class ThumbView(ui.View):
    """
    Обложка. Если реального изображения рядом нет —
    рисуется минималистичная карточка (без выдуманных картинок).
    """

    def __init__(self, image=None, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = CARD_2
        self.user_interaction_enabled = False
        self.img = image
        self.iv = None
        if image is not None:
            self.iv = ui.ImageView(frame=self.bounds)
            self.iv.flex = 'WH'
            self.iv.content_mode = ui.CONTENT_SCALE_ASPECT_FILL
            self.iv.image = image
            self.add_subview(self.iv)

    def draw(self):
        if self.img is not None:
            return
        w, h = self.width, self.height
        if w <= 0 or h <= 0:
            return
        # тонкий вертикальный градиент вместо выдуманной картинки
        steps = 20
        sh = h / float(steps)
        for i in range(steps):
            t = i / float(steps - 1)
            ui.set_color(mix('#0d1120', '#141a30', t))
            ui.fill_rect(0, i * sh, w, sh + 1.0)
        # тусклый значок видео по центру
        s = min(w, h) * 0.34
        cx, cy = w / 2.0, h / 2.0
        ui.set_color(rgba(ACCENT, 0.30))
        p = ui.Path.rounded_rect(cx - s / 2, cy - s * 0.34, s, s * 0.68, s * 0.12)
        p.line_width = max(1.0, s * 0.055)
        p.stroke()
        ui.set_color(rgba(ACCENT_2, 0.42))
        tri = ui.Path()
        tri.move_to(cx - s * 0.08, cy - s * 0.15)
        tri.line_to(cx + s * 0.16, cy)
        tri.line_to(cx - s * 0.08, cy + s * 0.15)
        tri.close()
        tri.fill()


# =====================================================================
#  СОСТОЯНИЕ / ХРАНИЛИЩЕ
# =====================================================================

def _project_dir():
    """
    Папка проекта = папка, в которой лежит сам NOX.py.

    При открытии NOX.py из «Файлы → На iPhone → NOX» через External Files
    Pythonista это папка NOX, поэтому NoxMedia и NOX_Data оказываются
    ровно рядом со скриптом. Никаких путей AppGroup в коде нет.
    """
    try:
        return os.path.dirname(os.path.abspath(__file__))
    except Exception:
        pass
    try:
        argv0 = sys.argv[0]
        if argv0:
            return os.path.dirname(os.path.abspath(argv0))
    except Exception:
        pass
    return os.path.abspath(os.getcwd())


PROJECT_DIR = _project_dir()
MEDIA_DIR = os.path.join(PROJECT_DIR, 'NoxMedia')
DATA_DIR = os.path.join(PROJECT_DIR, 'NOX_Data')
ICON_PATH = os.path.join(PROJECT_DIR, 'NOX_icon.png')
YT_DLP_DIR = os.path.join(PROJECT_DIR, 'yt_dlp')
STATE_PATH = os.path.join(DATA_DIR, 'state.json')
DEBUG_LOG = os.path.join(DATA_DIR, 'download_debug.txt')
LEGACY_STATE = os.path.join(os.path.expanduser('~/Documents'), '.nox_state.json')

# Показываем пользователю понятный путь, а не контейнер приложения.
MEDIA_LABEL = 'На iPhone / %s / NoxMedia' % os.path.basename(PROJECT_DIR)


def ensure_dirs():
    """Создаёт NoxMedia и NOX_Data рядом с NOX.py. True, если обе на месте."""
    ok = True
    for path in (MEDIA_DIR, DATA_DIR):
        try:
            os.makedirs(path, exist_ok=True)
        except Exception:
            ok = False
        if not os.path.isdir(path):
            ok = False
    return ok


# ---------------------------------------------------------------------
#  Локальный пакет yt_dlp рядом с NOX.py
# ---------------------------------------------------------------------

yt_dlp = None
YTDLP_ERROR = ''
YTDLP_VERSION = ''
YTDLP_DEBUG = ''          # диагностика без обращения к консоли


def _load_yt_dlp():
    """
    Импортирует ИМЕННО тот yt_dlp, что лежит рядом с NOX.py: PROJECT_DIR
    ставится первым в sys.path. Системные пути не хардкодятся.
    Неудача не роняет приложение — она превращается в понятную ошибку.
    """
    global yt_dlp, YTDLP_ERROR, YTDLP_VERSION, YTDLP_DEBUG
    if yt_dlp is not None:
        return yt_dlp
    if PROJECT_DIR not in sys.path:
        sys.path.insert(0, PROJECT_DIR)
    if not os.path.isdir(YT_DLP_DIR):
        YTDLP_ERROR = 'Модуль yt-dlp не найден'
        return None
    try:
        import yt_dlp as _mod
    except Exception as e:
        YTDLP_ERROR = 'Модуль yt-dlp не найден'
        YTDLP_DEBUG = repr(e)
        return None
    got = os.path.dirname(os.path.abspath(getattr(_mod, '__file__', '') or ''))
    if os.path.normpath(got) != os.path.normpath(YT_DLP_DIR):
        YTDLP_DEBUG = 'yt_dlp импортирован не из папки проекта: %s' % got
    yt_dlp = _mod
    YTDLP_ERROR = ''
    try:
        YTDLP_VERSION = str(getattr(_mod.version, '__version__', '') or '')
    except Exception:
        YTDLP_VERSION = ''
    return yt_dlp


def ytdlp_ready():
    return _load_yt_dlp() is not None


_LAST_ERROR = {'text': '', 'time': 0.0}


def log_debug(text):
    """
    Диагностика уходит в NOX_Data/download_debug.txt, а не в консоль:
    консольный мост Pythonista при открытом fullscreen ui.View лучше
    не трогать вовсе. Вызывается только главным потоком и только на сбое.
    """
    try:
        with io.open(DEBUG_LOG, 'a', encoding='utf-8') as f:
            f.write('%s\t%s\n' % (time.strftime('%Y-%m-%d %H:%M:%S'), text))
    except Exception:
        pass


def run_on_main(func):
    """
    Одноразовый перенос действия на главный поток. Периодического таймера
    не создаёт. Всё, что меняет ui.View, обязано идти через него.
    """
    if not callable(func):
        return
    try:
        ui.delay(func, 0)
    except Exception:
        pass


_IDLE_STATE = {'off': False}


def keep_screen_awake(flag):
    """
    Пока идёт многочасовая загрузка, экран iPhone гасить нельзя: вместе с
    ним система усыпляет и Pythonista, а вместе с ним — наш HTTP-поток.

    Вызывается ТОЛЬКО с главного потока (из общего такта). objc_util
    импортируется лениво и целиком в try/except: если его нет, NOX
    работает как раньше, просто экран может погаснуть.
    """
    flag = bool(flag)
    if _IDLE_STATE['off'] == flag:
        return _IDLE_STATE['off']
    try:
        import objc_util
        app = objc_util.ObjCClass('UIApplication').sharedApplication()
        app.setIdleTimerDisabled_(flag)
        _IDLE_STATE['off'] = flag
    except Exception:
        pass
    return _IDLE_STATE['off']


def nox_error(text):
    _LAST_ERROR['text'] = str(text)
    _LAST_ERROR['time'] = time.time()
    try:
        console.hud_alert(safe_name(str(text), 70), 'error', 1.4)
    except Exception:
        pass


def nox_ok(text):
    try:
        console.hud_alert(safe_name(str(text), 70), 'success', 1.2)
    except Exception:
        pass


class State(object):
    def __init__(self):
        self.data = {
            'quality': '720',
            'last_opened': None,
            'sort': 'new',
            'quality_filter': 'all',
            'watch_progress': {},
            'download_history': [],
            JOBS_KEY: [],
        }
        self.load()

    def load(self):
        ensure_dirs()
        source = STATE_PATH if os.path.exists(STATE_PATH) else LEGACY_STATE
        try:
            if os.path.exists(source):
                with io.open(source, 'r', encoding='utf-8') as f:
                    raw = json.load(f)
                if isinstance(raw, dict):
                    # Устаревшие ключи прежней архитектуры (a-Shell/Ярлык) и
                    # активные задания в JSON не восстанавливаются.
                    for dead in ('folder', 'bookmark', 'use_shortcut', 'jobs'):
                        raw.pop(dead, None)
                    self.data.update(raw)
        except Exception:
            pass

    def save(self):
        ensure_dirs()
        try:
            tmp = STATE_PATH + '.tmp'
            with io.open(tmp, 'w', encoding='utf-8') as f:
                f.write(json.dumps(self.data, ensure_ascii=False, indent=1))
            if os.path.exists(STATE_PATH):
                os.remove(STATE_PATH)
            os.rename(tmp, STATE_PATH)
        except Exception:
            pass

    # --- удобные свойства ---
    def get(self, key, default=None):
        return self.data.get(key, default)

    def set(self, key, value):
        self.data[key] = value
        self.save()

    @property
    def folder(self):
        """Медиатека всегда одна: PROJECT_DIR/NoxMedia рядом с NOX.py."""
        return MEDIA_DIR

    def ensure_folder(self):
        return ensure_dirs()

    # --- последний открытый файл ---
    def remember_opened(self, path):
        self.data['last_opened'] = {'path': path, 'time': time.time()}
        self.save()

    # --- позиция просмотра -----------------------------------------
    def watch_map(self):
        wp = self.data.get('watch_progress')
        if not isinstance(wp, dict):
            wp = {}
            self.data['watch_progress'] = wp
        return wp

    def watch_get(self, video_id):
        entry = self.watch_map().get(video_id)
        return entry if isinstance(entry, dict) else None

    def watch_note(self, video_id, watched_seconds, duration):
        """
        Накапливаем РЕАЛЬНО измеренное время просмотра.
        Досмотрено почти до конца — запись сбрасывается.
        """
        if not video_id or watched_seconds <= 0:
            return
        wp = self.watch_map()
        prev = wp.get(video_id) if isinstance(wp.get(video_id), dict) else {}
        position = float(prev.get('position') or 0.0) + float(watched_seconds)
        try:
            total = float(duration or prev.get('duration') or 0.0)
        except Exception:
            total = 0.0
        if total > 0:
            position = min(position, total)
            if position >= total - WATCH_DONE_TAIL:
                wp.pop(video_id, None)
                self.save()
                return
        wp[video_id] = {'position': round(position, 1),
                        'duration': round(total, 1) if total else None,
                        'updated_at': time.time()}
        self.save()

    def watch_forget(self, video_id):
        if self.watch_map().pop(video_id, None) is not None:
            self.save()

    # --- история загрузок ------------------------------------------
    def history(self):
        h = self.data.get('download_history')
        if not isinstance(h, list):
            h = []
            self.data['download_history'] = h
        return h

    def add_history(self, entry):
        h = self.history()
        h.append(entry)
        self.data['download_history'] = h[-HISTORY_LIMIT:]
        self.save()

    def last_opened_path(self):
        lo = self.data.get('last_opened')
        if not isinstance(lo, dict):
            return None
        p = lo.get('path')
        if p and os.path.exists(p):
            return p
        return None


STATE = State()


# =====================================================================
#  СКАНИРОВАНИЕ МЕДИАТЕКИ (только реальные файлы)
# =====================================================================

class MediaItem(object):
    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        self.stem, self.ext = os.path.splitext(self.name)
        self.ext = self.ext.lower()
        try:
            st = os.stat(path)
            self.size = st.st_size
            self.mtime = st.st_mtime
        except Exception:
            self.size = 0
            self.mtime = 0.0
        self.info = {}
        self.meta_source = ''
        self.title = self.stem
        self.duration = None
        self.uploader = ''
        self.video_id = ''
        self.webpage_url = ''
        self.height = None
        self.format_id = ''
        self.thumb_path = None
        self._load_info()
        self._find_thumb()

    def _meta_candidates(self):
        """Приоритет: свой .nox.json -> старый .info.json -> имя файла."""
        folder = os.path.dirname(self.path)
        base = re.sub(r'\.f\d+$', '', self.stem)
        stems = [self.stem] if base == self.stem else [self.stem, base]
        out = []
        for suffix in (SIDECAR_EXT, '.info.json'):
            for stem in stems:
                out.append((os.path.join(folder, stem + suffix), suffix))
        return out

    def _load_info(self):
        for path, suffix in self._meta_candidates():
            if not os.path.exists(path):
                continue
            try:
                with io.open(path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
            except Exception:
                continue
            if not isinstance(data, dict):
                continue
            self.info = data
            self.meta_source = suffix
            t = data.get('title')
            if isinstance(t, str) and t.strip():
                self.title = t.strip()
            d = data.get('duration')
            if isinstance(d, (int, float)) and d > 0:
                self.duration = float(d)
            up = data.get('uploader') or data.get('channel') or ''
            if isinstance(up, str):
                self.uploader = up.strip()
            vid = data.get('id')
            if isinstance(vid, str):
                self.video_id = vid
            wu = data.get('webpage_url')
            if isinstance(wu, str):
                self.webpage_url = wu
            h = data.get('height')
            if isinstance(h, (int, float)) and h > 0:
                self.height = int(h)
            fid = data.get('format_id')
            if isinstance(fid, str):
                self.format_id = fid
            break

    def _find_thumb(self):
        folder = os.path.dirname(self.path)
        base = re.sub(r'\.f\d+$', '', self.stem)
        for stem in (self.stem, base):
            for ext in IMAGE_EXT:
                p = os.path.join(folder, stem + ext)
                if os.path.exists(p):
                    self.thumb_path = p
                    return

    def load_thumb_image(self):
        """
        Только данные и только проверенные JPEG/PNG: ui.Image.named для
        произвольного локального файла не используется. Не распозналось —
        None, без исключения наружу.
        """
        if not self.thumb_path:
            return None
        try:
            with io.open(self.thumb_path, 'rb') as f:
                raw = f.read()
        except Exception:
            return None
        if not raw:
            return None
        if not (raw[:8] == b'\x89PNG\r\n\x1a\n' or raw[:3] == b'\xff\xd8\xff'):
            return None
        try:
            return ui.Image.from_data(raw)
        except Exception:
            return None

    @property
    def watch_id(self):
        """Стабильный ключ: id из метаданных, иначе имя файла."""
        if self.video_id:
            return self.video_id
        return os.path.basename(self.path)

    @property
    def quality_label(self):
        """Реальное качество: высота из метаданных или format_id вида url480."""
        h = self.height
        if not h and self.format_id:
            m = re.search(r'(\d{3,4})', self.format_id)
            if m:
                try:
                    h = int(m.group(1))
                except Exception:
                    h = None
        if not h:
            return ''
        for step in (2160, 1440, 1080, 720, 480, 360, 240, 144):
            if h >= step:
                return '4K' if step == 2160 else '%dp' % step
        return '%dp' % h

    @property
    def meta_line(self):
        parts = []
        d = fmt_duration(self.duration) if self.duration else ''
        if d:
            parts.append(d)
        q = self.quality_label
        if q:
            parts.append(q)
        if self.size:
            parts.append(fmt_size(self.size))
        return '  •  '.join(parts)

    @property
    def fmt_label(self):
        return self.ext.lstrip('.').upper()

    def sidecar_paths(self):
        """Файлы-спутники ИМЕННО этого видео — для удаления."""
        folder = os.path.dirname(self.path)
        base = re.sub(r'\.f\d+$', '', self.stem)
        stems = {self.stem, base}
        out = []
        for stem in stems:
            out.append(os.path.join(folder, stem + SIDECAR_EXT))
            out.append(os.path.join(folder, stem + '.info.json'))
            for ext in IMAGE_EXT_ALL:
                out.append(os.path.join(folder, stem + ext))
        return out


class TempItem(object):
    """Незавершённая загрузка: .part / .ytdl."""

    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        try:
            self.size = os.path.getsize(path)
            self.mtime = os.path.getmtime(path)
        except Exception:
            self.size = 0
            self.mtime = 0.0
        # "Название [id].mp4.part" -> "Название [id]"
        base = self.name
        for suf in ('.part', '.ytdl'):
            if base.endswith(suf):
                base = base[:-len(suf)]
        base = re.sub(r'-Frag\d+$', '', base)
        self.stem = os.path.splitext(base)[0]
        self.stem = re.sub(r'\.f\d+$', '', self.stem)
        self.title = self.stem
        self.total = None
        self._load_total()

    def _load_total(self):
        folder = os.path.dirname(self.path)
        p = os.path.join(folder, self.stem + '.info.json')
        if not os.path.exists(p):
            return
        try:
            with io.open(p, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except Exception:
            return
        if not isinstance(data, dict):
            return
        t = data.get('title')
        if isinstance(t, str) and t.strip():
            self.title = t.strip()
        total = None
        rd = data.get('requested_downloads')
        if isinstance(rd, list) and rd and isinstance(rd[0], dict):
            total = rd[0].get('filesize') or rd[0].get('filesize_approx')
        if not total:
            total = data.get('filesize') or data.get('filesize_approx')
        if isinstance(total, (int, float)) and total > 0:
            self.total = float(total)

    @property
    def progress(self):
        """Реальный прогресс или None. Никаких выдуманных процентов."""
        if self.total and self.total > 0:
            return max(0.0, min(1.0, self.size / self.total))
        return None


class Library(object):
    def __init__(self, state):
        self.state = state
        self.items = []
        self.temps = []
        self.names = []
        self.total_bytes = 0
        self.error = ''

    def scan(self):
        self.items = []
        self.temps = []
        self.names = []
        self.total_bytes = 0
        self.error = ''
        folder = self.state.folder
        if not os.path.isdir(folder):
            if not self.state.ensure_folder():
                self.error = 'Папка NOX недоступна'
                return
        try:
            names = os.listdir(folder)
        except Exception:
            self.error = 'Папка NOX недоступна'
            return
        self.names = sorted(names)
        for n in self.names:
            if n.startswith('.'):
                continue
            p = os.path.join(folder, n)
            if not os.path.isfile(p):
                continue
            try:
                self.total_bytes += os.path.getsize(p)   # видео + обложки + метаданные
            except Exception:
                pass
            low = n.lower()
            if low.endswith(TEMP_EXT) or '.part' in low:
                self.temps.append(TempItem(p))
                continue
            if low.endswith(VIDEO_EXT):
                self.items.append(MediaItem(p))
        self.items.sort(key=lambda i: i.mtime, reverse=True)
        self.temps.sort(key=lambda i: i.mtime, reverse=True)

    def orphan_temps(self, managed_paths):
        """
        Незавершённые файлы, за которыми НЕ стоит карточка загрузки.

        Сюда не попадают файлы приостановленных и упавших заданий: у них
        уже есть своя карточка с кнопками, и вторая строка «Не завершено»
        для того же файла была бы дублем.
        """
        busy = set()
        for p in managed_paths or ():
            if not p:
                continue
            busy.add(os.path.normpath(p))
            busy.add(os.path.normpath(p + '.part'))
        out = []
        for t in self.temps:
            base = t.path
            for suf in ('.part', '.ytdl'):
                if base.endswith(suf):
                    base = base[:-len(suf)]
            if os.path.normpath(t.path) in busy or os.path.normpath(base) in busy:
                continue
            out.append(t)
        return out

    @staticmethod
    def _quality_bucket(item):
        label = item.quality_label
        if not label:
            return ''
        if label == '4K':
            return 'max'
        try:
            h = int(label.rstrip('p'))
        except Exception:
            return ''
        if h >= 1440:
            return 'max'
        if h >= 1080:
            return '1080'
        for step in (720, 480, 360):
            if h >= step:
                return str(step)
        return ''

    def filtered(self, query, sort=None, quality=None):
        """Локальный поиск по названию, автору и имени файла + фильтр и сортировка."""
        out = list(self.items)
        q = (query or '').strip().lower()
        if q:
            out = [i for i in out
                   if q in (i.title + ' ' + i.name + ' ' + i.uploader).lower()]
        quality = quality or self.state.get('quality_filter', 'all')
        if quality and quality != 'all':
            out = [i for i in out if self._quality_bucket(i) == quality]
        sort = sort or self.state.get('sort', 'new')
        if sort == 'old':
            out.sort(key=lambda i: i.mtime)
        elif sort == 'title':
            out.sort(key=lambda i: i.title.lower())
        elif sort == 'size':
            out.sort(key=lambda i: i.size, reverse=True)
        elif sort == 'duration':
            out.sort(key=lambda i: i.duration or 0.0, reverse=True)
        else:
            out.sort(key=lambda i: i.mtime, reverse=True)
        return out

    def find_by_watch_id(self, watch_id):
        for i in self.items:
            if i.watch_id == watch_id:
                return i
        return None

    def used_bytes(self):
        """Весь объём медиатеки: MP4 + обложки + метаданные + незавершённые."""
        if self.total_bytes:
            return self.total_bytes
        return sum(i.size for i in self.items) + sum(t.size for t in self.temps)

    def disk(self):
        """(total, free) реального тома или (None, None)."""
        try:
            u = shutil.disk_usage(self.state.folder if os.path.isdir(self.state.folder)
                                  else PROJECT_DIR)
            return float(u.total), float(u.free)
        except Exception:
            return None, None


LIB = Library(STATE)


# =====================================================================
#  ЗАГРУЗКА (локальный yt_dlp внутри NOX)
# =====================================================================

URL_RE = re.compile(r'^https?://[^\s"\'`\\]+$', re.IGNORECASE)


def validate_url(url):
    url = (url or '').strip()
    if not url:
        return None, 'Ссылка не указана'
    if not URL_RE.match(url):
        return None, 'Ссылка выглядит некорректно'
    return url, None


# Pythonista на iOS не поддерживает subprocess: любая попытка yt-dlp
# определить или запустить ffmpeg заканчивалась
# RuntimeError: Subprocesses are not supported on ios.
# Поэтому ffmpeg для NOX не существует — ни проверок, ни склейки.
FFMPEG_AVAILABLE = False
NO_FFMPEG_PATH = os.path.join(PROJECT_DIR, '__NO_FFMPEG__')

HEIGHTS = {'360': 360, '480': 480, '720': 720}
# Прямые combined-форматы VK: в них уже есть и видео, и звук.
VK_DIRECT = {
    '360': ['url360', 'url240', 'url144'],
    '480': ['url480', 'url360', 'url240', 'url144'],
    '720': ['url720', 'url480', 'url360', 'url240', 'url144'],
    'MAX': ['url2160', 'url1440', 'url1080', 'url720',
            'url480', 'url360', 'url240', 'url144'],
}


SIDECAR_EXT = '.nox.json'
THUMB_LIMIT = 8 * 1024 * 1024

# Что реально можно отдать в UI Pythonista. WEBP сюда не входит:
# декодирование чужого формата в карточке — лишний риск, а конвертировать
# нечем (ffmpeg запрещён). Такая обложка просто пропускается.
SAFE_IMAGE_TYPES = ('.jpg', '.jpeg', '.png')

EXTRAS_NONE = 'none'
EXTRAS_PENDING = 'pending'
EXTRAS_RUNNING = 'running'
EXTRAS_READY = 'ready'
EXTRAS_PARTIAL = 'partial'
EXTRAS_ERROR = 'error'

SNAPSHOT_KEYS = ('id', 'title', 'uploader', 'channel', 'duration', 'width',
                 'height', 'format_id', 'ext', 'webpage_url', 'thumbnail',
                 'filesize')


def _entry_of(info):
    """Из результата extract_info достаём словарь самого видео."""
    if not isinstance(info, dict):
        return {}
    entries = info.get('entries')
    if isinstance(entries, list) and entries and isinstance(entries[0], dict):
        return entries[0]
    return info


def safe_metadata_snapshot(info):
    """
    Маленький обычный dict из уже полученного info. Ни сети, ни файлов —
    только чтение полей, поэтому вызывать можно и из рабочего потока.
    Огромный объект yt-dlp не сохраняется.
    """
    try:
        entry = _entry_of(info)
    except Exception:
        return {}
    if not entry:
        return {}
    snap = {}
    for key in SNAPSHOT_KEYS:
        try:
            value = entry.get(key)
        except Exception:
            value = None
        if isinstance(value, (str, int, float)):
            snap[key] = value          # пустые поля не занимают место
    if snap.get('filesize') is None:
        try:
            approx = entry.get('filesize_approx')
            if isinstance(approx, (int, float)):
                snap['filesize'] = approx
        except Exception:
            pass
    thumbs = []
    try:
        for t in (entry.get('thumbnails') or [])[:20]:
            if not isinstance(t, dict):
                continue
            url = t.get('url')
            if not isinstance(url, str) or not url.startswith('http'):
                continue
            thumbs.append({'url': url,
                           'width': t.get('width') if isinstance(
                               t.get('width'), (int, float)) else 0,
                           'preference': t.get('preference') if isinstance(
                               t.get('preference'), (int, float)) else 0})
    except Exception:
        thumbs = []
    snap['thumbnails'] = thumbs
    for key in ('filepath', '_filename'):
        try:
            value = entry.get(key)
        except Exception:
            value = None
        if isinstance(value, str) and value:
            snap[key] = value
    return snap


def _source_of(snap):
    url = snap.get('webpage_url') or ''
    try:
        from urllib.parse import urlparse
        host = urlparse(url).netloc
        return host or ''
    except Exception:
        return ''


def write_sidecar(video_path, snap):
    """Пишет <имя видео>.nox.json из снимка. Второго extract_info нет."""
    data = {
        'id': snap.get('id'),
        'title': snap.get('title'),
        'uploader': snap.get('uploader'),
        'channel': snap.get('channel'),
        'duration': snap.get('duration'),
        'width': snap.get('width'),
        'height': snap.get('height'),
        'format_id': snap.get('format_id'),
        'ext': snap.get('ext'),
        'webpage_url': snap.get('webpage_url'),
        'thumbnail': snap.get('thumbnail'),
        'filesize': snap.get('filesize'),
        'downloaded_at': time.strftime('%Y-%m-%dT%H:%M:%S'),
        'source': _source_of(snap),
    }
    try:
        if not data.get('filesize'):
            data['filesize'] = os.path.getsize(video_path)
    except Exception:
        pass
    base = os.path.splitext(video_path)[0]
    tmp = base + SIDECAR_EXT + '.tmp'
    with io.open(tmp, 'w', encoding='utf-8') as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=1))
    target = base + SIDECAR_EXT
    if os.path.exists(target):
        os.remove(target)
    os.rename(tmp, target)
    return target


def _thumb_candidates(snap):
    """Все URL обложек, лучшая первой."""
    out = []
    main = snap.get('thumbnail')
    if isinstance(main, str) and main.startswith('http'):
        out.append(main)
    thumbs = snap.get('thumbnails') or []
    ranked = []
    for t in thumbs:
        if not isinstance(t, dict):
            continue
        url = t.get('url')
        if isinstance(url, str) and url.startswith('http'):
            ranked.append(((t.get('preference') or 0, t.get('width') or 0), url))
    ranked.sort(key=lambda pair: pair[0], reverse=True)
    for _, url in ranked:
        if url not in out:
            out.append(url)
    return out[:5]


def _safe_image_ext(raw, ctype):
    """Расширение только для проверенных JPEG/PNG. WEBP -> ничего."""
    if raw[:8] == b'\x89PNG\r\n\x1a\n':
        return '.png'
    if raw[:3] == b'\xff\xd8\xff':
        return '.jpg'
    low = (ctype or '').lower()
    if 'png' in low:
        return '.png'
    if 'jpeg' in low or 'jpg' in low:
        return '.jpg'
    return ''


def fetch_thumbnail(video_path, snap):
    """
    Обложка обычным HTTP: ни yt-dlp postprocessor, ни ffmpeg, ни subprocess.
    Принимаются только JPEG и PNG; WEBP пропускается, конвертации нет.
    """
    last = ''
    for url in _thumb_candidates(snap):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'NOX/1.0'})
            with urllib.request.urlopen(req, timeout=30) as resp:
                ctype = resp.headers.get('Content-Type') or ''
                raw = resp.read(THUMB_LIMIT + 1)
        except Exception as e:
            last = repr(e)
            continue
        if not raw or len(raw) > THUMB_LIMIT:
            last = 'пустой или слишком большой файл'
            continue
        ext = _safe_image_ext(raw, ctype)
        if not ext:
            last = 'формат не JPEG/PNG (%s)' % (ctype or '?')
            continue
        target = os.path.splitext(video_path)[0] + ext
        tmp = target + '.tmp'
        with io.open(tmp, 'wb') as f:
            f.write(raw)
        if os.path.exists(target):
            os.remove(target)
        os.rename(tmp, target)
        return target
    if last:
        raise IOError(last)
    return ''


def media_path_of(job):
    """Путь к скачанному файлу: из снимка info, иначе из progress_hook."""
    snap = job.completed_info or {}
    for candidate in (snap.get('filepath'), snap.get('_filename'),
                      job.filename):
        if isinstance(candidate, str) and candidate and os.path.isfile(candidate):
            return candidate
    return ''


class ExtrasManager(object):
    """
    Отдельная от DownloadManager сущность. Запускается ТОЛЬКО после того,
    как рабочий поток загрузки завершился и MP4 уже отмечен finished.
    Ничего из неё не может изменить статус самого видео.
    """

    def __init__(self):
        self.tasks = []
        self.tick = 0
        self._lock = threading.RLock()
        self._thread = None

    def busy(self):
        with self._lock:
            return self._thread is not None and self._thread.is_alive()

    def pending(self):
        with self._lock:
            return len(self.tasks)

    def enqueue(self, job):
        """Вызывается главным потоком для уже завершённого задания."""
        if not ENABLE_EXTRAS:
            job.extras_status = EXTRAS_NONE
            return False
        path = media_path_of(job)
        if not path:
            job.extras_status = EXTRAS_ERROR
            job.extras_error = 'файл не найден'
            return False
        job.extras_status = EXTRAS_PENDING
        with self._lock:
            self.tasks.append({'job': job, 'path': path,
                               'snap': dict(job.completed_info or {})})
        return True

    def pump(self, downloads_busy):
        """
        Тоже только главный поток. Пока жив рабочий поток загрузки,
        extras не стартуют — они не должны идти параллельно с yt-dlp.
        """
        if downloads_busy or self.busy():
            return
        with self._lock:
            if not self.tasks:
                return
            task = self.tasks.pop(0)
            self._thread = threading.Thread(target=self._run, args=(task,),
                                            name='nox-extras', daemon=True)
            self._thread.start()

    def _run(self, task):
        job = task['job']
        path = task['path']
        snap = task['snap']
        job.extras_status = EXTRAS_RUNNING
        self.tick += 1
        # 1) sidecar — маленькая локальная операция
        try:
            write_sidecar(path, snap)
            job.metadata_ready = True
        except Exception as e:
            job.extras_error = 'sidecar: %r' % (e,)
        # 2) обложка — отдельный необязательный этап
        try:
            if fetch_thumbnail(path, snap):
                job.thumbnail_ready = True
        except Exception as e:
            job.thumbnail_error = repr(e)
        if job.metadata_ready and job.thumbnail_ready:
            job.extras_status = EXTRAS_READY
        elif job.metadata_ready or job.thumbnail_ready:
            job.extras_status = EXTRAS_PARTIAL
        else:
            job.extras_status = EXTRAS_ERROR
        with self._lock:
            self._thread = None
        self.tick += 1


EXTRAS = ExtrasManager()


# Признаки сетевого сбоя: после такого делается одна повторная попытка по IPv4.
NETWORK_HINTS = (
    'timed out', 'timeout', 'urlopen error', 'connection reset',
    'connection aborted', 'connection refused', 'connection error',
    'temporary failure in name resolution', 'name or service not known',
    'network is unreachable', 'no route to host', 'getaddrinfo',
    'unable to download webpage', 'eof occurred', 'remote end closed',
    # Обрыв посреди многочасовой загрузки — обычное дело: следующая попытка
    # продолжит файл через Range, а не начнёт его заново.
    'incomplete read', 'соединение оборвалось',
)


# Коды, которые лечит ОБЫЧНЫЙ повтор, а не новый разбор ссылки: сервер
# занят или временно лёг. Ссылка при этом жива.
RETRY_CODES = (408, 425, 429, 500, 502, 503, 504)


def is_network_error(exc):
    try:
        code = int(getattr(exc, 'code', 0) or 0)
    except Exception:
        code = 0
    if code and (code in RETRY_CODES or code >= 500):
        return True
    text = ('%r %s' % (exc, exc)).lower()
    return any(hint in text for hint in NETWORK_HINTS)


ST_QUEUED = 'queued'
ST_PREPARING = 'preparing'
ST_QUEUED_DOWNLOAD = 'queued_download'
ST_DOWNLOADING = 'downloading'
ST_NEEDS_RESOLVE = 'needs_resolve'
ST_PAUSED = 'paused'
ST_DELETING = 'deleting'
ST_PROCESSING = 'processing'
ST_FINISHED = 'finished'
ST_ERROR = 'error'
ST_CANCELLED = 'cancelled'

# Приостановленное задание живо, но очередь не занимает.
ACTIVE_STATES = (ST_QUEUED, ST_PREPARING, ST_QUEUED_DOWNLOAD,
                 ST_DOWNLOADING, ST_NEEDS_RESOLVE, ST_DELETING, ST_PROCESSING)
# Состояния, в которых задание принадлежит менеджеру и его .part не должен
# показываться отдельной строкой «Не завершено».
MANAGED_STATES = ACTIVE_STATES + (ST_PAUSED, ST_ERROR, ST_CANCELLED)
# Пока хоть одно задание здесь, экран iPhone не должен гаснуть.
AWAKE_STATES = (ST_QUEUED, ST_PREPARING, ST_QUEUED_DOWNLOAD,
                ST_DOWNLOADING, ST_NEEDS_RESOLVE)

STATUS_TEXT = {
    ST_QUEUED: 'В очереди',
    ST_PREPARING: 'Получение информации...',
    ST_QUEUED_DOWNLOAD: 'В очереди',
    ST_DOWNLOADING: 'Скачивается',
    ST_NEEDS_RESOLVE: 'Обновление ссылки...',
    ST_PAUSED: 'Приостановлено',
    ST_DELETING: 'Удаление...',
    ST_PROCESSING: 'Обработка файла...',
    ST_FINISHED: '✓ Готово',
    ST_ERROR: 'Ошибка загрузки',
    ST_CANCELLED: 'Остановлено',
}

STATUS_SHORT = {
    ST_QUEUED: 'В очереди',
    ST_PREPARING: 'Подготовка',
    ST_QUEUED_DOWNLOAD: 'В очереди',
    ST_DOWNLOADING: 'Скачивается',
    ST_NEEDS_RESOLVE: 'Ссылка',
    ST_PAUSED: 'Пауза',
    ST_DELETING: 'Удаление',
    ST_PROCESSING: 'Обработка',
    ST_FINISHED: '✓ Готово',
    ST_ERROR: 'Ошибка',
    ST_CANCELLED: 'Остановлено',
}

# Коды, означающие «прямая ссылка протухла»: их лечит свежий resolve.
EXPIRED_CODES = (401, 403, 404, 410)
MAX_URL_REFRESH = 3


class DirectUrlExpired(Exception):
    """Прямая ссылка перестала работать. Обычное исключение, не yt-dlp."""
    pass

# Параметры прямой HTTP-загрузки.
HTTP_CHUNK = 256 * 1024
HTTP_TIMEOUT = 60
HTTP_RETRIES = 6
HTTP_RETRY_PAUSE = 3.0
SPEED_WINDOW = 1.2          # окно усреднения скорости, секунды

# Протоколы, которые наш простой загрузчик тянуть не умеет: они собираются
# из сегментов и потребовали бы ffmpeg.
SEGMENTED_HINTS = ('m3u8', 'dash', 'ism', 'f4m')


def sanitize_filename(text, limit=120):
    """Имя файла из названия: те же <title> [<id>].mp4, но безопасно."""
    text = (text or '').strip()
    text = re.sub(r'[\\/:*?"<>|]', '_', text)
    text = re.sub(r'[\x00-\x1f]', '', text)
    text = re.sub(r'\s+', ' ', text).strip(' .')
    if not text:
        text = 'video'
    return text[:limit].strip(' .')


def target_path_for(title, video_id, ext):
    ext = sanitize_filename(str(ext or 'mp4'), 8).lstrip('.') or 'mp4'
    name = sanitize_filename(title)
    raw_id = str(video_id or '').strip()
    # Пустой id не должен превращаться в подставное имя.
    vid = sanitize_filename(raw_id, 40) if raw_id else ''
    stem = '%s [%s]' % (name, vid) if vid else name
    return os.path.join(MEDIA_DIR, '%s.%s' % (stem, ext))


VK_DIRECT_RE = re.compile(r'url(?:144|240|360|480|720|1080|1440|2160)')


def _is_vk_direct(fmt):
    """
    Прямой VK-формат urlXXX. Кодеки у него в info часто отсутствуют
    (yt-dlp -F показывает их как unknown), и это НЕ означает, что поток
    без звука: yt-dlp -f url480 такой файл качает целиком. Поэтому для
    urlXXX проверка кодеков не применяется.
    """
    if not isinstance(fmt, dict):
        return False
    fid = str(fmt.get('format_id') or '')
    if not VK_DIRECT_RE.fullmatch(fid):
        return False
    url = fmt.get('url')
    if not isinstance(url, str) or not url.startswith(('http://', 'https://')):
        return False
    proto = str(fmt.get('protocol') or '').lower()
    for hint in SEGMENTED_HINTS:
        if hint in proto:
            return False
    return True


def _is_combined(fmt):
    """Готовый файл со звуком: и видео, и аудио, и обычный HTTP."""
    if not isinstance(fmt, dict):
        return False
    url = fmt.get('url')
    if not isinstance(url, str) or not url.startswith('http'):
        return False
    vcodec = (fmt.get('vcodec') or 'none').lower()
    acodec = (fmt.get('acodec') or 'none').lower()
    if vcodec == 'none' or acodec == 'none':
        return False           # video-only / audio-only — склеивать нечем
    proto = (fmt.get('protocol') or '').lower()
    blob = proto + ' ' + str(fmt.get('format_id') or '').lower()
    for hint in SEGMENTED_HINTS:
        if hint in blob:
            return False       # сегментные потоки требуют ffmpeg
    return True


def _fmt_height(fmt):
    h = fmt.get('height')
    if isinstance(h, (int, float)) and h > 0:
        return int(h)
    m = re.search(r'(\d{3,4})', str(fmt.get('format_id') or ''))
    if m:
        try:
            return int(m.group(1))
        except Exception:
            return 0
    return 0


def pick_direct_format(info, quality):
    """
    Прямой combined-формат: сначала VK urlXXX по лестнице качества,
    затем лучший combined в пределах нужной высоты. Video-only и
    audio-only не выбираются никогда — склеивать их нечем.
    """
    entry = _entry_of(info)
    formats = entry.get('formats')
    if not isinstance(formats, list):
        formats = []

    # 1) Прямые VK urlXXX ищем в ИСХОДНОМ списке и раньше проверки кодеков:
    #    отсутствующие vcodec/acodec у них — норма, а не признак video-only.
    vk_by_id = {}
    for f in formats:
        if _is_vk_direct(f):
            vk_by_id.setdefault(str(f.get('format_id')), f)
    for fid in VK_DIRECT.get(quality, []):
        if fid in vk_by_id:
            return vk_by_id[fid]

    # 2) Иначе обычный combined-путь, где кодеки проверяются строго.
    combined = [f for f in formats if _is_combined(f)]
    if not combined and _is_combined(entry):
        combined = [entry]          # у некоторых экстракторов формат один
    if not combined:
        return None
    cap = HEIGHTS.get(quality)
    pool = combined
    if cap:
        limited = [f for f in combined if 0 < _fmt_height(f) <= cap]
        pool = limited or [f for f in combined if _fmt_height(f) == 0] or combined
    def rank(f):
        return (_fmt_height(f),
                f.get('tbr') if isinstance(f.get('tbr'), (int, float)) else 0,
                f.get('filesize') or f.get('filesize_approx') or 0)
    return sorted(pool, key=rank)[-1]


class DownloadJob(object):
    _seq = 0

    def __init__(self, url, quality):
        DownloadJob._seq += 1
        self.id = 'job%d' % DownloadJob._seq
        self.url = url
        self.quality = quality
        self.title = ''
        self.status = ST_QUEUED
        self.downloaded_bytes = 0
        self.total_bytes = None
        self.total_bytes_estimate = None
        self.speed = None
        self.eta = None
        self.filename = ''
        self.error = ''
        self.debug_error = ''      # техническая причина, без консоли
        # Телеметрия этапа: только присваивание Python-строки, ни файлов,
        # ни консоли, ни потоков.
        self.debug_stage = 'resolve-pending'
        self.debug_stage_time = time.monotonic()
        self.error_stage = ''      # этап, на котором реально упало
        self.first_hook_received = False
        # Результат resolve: прямой HTTPS-адрес и всё для его скачивания.
        self.resolved_url = ''
        self.resolved_headers = {}
        self.resolved_format_id = ''
        self.resolved_ext = 'mp4'
        # expected_size приходит из yt-dlp и может быть filesize_approx —
        # ПРИБЛИЗИТЕЛЬНЫМ. Проверять по нему целостность файла нельзя.
        self.expected_size = None
        # Точный размер, полученный от самого сервера (Content-Length или
        # хвост Content-Range). Только он годится для проверки перед rename.
        self.exact_total = None
        self.video_id = ''
        # Состояние дополнительных функций отдельно от статуса видео:
        # их сбой никогда не переводит само задание в error.
        self.completed_info = {}
        self.extras_status = EXTRAS_NONE
        self.extras_error = ''
        self.thumbnail_error = ''
        self.metadata_ready = False
        self.thumbnail_ready = False
        self.started_at = time.time()
        self.finished_at = None
        # Три РАЗНЫХ намерения, а не одно: остановить поток, встать на паузу,
        # удалить загрузку целиком. Пауза не равна отмене и не равна удалению.
        self.cancel_requested = False
        self.pause_requested = False
        self.delete_requested = False
        # Сколько раз уже обновляли протухшую прямую ссылку.
        self.refresh_resolve_attempts = 0
        self.restored = False

    def set_stage(self, stage):
        self.debug_stage = stage
        self.debug_stage_time = time.monotonic()

    @property
    def part_path(self):
        return (self.filename + '.part') if self.filename else ''

    @property
    def total(self):
        """
        Точный размер от сервера, иначе то, что сказал yt-dlp, иначе None.
        Ничего не выдумываем. Значения остаются обычными int Python —
        у него нет 32-битного переполнения, и файл на 20 ГБ считается так же
        точно, как на 20 МБ.
        """
        for v in (self.exact_total, self.total_bytes,
                  self.total_bytes_estimate, self.expected_size):
            try:
                if v and float(v) > 0:
                    return int(v) if float(v).is_integer() else float(v)
            except Exception:
                continue
        return None

    @property
    def percent(self):
        total = self.total
        if not total:
            return None
        return max(0.0, min(1.0, float(self.downloaded_bytes) / total))

    @property
    def is_active(self):
        return self.status in ACTIVE_STATES

    @property
    def needs_resolve(self):
        return self.status == ST_QUEUED and not self.resolved_url

    @property
    def needs_refresh(self):
        """Прямая ссылка протухла: нужен свежий resolve на главном потоке."""
        return self.status == ST_NEEDS_RESOLVE

    @property
    def is_managed(self):
        """Заданием владеет менеджер: его .part — не «ничей» файл."""
        return self.status in MANAGED_STATES

    @property
    def can_pause(self):
        return self.status in (ST_QUEUED, ST_PREPARING, ST_QUEUED_DOWNLOAD,
                               ST_DOWNLOADING, ST_NEEDS_RESOLVE)

    @property
    def can_resume(self):
        return self.status in (ST_PAUSED, ST_ERROR, ST_CANCELLED)

    def action_icon(self):
        """Какая кнопка нужна карточке: пауза, продолжение или никакой."""
        if self.can_pause:
            return 'pause'
        if self.can_resume:
            return 'play'
        return ''

    @property
    def display_title(self):
        if self.title:
            return self.title
        if self.filename:
            return os.path.splitext(os.path.basename(self.filename))[0]
        return self.url

    def sub_line(self):
        """Первая строка карточки — то, что реально известно."""
        if self.status == ST_DOWNLOADING:
            total = self.total
            if total:
                return 'Скачивается  •  %s / %s' % (
                    fmt_size(self.downloaded_bytes), fmt_size(total))
            return 'Скачивается  •  %s' % fmt_size(self.downloaded_bytes)
        if self.status == ST_PAUSED:
            # Пауза показывает уже скачанное: это реальный размер .part.
            total = self.total
            if total:
                return 'Приостановлено  •  %s / %s' % (
                    fmt_size(self.downloaded_bytes), fmt_size(total))
            if self.downloaded_bytes:
                return 'Приостановлено  •  %s' % fmt_size(self.downloaded_bytes)
            return STATUS_TEXT[ST_PAUSED]
        if self.status == ST_ERROR:
            return 'Ошибка: ' + (self.error or 'не удалось скачать')
        return STATUS_TEXT.get(self.status, '')

    def diag_line(self):
        """
        Временная телеметрия: если задание висит в подготовке дольше
        DIAG_AFTER, существующая строка карточки показывает точный этап,
        время в нём и состояние рабочего потока. Как только пошла обычная
        загрузка, строка исчезает сама.
        """
        if self.status not in (ST_QUEUED, ST_PREPARING, ST_QUEUED_DOWNLOAD):
            return ''
        try:
            elapsed = time.monotonic() - float(self.debug_stage_time or 0.0)
        except Exception:
            return ''
        if elapsed <= DIAG_AFTER:
            return ''
        alive = 'alive' if DOWNLOADER.worker_alive() else 'dead'
        return 'Диагностика: %s · %.1f c · worker %s' % (
            self.debug_stage, elapsed, alive)

    def tech_line(self):
        """
        Временная техническая строка ошибки: этап, на котором упало, и
        полный repr исключения. Полностью, без обрезки, она же уходит в
        NOX_Data/download_debug.txt.
        """
        if self.status != ST_ERROR:
            return ''
        parts = [p for p in (self.error_stage, self.debug_error) if p]
        return ' · '.join(parts)

    def detail_line(self):
        """Вторая строка: процент, скорость, остаток — только реальные."""
        diag = self.diag_line()
        if diag:
            return diag
        tech = self.tech_line()
        if tech:
            return tech
        if self.status != ST_DOWNLOADING:
            return ''
        bits = []
        pct = self.percent
        if pct is not None:
            bits.append('%d%%' % int(pct * 100))
        sp = fmt_speed(self.speed)
        if sp:
            bits.append(sp)
        eta = fmt_eta(self.eta)
        if eta:
            bits.append('осталось ' + eta)
        return '  ·  '.join(bits)

    def short_status(self):
        if self.status == ST_DOWNLOADING:
            pct = self.percent
            if pct is not None:
                return '%d%%' % int(pct * 100)
        return STATUS_SHORT.get(self.status, '')


class DownloadManager(object):
    """
    Разбор ссылки и сама загрузка разделены.

    resolve() выполняется на ГЛАВНОМ потоке и только там: на устройстве
    yt_dlp.extract_info внутри фонового потока валил Pythonista нативно,
    тогда как тот же URL на главном потоке проходит. Рабочий поток
    получает уже готовый прямой HTTPS-адрес и качает его обычным urllib —
    yt-dlp он не импортирует и не вызывает вообще.
    """

    def __init__(self):
        self.jobs = []
        self.revision = 0        # меняется при структурных изменениях
        self.tick = 0            # меняется на каждом обновлении прогресса
        # Счётчик «состав очереди надо сохранить на диск». Растёт и из
        # рабочего потока, но сам файл пишет только главный: так state.json
        # никогда не пишется двумя потоками сразу.
        self.dirty = 0
        self._lock = threading.RLock()
        self._thread = None

    def mark_dirty(self):
        self.dirty += 1

    # -- чтение состояния ------------------------------------------
    def all_jobs(self):
        with self._lock:
            return list(self.jobs)

    def active_jobs(self):
        return [j for j in self.all_jobs() if j.is_active]

    def visible_jobs(self):
        """
        Активные, приостановленные, ошибочные и остановленные плюс те, что
        только что завершились: карточка ~0.8 c показывает «Готово» и уходит.
        Рабочий поток этим не задерживается — это чисто состояние UI.
        """
        now = time.time()
        out = []
        for j in self.all_jobs():
            if j.is_active or j.status in (ST_PAUSED, ST_ERROR, ST_CANCELLED):
                out.append(j)
            elif j.status == ST_FINISHED and \
                    now - float(j.finished_at or 0) < DONE_LINGER:
                out.append(j)
        return out

    def active_paths(self):
        return [j.filename for j in self.active_jobs() if j.filename]

    def managed_paths(self):
        """
        Файлы, за которыми стоит живая карточка: не только качающиеся сейчас,
        но и поставленные на паузу, упавшие и остановленные. Их .part не
        должен вторым экземпляром показываться строкой «Не завершено».
        """
        return [j.filename for j in self.all_jobs()
                if j.filename and j.is_managed]

    def find(self, job_id):
        for j in self.all_jobs():
            if j.id == job_id:
                return j
        return None

    def worker_alive(self):
        """Только для главного потока: жив ли поток загрузки."""
        t = self._thread
        try:
            return bool(t is not None and t.is_alive())
        except Exception:
            return False

    def pending_resolve(self):
        """Новые ссылки и те, чей прямой адрес протух, — вперемешку."""
        return [j for j in self.all_jobs()
                if j.needs_resolve or j.needs_refresh]

    def awake_needed(self):
        """Идёт ли сейчас работа, ради которой экран не должен гаснуть."""
        return any(j.status in AWAKE_STATES for j in self.all_jobs())

    def signature(self):
        """
        Структурный отпечаток очереди: ТОЛЬКО состав видимых заданий.

        Статус сюда не входит намеренно: переходы между подготовкой и
        загрузкой меняют лишь содержимое уже существующей карточки, и
        полный rebuild четырёх экранов на них не нужен.
        """
        return tuple(j.id for j in self.visible_jobs())

    # -- изменение состояния ---------------------------------------
    def add(self, url, quality):
        url, err = validate_url(url)
        if err:
            return False, err
        if quality not in [q[0] for q in QUALITIES]:
            return False, 'Такое качество недоступно'
        if not ytdlp_ready():
            return False, YTDLP_ERROR or 'Модуль yt-dlp не найден'
        if not ensure_dirs():
            return False, 'Папка NOX недоступна'
        with self._lock:
            for j in self.jobs:
                if j.url == url and j.is_active:
                    return False, 'Эта ссылка уже в очереди'
            job = DownloadJob(url, quality)
            self.jobs.append(job)
            self.revision += 1
        self.mark_dirty()
        # Поток здесь НЕ запускается: сначала разбор ссылки на главном потоке.
        return True, 'Добавлено в очередь'

    # -- пауза, продолжение, удаление ------------------------------
    def pause(self, job_id):
        """
        Пауза — это НЕ отмена и НЕ удаление. Файл .part остаётся целиком,
        задание живо, очередь освобождается для следующего.
        """
        job = self.find(job_id)
        if job is None or not job.can_pause:
            return False
        job.pause_requested = True
        if job.status == ST_DOWNLOADING and self.worker_alive():
            # Останавливает рабочий поток сам цикл чтения: он закроет
            # соединение и файл штатно, .part не тронет.
            pass
        else:
            # Ни соединения, ни файла ещё нет — переводим сразу.
            job.status = ST_PAUSED
            job.speed = None
            job.eta = None
        with self._lock:
            self.revision += 1
        self.tick += 1
        self.mark_dirty()
        return True

    def resume(self, job_id):
        """
        Продолжение всегда идёт через СВЕЖИЙ resolve: прямая ссылка живёт
        считанные часы, а .part может пролежать сутки. Уже скачанные байты
        не теряются — их докачает Range.
        """
        job = self.find(job_id)
        if job is None or not job.can_resume:
            return False
        job.pause_requested = False
        job.cancel_requested = False
        job.delete_requested = False
        job.error = ''
        job.debug_error = ''
        job.error_stage = ''
        job.speed = None
        job.eta = None
        job.finished_at = None
        job.refresh_resolve_attempts = 0
        # Ссылку получаем заново; имя файла и .part сохраняем как есть.
        job.resolved_url = ''
        job.exact_total = None
        job.total_bytes = None
        job.downloaded_bytes = self.part_size(job)
        job.status = ST_QUEUED
        job.set_stage('resume-queued')
        with self._lock:
            self.revision += 1
        self.tick += 1
        self.mark_dirty()
        return True

    @staticmethod
    def part_size(job):
        """Сколько реально лежит на диске. Единственный источник правды."""
        part = job.part_path
        if not part:
            return 0
        try:
            return int(os.path.getsize(part))
        except Exception:
            return 0

    @staticmethod
    def _remove_part(job):
        """Удаление недокачанного файла. Только файлы, ничего из UI."""
        part = job.part_path
        if not part:
            return False
        try:
            if os.path.exists(part):
                os.remove(part)
                return True
        except Exception:
            pass
        return False

    def _drop_job(self, job_id):
        with self._lock:
            before = len(self.jobs)
            self.jobs = [j for j in self.jobs if j.id != job_id]
            if len(self.jobs) != before:
                self.revision += 1
        self.mark_dirty()

    def delete(self, job_id):
        """
        Крестик = удалить загрузку ПОЛНОСТЬЮ: остановить передачу, дождаться,
        пока рабочий поток закроет соединение и файл, удалить .part, снять
        задание и его сохранённое состояние.

        Если поток прямо сейчас пишет в файл, удаляем не отсюда: задание
        уходит в ST_DELETING, а .part убирает сам поток, когда закроет
        дескриптор. Иначе на iOS можно получить недописанный «висячий» файл.
        """
        job = self.find(job_id)
        if job is None:
            return False
        job.delete_requested = True
        job.cancel_requested = True
        job.pause_requested = False
        if job.status == ST_DOWNLOADING and self.worker_alive():
            job.status = ST_DELETING
            job.speed = None
            job.eta = None
            with self._lock:
                self.revision += 1
            self.tick += 1
            return True
        # Ни один поток этот файл не держит — удаляем прямо сейчас.
        self._remove_part(job)
        self._drop_job(job_id)
        self.tick += 1
        self._pump()
        return True

    def clear_finished(self):
        """Убирает завершённые, чья отметка «Готово» уже отвисела."""
        now = time.time()
        with self._lock:
            before = len(self.jobs)
            self.jobs = [j for j in self.jobs
                         if j.status != ST_FINISHED
                         or now - float(j.finished_at or 0) < DONE_LINGER]
            if len(self.jobs) != before:
                self.revision += 1

    # -- сохранение очереди между запусками ------------------------
    def snapshot(self):
        """
        Что имеет смысл пережить перезапуск. Прямая ссылка НЕ сохраняется:
        она протухает, и после запуска её всё равно берут заново.
        """
        out = []
        for j in self.all_jobs():
            if j.status in (ST_FINISHED, ST_DELETING):
                continue
            if not j.filename:
                continue        # ссылку ещё не разобрали — восстанавливать нечего
            out.append({
                'id': j.id,
                'url': j.url,
                'quality': j.quality,
                'title': j.title,
                'filename': j.filename,
                'video_id': j.video_id,
                'format_id': j.resolved_format_id,
                'ext': j.resolved_ext,
                'expected_size': j.expected_size,
                'started_at': j.started_at,
            })
        return out

    def persist(self, state):
        """Пишет ТОЛЬКО главный поток и только по структурным событиям."""
        try:
            state.set(JOBS_KEY, self.snapshot())
            return True
        except Exception:
            return False

    def restore(self, state):
        """
        Восстановление после запуска NOX.

        Правила простые и честные: источник правды — файл .part на диске.
        Есть .part — задание оживает как приостановленное. Нет — записи
        не остаётся. Ничего не стартует само: продолжение всегда нажимает
        человек, и оно всегда идёт через свежий resolve.
        """
        try:
            raw = state.get(JOBS_KEY)
        except Exception:
            raw = None
        if not isinstance(raw, list):
            return 0
        known = set(self.managed_paths())
        restored = 0
        for entry in raw:
            if not isinstance(entry, dict):
                continue
            url = entry.get('url')
            filename = entry.get('filename')
            if not url or not filename:
                continue
            if filename in known:
                continue        # у этого файла уже есть живая карточка
            known.add(filename)
            part = str(filename) + '.part'
            try:
                size = int(os.path.getsize(part))
            except Exception:
                continue        # файла нет — восстанавливать нечего
            if size <= 0:
                continue
            quality = entry.get('quality')
            if quality not in [q[0] for q in QUALITIES]:
                quality = STATE.get('quality', '720')
            job = DownloadJob(str(url), quality)
            job.restored = True
            job.filename = str(filename)
            job.title = str(entry.get('title') or '')
            job.video_id = str(entry.get('video_id') or '')
            job.resolved_format_id = str(entry.get('format_id') or '')
            job.resolved_ext = str(entry.get('ext') or 'mp4')
            size_hint = entry.get('expected_size')
            if isinstance(size_hint, (int, float)) and size_hint > 0:
                job.expected_size = size_hint
            try:
                job.started_at = float(entry.get('started_at') or time.time())
            except Exception:
                job.started_at = time.time()
            job.downloaded_bytes = size
            job.status = ST_PAUSED
            job.set_stage('restored-paused')
            with self._lock:
                self.jobs.append(job)
                self.revision += 1
            restored += 1
        if restored:
            self.mark_dirty()
        return restored

    # -- РАЗБОР ССЫЛКИ: только главный поток ------------------------
    def resolve_opts(self):
        """Минимальный набор: разбор ничего не качает и не пишет."""
        return {
            'quiet': True,
            'no_warnings': True,
            'noplaylist': True,
            'socket_timeout': 60,
            'retries': 5,
            'fragment_retries': 5,
            'ffmpeg_location': NO_FFMPEG_PATH,
            'fixup': 'never',
        }

    def resolve(self, job):
        """
        Единственный вызов yt-dlp во всём приложении, и всегда с
        download=False. Может подморозить интерфейс на несколько секунд —
        это осознанный размен на надёжность.
        """
        refreshing = job.needs_refresh
        if not (job.needs_resolve or refreshing):
            return False
        if refreshing:
            job.refresh_resolve_attempts += 1
            if job.refresh_resolve_attempts > MAX_URL_REFRESH:
                job.status = ST_ERROR
                job.error = 'Не удалось обновить ссылку'
                job.error_stage = 'refresh-give-up'
                job.debug_error = ('refresh: %d попыток подряд не дали рабочую '
                                   'ссылку' % (job.refresh_resolve_attempts - 1))
                job.finished_at = time.time()
                with self._lock:
                    self.revision += 1
                self.tick += 1
                self.mark_dirty()
                return False
        job.status = ST_PREPARING
        job.set_stage('refresh-enter' if refreshing else 'resolve-enter')
        self.tick += 1
        try:
            mod = _load_yt_dlp()
            if mod is None:
                raise RuntimeError(YTDLP_ERROR or 'Модуль yt-dlp не найден')
            with mod.YoutubeDL(self.resolve_opts()) as ydl:
                info = ydl.extract_info(job.url, download=False)
            job.set_stage('resolve-returned')
            fmt = pick_direct_format(info, job.quality)
            if not fmt:
                raise RuntimeError('Нет прямого формата со звуком')
            entry = _entry_of(info)
            title = entry.get('title')
            if isinstance(title, str) and title.strip():
                job.title = title.strip()
            job.resolved_url = fmt.get('url')
            headers = fmt.get('http_headers') or entry.get('http_headers') or {}
            job.resolved_headers = {str(k): str(v) for k, v in
                                    dict(headers).items()}
            job.resolved_format_id = str(fmt.get('format_id') or '')
            job.resolved_ext = str(fmt.get('ext') or entry.get('ext') or 'mp4')
            size = fmt.get('filesize') or fmt.get('filesize_approx')
            job.expected_size = size if isinstance(size, (int, float)) else None
            snap = safe_metadata_snapshot(info)
            # В метаданные попадает то, что реально скачано, а не первый
            # формат из общего info.
            snap['format_id'] = job.resolved_format_id
            snap['ext'] = job.resolved_ext
            for key in ('width', 'height'):
                value = fmt.get(key)
                if isinstance(value, (int, float)) and value > 0:
                    snap[key] = int(value)
            if job.expected_size:
                snap['filesize'] = job.expected_size
            job.completed_info = snap
            job.video_id = str(entry.get('id') or '')
            # Имя файла выбирается ОДИН раз. При обновлении протухшей ссылки
            # и при продолжении после паузы оно обязано совпасть с уже
            # лежащим .part, иначе докачивать будет нечего.
            if not job.filename:
                job.filename = target_path_for(
                    job.title or entry.get('id') or 'video',
                    entry.get('id'), job.resolved_ext)
            job.set_stage('format-selected')
            job.status = ST_QUEUED_DOWNLOAD
            job.set_stage('http-queued')
            self.mark_dirty()
        except Exception as e:
            job.error_stage = job.debug_stage
            job.set_stage('exception')
            job.status = ST_ERROR
            job.error = short_error(e)
            job.debug_error = 'resolve: %r' % (e,)
            job.finished_at = time.time()
            with self._lock:
                self.revision += 1
            self.tick += 1
            self.mark_dirty()
            return False
        with self._lock:
            self.revision += 1
        self.tick += 1
        self._pump()
        return True

    # -- рабочий поток: только HTTP --------------------------------
    def _pump(self):
        """
        Запускает следующее разобранное задание, если поток свободен.
        Вызывается из resolve/cancel и из самого потока по завершении,
        поэтому перестроение интерфейса второй поток создать не может.
        """
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            nxt = None
            for j in self.jobs:
                # Приостановленное задание очередь не занимает: у него статус
                # ST_PAUSED, и следующее за ним стартует как обычно.
                if j.status == ST_QUEUED_DOWNLOAD and not j.cancel_requested \
                        and not j.pause_requested and not j.delete_requested:
                    nxt = j
                    break
            if nxt is None:
                self._thread = None
                return
            nxt.set_stage('before-thread-create')
            self._thread = threading.Thread(target=self._run, args=(nxt,),
                                            name='nox-download', daemon=True)
            self._thread.start()
            # Поток мог успеть шагнуть дальше — не затираем более поздний этап.
            if nxt.debug_stage == 'before-thread-create':
                nxt.set_stage('thread-start-called')

    def _note_progress(self, job, state, chunk_len):
        """Скорость по окну ~1.2 c, а не по одному блоку."""
        job.downloaded_bytes += chunk_len
        state['bytes'] += chunk_len
        now = time.monotonic()
        delta = now - state['time']
        if delta < SPEED_WINDOW:
            return
        speed = state['bytes'] / delta if delta > 0 else 0.0
        job.speed = speed if speed > 0 else None
        total = job.total
        if total and job.speed:
            remain = max(0.0, total - job.downloaded_bytes)
            job.eta = int(remain / job.speed)
        else:
            job.eta = None
        state['bytes'] = 0
        state['time'] = now
        self.tick += 1

    def _run(self, job):
        """
        Рабочий поток. yt-dlp здесь не импортируется и не вызывается:
        только urllib, файлы и поля DownloadJob. Ни print, ни console,
        ни ui.
        """
        job.set_stage('worker-entered')
        final = job.filename
        part = job.part_path
        state = {'bytes': 0, 'time': time.monotonic()}
        last_error = None
        expired = False
        try:
            for attempt in range(1, HTTP_RETRIES + 1):
                if job.cancel_requested or job.pause_requested:
                    break
                if attempt > 1:
                    job.set_stage('http-retry')
                    self.tick += 1
                    slept = 0.0
                    while slept < HTTP_RETRY_PAUSE and not job.cancel_requested \
                            and not job.pause_requested:
                        time.sleep(0.2)
                        slept += 0.2
                    if job.cancel_requested or job.pause_requested:
                        break
                try:
                    done = self._transfer(job, part, state)
                except DirectUrlExpired as e:
                    # Ссылка протухла. Лечит это только новый resolve, а он
                    # живёт на главном потоке: yt-dlp здесь по-прежнему
                    # не импортируется и не вызывается.
                    expired = True
                    last_error = None
                    detail = 'expired: %r' % (e,)
                    job.debug_error = ((job.debug_error + ' | ' + detail)
                                       if job.debug_error else detail)
                    break
                except Exception as e:
                    last_error = e
                    if job.cancel_requested or job.pause_requested:
                        break
                    if not is_network_error(e) and attempt >= 2:
                        raise
                    continue
                if done:
                    last_error = None
                    break
            if job.delete_requested:
                job.set_stage('deleting')            # .part уберём в finally
            elif expired:
                job.resolved_url = ''
                job.status = ST_NEEDS_RESOLVE
                job.set_stage('needs-resolve')
            elif job.pause_requested:
                job.status = ST_PAUSED               # .part остаётся целиком
                job.set_stage('paused')
            elif job.cancel_requested:
                job.status = ST_CANCELLED            # .part остаётся
            elif last_error is not None:
                raise last_error
            else:
                # Переименование — только после сверки с точным размером.
                self._verify_size(job, part)
                os.replace(part, final)
                job.set_stage('http-finished')
                job.status = ST_FINISHED
                job.set_stage('finished')
        except Exception as e:
            if job.delete_requested:
                job.set_stage('deleting')
            elif job.pause_requested:
                job.status = ST_PAUSED
            elif job.cancel_requested:
                job.status = ST_CANCELLED
            else:
                job.error_stage = job.debug_stage
                job.set_stage('exception')
                job.status = ST_ERROR
                job.error = short_error(e)
                detail = 'http: %r' % (e,)
                job.debug_error = ((job.debug_error + ' | ' + detail)
                                   if job.debug_error else detail)
        finally:
            if job.delete_requested:
                # Соединение закрыто, файл закрыт — только теперь удаление
                # недокачанного файла безопасно.
                self._remove_part(job)
                self._drop_job(job.id)
            if job.status in (ST_FINISHED, ST_ERROR, ST_CANCELLED):
                job.finished_at = time.time()
            job.speed = None
            job.eta = None
            with self._lock:
                self.revision += 1
                self._thread = None
            self.tick += 1
            self.mark_dirty()
            try:
                self._pump()
            except Exception as e:
                job.debug_error = (job.debug_error + ' | ') if job.debug_error else ''
                job.debug_error += 'pump: %r' % (e,)

    @staticmethod
    def _verify_size(job, part):
        """
        Последняя проверка перед rename: столько ли байт на диске, сколько
        обещал СЕРВЕР. Участвует только exact_total — Content-Length или
        хвост Content-Range. filesize_approx из yt-dlp сюда не попадает:
        по приблизительному числу целостность не проверяют.
        """
        total = job.exact_total
        if not total:
            return
        try:
            got = int(os.path.getsize(part))
        except Exception:
            raise IOError('файл загрузки исчез до переименования')
        if got != int(total):
            raise IOError('размер не сошёлся: %d из %d байт' % (got, int(total)))

    def _transfer(self, job, part, state):
        """
        Одна попытка передачи. Возвращает True, если файл дошёл до конца.
        Докачивает через Range; если сервер Range проигнорировал — файл
        начинается заново, чтобы не получить битый MP4.
        """
        job.set_stage('http-opening')
        offset = self.part_size(job)
        headers = dict(job.resolved_headers or {})
        headers.setdefault('User-Agent', 'NOX/1.0')
        if offset > 0:
            headers['Range'] = 'bytes=%d-' % offset
        req = urllib.request.Request(job.resolved_url, headers=headers)
        try:
            resp = urllib.request.urlopen(req, timeout=HTTP_TIMEOUT)
        except urllib.error.HTTPError as e:
            code = _int_or_none(getattr(e, 'code', None)) or 0
            if code == 416:
                return self._handle_416(job, offset, e)
            if code in EXPIRED_CODES:
                # 429, таймауты и 5xx сюда НЕ попадают: это обычные сбои,
                # их лечит обычный повтор, а не новый разбор ссылки.
                raise DirectUrlExpired('HTTP %d' % code)
            raise
        try:
            code = resp.getcode()
            mode = 'wb'
            total = None
            if offset > 0 and code == 206:
                mode = 'ab'
                total = _total_from_content_range(
                    resp.headers.get('Content-Range'))
                if total is None:
                    length = _int_or_none(resp.headers.get('Content-Length'))
                    total = (offset + length) if length else None
            else:
                # 200 на запрос с Range означает, что сервер его не понял:
                # дописывать к старому файлу нельзя, начинаем заново.
                offset = 0
                total = _int_or_none(resp.headers.get('Content-Length'))
            job.downloaded_bytes = offset
            if total:
                # Размер пришёл от сервера — он точный, в отличие от
                # filesize_approx, и именно по нему проверяем файл в конце.
                job.total_bytes = int(total)
                job.exact_total = int(total)
            state['bytes'] = 0
            state['time'] = time.monotonic()
            job.status = ST_DOWNLOADING
            job.set_stage('http-downloading')
            self.tick += 1
            with io.open(part, mode) as f:
                while True:
                    if job.cancel_requested or job.pause_requested:
                        # Выход из with закрывает файл штатно: на диске
                        # остаётся ровно то, что успели дописать.
                        return False
                    chunk = resp.read(HTTP_CHUNK)
                    if not chunk:
                        break
                    f.write(chunk)
                    self._note_progress(job, state, len(chunk))
        finally:
            try:
                resp.close()
            except Exception:
                pass
        if job.cancel_requested or job.pause_requested:
            return False
        total = job.exact_total or job.total_bytes
        if total and job.downloaded_bytes < total:
            raise IOError('соединение оборвалось: %d из %d байт'
                          % (job.downloaded_bytes, int(total)))
        return True

    def _handle_416(self, job, offset, err):
        """
        416 Range Not Satisfiable. В заголовке приходит 'bytes */TOTAL'.

        Если на диске уже лежит ровно TOTAL байт — файл дошёл до конца, и
        серверу просто нечего отдать: это успех. Если .part больше или
        меньше, он источнику не соответствует, и его надо качать заново.
        """
        try:
            total = _total_from_content_range(err.headers.get('Content-Range'))
        except Exception:
            total = None
        if total and offset == int(total):
            job.exact_total = int(total)
            job.total_bytes = int(total)
            job.downloaded_bytes = offset
            job.set_stage('http-416-complete')
            self.tick += 1
            return True
        self._remove_part(job)
        job.downloaded_bytes = 0
        job.exact_total = None
        job.total_bytes = None
        job.set_stage('http-416-restart')
        self.tick += 1
        raise IOError('сервер отклонил докачку (416), файл будет скачан заново')


def _int_or_none(value):
    try:
        n = int(str(value).strip())
        return n if n > 0 else None
    except Exception:
        return None


def _total_from_content_range(value):
    """'bytes 1000-1999/500000000' -> 500000000."""
    if not value:
        return None
    m = re.search(r'/\s*(\d+)\s*$', str(value))
    if not m:
        return None
    return _int_or_none(m.group(1))


def short_error(exc):
    """Короткий человеческий текст. Технический repr живёт в job.debug_error."""
    text = str(exc or '').strip()
    text = re.sub(r'\x1b\[[0-9;]*m', '', text)
    text = re.sub(r'^ERROR:\s*', '', text)
    text = text.split('\n')[0].strip()
    low = text.lower()
    if not text:
        return 'Не удалось скачать'
    if 'unsupported url' in low or 'no video' in low:
        return 'Ссылка не поддерживается'
    if 'requested format' in low:
        return 'Такое качество недоступно'
    if 'private' in low or 'login' in low or 'sign in' in low:
        return 'Видео требует входа в аккаунт'
    if 'timed out' in low or 'timeout' in low:
        return 'Превышено время ожидания'
    if 'name or service not known' in low or 'urlopen error' in low \
            or 'connection' in low or 'network' in low:
        return 'Нет соединения с сервером'
    if 'no space left' in low:
        return 'Недостаточно места'
    return safe_name(text, 60)


DOWNLOADER = DownloadManager()


# =====================================================================
#  ОБЩИЕ БЛОКИ ИНТЕРФЕЙСА
# =====================================================================

_ICON_CACHE = {'loaded': False, 'image': None}


def load_project_icon():
    """
    NOX_icon.png рядом с NOX.py, если пользователь его положил.
    Файла нет — возвращаем None, интерфейс просто рисует шар как раньше.
    Никаких подставных иконок не создаётся.
    """
    if _ICON_CACHE['loaded']:
        return _ICON_CACHE['image']
    _ICON_CACHE['loaded'] = True
    img = None
    if os.path.isfile(ICON_PATH):
        # Тоже только как данные: ui.Image.named в файле не используется.
        try:
            with io.open(ICON_PATH, 'rb') as f:
                img = ui.Image.from_data(f.read())
        except Exception:
            img = None
    _ICON_CACHE['image'] = img
    return img


def build_header(width, y=0.0):
    """Шапка NOX + статус справа. Возвращает (view, height)."""
    h = 58.0
    v = ui.View(frame=(0, y, width, h))
    v.background_color = 'clear'

    logo = make_label(spaced('NOX', ' '), (F_BOLD, 27), TXT,
                      frame=(PAD, 2, 220, 32))
    v.add_subview(logo)

    sub = make_label(spaced('офлайн-медиатека'), (F_REG, 7.5), TXT_3,
                     frame=(PAD + 2, 33, 260, 12))
    v.add_subview(sub)

    orb_size = 32.0
    txt_w = 108.0
    right_w = orb_size + 10 + txt_w
    ox = width - PAD - right_w

    orb_frame = (ox, (h - orb_size) / 2 - 2, orb_size, orb_size)
    icon_img = load_project_icon()
    if icon_img is not None:
        holder = ui.View(frame=orb_frame)
        holder.background_color = 'clear'
        holder.corner_radius = orb_size / 2.0
        holder.user_interaction_enabled = False
        iv = ui.ImageView(frame=(0, 0, orb_size, orb_size))
        iv.flex = 'WH'
        iv.content_mode = ui.CONTENT_SCALE_ASPECT_FILL
        iv.image = icon_img
        holder.add_subview(iv)
        v.add_subview(holder)
    else:
        v.add_subview(OrbView(frame=orb_frame))

    t1 = make_label('Рады видеть', (F_REG, 9.5), TXT_3, ui.ALIGN_RIGHT,
                    frame=(ox + orb_size + 10, 11, txt_w, 12))
    v.add_subview(t1)
    t2 = make_label('Всегда офлайн', (F_BOLD, 12.5), ACCENT_2, ui.ALIGN_RIGHT,
                    frame=(ox + orb_size + 10, 24, txt_w, 16))
    v.add_subview(t2)
    return v, h


def section_header(width, y, title, right_text=None, right_action=None):
    h = 34.0
    v = ui.View(frame=(0, y, width, h))
    v.background_color = 'clear'
    lb = make_label(title, (F_BOLD, 21), TXT, frame=(PAD, 0, width - PAD * 2 - 90, h))
    v.add_subview(lb)
    if right_text and callable(right_action):
        btn_w = 74.0
        b = Tappable(action=right_action, press_scale=0.93,
                     frame=(width - PAD - btn_w, 0, btn_w, h))
        b.background_color = 'clear'
        rl = make_label(right_text, (F_REG, 13), TXT_2, ui.ALIGN_RIGHT,
                        frame=(0, 0, btn_w - 18, h))
        b.add_subview(rl)
        ic = Icon('chevron', TXT_2, 1.6, frame=(btn_w - 15, h / 2 - 7, 13, 14))
        b.add_subview(ic)
        v.add_subview(b)
    return v, h


def empty_block(width, y, title, subtitle, icon='film'):
    h = 150.0
    v = card_view((PAD, y, width - PAD * 2, h), CARD, 18, BORDER)
    w = v.width
    glow = GlowView(ACCENT, 0.20, 8, frame=(w / 2 - 46, 22, 92, 92))
    v.add_subview(glow)
    ic = Icon(icon, rgba(ACCENT_2, 0.55), 1.8, frame=(w / 2 - 19, 45, 38, 38))
    v.add_subview(ic)
    t = make_label(title, (F_BOLD, 16), TXT, ui.ALIGN_CENTER,
                   frame=(10, 92, w - 20, 20))
    v.add_subview(t)
    s = make_label(subtitle, (F_REG, 12), TXT_3, ui.ALIGN_CENTER, lines=2,
                   frame=(16, 112, w - 32, 30))
    v.add_subview(s)
    return v, h


def status_pill(text, icon_name, color, x, y, w=None, h=26.0, bg=None):
    lbl_font = (F_REG, 11.5)
    if w is None:
        w = 26 + len(text) * 6.4
    v = ui.View(frame=(x, y, w, h))
    v.background_color = bg if bg else rgba(ACCENT, 0.13)
    v.corner_radius = h / 2.0
    v.border_width = 1
    v.border_color = rgba(color, 0.35)
    v.user_interaction_enabled = False
    ic = Icon(icon_name, color, 1.5, frame=(7, (h - 14) / 2, 14, 14))
    v.add_subview(ic)
    lb = make_label(text, lbl_font, color, frame=(25, 0, w - 29, h))
    v.add_subview(lb)
    return v


# =====================================================================
#  НИЖНЯЯ НАВИГАЦИЯ
# =====================================================================

class TabItem(Tappable):
    def __init__(self, title, icon_name, index, on_tap, **kwargs):
        Tappable.__init__(self, action=self._tapped, press_scale=0.9, **kwargs)
        self.background_color = 'clear'
        self.index = index
        self.on_tap = on_tap
        self.selected = False
        self.glow = GlowView(ACCENT, 0.26, 7, frame=(0, 0, 54, 54))
        self.glow.alpha = 0.0
        self.add_subview(self.glow)
        self.icon = Icon(icon_name, TXT_3, 1.7, frame=(0, 0, 24, 24))
        self.add_subview(self.icon)
        self.label = make_label(title, (F_REG, 10.5), TXT_3, ui.ALIGN_CENTER)
        self.add_subview(self.label)

    def _tapped(self, sender):
        handler = self.on_tap
        if callable(handler):
            handler(self.index)

    def layout(self):
        w, h = self.width, self.height
        self.glow.frame = (w / 2 - 27, 1, 54, 54)
        self.icon.frame = (w / 2 - 12, 9, 24, 24)
        self.label.frame = (0, 35, w, 14)

    def set_selected(self, flag):
        if self.selected == flag:
            return
        self.selected = flag
        col = ACCENT_SOFT if flag else TXT_3
        self.icon.set_icon(color=col)
        self.label.text_color = col
        self.label.font = (F_BOLD if flag else F_REG, 10.5)
        animate(lambda: setattr(self.glow, 'alpha', 1.0 if flag else 0.0), 0.22)


class TabBar(ui.View):
    def __init__(self, on_tap, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = NAV_BG
        self.items = []
        specs = [('Главная', 'home'), ('Загрузки', 'download'),
                 ('Плеер', 'play_circle'), ('Настройки', 'gear')]
        for i, (title, icon) in enumerate(specs):
            it = TabItem(title, icon, i, on_tap)
            self.add_subview(it)
            self.items.append(it)
        self.items[0].set_selected(True)

    def draw(self):
        ui.set_color(rgba(BORDER_2, 0.55))
        ui.fill_rect(0, 0, self.width, 1)

    def layout(self):
        n = max(1, len(self.items))
        w = self.width / float(n)
        for i, it in enumerate(self.items):
            it.frame = (i * w, 4, w, min(52.0, self.height - 6))

    def select(self, index):
        for i, it in enumerate(self.items):
            it.set_selected(i == index)


# =====================================================================
#  БАЗОВЫЙ ЭКРАН
# =====================================================================

class Screen(ui.View):
    _ready = False
    _built_w = -1.0
    _building = False
    _job_views = None
    _job_sig = None
    app = None
    sv = None

    def __init__(self, app, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.app = app
        self.background_color = 'clear'
        self.sv = ui.ScrollView(frame=self.bounds)
        self.sv.flex = 'WH'
        self.sv.background_color = 'clear'
        self.sv.always_bounce_vertical = True
        self.sv.shows_vertical_scroll_indicator = False
        self.add_subview(self.sv)
        self._built_w = -1.0
        self._building = False
        self._job_views = {}
        self._job_sig = None
        self._ready = True

    def layout(self):
        if not self._ready:
            return
        self.sv.frame = self.bounds
        if self.width > 40 and abs(self.width - self._built_w) > 0.5:
            self.rebuild()

    def clear(self):
        # Перед снятием со сцены гасим всё анимируемое вглубь дерева, чтобы
        # ни одна ссылка на выброшенную view уже ничего не перерисовывала.
        # Новых ui.delay здесь не заводится.
        for v in list(self.sv.subviews):
            deactivate_tree(v)
            self.sv.remove_subview(v)

    # -- две отдельные кнопки карточки: пауза и удаление ---------------
    @staticmethod
    def _icon_button(icon_name, color, action, x, y, w=26.0, h=28.0):
        """
        Хит-таргет — настоящий прозрачный ui.Button, как у кнопки «Скачать»:
        собственный touch_ended у Tappable на устройстве до обработчика
        не доходил. Возвращает (holder, icon), чтобы значок можно было
        поменять на месте, не пересобирая карточку.
        """
        holder = ui.View(frame=(x, y, w, h))
        holder.background_color = 'clear'
        icon = Icon(icon_name or 'pause', color, 1.5,
                    frame=(w / 2 - 7, h / 2 - 7, 14, 14))
        holder.add_subview(icon)
        hit = ui.Button(frame=holder.bounds)
        hit.flex = 'WH'
        hit.background_color = 'clear'
        hit.action = action
        holder.add_subview(hit)
        return holder, icon

    def _toggle_button(self, job, x, y, w=26.0, h=28.0):
        """⏸ или ▶ — пауза и продолжение, отдельная кнопка от крестика."""
        name = job.action_icon()
        holder, icon = self._icon_button(name, TXT_2, self._make_toggle(job),
                                         x, y, w, h)
        holder.hidden = not name
        return holder, icon

    def _dismiss_button(self, job, x, y, w=26.0, h=28.0):
        """× — удалить загрузку полностью вместе с недокачанным файлом."""
        holder, _ = self._icon_button('close', TXT_3, self._make_dismiss(job),
                                      x, y, w, h)
        return holder

    def _temp_button(self, temp, x, y, w=26.0, h=28.0):
        """× у недокачанного файла без карточки: удаляет сам файл."""
        path = getattr(temp, 'path', '')

        def _act(sender):
            self.app.delete_temp(path)
        holder, _ = self._icon_button('close', TXT_3, _act, x, y, w, h)
        return holder

    def _make_toggle(self, job):
        job_id = getattr(job, 'id', None)

        def _act(sender):
            self.app.toggle_job(job_id)
        return _act

    def _make_dismiss(self, job):
        job_id = getattr(job, 'id', None)

        def _act(sender):
            self.app.delete_job(job_id)
        return _act

    def rebuild(self):
        """Перестроение атомарно: сначала очистка, потом сборка."""
        if self._building or not self._ready:
            return
        if self.width <= 40:
            return
        self._building = True
        try:
            self.clear()
            self._job_views = {}
            self._job_sig = DOWNLOADER.signature()
            self.build()
            self._built_w = self.width
        except Exception as e:
            self.clear()
            self._job_views = {}
            self._built_w = -1.0
            nox_error(str(e))
        finally:
            self._building = False

    def refresh_jobs(self, phase=0.0):
        """
        Точечное обновление строк загрузки: тексты и полоса меняются на месте.
        Ни одна view не создаётся и не удаляется. Полный rebuild — только
        когда реально изменился СОСТАВ очереди.
        """
        if not self._ready or self.hidden or self.width <= 40:
            return
        if DOWNLOADER.signature() != self._job_sig:
            self.rebuild()
            return
        views = self._job_views or {}
        if not views:
            return
        for job in DOWNLOADER.visible_jobs():
            row = views.get(job.id)
            if not row:
                continue
            try:
                self._apply_job(job, row, phase)
            except Exception as e:
                log_debug('refresh_jobs: %r' % (e,))

    def _apply_job(self, job, row, phase=0.0):
        title = row.get('title')
        if title is not None:
            text = safe_name(job.display_title, 26)
            if title.text != text:
                title.text = text
        sub = row.get('sub')
        if sub is not None:
            if row.get('detail') is None:
                text = job.diag_line() or job.sub_line()
            else:
                text = job.sub_line()
            if sub.text != text:
                sub.text = text
        detail = row.get('detail')
        if detail is not None:
            text = job.detail_line()
            if detail.text != text:
                detail.text = text
        status = row.get('status')
        if status is not None:
            text = job.short_status()
            if status.text != text:
                status.text = text
        # Кнопка ⏸/▶ меняет только значок: карточка не пересобирается.
        toggle, toggle_icon = row.get('toggle'), row.get('toggle_icon')
        if toggle is not None and toggle_icon is not None:
            name = job.action_icon()
            if not name:
                toggle.hidden = True
            else:
                toggle.hidden = False
                if toggle_icon.icon_name != name:
                    toggle_icon.set_icon(name)
        bar = row.get('bar')
        if bar is not None:
            value = job.percent
            if value is None and job.status in (ST_ERROR, ST_CANCELLED):
                value = 0.0
            bar.set_value(value)
            if value is None:
                # Бегунок двигает общий такт приложения, а не свой таймер.
                bar.set_phase(phase)

    def build(self):
        """Наполнение экрана. Переопределяется наследниками."""
        pass

    def on_show(self):
        self.rebuild()


# =====================================================================
#  ЭКРАН 1 — ГЛАВНАЯ
# =====================================================================

class SearchDelegate(object):
    def __init__(self, cb):
        self.cb = cb

    def textfield_did_change(self, textfield):
        handler = self.cb
        if not callable(handler):
            return
        try:
            handler(textfield.text or '')
        except Exception:
            pass

    def textfield_should_return(self, textfield):
        try:
            textfield.end_editing()
        except Exception:
            pass
        return True


class HomeScreen(Screen):
    query = ''
    _delegate = None

    def __init__(self, app, **kwargs):
        self.query = ''
        self._delegate = None
        Screen.__init__(self, app, **kwargs)

    def _on_query(self, text):
        self.query = text
        self.rebuild()

    @ui.in_background
    def _open_filter_menu(self, sender):
        """Штатный для Pythonista список: сортировка и фильтр качества."""
        labels = ['Сортировка: ' + t for _, t in SORT_OPTIONS]
        labels += ['Качество: ' + t for _, t in QUALITY_FILTERS]
        try:
            import dialogs
            choice = dialogs.list_dialog('Сортировка и фильтр', labels)
        except KeyboardInterrupt:
            return
        except Exception as e:
            log_debug('filter menu: %r' % (e,))
            nox_error('Меню недоступно')
            return
        if not choice:
            return
        if choice.startswith('Сортировка: '):
            title = choice[len('Сортировка: '):]
            for key, text in SORT_OPTIONS:
                if text == title:
                    STATE.set('sort', key)
                    break
        elif choice.startswith('Качество: '):
            title = choice[len('Качество: '):]
            for key, text in QUALITY_FILTERS:
                if text == title:
                    STATE.set('quality_filter', key)
                    break
        # Перестроение — только на главном потоке.
        run_on_main(self.rebuild)

    # ---------------------------------------------------------------
    def build(self):
        # LIB.scan() здесь НЕ вызывается: экран читает уже собранное
        # состояние медиатеки. Диск сканируется только в reload_all().
        w = self.width
        y = 6.0

        head, hh = build_header(w, y)
        self.sv.add_subview(head)
        y += hh + 8

        y = self._build_search(w, y)
        y += 14
        y = self._build_continue(w, y)
        y = self._build_library(w, y)
        y = self._build_downloads(w, y)

        y += 20 + self.app.bottom_inset + NAV_H
        self.sv.content_size = (0, y)

    # ---------------------------------------------------------------
    def _build_search(self, w, y):
        h = 52.0
        box = card_view((PAD, y, w - PAD * 2, h), CARD, 15, BORDER)
        ic = Icon('search', TXT_3, 1.8, frame=(16, h / 2 - 10, 20, 20))
        box.add_subview(ic)

        tf = ui.TextField(frame=(46, 6, box.width - 46 - 52, h - 12))
        tf.placeholder = 'Поиск в медиатеке...'
        tf.background_color = 'clear'
        tf.text_color = TXT
        tf.tint_color = ACCENT
        tf.font = (F_REG, 15)
        tf.bordered = False
        tf.clear_button_mode = 'while_editing'
        tf.autocorrection_type = False
        tf.text = self.query
        self._delegate = SearchDelegate(self._on_query)
        tf.delegate = self._delegate
        box.add_subview(tf)

        # Значок и его место прежние; сверху — прозрачный ui.Button.
        active = (STATE.get('sort', 'new') != 'new'
                  or STATE.get('quality_filter', 'all') != 'all')
        tune_holder = ui.View(frame=(box.width - 50, h / 2 - 18, 36, 36))
        tune_holder.background_color = 'clear'
        tune_holder.add_subview(Icon('tune', ACCENT_2 if active else TXT_2, 1.7,
                                     frame=(7, 7, 22, 22)))
        tune_hit = ui.Button(frame=tune_holder.bounds)
        tune_hit.flex = 'WH'
        tune_hit.background_color = 'clear'
        tune_hit.action = self._open_filter_menu
        tune_holder.add_subview(tune_hit)
        box.add_subview(tune_holder)
        self.sv.add_subview(box)
        return y + h

    # ---------------------------------------------------------------
    def _build_continue(self, w, y):
        item, watch = self._resume_candidate()
        if item is None:
            return y                       # блок полностью скрыт
        h = 152.0
        card = card_view((PAD, y, w - PAD * 2, h), CARD, 18, BORDER_2)
        cw = card.width

        img = item.load_thumb_image()
        if img is not None:
            holder = ui.View(frame=(cw * 0.42, 0, cw * 0.58, h))
            holder.background_color = 'clear'
            holder.corner_radius = 18
            holder.alpha = 0.55
            holder.user_interaction_enabled = False
            iv = ui.ImageView(frame=holder.bounds)
            iv.flex = 'WH'
            iv.content_mode = ui.CONTENT_SCALE_ASPECT_FILL
            iv.image = img
            holder.add_subview(iv)
            card.add_subview(holder)
        else:
            glow = GlowView(ACCENT, 0.13, 9, frame=(cw - 190, -50, 220, 220))
            card.add_subview(glow)

        pill = status_pill('Скачано', 'check', ACCENT_2, 0, 14, w=96)
        pill.x = cw - 96 - 14
        card.add_subview(pill)

        cap = make_label(spaced('Продолжить просмотр'), (F_REG, 8), TXT_3,
                         frame=(18, 20, cw - 130, 12))
        card.add_subview(cap)

        title = make_label(safe_name(item.title, 34), (F_REG, 24), TXT,
                           frame=(18, 36, cw - 130, 32))
        card.add_subview(title)

        meta_parts = []
        left = self._remaining(watch)
        if left:
            meta_parts.append('Осталось ' + left)
        q = item.quality_label
        if q:
            meta_parts.append(q)
        meta_parts.append(fmt_size(item.size))
        if item.uploader:
            meta_parts.append(safe_name(item.uploader, 16))
        meta = make_label('  •  '.join([m for m in meta_parts if m]),
                          (F_REG, 12), TXT_2, frame=(18, 72, cw - 130, 16))
        card.add_subview(meta)

        # Полоса реальная: доля просмотренного из watch_progress.
        fraction = self._watched_fraction(watch)
        if fraction is not None:
            card.add_subview(ProgressBar(fraction,
                                         frame=(18, 94, cw * 0.52, 5)))

        btn = Tappable(action=lambda s: self.app.open_media(item),
                       frame=(18, h - 56, 168, 40))
        btn.background_color = 'clear'
        circ = ui.View(frame=(0, 2, 36, 36))
        circ.background_color = ACCENT_DEEP
        circ.corner_radius = 18
        circ.user_interaction_enabled = False
        pic = Icon('play', TXT, 1.6, frame=(11, 9, 18, 18))
        circ.add_subview(pic)
        btn.add_subview(circ)
        blb = make_label('Продолжить', (F_BOLD, 15), TXT, frame=(48, 2, 118, 36))
        btn.add_subview(blb)
        card.add_subview(btn)

        self.sv.add_subview(card)
        return y + h + 20

    @staticmethod
    def _watched_fraction(watch):
        try:
            pos = float(watch.get('position') or 0.0)
            total = float(watch.get('duration') or 0.0)
        except Exception:
            return None
        if total <= 0:
            return None
        return max(0.0, min(1.0, pos / total))

    @staticmethod
    def _remaining(watch):
        try:
            pos = float(watch.get('position') or 0.0)
            total = float(watch.get('duration') or 0.0)
        except Exception:
            return ''
        if total <= 0:
            return ''
        return fmt_duration(max(0.0, total - pos))

    def _resume_candidate(self):
        """
        Последнее реально недосмотренное видео: позиция больше
        WATCH_MIN_START и до конца ещё больше WATCH_DONE_TAIL.
        Ничего подходящего — блок не показывается, заглушек нет.
        """
        best, best_watch, best_time = None, None, -1.0
        for video_id, watch in STATE.watch_map().items():
            if not isinstance(watch, dict):
                continue
            try:
                pos = float(watch.get('position') or 0.0)
                total = float(watch.get('duration') or 0.0)
            except Exception:
                continue
            if pos <= WATCH_MIN_START:
                continue
            if total > 0 and total - pos <= WATCH_DONE_TAIL:
                continue
            item = LIB.find_by_watch_id(video_id)
            if item is None:
                continue
            when = float(watch.get('updated_at') or 0.0)
            if when > best_time:
                best, best_watch, best_time = item, watch, when
        return best, best_watch

    # ---------------------------------------------------------------
    def _build_library(self, w, y):
        items = LIB.filtered(self.query)
        head, hh = section_header(w, y, 'Медиатека',
                                  'Все' if items else None,
                                  lambda s: self.app.select_tab(2))
        self.sv.add_subview(head)
        y += hh + 6

        if LIB.error:
            v, vh = empty_block(w, y, 'Папка NOX недоступна',
                                'Проверьте путь в разделе «Настройки»', 'folder')
            self.sv.add_subview(v)
            return y + vh + 22

        if not items:
            if self.query:
                v, vh = empty_block(w, y, 'Ничего не найдено',
                                    'Измените поисковый запрос', 'search')
            else:
                v, vh = empty_block(w, y, 'Медиатека пуста',
                                    'Скачанные видео появятся здесь', 'film')
            self.sv.add_subview(v)
            return y + vh + 22

        card_w = 104.0
        gap = 10.0
        thumb_h = 122.0
        row_h = thumb_h + 8 + 17 + 14 + 6 + 26

        row = ui.ScrollView(frame=(0, y, w, row_h))
        row.background_color = 'clear'
        row.shows_horizontal_scroll_indicator = False
        row.always_bounce_horizontal = True

        x = PAD
        for item in items[:24]:
            row.add_subview(self._library_card(item, x, 0, card_w, thumb_h, row_h))
            x += card_w + gap
        row.content_size = (x - gap + PAD, 0)
        self.sv.add_subview(row)
        return y + row_h + 22

    def _library_card(self, item, x, y, cw, thumb_h, total_h):
        c = Tappable(action=lambda s: self.app.open_media(item),
                     frame=(x, y, cw, total_h))
        c.background_color = 'clear'

        th = ThumbView(item.load_thumb_image(), frame=(0, 0, cw, thumb_h))
        th.corner_radius = 12
        th.border_width = 1
        th.border_color = BORDER
        c.add_subview(th)

        badge = Icon('check_badge', ACCENT, 1.5, frame=(cw - 24, 6, 18, 18))
        c.add_subview(badge)

        ty = thumb_h + 8
        t = make_label(safe_name(item.title, 22), (F_BOLD, 11.5), TXT,
                       frame=(1, ty, cw - 2, 15))
        c.add_subview(t)

        m = make_label(item.meta_line or item.fmt_label, (F_REG, 9.5), TXT_3,
                       frame=(1, ty + 16, cw - 2, 13))
        c.add_subview(m)

        py = ty + 34
        pill = ui.View(frame=(0, py, 66, 24))
        pill.background_color = rgba(ACCENT, 0.12)
        pill.corner_radius = 12
        pill.border_width = 1
        pill.border_color = rgba(ACCENT, 0.30)
        pill.user_interaction_enabled = False
        pi = Icon('check', ACCENT_2, 1.5, frame=(6, 6, 12, 12))
        pill.add_subview(pi)
        pl = make_label('Офлайн', (F_REG, 10), ACCENT_2, frame=(21, 0, 44, 24))
        pill.add_subview(pl)
        c.add_subview(pill)

        dots = Tappable(action=lambda s: self.app.item_menu(item),
                        press_scale=0.85, frame=(cw - 22, py, 22, 24))
        dots.background_color = 'clear'
        di = Icon('dots', TXT_3, 1.4, frame=(7, 5, 8, 14))
        dots.add_subview(di)
        c.add_subview(dots)
        return c

    # ---------------------------------------------------------------
    def _build_downloads(self, w, y):
        jobs = DOWNLOADER.visible_jobs()
        temps = LIB.orphan_temps(DOWNLOADER.managed_paths())
        head, hh = section_header(w, y, 'Загрузки',
                                  'Все' if (temps or jobs) else None,
                                  lambda s: self.app.select_tab(1))
        self.sv.add_subview(head)
        y += hh + 6

        if not temps and not jobs:
            v, vh = empty_block(w, y, 'Нет активных загрузок',
                                'Вставьте ссылку на вкладке «Загрузки»', 'download')
            self.sv.add_subview(v)
            return y + vh

        box = card_view((PAD, y, w - PAD * 2, 0), CARD, 16, BORDER)
        by = 0.0
        for j in jobs[:4]:
            r = self._download_row(box.width, by, None, j)
            box.add_subview(r)
            by += r.height
        for t in temps[:3]:
            r = self._download_row(box.width, by, t, None)
            box.add_subview(r)
            by += r.height
        box.height = max(60.0, by)
        self.sv.add_subview(box)
        return y + box.height

    def _download_row(self, w, y, temp, job):
        h = 74.0
        v = ui.View(frame=(0, y, w, h))
        v.background_color = 'clear'

        th = ThumbView(None, frame=(12, 12, 48, 50))
        th.corner_radius = 9
        th.border_width = 1
        th.border_color = BORDER
        v.add_subview(th)

        title = temp.title if temp is not None else job.display_title
        tl = make_label(safe_name(title, 26), (F_BOLD, 13), TXT,
                        frame=(70, 14, w - 70 - 118, 17))
        v.add_subview(tl)

        if temp is not None:
            # Незавершённый файл прошлого запуска: сейчас он никуда не качается.
            sub = 'Не завершено  •  ' + fmt_size(temp.size)
            bar_value = temp.progress
            st_text, st_icon, st_col = 'Не завершено', 'clock', TXT_2
            indeterminate = False
        else:
            # У компактной строки второй линии нет: пока висит диагностика,
            # она занимает место обычной подписи и исчезает вместе с ней.
            sub = job.diag_line() or job.sub_line()
            bar_value = job.percent
            st_text = job.short_status()
            st_col = ERR_TXT if job.status == ST_ERROR else ACCENT_2
            if job.status == ST_ERROR:
                st_icon = 'close'
            elif job.status == ST_FINISHED:
                st_icon, st_col = 'check', ACCENT_2
                bar_value = 1.0
            elif job.status == ST_PAUSED:
                st_icon, st_col = 'pause', TXT_2
            elif job.status == ST_DELETING:
                st_icon, st_col = 'trash', TXT_2
            elif job.status == ST_NEEDS_RESOLVE:
                st_icon, st_col = 'refresh', ACCENT_2
            elif job.status == ST_CANCELLED:
                st_icon = 'clock'
                st_col = TXT_2
            elif job.status == ST_DOWNLOADING and bar_value is not None:
                st_icon = 'download'
            else:
                st_icon = 'ring'
            indeterminate = (job.status in (ST_PREPARING, ST_PROCESSING) or
                             (job.status == ST_DOWNLOADING and bar_value is None))
            if job.status in (ST_ERROR, ST_CANCELLED) and bar_value is None:
                bar_value = 0.0

        sl = make_label(sub, (F_REG, 10.5),
                        ERR_TXT if (job is not None and job.status == ST_ERROR)
                        else TXT_3,
                        frame=(70, 31, w - 70 - 118, 14))
        v.add_subview(sl)
        bar = ProgressBar(bar_value, frame=(70, 50, w - 70 - 118, 5))
        v.add_subview(bar)
        if indeterminate:
            # Начальная фаза берётся у общего такта приложения; дальше её
            # двигает NoxApp._tick, собственного таймера у полосы нет.
            bar.set_phase(self.app.indeterminate_phase)

        ic = Icon(st_icon, st_col, 1.6, frame=(w - 112, h / 2 - 10, 20, 20))
        v.add_subview(ic)
        st = make_label(st_text, (F_REG, 11), st_col,
                        frame=(w - 88, h / 2 - 9, 58, 18))
        v.add_subview(st)

        if job is not None:
            # Две отдельные кнопки: пауза/продолжение сверху, удаление снизу.
            toggle, toggle_icon = self._toggle_button(job, w - 30, 8, 26, 28)
            v.add_subview(toggle)
            v.add_subview(self._dismiss_button(job, w - 30, 38, 26, 28))
            self._job_views[job.id] = {'sub': sl, 'bar': bar, 'status': st,
                                       'title': tl, 'toggle': toggle,
                                       'toggle_icon': toggle_icon}
        else:
            v.add_subview(self._temp_button(temp, w - 30, h / 2 - 14))

        ui_line = ui.View(frame=(70, h - 1, w - 82, 1))
        ui_line.background_color = rgba(BORDER, 0.7)
        ui_line.user_interaction_enabled = False
        v.add_subview(ui_line)
        return v


# =====================================================================
#  ЭКРАН 2 — ЗАГРУЗКИ
# =====================================================================

class UrlDelegate(object):
    def __init__(self, owner):
        self.owner = owner

    def textfield_did_change(self, textfield):
        self.owner.url_text = textfield.text or ''

    def textfield_should_return(self, textfield):
        self.owner.url_text = textfield.text or ''
        try:
            textfield.end_editing()
        except Exception:
            pass
        return True


class DownloadsScreen(Screen):
    url_text = ''
    quality = '720'
    _chips = ()
    _url_field = None
    _delegate = None
    _dl_button = None

    def __init__(self, app, **kwargs):
        self.url_text = ''
        self.quality = STATE.get('quality', '720')
        self._chips = []
        self._url_field = None
        self._delegate = None
        Screen.__init__(self, app, **kwargs)

    def build(self):
        # LIB.scan() здесь НЕ вызывается — см. комментарий в HomeScreen.build.
        w = self.width
        self._chips = []
        y = 6.0

        head, hh = build_header(w, y)
        self.sv.add_subview(head)
        y += hh + 12

        y = self._build_title(w, y)
        y = self._build_url_box(w, y)
        y = self._build_quality(w, y)
        y = self._build_button(w, y)
        y = self._build_queue(w, y)
        y = self._build_storage(w, y)

        y += 20 + self.app.bottom_inset + NAV_H
        self.sv.content_size = (0, y)

    # ---------------------------------------------------------------
    def _build_title(self, w, y):
        t = make_label('Загрузчик', (F_BOLD, 29), TXT, frame=(PAD, y, w - 100, 36))
        self.sv.add_subview(t)
        s = make_label('Сохраняйте видео для тишины', (F_REG, 12.5), TXT_3,
                       frame=(PAD + 1, y + 36, w - 100, 17))
        self.sv.add_subview(s)

        g = Tappable(action=lambda x: self.app.select_tab(3), press_scale=0.92,
                     frame=(w - PAD - 46, y + 6, 46, 46))
        g.background_color = CARD
        g.corner_radius = 14
        g.border_width = 1
        g.border_color = BORDER
        g.add_subview(Icon('gear', TXT_2, 1.5, frame=(13, 13, 20, 20)))
        self.sv.add_subview(g)
        return y + 62

    # ---------------------------------------------------------------
    def _build_url_box(self, w, y):
        h = 118.0
        box = card_view((PAD, y, w - PAD * 2, h), CARD, 16, BORDER)
        bw = box.width

        field = card_view((14, 14, bw - 28, 52), FIELD, 13, BORDER_2)
        field.add_subview(Icon('link', TXT_3, 1.6, frame=(14, 16, 20, 20)))

        paste_w = 90.0
        tf = ui.TextField(frame=(42, 8, field.width - 42 - paste_w - 16, 36))
        tf.placeholder = 'Вставьте ссылку на видео...'
        tf.background_color = 'clear'
        tf.text_color = TXT
        tf.tint_color = ACCENT
        tf.font = (F_REG, 14)
        tf.bordered = False
        tf.autocorrection_type = False
        tf.autocapitalization_type = ui.AUTOCAPITALIZE_NONE
        tf.keyboard_type = ui.KEYBOARD_URL
        tf.text = self.url_text
        self._delegate = UrlDelegate(self)
        tf.delegate = self._delegate
        self._url_field = tf
        field.add_subview(tf)

        pb = Tappable(action=self._paste, press_scale=0.92,
                      frame=(field.width - paste_w - 8, 8, paste_w, 36))
        pb.background_color = CARD_3
        pb.corner_radius = 10
        pb.border_width = 1
        pb.border_color = BORDER_2
        pb.add_subview(Icon('clip', TXT, 1.5, frame=(12, 10, 16, 16)))
        pb.add_subview(make_label('Вставить', (F_REG, 12.5), TXT,
                                  frame=(33, 0, paste_w - 36, 36)))
        field.add_subview(pb)
        box.add_subview(field)

        box.add_subview(Icon('info', TXT_4, 1.5, frame=(15, 80, 15, 15)))
        box.add_subview(make_label('Поддерживает VK, YouTube, Vimeo и другие',
                                   (F_REG, 11.5), TXT_3,
                                   frame=(38, 78, bw - 50, 18)))
        self.sv.add_subview(box)
        return y + h + 18

    def _paste(self, sender):
        if clipboard is None:
            nox_error('Буфер обмена недоступен')
            return
        try:
            text = (clipboard.get() or '').strip()
        except Exception:
            text = ''
        if not text:
            nox_error('Буфер обмена пуст')
            return
        self.url_text = text
        if self._url_field is not None:
            self._url_field.text = text

    # ---------------------------------------------------------------
    def _build_quality(self, w, y):
        self.sv.add_subview(make_label('Качество', (F_BOLD, 19), TXT,
                                       frame=(PAD, y, 160, 26)))
        self.sv.add_subview(make_label('Выше качество — больше файл',
                                       (F_REG, 10.5), TXT_3, ui.ALIGN_RIGHT,
                                       frame=(w - PAD - 210, y + 5, 210, 18)))
        y += 34

        gap = 10.0
        cw = (w - PAD * 2 - gap * 3) / 4.0
        ch = 66.0
        for i, (key, top, bottom) in enumerate(QUALITIES):
            x = PAD + i * (cw + gap)
            chip = Tappable(action=self._make_pick(key), press_scale=0.94,
                            frame=(x, y, cw, ch))
            chip.background_color = CARD
            chip.corner_radius = 14
            chip.border_width = 1
            chip.border_color = BORDER
            glow = GlowView(ACCENT, 0.30, 7, frame=(-cw * 0.25, -ch * 0.25,
                                                    cw * 1.5, ch * 1.5))
            glow.alpha = 0.0
            chip.add_subview(glow)
            t = make_label(top, (F_BOLD, 15.5), TXT, ui.ALIGN_CENTER,
                           frame=(0, 14, cw, 20))
            chip.add_subview(t)
            b = make_label(bottom, (F_REG, 10), TXT_3, ui.ALIGN_CENTER,
                           frame=(0, 36, cw, 14))
            chip.add_subview(b)
            self.sv.add_subview(chip)
            self._chips.append((key, chip, t, b, glow))
        self._refresh_chips()
        return y + ch + 20

    def _make_pick(self, key):
        def _pick(sender):
            self.quality = key
            STATE.set('quality', key)
            self._refresh_chips()
        return _pick

    def _refresh_chips(self):
        for key, chip, t, b, glow in self._chips:
            sel = (key == self.quality)
            def apply(chip=chip, t=t, b=b, glow=glow, sel=sel):
                chip.border_color = ACCENT if sel else BORDER
                chip.border_width = 1.6 if sel else 1.0
                chip.background_color = '#12142c' if sel else CARD
                t.text_color = TXT if sel else TXT_2
                b.text_color = ACCENT_2 if sel else TXT_3
                glow.alpha = 1.0 if sel else 0.0
            animate(apply, 0.18)

    # ---------------------------------------------------------------
    def _build_button(self, w, y):
        h = 62.0
        # Визуал остаётся прежним, но действие снято с Tappable: на устройстве
        # его touch_ended до обработчика не доходил. Хит-таргетом служит
        # настоящий прозрачный ui.Button поверх всей кнопки (см. ниже).
        btn = Tappable(action=None, press_scale=0.97,
                       frame=(PAD, y, w - PAD * 2, h))
        btn.background_color = ACCENT_DEEP
        btn.corner_radius = h / 2.0
        grad = GradientView(ACCENT_DEEP, ACCENT_2, frame=btn.bounds)
        grad.flex = 'WH'
        btn.add_subview(grad)
        bw = btn.width
        btn.add_subview(Icon('download', TXT, 2.0,
                             frame=(bw / 2 - 78, h / 2 - 13, 26, 26)))
        btn.add_subview(make_label('Скачать', (F_BOLD, 20), TXT,
                                   frame=(bw / 2 - 44, 0, 160, h)))
        self._dl_button = btn

        hit = ui.Button(frame=btn.bounds)
        hit.flex = 'WH'
        hit.background_color = 'clear'
        hit.action = self._download
        btn.add_subview(hit)

        self.sv.add_subview(btn)
        y += h + 10
        if ytdlp_ready():
            note = 'Для больших загрузок не закрывайте и не сворачивайте NOX.'
            note_col = TXT_4
        else:
            note = (YTDLP_ERROR or 'Модуль yt-dlp не найден') + \
                   '  •  положите папку yt_dlp рядом с NOX.py'
            note_col = ERR_TXT
        self.sv.add_subview(make_label(note, (F_REG, 10.5), note_col,
                                       ui.ALIGN_CENTER,
                                       frame=(PAD, y, w - PAD * 2, 16)))
        return y + 26

    def _pulse_download_button(self):
        """Прежняя press-анимация 0.97 -> 1.0: касание теперь ловит ui.Button."""
        btn = self._dl_button
        if btn is None or btn.superview is None:
            return

        def down():
            try:
                btn.transform = ui.Transform.scale(0.97, 0.97)
            except Exception:
                pass

        def up():
            try:
                btn.transform = ui.Transform.scale(1.0, 1.0)
            except Exception:
                pass

        animate(down, 0.08, 0.0, lambda: animate(up, 0.14))

    def _download(self, sender):
        self._pulse_download_button()
        url = self.url_text
        if self._url_field is not None:
            url = self._url_field.text or url
            try:
                self._url_field.end_editing()
            except Exception:
                pass
        ok, msg = DOWNLOADER.add(url, self.quality)
        if ok:
            nox_ok(msg)
            self.url_text = ''
            if self._url_field is not None:
                self._url_field.text = ''
            # Никакого синхронного rebuild и никакого сканирования диска
            # в момент старта: карточку добавит единственный корневой такт,
            # когда увидит новое задание в signature(). Он же следом
            # разберёт ссылку — на главном потоке.
            run_on_main(self.app.tick_soon)
        else:
            nox_error(msg)

    # ---------------------------------------------------------------
    def _build_queue(self, w, y):
        jobs = DOWNLOADER.visible_jobs()
        temps = LIB.orphan_temps(DOWNLOADER.managed_paths())
        count = len(temps) + len(jobs)
        self.sv.add_subview(make_label('Очередь загрузок', (F_BOLD, 19), TXT,
                                       frame=(PAD, y, w - 140, 26)))
        right = ('%d элем.' % count) if count else 'Пусто'
        self.sv.add_subview(make_label(right, (F_REG, 12), TXT_3, ui.ALIGN_RIGHT,
                                       frame=(w - PAD - 120, y + 5, 120, 18)))
        y += 36

        if not count:
            v, vh = empty_block(w, y, 'Очередь пуста',
                                'Активных загрузок сейчас нет', 'clock')
            self.sv.add_subview(v)
            return y + vh + 20

        for j in jobs:
            card = self._queue_card(w, y, None, j)
            self.sv.add_subview(card)
            y += card.height + 10
        for t in temps:
            card = self._queue_card(w, y, t, None)
            self.sv.add_subview(card)
            y += card.height + 10
        return y + 10

    def _queue_card(self, w, y, temp, job):
        h = 86.0
        c = card_view((PAD, y, w - PAD * 2, h), CARD, 14, BORDER)
        cw = c.width

        th = ThumbView(None, frame=(10, 12, 62, 62))
        th.corner_radius = 10
        th.border_width = 1
        th.border_color = BORDER
        c.add_subview(th)

        left = 82.0
        right_w = 120.0
        title = temp.title if temp is not None else job.display_title
        tl = make_label(safe_name(title, 24), (F_BOLD, 14), TXT,
                        frame=(left, 14, cw - left - right_w, 18))
        c.add_subview(tl)

        if temp is not None:
            # Файл прошлого запуска: NOX его сейчас не качает и не притворяется.
            sub, detail = 'Не завершено  •  ' + fmt_size(temp.size), ''
            bar_value = temp.progress
            indeterminate = False
            st_text, st_icon, st_col = 'Не завершено', 'clock', TXT_2
            sub_col = TXT_3
        else:
            sub, detail = job.sub_line(), job.detail_line()
            bar_value = job.percent
            st_text = job.short_status()
            if job.status == ST_ERROR:
                st_icon, st_col, sub_col = 'close', ERR_TXT, ERR_TXT
            elif job.status == ST_FINISHED:
                st_icon, st_col, sub_col = 'check', ACCENT_2, ACCENT_2
                bar_value = 1.0
            elif job.status == ST_PAUSED:
                st_icon, st_col, sub_col = 'pause', TXT_2, TXT_3
            elif job.status == ST_DELETING:
                st_icon, st_col, sub_col = 'trash', TXT_2, TXT_3
            elif job.status == ST_NEEDS_RESOLVE:
                st_icon, st_col, sub_col = 'refresh', ACCENT_2, TXT_3
            elif job.status == ST_CANCELLED:
                st_icon, st_col, sub_col = 'clock', TXT_2, TXT_3
            elif job.status == ST_DOWNLOADING and bar_value is not None:
                st_icon, st_col, sub_col = 'download', ACCENT_2, TXT_3
            else:
                st_icon, st_col, sub_col = 'ring', ACCENT_2, TXT_3
            indeterminate = (job.status in (ST_PREPARING, ST_PROCESSING) or
                             (job.status == ST_DOWNLOADING and bar_value is None))
            if job.status in (ST_ERROR, ST_CANCELLED) and bar_value is None:
                bar_value = 0.0

        sl = make_label(sub, (F_REG, 11), sub_col,
                        frame=(left, 33, cw - left - right_w, 15))
        c.add_subview(sl)
        bar = ProgressBar(bar_value, frame=(left, 56, cw - left - 16, 5))
        c.add_subview(bar)
        if indeterminate:
            # Начальная фаза берётся у общего такта приложения; дальше её
            # двигает NoxApp._tick, собственного таймера у полосы нет.
            bar.set_phase(self.app.indeterminate_phase)
        # Вторая строка занимает уже существовавшее пустое место под полосой,
        # ни один элемент карточки не сдвинут. Две строки нужны техническому
        # тексту ошибки — обычные подписи в одну строку выглядят как прежде.
        dl = make_label(detail, (F_REG, 9.5),
                        ERR_TXT if (job is not None and job.status == ST_ERROR)
                        else TXT_4, lines=2,
                        frame=(left, 61, cw - left - 16, 24))
        c.add_subview(dl)

        st = make_label(st_text, (F_REG, 12), st_col,
                        frame=(cw - 118, 24, 76, 20))
        c.add_subview(Icon(st_icon, st_col, 1.6, frame=(cw - 142, 24, 20, 20)))
        c.add_subview(st)

        if job is not None:
            # Пауза и удаление — разные кнопки. Высота карточки прежняя.
            toggle, toggle_icon = self._toggle_button(job, cw - 34, 10, 26, 26)
            c.add_subview(toggle)
            c.add_subview(self._dismiss_button(job, cw - 34, 46, 26, 26))
            self._job_views[job.id] = {'sub': sl, 'detail': dl, 'bar': bar,
                                       'status': st, 'title': tl,
                                       'toggle': toggle,
                                       'toggle_icon': toggle_icon}
        else:
            c.add_subview(self._temp_button(temp, cw - 34, 30, 26, 26))
        return c

    # ---------------------------------------------------------------
    def _build_storage(self, w, y):
        h = 96.0
        c = Tappable(action=lambda s: self.app.select_tab(3), press_scale=0.985,
                     frame=(PAD, y, w - PAD * 2, h))
        c.background_color = CARD
        c.corner_radius = 16
        c.border_width = 1
        c.border_color = BORDER
        cw = c.width

        box = ui.View(frame=(14, 20, 48, 48))
        box.background_color = rgba(ACCENT, 0.12)
        box.corner_radius = 12
        box.user_interaction_enabled = False
        box.add_subview(Icon('drive', ACCENT_2, 1.7, frame=(12, 12, 24, 24)))
        c.add_subview(box)

        total, free = LIB.disk()
        used = LIB.used_bytes()

        c.add_subview(make_label('Офлайн-хранилище', (F_BOLD, 15), TXT,
                                 frame=(74, 20, cw - 74 - 120, 20)))
        if total:
            sub = '%s из %s занято' % (fmt_size(used), fmt_size(total))
        else:
            sub = '%s в медиатеке' % fmt_size(used)
        c.add_subview(make_label(sub, (F_REG, 11.5), TXT_3,
                                 frame=(74, 40, cw - 74 - 120, 16)))

        if total and free is not None:
            c.add_subview(make_label(fmt_size(free), (F_BOLD, 17), TXT,
                                     ui.ALIGN_RIGHT,
                                     frame=(cw - 138, 22, 116, 22)))
            c.add_subview(make_label('Свободно', (F_REG, 10.5), TXT_3,
                                     ui.ALIGN_RIGHT,
                                     frame=(cw - 138, 44, 116, 15)))
            c.add_subview(Icon('chevron', TXT_3, 1.5, frame=(cw - 20, 38, 12, 14)))
            ratio = 0.0
            if total > 0:
                ratio = max(0.0, min(1.0, (total - free) / total))
            bar = ProgressBar(ratio, frame=(74, 66, cw - 90, 5))
            c.add_subview(bar)
        else:
            c.add_subview(make_label('Объём тома недоступен', (F_REG, 11),
                                     TXT_4, ui.ALIGN_RIGHT,
                                     frame=(cw - 160, 34, 146, 18)))
        self.sv.add_subview(c)
        return y + h


# =====================================================================
#  ЭКРАН 3 — ПЛЕЕР
# =====================================================================

class PlayerScreen(Screen):
    def build(self):
        # LIB.scan() здесь НЕ вызывается: экран читает уже собранное
        # состояние медиатеки. Диск сканируется только в reload_all().
        w = self.width
        y = 6.0

        head, hh = build_header(w, y)
        self.sv.add_subview(head)
        y += hh + 12

        self.sv.add_subview(make_label('Плеер', (F_BOLD, 29), TXT,
                                       frame=(PAD, y, w - 80, 36)))
        self.sv.add_subview(make_label('Только локальные файлы', (F_REG, 12.5),
                                       TXT_3, frame=(PAD + 1, y + 36, w - 80, 17)))
        y += 68

        items = LIB.items
        if LIB.error:
            v, vh = empty_block(w, y, 'Папка NOX недоступна',
                                'Проверьте путь в разделе «Настройки»', 'folder')
            self.sv.add_subview(v)
            y += vh
        elif not items:
            v, vh = empty_block(w, y, 'Нет видео для просмотра',
                                'Скачайте видео на вкладке «Загрузки»', 'play_circle')
            self.sv.add_subview(v)
            y += vh
        else:
            for item in items:
                row = self._row(w, y, item)
                self.sv.add_subview(row)
                y += row.height + 10

        y += 20 + self.app.bottom_inset + NAV_H
        self.sv.content_size = (0, y)

    def _row(self, w, y, item):
        h = 92.0
        c = Tappable(action=lambda s: self.app.open_media(item),
                     press_scale=0.985, frame=(PAD, y, w - PAD * 2, h))
        c.background_color = CARD
        c.corner_radius = 14
        c.border_width = 1
        c.border_color = BORDER
        cw = c.width

        th = ThumbView(item.load_thumb_image(), frame=(10, 10, 112, 72))
        th.corner_radius = 10
        th.border_width = 1
        th.border_color = BORDER
        c.add_subview(th)

        ov = ui.View(frame=(52, 34, 28, 28))
        ov.background_color = rgba('#000000', 0.45)
        ov.corner_radius = 14
        ov.user_interaction_enabled = False
        ov.add_subview(Icon('play', TXT, 1.4, frame=(9, 7, 14, 14)))
        c.add_subview(ov)

        left = 132.0
        c.add_subview(make_label(safe_name(item.title, 30), (F_BOLD, 14.5), TXT,
                                 frame=(left, 16, cw - left - 40, 19)))
        c.add_subview(make_label(item.meta_line, (F_REG, 11.5), TXT_2,
                                 frame=(left, 37, cw - left - 40, 16)))
        tail = item.uploader or item.fmt_label
        c.add_subview(make_label(safe_name(tail, 26), (F_REG, 10.5), TXT_4,
                                 frame=(left, 55, cw - left - 40, 15)))

        dots = Tappable(action=lambda s: self.app.item_menu(item),
                        press_scale=0.85, frame=(cw - 34, h / 2 - 16, 30, 32))
        dots.background_color = 'clear'
        dots.add_subview(Icon('dots', TXT_3, 1.4, frame=(11, 8, 8, 16)))
        c.add_subview(dots)
        return c


# =====================================================================
#  ЭКРАН 4 — НАСТРОЙКИ
# =====================================================================

SETUP_TEXT = (
    'NOX скачивает видео сам, внутри Pythonista. a-Shell, Ярлык\n'
    '«NOX Download» и закладки больше не нужны — их можно удалить.\n\n'
    '1.  Папка проекта — та, где лежит NOX.py:\n'
    '     Файлы → На iPhone → NOX\n\n'
    '2.  Рядом с NOX.py должны быть:\n'
    '     yt_dlp/        — пакет загрузчика\n'
    '     NoxMedia/      — сюда сохраняются видео\n'
    '     NOX_Data/      — настройки\n'
    '     NOX_icon.png   — необязательно\n\n'
    '3.  NoxMedia и NOX_Data NOX создаёт сам.\n'
    '     Папку yt_dlp нужно положить рядом с NOX.py вручную.\n\n'
    '4.  Скачанное ранее видео просто перенесите в NoxMedia\n'
    '     вместе с его .info.json и нажмите «Обновить медиатеку».\n\n'
    '5.  Загрузка идёт, пока NOX открыт: свернуть Pythonista\n'
    '     надолго iOS не даст.'
)


class SettingsScreen(Screen):
    _chips = ()

    def __init__(self, app, **kwargs):
        self._chips = []
        Screen.__init__(self, app, **kwargs)

    def build(self):
        # LIB.scan() здесь НЕ вызывается — см. комментарий в HomeScreen.build.
        w = self.width
        self._chips = []
        y = 6.0

        head, hh = build_header(w, y)
        self.sv.add_subview(head)
        y += hh + 12

        self.sv.add_subview(make_label('Настройки', (F_BOLD, 29), TXT,
                                       frame=(PAD, y, w - 80, 36)))
        self.sv.add_subview(make_label('Общая папка и параметры загрузки',
                                       (F_REG, 12.5), TXT_3,
                                       frame=(PAD + 1, y + 36, w - 60, 17)))
        y += 70

        y = self._quality_block(w, y)
        y = self._folder_block(w, y)
        y = self._storage_block(w, y)
        y = self._setup_block(w, y)
        y = self._about_block(w, y)

        y += 20 + self.app.bottom_inset + NAV_H
        self.sv.content_size = (0, y)

    # ---------------------------------------------------------------
    def _card(self, w, y, h, title):
        c = card_view((PAD, y, w - PAD * 2, h), CARD, 16, BORDER)
        c.add_subview(make_label(title, (F_BOLD, 15), TXT,
                                 frame=(16, 14, c.width - 32, 20)))
        return c

    def _quality_block(self, w, y):
        h = 116.0
        c = self._card(w, y, h, 'Качество по умолчанию')
        cw = c.width
        gap = 8.0
        chw = (cw - 32 - gap * 3) / 4.0
        cur = STATE.get('quality', '720')
        for i, (key, top, bottom) in enumerate(QUALITIES):
            chip = Tappable(action=self._make_pick(key), press_scale=0.94,
                            frame=(16 + i * (chw + gap), 48, chw, 52))
            chip.background_color = '#12142c' if key == cur else CARD_2
            chip.corner_radius = 12
            chip.border_width = 1.6 if key == cur else 1.0
            chip.border_color = ACCENT if key == cur else BORDER
            t = make_label(top, (F_BOLD, 14), TXT if key == cur else TXT_2,
                           ui.ALIGN_CENTER, frame=(0, 10, chw, 18))
            chip.add_subview(t)
            b = make_label(bottom, (F_REG, 9.5),
                           ACCENT_2 if key == cur else TXT_3,
                           ui.ALIGN_CENTER, frame=(0, 28, chw, 14))
            chip.add_subview(b)
            c.add_subview(chip)
            self._chips.append(chip)
        self.sv.add_subview(c)
        return y + h + 14

    def _make_pick(self, key):
        def _pick(sender):
            STATE.set('quality', key)
            self.app.downloads.quality = key
            self.rebuild()
        return _pick

    # ---------------------------------------------------------------
    def _folder_block(self, w, y):
        h = 194.0
        c = self._card(w, y, h, 'Папка медиатеки')
        cw = c.width

        c.add_subview(make_label(MEDIA_LABEL, (F_BOLD, 13), TXT,
                                 frame=(16, 40, cw - 32, 20)))
        c.add_subview(make_label('Рядом с NOX.py — сюда сохраняются все файлы',
                                 (F_REG, 10.5), TXT_3, lines=2,
                                 frame=(16, 60, cw - 32, 28)))
        exists = os.path.isdir(MEDIA_DIR)
        c.add_subview(status_pill('Доступна' if exists else 'Недоступна',
                                  'check' if exists else 'close',
                                  ACCENT_2 if exists else ERR_TXT,
                                  16, 92, w=104))

        c.add_subview(make_label('Загрузчик yt-dlp', (F_REG, 12.5), TXT_2,
                                 frame=(16, 132, cw - 150, 18)))
        ready = ytdlp_ready()
        if ready:
            value = 'Подключён' + (('  ' + YTDLP_VERSION) if YTDLP_VERSION else '')
        else:
            value = YTDLP_ERROR or 'Модуль yt-dlp не найден'
        c.add_subview(make_label(value, (F_BOLD, 12.5),
                                 ACCENT_2 if ready else ERR_TXT,
                                 frame=(16, 151, cw - 150, 18)))
        c.add_subview(make_label('Пакет yt_dlp лежит рядом с NOX.py — '
                                 'загрузка идёт внутри приложения',
                                 (F_REG, 10), TXT_4, lines=2,
                                 frame=(16, 170, cw - 32, 16)))
        c.add_subview(status_pill('Готов' if ready else 'Нет модуля',
                                  'check' if ready else 'close',
                                  ACCENT_2 if ready else ERR_TXT,
                                  cw - 16 - 108, 138, w=108))
        self.sv.add_subview(c)
        return y + h + 14

    # ---------------------------------------------------------------
    def _storage_block(self, w, y):
        h = 150.0
        c = self._card(w, y, h, 'Хранилище')
        cw = c.width
        total, free = LIB.disk()
        used = LIB.used_bytes()

        rows = [
            ('Файлов в медиатеке', str(len(LIB.items))),
            ('Занято медиатекой', fmt_size(used)),
            ('Свободно на устройстве', fmt_size(free) if free else '—'),
        ]
        ry = 42.0
        for name, value in rows:
            c.add_subview(make_label(name, (F_REG, 12.5), TXT_2,
                                     frame=(16, ry, cw - 150, 18)))
            c.add_subview(make_label(value, (F_BOLD, 12.5), TXT, ui.ALIGN_RIGHT,
                                     frame=(cw - 150, ry, 134, 18)))
            ry += 24

        rb = Tappable(action=self._refresh, press_scale=0.95,
                      frame=(16, h - 46, cw - 32, 34))
        rb.background_color = CARD_3
        rb.corner_radius = 11
        rb.border_width = 1
        rb.border_color = BORDER_2
        rb.add_subview(Icon('refresh', ACCENT_2, 1.6, frame=(14, 9, 16, 16)))
        rb.add_subview(make_label('Обновить медиатеку', (F_REG, 13), TXT,
                                  frame=(38, 0, cw - 70, 34)))
        c.add_subview(rb)
        self.sv.add_subview(c)
        return y + h + 14

    def _refresh(self, sender):
        """Пересканировать NoxMedia и обновить все четыре экрана."""
        self.app.reload_all()
        nox_ok('Найдено файлов: %d' % len(LIB.items))

    # ---------------------------------------------------------------
    def _setup_block(self, w, y):
        body_w = w - PAD * 2 - 32
        lines = SETUP_TEXT.count('\n') + 1
        body_h = lines * 16.0 + 46
        h = 44 + body_h + 58
        c = self._card(w, y, h, 'Разовая настройка')
        cw = c.width
        c.add_subview(make_label(SETUP_TEXT, (F_REG, 11), TXT_2, lines=0,
                                 frame=(16, 40, body_w, body_h)))

        cb = Tappable(action=self._copy_setup, press_scale=0.95,
                      frame=(16, h - 46, cw - 32, 34))
        cb.background_color = CARD_3
        cb.corner_radius = 11
        cb.border_width = 1
        cb.border_color = BORDER_2
        cb.add_subview(Icon('clip', ACCENT_2, 1.5, frame=(14, 9, 16, 16)))
        cb.add_subview(make_label('Скопировать инструкцию', (F_REG, 13), TXT,
                                  frame=(38, 0, cw - 70, 34)))
        c.add_subview(cb)
        self.sv.add_subview(c)
        return y + h + 14

    def _copy_setup(self, sender):
        if clipboard is None:
            nox_error('Буфер обмена недоступен')
            return
        try:
            clipboard.set(SETUP_TEXT + '\n\nПапка медиатеки: ' + MEDIA_DIR)
            nox_ok('Скопировано')
        except Exception:
            nox_error('Не удалось скопировать')

    # ---------------------------------------------------------------
    def _about_block(self, w, y):
        h = 128.0
        c = self._card(w, y, h, 'О NOX')
        cw = c.width
        text = ('NOX %s — личная офлайн-медиатека.\n'
                'Загрузка выполняется локальным yt-dlp внутри приложения, '
                'воспроизведение — стандартным просмотрщиком iOS.\n'
                'Приложение показывает только реальные файлы из папки NoxMedia.'
                % APP_VERSION)
        c.add_subview(make_label(text, (F_REG, 11.5), TXT_3, lines=0,
                                 frame=(16, 40, cw - 32, 74)))
        self.sv.add_subview(c)
        return y + h


# =====================================================================
#  КОРНЕВОЕ ПРИЛОЖЕНИЕ
# =====================================================================

class NoxApp(ui.View):
    def __init__(self, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.name = APP_NAME
        self.background_color = BG
        self.top_inset = 0.0
        self.bottom_inset = 0.0
        self._alive = True
        self._tab = 0
        self._last_sig = ()
        self._last_tick = -1
        self.indeterminate_phase = 0.0
        self._logged_errors = set()
        self._recorded_history = set()
        self._resolving = False
        self._last_dirty = -1
        # Синхронизируемся со стартовым значением: иначе первый же такт
        # принял бы «изменение» за отработавшие extras и сделал reload_all.
        self._last_extras_tick = EXTRAS.tick

        STATE.ensure_folder()
        _load_yt_dlp()
        # Незавершённые загрузки прошлого запуска возвращаются как
        # приостановленные. Сами по себе они не стартуют никогда.
        try:
            DOWNLOADER.restore(STATE)
        except Exception as e:
            log_debug('restore: %r' % (e,))

        self.body = ui.View(frame=self.bounds)
        self.body.background_color = 'clear'
        self.add_subview(self.body)

        self.home = HomeScreen(self)
        self.downloads = DownloadsScreen(self)
        self.player = PlayerScreen(self)
        self.settings = SettingsScreen(self)
        self.screens = [self.home, self.downloads, self.player, self.settings]
        for i, s in enumerate(self.screens):
            s.background_color = 'clear'
            s.hidden = (i != 0)
            s.alpha = 1.0 if i == 0 else 0.0
            self.body.add_subview(s)

        self.tabbar = TabBar(self.select_tab)
        self.add_subview(self.tabbar)

    # ---------------------------------------------------------------
    def _compute_insets(self):
        try:
            sw, sh = ui.get_screen_size()
        except Exception:
            sw, sh = self.width, self.height
        notched = sh >= 800
        if self.height >= sh - 2:
            self.top_inset = 56.0 if notched else 24.0
            self.bottom_inset = 26.0 if notched else 8.0
        else:
            self.top_inset = 6.0
            self.bottom_inset = 20.0 if notched else 6.0

    def layout(self):
        self._compute_insets()
        nav_total = NAV_H + self.bottom_inset
        self.body.frame = (0, self.top_inset, self.width,
                           max(50.0, self.height - self.top_inset))
        # Каждый экран получает размер независимо: сбой перестроения одного
        # экрана не должен оставлять остальные с дефолтным кадром 100x100
        # (именно так на скриншоте узкая «Загрузки» легла поверх «Главной»).
        for s in self.screens:
            try:
                s.frame = self.body.bounds
            except Exception as e:
                nox_error(str(e))
        self.tabbar.frame = (0, self.height - nav_total, self.width, nav_total)

    def draw(self):
        # мягкая подсветка сверху, как на референсах
        w, h = self.width, self.height
        if w <= 0:
            return
        steps = 22
        band = h * 0.42 / steps
        for i in range(steps):
            t = i / float(steps - 1)
            ui.set_color(rgba('#131a3a', 0.30 * (1.0 - t)))
            ui.fill_rect(0, i * band, w, band + 1.0)

    # ---------------------------------------------------------------
    def select_tab(self, index):
        try:
            index = int(index)
        except Exception:
            return
        if not (0 <= index < len(self.screens)):
            return
        if index == self._tab:
            self.screens[index].rebuild()
            return
        self._tab = index
        self.tabbar.select(index)
        new = self.screens[index]
        # Видимость выставляется синхронно и не зависит от completion-блока
        # анимации: раньше старый экран прятался только в completion, и когда
        # тот падал, экран оставался на виду поверх нового.
        for i, s in enumerate(self.screens):
            if i == index:
                continue
            s.alpha = 0.0
            s.hidden = True
        new.hidden = False
        new.alpha = 0.0
        new.on_show()
        animate(lambda: setattr(new, 'alpha', 1.0), 0.22)

    def rebuild_screens(self):
        """Перестроить экраны по уже собранному состоянию, без чтения диска."""
        for s in self.screens:
            try:
                if self.body.width > 40:
                    s.frame = self.body.bounds
                s.rebuild()
            except Exception as e:
                nox_error(str(e))

    def reload_all(self):
        """
        ЕДИНСТВЕННОЕ место, где сканируется NoxMedia. Вызывается только на
        разовых событиях: старт приложения, «Обновить медиатеку», удаление
        файла и завершение загрузки. Во время активной загрузки диск,
        в который пишет рабочий поток, не читается.
        """
        ensure_dirs()
        try:
            LIB.scan()
        except Exception as e:
            nox_error(str(e))
        self.rebuild_screens()

    # ---------------------------------------------------------------
    @ui.in_background
    def open_media(self, item):
        if not os.path.exists(item.path):
            nox_error('Файл больше не существует')
            run_on_main(self.reload_all)
            return
        STATE.remember_opened(item.path)
        started = time.monotonic()
        try:
            console.quicklook(item.path)
        except Exception:
            nox_error('Не удалось открыть файл')
        # Просмотрщик iOS позицию не сообщает, поэтому засекается реальное
        # время, что он был открыт, и накапливается в watch_progress.
        try:
            STATE.watch_note(item.watch_id, time.monotonic() - started,
                             item.duration)
        except Exception as e:
            log_debug('watch_note: %r' % (e,))
        run_on_main(self._refresh_home_after_watch)

    def _refresh_home_after_watch(self):
        """Главный поток: обновить «Продолжить просмотр» после просмотра."""
        try:
            self.home.rebuild()
        except Exception as e:
            log_debug('refresh home: %r' % (e,))

    @ui.in_background
    def item_menu(self, item):
        try:
            choice = console.alert(safe_name(item.title, 30),
                                   item.meta_line or item.fmt_label,
                                   'Открыть', 'Показать источник', 'Удалить')
        except KeyboardInterrupt:
            return
        except Exception:
            return
        if choice == 1:
            self.open_media(item)
        elif choice == 2:
            src = item.webpage_url or 'Источник неизвестен'
            if clipboard is not None and item.webpage_url:
                try:
                    clipboard.set(item.webpage_url)
                except Exception:
                    pass
            try:
                console.alert('Источник', src, 'Закрыть',
                              hide_cancel_button=True)
            except KeyboardInterrupt:
                pass
            except Exception:
                pass
        elif choice == 3:
            self._delete(item)

    def _delete(self, item):
        try:
            console.alert('Удалить файл?',
                          safe_name(item.title, 40), 'Удалить')
        except KeyboardInterrupt:
            return
        except Exception:
            return
        # Только спутники ИМЕННО этого видео: .nox.json, обложка,
        # старый .info.json. Чужие файлы не трогаем.
        targets = [item.path] + item.sidecar_paths()
        try:
            STATE.watch_forget(item.watch_id)
        except Exception as e:
            log_debug('watch_forget: %r' % (e,))
        removed = False
        for t in targets:
            try:
                if os.path.isfile(t):
                    os.remove(t)
                    removed = True
            except Exception:
                pass
        lo = STATE.get('last_opened')
        if isinstance(lo, dict) and lo.get('path') == item.path:
            STATE.set('last_opened', None)
        if removed:
            nox_ok('Удалено')
        else:
            nox_error('Файл больше не существует')
        # Файлы уже удалены в фоне, а перечитывание папки и перестроение
        # экранов выполняет главный поток.
        run_on_main(self.reload_all)

    def toggle_job(self, job_id):
        """
        ⏸ — приостановить, ▶ — продолжить или повторить.

        Пауза оставляет .part нетронутым и освобождает очередь. Продолжение
        всегда идёт через СВЕЖИЙ разбор ссылки: прямой адрес живёт часы,
        а пауза может длиться сутки.
        """
        try:
            job = DOWNLOADER.find(job_id)
            if job is None:
                return
            if job.can_pause:
                if DOWNLOADER.pause(job_id):
                    nox_ok('Пауза')
            elif job.can_resume:
                if DOWNLOADER.resume(job_id):
                    nox_ok('Продолжаю' if job.downloaded_bytes else 'Повторяю')
        except Exception as e:
            nox_error(str(e))
            return
        self._after_job_button()

    def delete_job(self, job_id):
        """
        Крестик = удалить загрузку полностью: остановить передачу, удалить
        недокачанный файл, убрать задание и его сохранённое состояние.
        Если поток прямо сейчас пишет в файл, .part удалит он сам, когда
        закроет дескриптор, — карточка до этого показывает «Удаление...».
        """
        try:
            job = DOWNLOADER.find(job_id)
            if job is None:
                return
            if DOWNLOADER.delete(job_id):
                nox_ok('Удаляю загрузку' if job.status == ST_DELETING
                       else 'Загрузка удалена')
        except Exception as e:
            nox_error(str(e))
            return
        self._after_job_button()

    def delete_temp(self, path):
        """Крестик у недокачанного файла, за которым нет карточки."""
        if not path:
            return
        try:
            if os.path.exists(path):
                os.remove(path)
                nox_ok('Удалено')
            else:
                nox_error('Файл больше не существует')
        except Exception as e:
            nox_error(str(e))
            return
        try:
            ui.delay(self.reload_all, 0.05)
        except Exception:
            self.reload_all()

    def _after_job_button(self):
        """
        Экран нельзя разбирать прямо в обработчике касания его же кнопки:
        перестраиваем отложенно, когда touch-событие уже отработало.
        Одноразовый delay, не цепочка.
        """
        try:
            ui.delay(self.after_downloads_changed, 0.05)
        except Exception:
            self.after_downloads_changed()

    # ---------------------------------------------------------------
    def start_autorefresh(self):
        self._alive = True
        self._tick()

    def _tick(self):
        """
        ЕДИНСТВЕННЫЙ периодический ui.delay во всём приложении.

        Крутится на главном потоке — рабочий поток yt-dlp ui.View не трогает.
        Пока что-то качается, шаг UI_REFRESH (~4-5 раз в секунду), в покое —
        раз в IDLE_REFRESH. Здесь же двигается общая фаза бегунка, которую
        получают все неопределённые полосы: своих таймеров у них нет.
        """
        if not self._alive:
            return
        step = IDLE_REFRESH
        try:
            active = DOWNLOADER.active_jobs()
            if active or EXTRAS.busy() or EXTRAS.pending():
                step = UI_REFRESH
            if active:
                self.indeterminate_phase += 0.08
                if self.indeterminate_phase > 1.0:
                    self.indeterminate_phase = 0.0
            # Экран не гасим, только пока что-то реально качается или
            # разбирается. Пауза, ошибка и пустая очередь его не держат.
            keep_screen_awake(DOWNLOADER.awake_needed())
            self._resolve_pending()
            self._persist_jobs()
            self._persist_debug()
            self._record_history()
            self._drive_extras(active)
            sig = DOWNLOADER.signature()
            if sig != self._last_sig:
                # Состав очереди изменился. Сканируем диск, ТОЛЬКО если
                # задание исчезло (завершилось, снято): при добавлении
                # нового задания читать папку нельзя — в неё уже пишет
                # рабочий поток.
                gone = bool(set(self._last_sig) - set(sig))
                self.after_downloads_changed(rescan=gone)
            elif self._tab in (0, 1) and active \
                    and DOWNLOADER.tick != self._last_tick:
                # Смена статуса и прогресс — только точечное обновление,
                # без LIB.scan и без rebuild экранов.
                self._last_tick = DOWNLOADER.tick
                self.screens[self._tab].refresh_jobs(self.indeterminate_phase)
        except Exception as e:
            log_debug('tick: %r' % (e,))
        try:
            ui.delay(self._tick, step)
        except Exception:
            self._alive = False

    def tick_soon(self):
        """
        Два одноразовых шага и ни одного нового таймера: сначала показать
        карточку, следом — разобрать ссылку. _tick отсюда не вызывается,
        иначе появилась бы вторая периодическая цепочка.
        """
        try:
            if DOWNLOADER.signature() != self._last_sig:
                self.after_downloads_changed(rescan=False)
        except Exception as e:
            log_debug('tick_soon: %r' % (e,))
        run_on_main(self._resolve_pending)

    def sync_downloads(self):
        """Отметить текущий состав очереди как уже показанный."""
        self._last_sig = DOWNLOADER.signature()
        self._last_tick = DOWNLOADER.tick

    def after_downloads_changed(self, rescan=True):
        """
        Единичное событие: задание добавилось, ушло или завершилось.
        rescan=False — просто показать новую карточку, не трогая диск.
        """
        DOWNLOADER.clear_finished()
        self.sync_downloads()
        if rescan:
            self.reload_all()
        else:
            self.rebuild_screens()

    def _resolve_pending(self):
        """
        Единственное место, где вызывается yt-dlp, и всегда на ГЛАВНОМ
        потоке. За один заход разбирается одна ссылка: вызов блокирующий,
        интерфейс на это время замирает — осознанный размен на надёжность.
        """
        if self._resolving:
            return
        if DOWNLOADER.signature() != self._last_sig:
            return          # карточку ещё не показали — разбор следующим шагом
        pending = DOWNLOADER.pending_resolve()
        if not pending:
            return
        self._resolving = True
        try:
            DOWNLOADER.resolve(pending[0])
        except Exception as e:
            log_debug('resolve: %r' % (e,))
        finally:
            self._resolving = False

    def _drive_extras(self, active_downloads):
        """
        Метаданные и обложка ставятся в очередь только для уже finished
        задания и стартуют, лишь когда поток загрузки мёртв. Всё это —
        решения главного потока, сам ExtrasManager к UI не обращается.
        """
        if not ENABLE_EXTRAS:
            return
        try:
            for job in DOWNLOADER.all_jobs():
                if job.status != ST_FINISHED:
                    continue
                if job.extras_status != EXTRAS_NONE:
                    continue
                EXTRAS.enqueue(job)
            EXTRAS.pump(bool(active_downloads))
            if EXTRAS.tick != self._last_extras_tick:
                self._last_extras_tick = EXTRAS.tick
                if not EXTRAS.busy() and not EXTRAS.pending():
                    # Обложка и метаданные готовы — перечитываем папку,
                    # чтобы карточки подхватили их.
                    self.reload_all()
        except Exception as e:
            log_debug('extras: %r' % (e,))

    def _record_history(self):
        """
        История — отдельная сущность: пишется главным потоком, на
        DownloadManager и активную очередь не влияет.
        """
        for job in DOWNLOADER.all_jobs():
            if job.status not in (ST_FINISHED, ST_ERROR, ST_CANCELLED):
                continue
            if job.id in self._recorded_history:
                continue
            self._recorded_history.add(job.id)
            try:
                STATE.add_history({
                    'id': job.id,
                    'title': job.display_title,
                    'date': time.strftime('%Y-%m-%dT%H:%M:%S'),
                    'status': job.status,
                    'quality': job.quality,
                    'filesize': int(job.downloaded_bytes or 0),
                })
            except Exception as e:
                log_debug('history: %r' % (e,))

    def _persist_jobs(self):
        """
        Состав очереди на диск пишет ТОЛЬКО главный поток и только по
        структурным событиям: добавили, разобрали, поставили на паузу,
        удалили, завершили. На каждый процент прогресса state.json не
        трогается — источником правды для «сколько уже скачано» служит
        размер самого файла .part.
        """
        if DOWNLOADER.dirty == self._last_dirty:
            return
        self._last_dirty = DOWNLOADER.dirty
        try:
            DOWNLOADER.persist(STATE)
        except Exception as e:
            log_debug('persist: %r' % (e,))

    def _persist_debug(self):
        """
        Техническую причину сбоя пишет ГЛАВНЫЙ поток, и только после ошибки:
        NOX_Data/download_debug.txt. Рабочий поток к файлам логов не ходит.
        """
        for job in DOWNLOADER.all_jobs():
            if not job.debug_error or job.id in self._logged_errors:
                continue
            self._logged_errors.add(job.id)
            try:
                ensure_dirs()
                with io.open(DEBUG_LOG, 'a', encoding='utf-8') as f:
                    f.write('%s\t%s\t%s\t%s\t%s\n' % (
                        time.strftime('%Y-%m-%d %H:%M:%S'),
                        job.url, job.status,
                        job.error_stage or job.debug_stage, job.debug_error))
            except Exception:
                pass

    def will_close(self):
        self._alive = False
        # Очередь сохраняем последний раз и возвращаем экрану право гаснуть.
        try:
            DOWNLOADER.persist(STATE)
        except Exception:
            pass
        keep_screen_awake(False)
        try:
            ui.cancel_delays()
        except Exception:
            pass


# =====================================================================
#  ТОЧКА ВХОДА
# =====================================================================

def main():
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

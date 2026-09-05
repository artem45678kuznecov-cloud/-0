# coding: utf-8
"""
NOX - офлайн-медиатека для iPhone (Pythonista 3).

Один файл. Импортируйте в Pythonista и нажмите Run.

Архитектура (одна, рабочая):

    NOX (Pythonista)
        -> shortcuts://run-shortcut?name=NOX%20Download&text=<команда>
        -> Ярлык "NOX Download"
        -> a-Shell (действие "Execute Command")
        -> yt-dlp
        -> общая папка NoxMedia
        -> NOX видит файлы напрямую

Все пути считаются от самого NOX.py, никаких путей контейнера в коде нет:

    Файлы -> На iPhone -> NOX          <- PROJECT_DIR (папка с NOX.py)
        NOX.py
        NOX_icon.png                   <- необязательно
        NoxMedia/                      <- медиатека (одна для NOX и a-Shell)
        NOX_Data/state.json            <- настройки и состояние

a-Shell получает доступ к той же самой NoxMedia один раз командой pickFolder.

Инструкция по разовой настройке - на вкладке "Настройки" внутри приложения.
"""

import os
import io
import re
import sys
import json
import time
import shutil
import threading
import webbrowser

try:
    from urllib.parse import quote as _url_quote
except ImportError:                                    # pragma: no cover
    from urllib import quote as _url_quote             # type: ignore

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

# Сколько ждать реального появления файлов от yt-dlp, прежде чем считать
# запуск неудавшимся, и сколько после этого держать строку с ошибкой.
PENDING_TIMEOUT = 90.0
ERROR_TTL = 300.0

VIDEO_EXT   = ('.mp4', '.mov', '.m4v', '.mkv', '.webm')
IMAGE_EXT   = ('.jpg', '.jpeg', '.png')
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
    """Полоса прогресса. value=None -> неопределённый режим (бегунок)."""

    def __init__(self, value=None, track=BORDER, fill=ACCENT, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.user_interaction_enabled = False
        self.value = value
        self.track = track
        self.fill_color = fill
        self._phase = 0.0
        self._running = False

    def set_value(self, v):
        self.value = v
        redraw(self)

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

    def start_indeterminate(self):
        if self._running:
            return
        self._running = True
        self._tick()

    def stop(self):
        self._running = False

    def _tick(self):
        if not self._running:
            return
        if self.superview is None:
            self._running = False
            return
        self._phase += 0.035
        if self._phase > 1.0:
            self._phase = 0.0
        redraw(self)
        try:
            ui.delay(self._tick, 0.05)
        except Exception:
            self._running = False


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
STATE_PATH = os.path.join(DATA_DIR, 'state.json')
JOB_PATH = os.path.join(DATA_DIR, 'nox_job.json')
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


_LAST_ERROR = {'text': '', 'time': 0.0}


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
            'bookmark': 'NoxMedia',
            'use_shortcut': True,
            'last_opened': None,
            'jobs': [],
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
                    raw.pop('folder', None)      # путь больше не настраивается
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

    def last_opened_path(self):
        lo = self.data.get('last_opened')
        if not isinstance(lo, dict):
            return None
        p = lo.get('path')
        if p and os.path.exists(p):
            return p
        return None

    # --- задания на загрузку ---
    def add_job(self, url, quality):
        """
        Запоминаем снимок папки на момент старта: любой НОВЫЙ файл в NoxMedia
        означает, что yt-dlp реально начал работу (он всегда пишет .info.json).
        """
        jobs = [j for j in self.data.get('jobs', []) if isinstance(j, dict)]
        jobs = [j for j in jobs if j.get('url') != url]
        try:
            known = sorted(os.listdir(MEDIA_DIR))[:500]
        except Exception:
            known = []
        jobs.append({'url': url, 'quality': quality, 'time': time.time(),
                     'known': known})
        self.data['jobs'] = jobs[-8:]
        self.save()

    def drop_job(self, url):
        jobs = [j for j in self.data.get('jobs', []) if isinstance(j, dict)]
        before = len(jobs)
        self.data['jobs'] = [j for j in jobs if j.get('url') != url]
        self.save()
        return len(self.data['jobs']) != before

    def raw_jobs(self):
        return [j for j in self.data.get('jobs', []) if isinstance(j, dict)]

    def set_jobs(self, jobs):
        if jobs != self.data.get('jobs'):
            self.data['jobs'] = jobs
            self.save()


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
        self.title = self.stem
        self.duration = None
        self.uploader = ''
        self.video_id = ''
        self.webpage_url = ''
        self.thumb_path = None
        self._load_info()
        self._find_thumb()

    def _load_info(self):
        folder = os.path.dirname(self.path)
        candidates = [self.stem + '.info.json']
        # yt-dlp иногда добавляет суффикс формата: "name.f137.mp4"
        base = re.sub(r'\.f\d+$', '', self.stem)
        if base != self.stem:
            candidates.append(base + '.info.json')
        for c in candidates:
            p = os.path.join(folder, c)
            if not os.path.exists(p):
                continue
            try:
                with io.open(p, 'r', encoding='utf-8') as f:
                    data = json.load(f)
            except Exception:
                continue
            if not isinstance(data, dict):
                continue
            self.info = data
            t = data.get('title')
            if isinstance(t, str) and t.strip():
                self.title = t.strip()
            d = data.get('duration')
            if isinstance(d, (int, float)) and d > 0:
                self.duration = d
            up = data.get('uploader') or data.get('channel') or ''
            if isinstance(up, str):
                self.uploader = up.strip()
            vid = data.get('id')
            if isinstance(vid, str):
                self.video_id = vid
            wu = data.get('webpage_url')
            if isinstance(wu, str):
                self.webpage_url = wu
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
        if not self.thumb_path:
            return None
        try:
            return ui.Image.named(self.thumb_path)
        except Exception:
            pass
        try:
            with io.open(self.thumb_path, 'rb') as f:
                return ui.Image.from_data(f.read())
        except Exception:
            return None

    @property
    def meta_line(self):
        parts = []
        d = fmt_duration(self.duration) if self.duration else ''
        if d:
            parts.append(d)
        if self.size:
            parts.append(fmt_size(self.size))
        return '  •  '.join(parts)

    @property
    def fmt_label(self):
        return self.ext.lstrip('.').upper()


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
        self.error = ''

    def scan(self):
        self.items = []
        self.temps = []
        self.names = []
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
            low = n.lower()
            if low.endswith(TEMP_EXT) or '.part' in low:
                self.temps.append(TempItem(p))
                continue
            if low.endswith(VIDEO_EXT):
                self.items.append(MediaItem(p))
        self.items.sort(key=lambda i: i.mtime, reverse=True)
        self.temps.sort(key=lambda i: i.mtime, reverse=True)

    def jobs(self):
        """
        Задания NOX, ожидающие подтверждения от yt-dlp.

        Подтверждение — появление в NoxMedia НОВОГО файла относительно снимка,
        сделанного при запуске (yt-dlp с --write-info-json создаёт его сразу).
        Подтверждённое задание убирается: дальше его показывает реальный .part.
        Неподтверждённое живёт PENDING_TIMEOUT секунд, затем становится ошибкой
        и через ERROR_TTL исчезает само — вечных «Подготовка...» больше нет.
        """
        now = time.time()
        current = set(self.names)
        keep = []
        out = []
        for j in self.state.raw_jobs():
            url = j.get('url')
            if not url:
                continue
            started = float(j.get('time', 0) or 0)
            known = set(j.get('known') or [])
            if current - known:
                continue                       # yt-dlp реально начал работу
            age = now - started
            if age >= PENDING_TIMEOUT + ERROR_TTL:
                continue                       # ошибка отвисела своё — убираем
            keep.append(j)
            out.append({
                'url': url,
                'quality': j.get('quality', ''),
                'time': started,
                'status': 'pending' if age < PENDING_TIMEOUT else 'error',
            })
        self.state.set_jobs(keep)
        return out

    def filtered(self, query):
        q = (query or '').strip().lower()
        if not q:
            return list(self.items)
        out = []
        for i in self.items:
            hay = (i.title + ' ' + i.name + ' ' + i.uploader).lower()
            if q in hay:
                out.append(i)
        return out

    def used_bytes(self):
        total = 0
        for i in self.items:
            total += i.size
        for t in self.temps:
            total += t.size
        return total

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
#  ЗАПУСК ЗАГРУЗКИ (a-Shell + yt-dlp)
# =====================================================================

def format_selector(quality):
    """
    Приоритет: точный прямой urlXXX -> ближайший меньший прямой ->
    single-file (видео+аудио) -> любой совместимый.
    Никаких video-only + audio-only (ffmpeg не требуется).
    """
    if quality == 'MAX':
        chain = list(DIRECT_LADDER)
    else:
        key = 'url' + str(quality)
        if key in DIRECT_LADDER:
            chain = DIRECT_LADDER[DIRECT_LADDER.index(key):]
        else:
            chain = list(DIRECT_LADDER)
    chain = chain + [
        'best[ext=mp4][vcodec!=none][acodec!=none]',
        'best[vcodec!=none][acodec!=none]',
        'best',
    ]
    return '/'.join(chain)


URL_RE = re.compile(r'^https?://[^\s"\'`\\]+$', re.IGNORECASE)


def validate_url(url):
    url = (url or '').strip()
    if not url:
        return None, 'Ссылка не указана'
    if not URL_RE.match(url):
        return None, 'Ссылка выглядит некорректно'
    return url, None


def build_command(url, quality, bookmark):
    fmt = format_selector(quality)
    out = '%(title)s [%(id)s].%(ext)s'
    # a-Shell не разбирает «&&» — он воспринял всю строку как один вызов и
    # напечатал Usage. Execute Command должен получить ДВЕ команды,
    # разделённые переводом строки; закладка адресуется как ~NoxMedia.
    return (
        'cd ~{bm}\n'
        'yt-dlp -f "{fmt}" --continue --retries infinite '
        '--write-info-json --write-thumbnail -o "{out}" "{url}"'
    ).format(bm=bookmark, fmt=fmt, out=out, url=url)


def write_job_file(url, quality, folder):
    """Дублируем задание в NOX_Data — на случай ручного запуска."""
    try:
        ensure_dirs()
        payload = {'url': url, 'quality': quality, 'folder': folder,
                   'created': time.time()}
        with io.open(JOB_PATH, 'w', encoding='utf-8') as f:
            f.write(json.dumps(payload, ensure_ascii=False, indent=1))
    except Exception:
        pass


def start_download(url, quality):
    """Возвращает (ok, сообщение)."""
    url, err = validate_url(url)
    if err:
        return False, err
    if quality not in [q[0] for q in QUALITIES]:
        return False, 'Такое качество недоступно'
    if not STATE.ensure_folder():
        return False, 'Папка NOX недоступна'

    bookmark = (STATE.get('bookmark') or 'NoxMedia').strip() or 'NoxMedia'
    cmd = build_command(url, quality, bookmark)

    write_job_file(url, quality, STATE.folder)

    if clipboard is not None:
        try:
            clipboard.set(cmd)
        except Exception:
            pass

    STATE.add_job(url, quality)

    if not STATE.get('use_shortcut', True):
        return True, 'Команда скопирована. Вставьте её в a-Shell.'

    try:
        target = ('shortcuts://run-shortcut?name=NOX%20Download&input=text&text='
                  + _url_quote(cmd, safe=''))
        webbrowser.open(target)
    except Exception:
        return False, 'Не удалось запустить загрузку'
    return True, 'Передано в a-Shell'


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
        try:
            img = ui.Image.named(ICON_PATH)
        except Exception:
            img = None
        if img is None:
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
        self._ready = True

    def layout(self):
        if not self._ready:
            return
        self.sv.frame = self.bounds
        if self.width > 40 and abs(self.width - self._built_w) > 0.5:
            self.rebuild()

    def clear(self):
        for v in list(self.sv.subviews):
            self.sv.remove_subview(v)

    # -- крестик «убрать из очереди NOX» -----------------------------
    def _dismiss_button(self, job, x, y, w=26.0, h=28.0):
        """
        Хит-таргет — настоящий прозрачный ui.Button, как у кнопки «Скачать».
        Крестик убирает только запись NOX; процесс yt-dlp он не трогает,
        поэтому и появляется лишь у неподтверждённых и ошибочных заданий.
        """
        holder = ui.View(frame=(x, y, w, h))
        holder.background_color = 'clear'
        holder.add_subview(Icon('close', TXT_3, 1.5,
                                frame=(w / 2 - 7, h / 2 - 7, 14, 14)))
        hit = ui.Button(frame=holder.bounds)
        hit.flex = 'WH'
        hit.background_color = 'clear'
        hit.action = self._make_dismiss(job)
        holder.add_subview(hit)
        return holder

    def _make_dismiss(self, job):
        def _act(sender):
            self.app.cancel_job(job)
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
            self.build()
            self._built_w = self.width
        except Exception as e:
            self.clear()
            self._built_w = -1.0
            nox_error(str(e))
        finally:
            self._building = False

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

    # ---------------------------------------------------------------
    def build(self):
        w = self.width
        LIB.scan()
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

        tune = Icon('tune', TXT_2, 1.7, frame=(box.width - 42, h / 2 - 11, 22, 22))
        box.add_subview(tune)
        self.sv.add_subview(box)
        return y + h

    # ---------------------------------------------------------------
    def _build_continue(self, w, y):
        path = STATE.last_opened_path()
        if not path:
            return y                       # блок полностью скрыт
        item = MediaItem(path)
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
        if item.duration:
            meta_parts.append(fmt_duration(item.duration))
        meta_parts.append(fmt_size(item.size))
        if item.uploader:
            meta_parts.append(safe_name(item.uploader, 18))
        meta = make_label('  •  '.join([m for m in meta_parts if m]),
                          (F_REG, 12), TXT_2, frame=(18, 72, cw - 130, 16))
        card.add_subview(meta)

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
        temps = LIB.temps
        jobs = LIB.jobs()
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
        rows = 0
        for t in temps[:4]:
            r = self._download_row(box.width, by, t.title, t, None)
            box.add_subview(r)
            by += r.height
            rows += 1
        for j in jobs[:3]:
            r = self._download_row(box.width, by, safe_name(j.get('url', ''), 30),
                                   None, j)
            box.add_subview(r)
            by += r.height
            rows += 1
        box.height = max(60.0, by)
        self.sv.add_subview(box)
        return y + box.height

    def _download_row(self, w, y, title, temp, job):
        h = 74.0
        v = ui.View(frame=(0, y, w, h))
        v.background_color = 'clear'

        th = ThumbView(None, frame=(12, 12, 48, 50))
        th.corner_radius = 9
        th.border_width = 1
        th.border_color = BORDER
        v.add_subview(th)

        tl = make_label(safe_name(title, 26), (F_BOLD, 13), TXT,
                        frame=(70, 14, w - 70 - 118, 17))
        v.add_subview(tl)

        if temp is not None:
            prog = temp.progress
            if prog is None:
                sub = 'Скачивается  •  ' + fmt_size(temp.size)
            else:
                sub = '%s из %s' % (fmt_size(temp.size), fmt_size(temp.total))
            sl = make_label(sub, (F_REG, 10.5), TXT_3,
                            frame=(70, 31, w - 70 - 118, 14))
            v.add_subview(sl)
            bar = ProgressBar(prog, frame=(70, 50, w - 70 - 118, 5))
            v.add_subview(bar)
            if prog is None:
                bar.start_indeterminate()
                st_text, st_icon = 'Скачивается', 'ring'
            else:
                st_text, st_icon = '%d%%' % int(prog * 100), 'download'
        elif job.get('status') == 'error':
            sl = make_label('Не удалось начать загрузку', (F_REG, 10.5), ERR_TXT,
                            frame=(70, 31, w - 70 - 118, 14))
            v.add_subview(sl)
            bar = ProgressBar(0.0, frame=(70, 50, w - 70 - 118, 5))
            v.add_subview(bar)
            st_text, st_icon, st_col = 'Ошибка', 'close', ERR_TXT
        else:
            sl = make_label('Подготовка...', (F_REG, 10.5), TXT_3,
                            frame=(70, 31, w - 70 - 118, 14))
            v.add_subview(sl)
            bar = ProgressBar(None, frame=(70, 50, w - 70 - 118, 5))
            v.add_subview(bar)
            bar.start_indeterminate()
            st_text, st_icon, st_col = 'Подготовка', 'ring', ACCENT_2

        if temp is not None:
            st_col = ACCENT_2
        ic = Icon(st_icon, st_col, 1.6, frame=(w - 112, h / 2 - 10, 20, 20))
        v.add_subview(ic)
        st = make_label(st_text, (F_REG, 11), st_col,
                        frame=(w - 88, h / 2 - 9, 58, 18))
        v.add_subview(st)

        if job is not None:
            v.add_subview(self._dismiss_button(job, w - 30, h / 2 - 14))

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
        w = self.width
        LIB.scan()
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
        self.sv.add_subview(make_label('Для больших загрузок не закрывайте a-Shell.',
                                       (F_REG, 10.5), TXT_4, ui.ALIGN_CENTER,
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
        ok, msg = start_download(url, self.quality)
        if ok:
            nox_ok(msg)
            self.url_text = ''
            if self._url_field is not None:
                self._url_field.text = ''
            try:
                ui.delay(self.rebuild, 0.6)
            except Exception:
                self.rebuild()
        else:
            nox_error(msg)

    # ---------------------------------------------------------------
    def _build_queue(self, w, y):
        temps = LIB.temps
        jobs = LIB.jobs()
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

        for t in temps:
            card = self._queue_card(w, y, t.title, t, None)
            self.sv.add_subview(card)
            y += card.height + 10
        for j in jobs:
            card = self._queue_card(w, y, safe_name(j.get('url', ''), 32), None, j)
            self.sv.add_subview(card)
            y += card.height + 10
        return y + 10

    def _queue_card(self, w, y, title, temp, job):
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
        c.add_subview(make_label(safe_name(title, 24), (F_BOLD, 14), TXT,
                                 frame=(left, 14, cw - left - right_w, 18)))

        if temp is not None:
            prog = temp.progress
            if prog is None:
                sub = 'Скачивается  •  ' + fmt_size(temp.size)
                st_text = 'Скачивается'
            else:
                sub = '%s из %s' % (fmt_size(temp.size), fmt_size(temp.total))
                st_text = '%d%%' % int(prog * 100)
            c.add_subview(make_label(sub, (F_REG, 11), TXT_3,
                                     frame=(left, 33, cw - left - right_w, 15)))
            bar = ProgressBar(prog, frame=(left, 56, cw - left - 16, 5))
            c.add_subview(bar)
            if prog is None:
                bar.start_indeterminate()
            c.add_subview(make_label(st_text, (F_BOLD, 12), ACCENT_2, ui.ALIGN_RIGHT,
                                     frame=(cw - right_w - 4, 14, right_w - 44, 18)))
            # Раньше здесь стоял значок паузы: остановить процесс yt-dlp
            # из Pythonista нечем, и кнопка обещала то, чего сделать нельзя.
            # Значок заменён на нейтральный индикатор идущей загрузки.
            pz = ui.View(frame=(cw - 46, 16, 34, 34))
            pz.background_color = 'clear'
            pz.corner_radius = 17
            pz.border_width = 1
            pz.border_color = BORDER_2
            pz.user_interaction_enabled = False
            pz.add_subview(Icon('download', ACCENT_2, 1.5, frame=(10, 10, 14, 14)))
            c.add_subview(pz)
        elif job.get('status') == 'error':
            c.add_subview(make_label('Не удалось начать загрузку', (F_REG, 11),
                                     ERR_TXT,
                                     frame=(left, 33, cw - left - right_w, 15)))
            c.add_subview(ProgressBar(0.0, frame=(left, 56, cw - left - 16, 5)))
            c.add_subview(Icon('close', ERR_TXT, 1.6, frame=(cw - 118, 24, 20, 20)))
            c.add_subview(make_label('Ошибка', (F_REG, 12), ERR_TXT,
                                     frame=(cw - 94, 24, 76, 20)))
            c.add_subview(self._dismiss_button(job, cw - 34, 22, 26, 26))
        else:
            c.add_subview(make_label('Подготовка...', (F_REG, 11), TXT_3,
                                     frame=(left, 33, cw - left - right_w, 15)))
            bar = ProgressBar(None, frame=(left, 56, cw - left - 16, 5))
            c.add_subview(bar)
            bar.start_indeterminate()
            c.add_subview(Icon('ring', ACCENT_2, 1.6, frame=(cw - 118, 24, 20, 20)))
            c.add_subview(make_label('Подготовка', (F_REG, 12), TXT_2,
                                     frame=(cw - 94, 24, 76, 20)))
            c.add_subview(self._dismiss_button(job, cw - 34, 22, 26, 26))
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
        w = self.width
        LIB.scan()
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
    '1.  NOX сам создал рядом с NOX.py две папки:\n'
    '     Файлы → На iPhone → NOX → NoxMedia\n'
    '     Файлы → На iPhone → NOX → NOX_Data\n\n'
    '2.  Откройте a-Shell и выполните:  pickFolder\n'
    '     Выберите именно На iPhone → NOX → NoxMedia\n'
    '     и нажмите «Открыть».\n'
    '     Проверьте имя закладки командой:  showmarks\n'
    '     Если имя другое — впишите его в поле «Закладка a-Shell».\n\n'
    '3.  Создайте Ярлык (приложение «Быстрые команды»):\n'
    '     • Новый ярлык, имя ровно:  NOX Download\n'
    '     • Добавьте одно действие приложения a-Shell — «Execute Command»\n'
    '       («Выполнить команду»).\n'
    '     • В поле команды вставьте переменную «Вход ярлыка»\n'
    '       (Shortcut Input) и сохраните.\n\n'
    '4.  Уже скачанное ранее видео перенесите в NOX → NoxMedia\n'
    '     вместе с его .info.json и нажмите «Обновить медиатеку».\n\n'
    '5.  Готово. Кнопка «Скачать» передаёт команду в a-Shell.\n'
    '     Команда также всегда копируется в буфер обмена —\n'
    '     её можно вставить в a-Shell вручную.'
)


class SettingsScreen(Screen):
    _chips = ()

    def __init__(self, app, **kwargs):
        self._chips = []
        Screen.__init__(self, app, **kwargs)

    def build(self):
        w = self.width
        LIB.scan()
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
        c.add_subview(make_label('Рядом с NOX.py — одна папка для NOX и a-Shell',
                                 (F_REG, 10.5), TXT_3, lines=2,
                                 frame=(16, 60, cw - 32, 28)))
        exists = os.path.isdir(MEDIA_DIR)
        c.add_subview(status_pill('Доступна' if exists else 'Недоступна',
                                  'check' if exists else 'close',
                                  ACCENT_2 if exists else '#ff6b81',
                                  16, 92, w=104))

        c.add_subview(make_label('Закладка a-Shell', (F_REG, 12.5), TXT_2,
                                 frame=(16, 132, cw - 150, 18)))
        c.add_subview(make_label(STATE.get('bookmark', 'NoxMedia'),
                                 (F_BOLD, 12.5), ACCENT_2,
                                 frame=(16, 151, cw - 150, 18)))
        # Проверить bookmark из Pythonista невозможно — статус не выдумываем.
        c.add_subview(make_label('Настраивается один раз через a-Shell → pickFolder',
                                 (F_REG, 10), TXT_4, lines=2,
                                 frame=(16, 170, cw - 32, 16)))
        bmb = Tappable(action=self._change_bookmark, press_scale=0.94,
                       frame=(cw - 16 - 108, 138, 108, 30))
        bmb.background_color = CARD_3
        bmb.corner_radius = 10
        bmb.border_width = 1
        bmb.border_color = BORDER_2
        bmb.add_subview(make_label('Изменить', (F_REG, 11.5), TXT,
                                   ui.ALIGN_CENTER, frame=(0, 0, 108, 30)))
        c.add_subview(bmb)
        self.sv.add_subview(c)
        return y + h + 14

    @ui.in_background
    def _change_bookmark(self, sender):
        try:
            new = console.input_alert('Закладка a-Shell',
                                      'Имя закладки из команды showmarks',
                                      STATE.get('bookmark', 'NoxMedia'),
                                      'Сохранить')
        except KeyboardInterrupt:
            return
        except Exception:
            return
        new = (new or '').strip()
        if not new:
            return
        STATE.set('bookmark', re.sub(r'\s+', '', new))
        self.rebuild()

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
                'Загрузка выполняется связкой a-Shell + yt-dlp в общую папку, '
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

        STATE.ensure_folder()

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

    def reload_all(self):
        """«Обновить медиатеку»: пересканировать папку и перестроить всё."""
        ensure_dirs()
        try:
            LIB.scan()
        except Exception as e:
            nox_error(str(e))
        for s in self.screens:
            try:
                if self.body.width > 40:
                    s.frame = self.body.bounds
                s.rebuild()
            except Exception as e:
                nox_error(str(e))

    # ---------------------------------------------------------------
    @ui.in_background
    def open_media(self, item):
        if not os.path.exists(item.path):
            nox_error('Файл больше не существует')
            self.reload_all()
            return
        STATE.remember_opened(item.path)
        try:
            console.quicklook(item.path)
        except Exception:
            nox_error('Не удалось открыть файл')
        try:
            self.home.rebuild()
        except Exception:
            pass

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
        folder = os.path.dirname(item.path)
        base = re.sub(r'\.f\d+$', '', item.stem)
        targets = [item.path]
        for stem in set([item.stem, base]):
            targets.append(os.path.join(folder, stem + '.info.json'))
            for ext in IMAGE_EXT:
                targets.append(os.path.join(folder, stem + ext))
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
        self.reload_all()

    def cancel_job(self, job):
        """
        Убирает ТОЛЬКО запись NOX. Процесс yt-dlp в a-Shell при этом не
        останавливается — остановить его отсюда нечем, и делать вид, что
        остановили, мы не будем. Поэтому крестик есть лишь у заданий, по
        которым yt-dlp ещё не подтвердил старт (или уже сообщил об ошибке).
        """
        try:
            STATE.drop_job((job or {}).get('url'))
        except Exception as e:
            nox_error(str(e))
            return
        nox_ok('Убрано из очереди NOX')
        # Экран нельзя разбирать прямо в обработчике касания его же кнопки:
        # перестраиваем отложенно, когда touch-событие уже отработало.
        try:
            ui.delay(self.reload_all, 0.05)
        except Exception:
            self.reload_all()

    # ---------------------------------------------------------------
    def start_autorefresh(self):
        self._alive = True
        self._tick()

    def _tick(self):
        if not self._alive:
            return
        try:
            if self._tab in (0, 1):
                folder = STATE.folder
                busy = False
                try:
                    for n in os.listdir(folder):
                        low = n.lower()
                        if low.endswith(TEMP_EXT) or '.part' in low:
                            busy = True
                            break
                except Exception:
                    busy = False
                if busy or STATE.raw_jobs():
                    self.screens[self._tab].rebuild()
        except Exception:
            pass
        try:
            ui.delay(self._tick, 3.0)
        except Exception:
            self._alive = False

    def will_close(self):
        self._alive = False
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

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

VIDEO_EXT   = ('.mp4', '.mov', '.m4v', '.mkv', '.webm')
# .webp yt-dlp пишет часто, но Pythonista его обычно не декодирует:
# такой файл найдётся, картинка не откроется — и карточка честно останется
# минималистичной, без выдуманной обложки.
IMAGE_EXT   = ('.jpg', '.jpeg', '.png', '.webp')
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
YT_DLP_DIR = os.path.join(PROJECT_DIR, 'yt_dlp')
STATE_PATH = os.path.join(DATA_DIR, 'state.json')
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


def _load_yt_dlp():
    """
    Импортирует ИМЕННО тот yt_dlp, что лежит рядом с NOX.py: PROJECT_DIR
    ставится первым в sys.path. Системные пути не хардкодятся.
    Неудача не роняет приложение — она превращается в понятную ошибку.
    """
    global yt_dlp, YTDLP_ERROR, YTDLP_VERSION
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
        print('NOX: не удалось импортировать yt_dlp: %r' % (e,))
        return None
    got = os.path.dirname(os.path.abspath(getattr(_mod, '__file__', '') or ''))
    if os.path.normpath(got) != os.path.normpath(YT_DLP_DIR):
        print('NOX: yt_dlp импортирован не из папки проекта: %s' % got)
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

    def orphan_temps(self, active_paths):
        """
        Незавершённые файлы, за которыми НЕ стоит живой DownloadJob.
        После перезапуска NOX такие .part показываются как «Не завершено»,
        а не как идущая прямо сейчас загрузка.
        """
        busy = set()
        for p in active_paths or ():
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


def ffmpeg_available():
    """
    Склейка video-only + audio-only допустима только при наличии ffmpeg.
    В Pythonista его, как правило, нет — тогда такие форматы не выбираются.
    """
    mod = _load_yt_dlp()
    if mod is not None:
        try:
            from yt_dlp.postprocessor.ffmpeg import FFmpegPostProcessor
            pp = FFmpegPostProcessor()
            if getattr(pp, 'available', False):
                return True
        except Exception:
            pass
    try:
        return bool(shutil.which('ffmpeg'))
    except Exception:
        return False


HEIGHTS = {'360': 360, '480': 480, '720': 720}
# Прямые combined-форматы VK: они уже содержат звук.
VK_DIRECT = {
    '360': ['url360', 'url240', 'url144'],
    '480': ['url480', 'url360', 'url240', 'url144'],
    '720': ['url720', 'url480', 'url360', 'url240', 'url144'],
    'MAX': ['url2160', 'url1440', 'url1080', 'url720',
            'url480', 'url360', 'url240', 'url144'],
}


def format_selector(quality, allow_merge=None):
    """
    Сначала готовый файл со звуком (progressive/combined), только потом —
    склейка, и лишь если ffmpeg реально доступен.
    'best' в yt-dlp по определению возвращает формат с видео И аудио.
    """
    if allow_merge is None:
        allow_merge = ffmpeg_available()
    h = HEIGHTS.get(quality)
    parts = list(VK_DIRECT.get(quality, []))
    if h:
        parts.append('best[height<=%d][ext=mp4]' % h)
        parts.append('best[height<=%d]' % h)
    else:
        parts.append('best[ext=mp4]')
    if allow_merge:
        if h:
            parts.append('bestvideo[height<=%d]+bestaudio' % h)
        else:
            parts.append('bestvideo+bestaudio')
    parts.append('best')
    return '/'.join(parts)


class DownloadCancelledByUser(Exception):
    """Запасное исключение отмены, если DownloadCancelled нет в этой версии."""
    pass


def _cancel_exception():
    mod = _load_yt_dlp()
    if mod is not None:
        try:
            from yt_dlp.utils import DownloadCancelled
            return DownloadCancelled
        except Exception:
            pass
    return DownloadCancelledByUser


class _QuietLogger(object):
    """
    Интерфейс получает короткий текст, но настоящая причина не теряется:
    всё, что говорит yt-dlp, печатается в консоль Pythonista.
    Глушится только служебный поток [debug].
    """

    def debug(self, msg):
        text = str(msg)
        if text.startswith('[debug] '):
            return
        print('NOX yt-dlp: %s' % text)

    def info(self, msg):
        print('NOX yt-dlp: %s' % msg)

    def warning(self, msg):
        print('NOX yt-dlp: %s' % msg)

    def error(self, msg):
        print('NOX yt-dlp: %s' % msg)


# Признаки сетевого сбоя: после такого делается одна повторная попытка по IPv4.
NETWORK_HINTS = (
    'timed out', 'timeout', 'urlopen error', 'connection reset',
    'connection aborted', 'connection refused', 'connection error',
    'temporary failure in name resolution', 'name or service not known',
    'network is unreachable', 'no route to host', 'getaddrinfo',
    'unable to download webpage', 'eof occurred', 'remote end closed',
)


def is_network_error(exc):
    text = ('%r %s' % (exc, exc)).lower()
    return any(hint in text for hint in NETWORK_HINTS)


ST_QUEUED = 'queued'
ST_PREPARING = 'preparing'
ST_DOWNLOADING = 'downloading'
ST_PROCESSING = 'processing'
ST_FINISHED = 'finished'
ST_ERROR = 'error'
ST_CANCELLED = 'cancelled'

ACTIVE_STATES = (ST_QUEUED, ST_PREPARING, ST_DOWNLOADING, ST_PROCESSING)

STATUS_TEXT = {
    ST_QUEUED: 'В очереди',
    ST_PREPARING: 'Получение информации...',
    ST_DOWNLOADING: 'Скачивается',
    ST_PROCESSING: 'Обработка файла...',
    ST_FINISHED: 'Готово',
    ST_ERROR: 'Ошибка загрузки',
    ST_CANCELLED: 'Остановлено',
}

STATUS_SHORT = {
    ST_QUEUED: 'В очереди',
    ST_PREPARING: 'Подготовка',
    ST_DOWNLOADING: 'Скачивается',
    ST_PROCESSING: 'Обработка',
    ST_FINISHED: 'Готово',
    ST_ERROR: 'Ошибка',
    ST_CANCELLED: 'Остановлено',
}


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
        self.started_at = time.time()
        self.finished_at = None
        self.cancel_requested = False

    @property
    def total(self):
        """Точный размер, иначе оценка yt-dlp, иначе None. Ничего не выдумываем."""
        for v in (self.total_bytes, self.total_bytes_estimate):
            try:
                if v and float(v) > 0:
                    return float(v)
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
        if self.status == ST_ERROR and self.error:
            return safe_name(self.error, 46)
        return STATUS_TEXT.get(self.status, '')

    def detail_line(self):
        """Вторая строка: процент, скорость, остаток — только реальные."""
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
    Живёт на уровне приложения, а не экрана: перестроение интерфейса и
    переключение вкладок его не трогают. Режим последовательный — один
    рабочий поток, остальные задания ждут в очереди.
    """

    def __init__(self):
        self.jobs = []
        self.revision = 0        # меняется при структурных изменениях
        self.tick = 0            # меняется на каждом обновлении прогресса
        self._lock = threading.RLock()
        self._thread = None

    # -- чтение состояния ------------------------------------------
    def all_jobs(self):
        with self._lock:
            return list(self.jobs)

    def active_jobs(self):
        return [j for j in self.all_jobs() if j.is_active]

    def visible_jobs(self):
        """Активные + недавно завершившиеся с ошибкой/остановкой."""
        return [j for j in self.all_jobs()
                if j.is_active or j.status in (ST_ERROR, ST_CANCELLED)]

    def active_paths(self):
        return [j.filename for j in self.active_jobs() if j.filename]

    def find(self, job_id):
        for j in self.all_jobs():
            if j.id == job_id:
                return j
        return None

    def signature(self):
        """Структурный отпечаток: меняется — значит нужен полный rebuild."""
        return tuple((j.id, j.status) for j in self.visible_jobs())

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
        self._pump()
        return True, 'Добавлено в очередь'

    def cancel(self, job_id):
        """Настоящая отмена: рабочий поток прервётся на ближайшем hook."""
        job = self.find(job_id)
        if job is None:
            return False
        if job.is_active:
            job.cancel_requested = True
            if job.status == ST_QUEUED:
                job.status = ST_CANCELLED
                job.finished_at = time.time()
        with self._lock:
            self.revision += 1
        self._pump()
        return True

    def remove(self, job_id):
        job = self.find(job_id)
        if job is None:
            return False
        if job.is_active:
            job.cancel_requested = True
        with self._lock:
            self.jobs = [j for j in self.jobs if j.id != job_id]
            self.revision += 1
        return True

    def clear_finished(self):
        with self._lock:
            before = len(self.jobs)
            self.jobs = [j for j in self.jobs if j.status != ST_FINISHED]
            if len(self.jobs) != before:
                self.revision += 1

    # -- рабочий поток ---------------------------------------------
    def _pump(self):
        """
        Запускает следующее задание, только если поток не занят.
        Вызывается из add/cancel и из самого потока по завершении, поэтому
        перестроение интерфейса второй поток создать не может.
        """
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            nxt = None
            for j in self.jobs:
                if j.status == ST_QUEUED and not j.cancel_requested:
                    nxt = j
                    break
            if nxt is None:
                self._thread = None
                return
            nxt.status = ST_PREPARING
            self.revision += 1
            self._thread = threading.Thread(target=self._run, args=(nxt,),
                                            name='nox-download', daemon=True)
            self._thread.start()

    def _hook(self, job, d):
        if job.cancel_requested:
            raise _cancel_exception()('Загрузка остановлена пользователем')
        # Отдельного metadata-запроса больше нет, поэтому название приходит
        # сюда — с первым же вызовом hook, как только yt-dlp его знает.
        info = d.get('info_dict') or {}
        title = info.get('title')
        if isinstance(title, str) and title.strip():
            job.title = title.strip()
        st = d.get('status')
        if st == 'downloading':
            job.status = ST_DOWNLOADING
            job.downloaded_bytes = d.get('downloaded_bytes') or 0
            job.total_bytes = d.get('total_bytes')
            job.total_bytes_estimate = d.get('total_bytes_estimate')
            job.speed = d.get('speed')
            job.eta = d.get('eta')
            name = d.get('filename')
            if not name:
                info = d.get('info_dict') or {}
                name = info.get('_filename') or info.get('filepath')
            if name:
                job.filename = name
        elif st == 'finished':
            job.status = ST_PROCESSING
            done = d.get('total_bytes') or d.get('downloaded_bytes')
            if done:
                job.downloaded_bytes = done
                if not job.total_bytes:
                    job.total_bytes = done
            job.speed = None
            job.eta = None
            name = d.get('filename')
            if name:
                job.filename = name
        elif st == 'error':
            job.status = ST_ERROR
            if not job.error:
                job.error = 'Загрузка прервана'
        self.tick += 1

    def _ydl_opts(self, job, ipv4=False):
        allow_merge = ffmpeg_available()
        opts = {
            'format': format_selector(job.quality, allow_merge),
            'outtmpl': os.path.join(MEDIA_DIR, '%(title)s [%(id)s].%(ext)s'),
            'continuedl': True,
            'noprogress': True,
            'quiet': True,
            'no_warnings': True,
            'noplaylist': True,
            'writeinfojson': True,
            'writethumbnail': True,
            'extractor_retries': 5,
            'retries': 10,
            'fragment_retries': 10,
            # Лимит ОДНОЙ сетевой операции, а не всей загрузки. На сотовой
            # сети iPhone тридцати секунд не хватало уже на извлечении.
            'socket_timeout': 120,
            'logger': _QuietLogger(),
            'progress_hooks': [lambda d, _j=job: self._hook(_j, d)],
        }
        if ipv4:
            # Только как запасной вариант после сетевого сбоя, не всегда.
            opts['source_address'] = '0.0.0.0'
        if allow_merge:
            opts['merge_output_format'] = 'mp4'
        return opts

    def _absorb_info(self, job, info):
        """Название из результата единственного extract_info(download=True)."""
        if not isinstance(info, dict):
            return
        entries = info.get('entries')
        if isinstance(entries, list) and entries:
            info = entries[0] or {}
        title = info.get('title')
        if isinstance(title, str) and title.strip():
            job.title = title.strip()
        path = info.get('filepath') or info.get('_filename')
        if isinstance(path, str) and path:
            job.filename = path
        self.tick += 1

    def _attempt(self, job, ipv4):
        """
        ОДИН вызов extract_info(download=True): он же получает метаданные,
        он же выбирает формат, он же качает. Отдельного metadata-запроса,
        на котором раньше выпадал timeout, больше не существует.
        """
        mod = _load_yt_dlp()
        if mod is None:
            raise RuntimeError(YTDLP_ERROR or 'Модуль yt-dlp не найден')
        with mod.YoutubeDL(self._ydl_opts(job, ipv4)) as ydl:
            info = ydl.extract_info(job.url, download=True)
        self._absorb_info(job, info)

    def _run(self, job):
        cancel_exc = _cancel_exception()
        try:
            # Попытка 1 — обычная сеть; попытка 2 — та же задача по IPv4,
            # и только если первая упала именно на сети.
            for number, ipv4 in ((1, False), (2, True)):
                if job.cancel_requested:
                    raise cancel_exc('Загрузка остановлена пользователем')
                try:
                    self._attempt(job, ipv4)
                    break
                except (cancel_exc, DownloadCancelledByUser):
                    raise
                except Exception as e:
                    print('NOX yt-dlp attempt %d (%s) failed: %r'
                          % (number, 'IPv4' if ipv4 else 'обычная сеть', e))
                    if job.cancel_requested:
                        raise
                    if number == 1 and is_network_error(e):
                        print('NOX: retry via IPv4')
                        job.status = ST_PREPARING
                        job.speed = None
                        job.eta = None
                        self.tick += 1
                        continue
                    raise
            if job.cancel_requested:
                job.status = ST_CANCELLED
            else:
                job.status = ST_FINISHED
        except cancel_exc:
            job.status = ST_CANCELLED
        except DownloadCancelledByUser:
            job.status = ST_CANCELLED
        except Exception as e:
            if job.cancel_requested:
                job.status = ST_CANCELLED
            else:
                job.status = ST_ERROR
                job.error = short_error(e)
                print('NOX: ошибка загрузки %s: %r' % (job.url, e))
        finally:
            job.finished_at = time.time()
            job.speed = None
            job.eta = None
            with self._lock:
                self.revision += 1
                self._thread = None
            self.tick += 1
            try:
                self._pump()
            except Exception as e:
                print('NOX: не удалось взять следующее задание: %r' % (e,))


def short_error(exc):
    """Короткий человеческий текст. Полный traceback — только в консоль."""
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
        for v in list(self.sv.subviews):
            self.sv.remove_subview(v)

    # -- крестик: настоящая остановка загрузки ------------------------
    def _dismiss_button(self, job, x, y, w=26.0, h=28.0):
        """
        Хит-таргет — настоящий прозрачный ui.Button, как у кнопки «Скачать».
        yt-dlp работает внутри NOX, поэтому крестик реально прерывает
        рабочий поток, а не просто прячет строку.
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
        job_id = getattr(job, 'id', None)

        def _act(sender):
            self.app.cancel_job(job_id)
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

    def refresh_jobs(self):
        """
        Точечное обновление строк загрузки: тексты и полоса меняются на месте.
        Экран целиком пересобирается только когда меняется состав очереди или
        статус задания, а не на каждый чанк из сети.
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
                self._apply_job(job, row)
            except Exception as e:
                print('NOX: не удалось обновить строку загрузки: %r' % (e,))

    def _apply_job(self, job, row):
        title = row.get('title')
        if title is not None:
            text = safe_name(job.display_title, 26)
            if title.text != text:
                title.text = text
        sub = row.get('sub')
        if sub is not None:
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
        bar = row.get('bar')
        if bar is not None:
            value = job.percent
            if value is None and job.status in (ST_ERROR, ST_CANCELLED):
                value = 0.0
            if value is None:
                bar.start_indeterminate()
            else:
                bar.stop()
            bar.set_value(value)

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
        jobs = DOWNLOADER.visible_jobs()
        temps = LIB.orphan_temps(DOWNLOADER.active_paths())
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
            sub = job.sub_line()
            bar_value = job.percent
            st_text = job.short_status()
            st_col = ERR_TXT if job.status == ST_ERROR else ACCENT_2
            if job.status == ST_ERROR:
                st_icon = 'close'
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
            bar.start_indeterminate()

        ic = Icon(st_icon, st_col, 1.6, frame=(w - 112, h / 2 - 10, 20, 20))
        v.add_subview(ic)
        st = make_label(st_text, (F_REG, 11), st_col,
                        frame=(w - 88, h / 2 - 9, 58, 18))
        v.add_subview(st)

        if job is not None:
            v.add_subview(self._dismiss_button(job, w - 30, h / 2 - 14))
            self._job_views[job.id] = {'sub': sl, 'bar': bar, 'status': st,
                                       'title': tl}

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
        if ytdlp_ready():
            note, note_col = 'Для больших загрузок не закрывайте NOX.', TXT_4
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
            self.rebuild()
        else:
            nox_error(msg)

    # ---------------------------------------------------------------
    def _build_queue(self, w, y):
        jobs = DOWNLOADER.visible_jobs()
        temps = LIB.orphan_temps(DOWNLOADER.active_paths())
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
            bar.start_indeterminate()
        # Вторая строка занимает уже существовавшее пустое место под полосой,
        # ни один элемент карточки не сдвинут.
        dl = make_label(detail, (F_REG, 10), TXT_4,
                        frame=(left, 64, cw - left - 16, 14))
        c.add_subview(dl)

        st = make_label(st_text, (F_REG, 12), st_col,
                        frame=(cw - 118, 24, 76, 20))
        c.add_subview(Icon(st_icon, st_col, 1.6, frame=(cw - 142, 24, 20, 20)))
        c.add_subview(st)

        if job is not None:
            c.add_subview(self._dismiss_button(job, cw - 34, 22, 26, 26))
            self._job_views[job.id] = {'sub': sl, 'detail': dl, 'bar': bar,
                                       'status': st, 'title': tl}
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

        STATE.ensure_folder()
        _load_yt_dlp()

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

    def cancel_job(self, job_id):
        """
        Настоящая остановка: менеджер выставляет cancel_requested, ближайший
        progress_hook поднимает исключение отмены и рабочий поток завершается.
        Файл .part не удаляется — повторная загрузка продолжит его.
        """
        try:
            job = DOWNLOADER.find(job_id)
            if job is None:
                DOWNLOADER.remove(job_id)
            elif job.is_active:
                DOWNLOADER.cancel(job_id)
                nox_ok('Останавливаю загрузку')
            else:
                DOWNLOADER.remove(job_id)
                nox_ok('Убрано из очереди')
        except Exception as e:
            nox_error(str(e))
            return
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
        """
        Единственное место, где интерфейс читает состояние загрузки.
        Крутится на главном потоке через ui.delay — рабочий поток yt-dlp
        сам ui.View не трогает. Пока что-то качается, шаг UI_REFRESH
        (~5 обновлений в секунду), в покое — раз в IDLE_REFRESH.
        """
        if not self._alive:
            return
        step = IDLE_REFRESH
        try:
            active = DOWNLOADER.active_jobs()
            if active:
                step = UI_REFRESH
            screen = self.screens[self._tab]
            if self._tab in (0, 1):
                if DOWNLOADER.signature() != self._last_sig:
                    self._last_sig = DOWNLOADER.signature()
                    self.after_downloads_changed()
                elif active and DOWNLOADER.tick != self._last_tick:
                    self._last_tick = DOWNLOADER.tick
                    screen.refresh_jobs()
            elif DOWNLOADER.signature() != self._last_sig:
                self._last_sig = DOWNLOADER.signature()
                self.after_downloads_changed()
        except Exception as e:
            print('NOX: сбой обновления интерфейса: %r' % (e,))
        try:
            ui.delay(self._tick, step)
        except Exception:
            self._alive = False

    def after_downloads_changed(self):
        """
        Состав очереди изменился: пересканировать NoxMedia и обновить
        Главную, Плеер, Загрузки и хранилище. Завершённые задания уходят
        из очереди, файл появляется в медиатеке.
        """
        DOWNLOADER.clear_finished()
        self._last_sig = DOWNLOADER.signature()
        self._last_tick = DOWNLOADER.tick
        self.reload_all()

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

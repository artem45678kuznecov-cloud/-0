# coding: utf-8
"""
NOX / интерфейс.

Тема, стекло, иконки, карточки, листы, четыре экрана и корневой NoxApp.
Модуль читает состояние подсистем и вызывает их публичные методы, но сам
ничего не качает и не воспроизводит.

Код перенесён из монолитного NOX.py дословно.
"""

import os
import io
import time

import ui

try:
    import clipboard
except ImportError:                                    # pragma: no cover
    clipboard = None

# yt_dlp / YTDLP_ERROR / YTDLP_VERSION переприсваиваются внутри
# nox_download._load_yt_dlp (там стоит global). Забирать их через
# "from ... import" нельзя: копия имени осталась бы навсегда пустой,
# поэтому экраны читают их как атрибуты модуля.
import nox_download
import nox_debug

from nox_core import (
    APP_NAME, APP_VERSION, WATCH_DONE_TAIL, WATCH_MIN_START, SORT_OPTIONS,
    QUALITY_FILTERS, QUALITIES, fmt_size, fmt_duration, fmt_clock,
    safe_name, MEDIA_DIR, ICON_PATH, DEBUG_LOG, MEDIA_LABEL, ensure_dirs,
    log_debug, run_on_main, nox_error, nox_ok, STATE, LIB,
)
from nox_download import (
    ENABLE_EXTRAS, _load_yt_dlp, ytdlp_ready, EXTRAS_NONE, EXTRAS,
    ST_PREPARING, ST_DOWNLOADING, ST_PAUSED, ST_DELETING, ST_PROCESSING,
    ST_FINISHED, ST_ERROR, ST_CANCELLED, DOWNLOADER,
)
from nox_player import (
    PLAYER,
)


# =====================================================================
#  ТЕМА
# =====================================================================

# ---------------------------------------------------------------------
#  NOX Dark Liquid Glass — единственный источник цветов и размеров.
#  Случайных hex-значений по файлу быть не должно: всё берётся отсюда.
# ---------------------------------------------------------------------

# Фон почти чёрный и ЦЕЛЬНЫЙ: один градиент сверху вниз, без отдельных
# светящихся видов — именно они читались на устройстве прямоугольниками.
BG            = '#040611'
BG_TOP        = '#070A18'
BG_MID        = '#030611'
BG_DEEP       = '#02040B'
BG_GLOW       = '#141a4a'          # едва заметный холодный оттенок

# СТЕКЛО. Материал даёт тёмный UIBlurEffect, поверх него ложится
# ТЁМНАЯ подкраска — не светло-серая заливка. Значения подобраны так,
# чтобы поверхность оставалась почти чёрной и прозрачной.
GLASS_TINT          = '#080B18'
GLASS_TINT_A        = 0.66         # поверх настоящего blur
GLASS_THIN_A        = 0.70         # дешёвый материал карточек
GLASS_BG            = GLASS_TINT   # совместимость со старым именем
GLASS_BG_STRONG     = '#0E1224'    # приподнятая поверхность
GLASS_BG_DEEP       = '#04060E'    # утопленная (поле ввода, дорожка)
GLASS_BORDER        = '#7878F5'    # рисуется с малой альфой, см. ниже
GLASS_BORDER_A      = 0.20         # неактивная рамка почти не видна
GLASS_BORDER_SOFT   = '#7878F5'
GLASS_BORDER_ACTIVE = '#8d8bff'
GLASS_HIGHLIGHT     = '#ffffff'
# Верхний блик обязан ТОЛЬКО слегка ловиться глазом: на больших
# поверхностях 0.20 осветляла их до серого.
GLASS_HL_A          = 0.05
GLASS_GLOW          = '#5b57e0'

# Акцент — только интерактив.
ACCENT       = '#7b7bf5'
ACCENT_LIGHT = '#b3b0ff'
ACCENT_DEEP  = '#4f49d8'
ACCENT_SOFT  = '#8d8bff'
DANGER       = '#ff6b81'

# Текст.
TEXT_PRIMARY   = '#ffffff'
TEXT_SECONDARY = '#9aa2bd'
TEXT_MUTED     = '#646b87'
TEXT_FAINT     = '#474e68'

# Размеры.
NAV_HEIGHT   = 66.0        # высота плавающей капсулы навигации
NAV_SIDE     = 16.0        # отступ капсулы от краёв экрана
NAV_GAP      = 8.0         # зазор между капсулой и safe area снизу
PILL_H       = 52.0        # высота капсулы активной вкладки
CARD_RADIUS  = 18.0
GLASS_RADIUS = 22.0
PILL_RADIUS  = 999.0       # «до упора», радиус ограничивается по высоте
CTRL_ZONE    = 40.0        # ширина колонки ⋯ и круглых кнопок карточки

F_BOLD      = '<system-bold>'
F_REG       = '<system>'

PAD         = 20.0

# --- совместимые псевдонимы ------------------------------------------
# Старые имена остаются рабочими, чтобы ни один вызов не остался с
# «случайным» цветом: все они указывают в ту же палитру.
NAV_BG      = GLASS_BG
CARD        = GLASS_BG
CARD_2      = GLASS_BG_STRONG
CARD_3      = GLASS_BG_STRONG
FIELD       = GLASS_BG_DEEP
BORDER      = GLASS_BORDER
BORDER_2    = GLASS_BORDER
BORDER_HI   = GLASS_BORDER_ACTIVE
ACCENT_2    = ACCENT_LIGHT
TXT         = TEXT_PRIMARY
TXT_2       = TEXT_SECONDARY
TXT_3       = TEXT_MUTED
TXT_4       = TEXT_FAINT
ERR_TXT     = DANGER
NAV_H       = NAV_HEIGHT

# Как часто интерфейс перечитывает состояние загрузки. progress_hook
# дёргается очень часто, поэтому UI обновляется не чаще ~5 раз в секунду.
UI_REFRESH = 0.22
IDLE_REFRESH = 3.0




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


def mixa(h1, h2, t, alpha):
    """Смешанный цвет с альфой — для градиентов по стеклу."""
    c = mix(h1, h2, t)
    return (c[0], c[1], c[2], alpha)


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

# =====================================================================
#  СТЕКЛО
# =====================================================================

# Нативный blur пробуем ровно один раз. Не получилось — весь интерфейс
# продолжает работать на нарисованном стекле, приложение не падает.
# ---------------------------------------------------------------------
#  НАТИВНЫЙ МАТЕРИАЛ: Liquid Glass, CAGradientLayer, тени CALayer.
#
#  ПОЧЕМУ ЭТО ЗДЕСЬ. Прошлая версия рисовала стекло, градиенты и фон
#  десятками ui.fill_rect подряд. На устройстве соседние прямоугольники
#  ложатся на дробные границы пикселей, и швы между ними видны как
#  горизонтальные полосы (scanline). Никакая настройка альфы это не
#  лечит — лечит только отказ от полос: настоящий материал UIKit и
#  непрерывный CAGradientLayer.
#
#  Всё ниже — публичный API Apple, каждый вызов в try/except, и на любом
#  отказе NOX спускается на уровень ниже, но не падает.
# ---------------------------------------------------------------------

# ГЛАВНОЕ РЕШЕНИЕ ЭТОГО ПРОХОДА.
#
# UIGlassEffect на реальном устройстве отрисовывается почти БЕЛЫМ: поиск,
# капсула статуса, панель URL, плитки качества, навигация и листы уходили
# в светло-серый, и NOX переставал быть тёмным. Название UIKit-класса тут
# не важно — важен вид, поэтому основным материалом становится ТЁМНЫЙ
# UIBlurEffect, а UIGlassEffect остаётся выключенным экспериментом.
USE_UIGLASS = False

# Уровни материала:
#   1 — UIGlassEffect / UIGlassContainerEffect (только при USE_UIGLASS)
#   2 — UIBlurEffect + UIVisualEffectView  <-- рабочий режим NOX
#   3 — обычная тёмная полупрозрачная ui.View
GLASS_L1, GLASS_L2, GLASS_L3 = 1, 2, 3
GLASS_LEVEL_NAMES = {
    GLASS_L1: 'UIGlassEffect',
    GLASS_L2: 'UIBlurEffect (dark)',
    GLASS_L3: 'tinted view',
}

# Стили UIBlurEffect, которые остаются тёмными при любой системной теме:
# .dark, systemThinMaterialDark, systemUltraThinMaterialDark.
BLUR_STYLES = (2, 10, 9)

_OBJC = {'checked': False, 'mod': None, 'level': GLASS_L3,
         'effectview': None, 'glass': None, 'container': None,
         'blur': None, 'gradient': None, 'color': None,
         'surfaces': 0, 'gradients': 0, 'shadows': 0}


def _objc():
    """Однократно выясняем, что реально доступно на этом устройстве."""
    if _OBJC['checked']:
        return _OBJC['mod']
    _OBJC['checked'] = True
    try:
        import objc_util
    except Exception:
        return None

    def cls(name):
        try:
            return objc_util.ObjCClass(name)
        except Exception:
            return None

    try:
        objc_util.load_framework('QuartzCore')
    except Exception:
        pass
    _OBJC['mod'] = objc_util
    _OBJC['effectview'] = cls('UIVisualEffectView')
    _OBJC['glass'] = cls('UIGlassEffect')            # iOS 26+
    _OBJC['container'] = cls('UIGlassContainerEffect')
    _OBJC['blur'] = cls('UIBlurEffect')
    _OBJC['gradient'] = cls('CAGradientLayer')
    _OBJC['color'] = cls('UIColor')
    if USE_UIGLASS and _OBJC['effectview'] is not None \
            and _OBJC['glass'] is not None:
        _OBJC['level'] = GLASS_L1
    elif _OBJC['effectview'] is not None and _OBJC['blur'] is not None:
        _OBJC['level'] = GLASS_L2
    else:
        _OBJC['level'] = GLASS_L3
    return objc_util


def glass_level():
    """Какой материал реально используется в этом запуске."""
    _objc()
    return _OBJC['level']


def glass_report():
    """Строка для экрана «Настройки» и для отчёта."""
    lvl = glass_level()
    name = GLASS_LEVEL_NAMES.get(lvl, 'tinted view')
    if lvl == GLASS_L1 and _OBJC['container'] is not None:
        name += ' + UIGlassContainerEffect'
    return name


def _uicolor(hex_color, alpha=1.0):
    m = _OBJC['mod']
    if m is None or _OBJC['color'] is None:
        return None
    r, g, b = parse_hex(hex_color)
    return _OBJC['color'].colorWithRed_green_blue_alpha_(r, g, b, alpha)


def _no_implicit_animation(func):
    """
    CALayer по умолчанию анимирует смену frame. При раскладке это даёт
    «плывущие» слои, поэтому неявные анимации выключаются.
    """
    m = _OBJC['mod']
    if m is None:
        return func()
    try:
        ca = m.ObjCClass('CATransaction')
        ca.begin()
        ca.setDisableActions_(True)
        try:
            return func()
        finally:
            ca.commit()
    except Exception:
        return func()


def attach_material(view, radius, tint=None, tint_alpha=0.0,
                    interactive=False, container=False):
    """
    Кладёт ПОД содержимое view лучший доступный материал и возвращает
    уровень (1/2) либо 0, если нативного слоя нет.

    LEVEL 1 — UIGlassEffect (для панели навигации UIGlassContainerEffect),
    LEVEL 2 — UIBlurEffect. Прозрачность самой effect view не трогаем:
    затемнение и оттенок делает отдельный тонкий слой поверх неё.
    """
    m = _objc()
    if m is None or _OBJC['effectview'] is None:
        return 0
    try:
        host = view.objc_instance
    except Exception:
        return 0
    effect, level = None, 0
    if not USE_UIGLASS:
        # Прямой путь: только тёмный blur. Никаких стеклянных эффектов,
        # которые светлеют на устройстве.
        container = False
    if container and USE_UIGLASS and _OBJC['container'] is not None:
        try:
            effect = _OBJC['container'].alloc().init()
            level = GLASS_L1
        except Exception:
            effect = None
    if effect is None and USE_UIGLASS and _OBJC['glass'] is not None:
        try:
            g = _OBJC['glass'].alloc().init()
            if interactive:
                try:
                    g.setInteractive_(True)
                except Exception:
                    pass
            if tint:
                col = _uicolor(tint, tint_alpha if tint_alpha else 0.22)
                if col is not None:
                    try:
                        g.setTintColor_(col)
                    except Exception:
                        pass
            effect, level = g, GLASS_L1
        except Exception:
            effect = None
    if effect is None and _OBJC['blur'] is not None:
        for style in BLUR_STYLES:
            try:
                effect = _OBJC['blur'].effectWithStyle_(style)
                if effect is not None:
                    level = GLASS_L2
                    break
            except Exception:
                effect = None
    if effect is None:
        return 0
    try:
        ev = _OBJC['effectview'].alloc().initWithEffect_(effect)
        b = view.bounds
        ev.setFrame_(m.CGRect(m.CGPoint(0, 0), m.CGSize(b[2], b[3])))
        ev.setAutoresizingMask_(18)          # гибкие ширина и высота
        lay = ev.layer()
        lay.setCornerRadius_(float(radius))
        try:
            lay.setCornerCurve_(m.ns('continuous'))
        except Exception:
            pass
        lay.setMasksToBounds_(True)
        host.insertSubview_atIndex_(ev, 0)
        ev.release()
        _OBJC['surfaces'] += 1
        return level
    except Exception:
        return 0


def attach_gradient(view, stops, radius=0.0, start=(0.5, 0.0),
                    end=(0.5, 1.0), radial=False, below=False):
    """
    Непрерывный CAGradientLayer вместо десятков ui.fill_rect.

    stops — список (hex, alpha, location 0..1). Возвращает слой, чтобы
    его можно было пересчитать в layout(), либо None.
    """
    m = _objc()
    if m is None or _OBJC['gradient'] is None or not stops:
        return None
    try:
        lay = _OBJC['gradient'].layer()
        cols, locs = [], []
        for hex_color, alpha, loc in stops:
            col = _uicolor(hex_color, alpha)
            if col is None:
                return None
            cols.append(col.CGColor())
            locs.append(m.ns(float(loc)))
        lay.setColors_(m.ns(cols))
        lay.setLocations_(m.ns(locs))
        lay.setStartPoint_(m.CGPoint(start[0], start[1]))
        lay.setEndPoint_(m.CGPoint(end[0], end[1]))
        if radial:
            try:
                lay.setType_(m.ns('radial'))
            except Exception:
                pass
        b = view.bounds
        lay.setFrame_(m.CGRect(m.CGPoint(0, 0), m.CGSize(b[2], b[3])))
        if radius:
            lay.setCornerRadius_(float(radius))
            try:
                lay.setCornerCurve_(m.ns('continuous'))
            except Exception:
                pass
            lay.setMasksToBounds_(True)
        host = view.objc_instance.layer()
        if below:
            host.insertSublayer_atIndex_(lay, 0)
        else:
            host.addSublayer_(lay)
        _OBJC['gradients'] += 1
        return lay
    except Exception:
        return None


def sync_layer(lay, x, y, w, h):
    """Пересчёт кадра слоя без неявной анимации."""
    m = _OBJC['mod']
    if lay is None or m is None:
        return

    def _apply():
        try:
            lay.setFrame_(m.CGRect(m.CGPoint(x, y),
                                   m.CGSize(max(0.0, w), max(0.0, h))))
        except Exception:
            pass
    _no_implicit_animation(_apply)


def apply_shadow(view, color=GLASS_GLOW, opacity=0.35, radius=14.0,
                 offset=(0.0, 4.0), corner=None):
    """
    Мягкая тень публичными свойствами CALayer.

    corner задаёт shadowPath. Без него CALayer считает тень по альфе всего
    поддерева и вокруг кнопки «Скачать» появлялся тёмный ПРЯМОУГОЛЬНИК.
    С явным скруглённым shadowPath форма тени совпадает с капсулой.
    """
    m = _objc()
    if m is None:
        return False
    try:
        lay = view.objc_instance.layer()
        col = _uicolor(color, 1.0)
        if col is None:
            return False
        lay.setShadowColor_(col.CGColor())
        lay.setShadowOpacity_(float(opacity))
        lay.setShadowRadius_(float(radius))
        lay.setShadowOffset_(m.CGSize(offset[0], offset[1]))
        lay.setMasksToBounds_(False)
        if corner is not None:
            set_shadow_path(view, corner)
        _OBJC['shadows'] += 1
        return True
    except Exception:
        return False


def set_shadow_path(view, corner):
    """Форма тени = скруглённый прямоугольник ровно по границам view."""
    m = _OBJC['mod']
    if m is None:
        return False
    try:
        w, h = view.width, view.height
        if w <= 0 or h <= 0:
            return False
        rect = m.CGRect(m.CGPoint(0, 0), m.CGSize(w, h))
        path = m.ObjCClass('UIBezierPath') \
            .bezierPathWithRoundedRect_cornerRadius_(rect, float(corner))
        view.objc_instance.layer().setShadowPath_(path.CGPath())
        return True
    except Exception:
        return False


def set_corner(view, radius, continuous=True):
    """cornerRadius + continuous curve там, где это доступно."""
    try:
        view.corner_radius = float(radius)
    except Exception:
        pass
    m = _OBJC['mod']
    if m is None or not continuous:
        return
    try:
        view.objc_instance.layer().setCornerCurve_(m.ns('continuous'))
    except Exception:
        pass


def native_blur_available():
    """Совместимость: включён ли вообще нативный материал."""
    return glass_level() in (GLASS_L1, GLASS_L2)


class GlassView(ui.View):
    """
    ЕДИНАЯ фабрика тёмного стекла NOX (она же DarkGlassView).

    Централизует всё: стиль blur, тёмный оттенок, тонкую рамку,
    скругление, едва заметный блик и мягкую тень. Разного «случайного»
    материала у кнопок больше нет.

    Ничего не рисует в draw(): материал даёт UIKit, оттенок и рамку —
    обычная подкрашенная view, блик — один CAGradientLayer, свечение —
    тень CALayer. Полосам взяться неоткуда.

    material='glass' — просит настоящий blur (крупные поверхности);
    material='thin'  — без effect view, только тёмная подкраска
                       (карточек на экране много, blur им не нужен).
    """

    def __init__(self, radius=CARD_RADIUS, tint=GLASS_TINT, tint_alpha=None,
                 border=GLASS_BORDER, border_alpha=GLASS_BORDER_A,
                 border_w=1.0, highlight=0.0, glow=0.0,
                 glow_color=GLASS_GLOW, material='thin', interactive=False,
                 container=False, shadow=None, fill=None, fill_alpha=None,
                 **kwargs):
        # fill/fill_alpha — прежние имена тех же параметров.
        if fill is not None:
            tint = fill
        if fill_alpha is not None:
            tint_alpha = fill_alpha
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.radius = radius
        self.tint = tint
        self.border = border
        self.border_alpha = border_alpha
        self.border_w = border_w
        self.glow = glow
        self.glow_color = glow_color
        self.material = material
        self.level = 0
        self._grad = None
        self._shadow_corner = None
        # Блик приглушается жёстко: на больших поверхностях даже 0.10
        # белого читается как осветление до серого.
        self._highlight = min(float(highlight or 0.0), GLASS_HL_A)
        if tint_alpha is None:
            tint_alpha = GLASS_TINT_A if material == 'glass' else GLASS_THIN_A
        self.tint_alpha = tint_alpha

        r = self._radius()
        if material == 'glass':
            self.level = attach_material(self, r, tint=tint,
                                         tint_alpha=0.20,
                                         interactive=interactive,
                                         container=container)
        # Оттенок и рамка — отдельный слой ПОВЕРХ материала.
        skin = ui.View(frame=self.bounds)
        skin.flex = 'WH'
        skin.user_interaction_enabled = False
        skin.background_color = rgba(tint, self._skin_alpha())
        set_corner(skin, r)
        skin.border_width = border_w
        skin.border_color = rgba(border, border_alpha)
        self.add_subview(skin)
        self.skin = skin
        if self._highlight > 0:
            self._grad = attach_gradient(
                skin,
                [(GLASS_HIGHLIGHT, self._highlight, 0.0),
                 (GLASS_HIGHLIGHT, self._highlight * 0.30, 0.30),
                 (GLASS_HIGHLIGHT, 0.0, 0.85)],
                radius=r)
        if glow > 0:
            self._shadow_corner = r
            apply_shadow(self, glow_color, min(0.55, glow), 16.0, (0.0, 0.0),
                         corner=r)
        elif shadow:
            self._shadow_corner = r
            apply_shadow(self, BG_DEEP, 0.55, 18.0, (0.0, 10.0), corner=r)

    def _skin_alpha(self):
        # Под настоящим blur оттенок чуть легче, но остаётся ТЁМНЫМ:
        # именно попытка «не гасить эффект» делала поверхности серыми.
        if self.level in (GLASS_L1, GLASS_L2):
            return max(0.55, self.tint_alpha - 0.06)
        return self.tint_alpha

    def _radius(self):
        w, h = self.width, self.height
        return min(float(self.radius), max(1.0, min(w, h) / 2.0))

    def layout(self):
        skin = getattr(self, 'skin', None)
        if skin is None:
            return
        r = self._radius()
        set_corner(skin, r)
        if self._grad is not None:
            sync_layer(self._grad, 0, 0, self.width, self.height)
        if self._shadow_corner is not None:
            self._shadow_corner = r
            set_shadow_path(self, r)

    def set_active(self, flag, active_color=GLASS_BORDER_ACTIVE,
                   idle_color=GLASS_BORDER, glow=0.30):
        """Выделение меняет цвета готовых слоёв, ничего не пересобирая."""
        self.border = active_color if flag else idle_color
        self.border_w = 1.2 if flag else 1.0
        self.glow = glow if flag else 0.0
        skin = getattr(self, 'skin', None)
        if skin is None:
            return
        skin.border_color = rgba(self.border, 0.75 if flag else self.border_alpha)
        skin.border_width = self.border_w
        # Активная плитка — тёмное фиолетовое стекло, а не заливка акцентом.
        skin.background_color = (rgba(ACCENT_DEEP, 0.30) if flag
                                 else rgba(self.tint, self._skin_alpha()))
        r = self._radius()
        if flag:
            self._shadow_corner = r
            apply_shadow(self, ACCENT, 0.35, 12.0, (0.0, 0.0), corner=r)
        else:
            apply_shadow(self, ACCENT, 0.0, 0.0, (0.0, 0.0))


# Явное имя тёмной фабрики: то же самое стекло.
DarkGlassView = GlassView


def glass_card(frame, radius=CARD_RADIUS, **kwargs):
    """
    Карточка медиатеки/очереди. По умолчанию дешёвый материал: таких
    поверхностей на экране много, и 50 живых effect view — это работа
    GPU на каждый кадр рядом с многочасовой загрузкой.
    """
    kwargs.setdefault('material', 'thin')
    return GlassView(radius=radius, frame=frame, **kwargs)


def glass_panel(frame, radius=GLASS_RADIUS, **kwargs):
    """Крупная поверхность, которой положен НАСТОЯЩИЙ Liquid Glass."""
    kwargs.setdefault('material', 'glass')
    return GlassView(radius=radius, frame=frame, **kwargs)


def glass_pill(frame, radius=None, **kwargs):
    """Капсула: радиус всегда равен половине высоты."""
    h = frame[3]
    return GlassView(radius=(radius if radius else h / 2.0),
                     frame=frame, **kwargs)


def card_view(frame, bg=None, radius=CARD_RADIUS, border=BORDER, border_w=1):
    """Старая фабрика карточек — то же стекло, чтобы стили не разъезжались."""
    return GlassView(radius=radius, tint=(bg or GLASS_TINT), border=border,
                     border_w=border_w, material='thin', frame=frame)


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


class GradientFill(ui.View):
    """
    Непрерывный градиент одним CAGradientLayer.

    Ни одного ui.fill_rect: раньше и фон, и CTA, и затемнение обложки
    строились из 20-54 прямоугольников подряд, и швы между ними были
    видны на устройстве полосами. Здесь слой один, и он непрерывный.
    Если CoreAnimation недоступна, рисуется ОДИН ровный цвет — тоже
    без полос.
    """

    def __init__(self, stops, radius=0.0, start=(0.5, 0.0), end=(0.5, 1.0),
                 radial=False, flat=None, flat_alpha=1.0, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.user_interaction_enabled = False
        self.stops = list(stops)
        self.radius = radius
        self.flat = flat or (self.stops[0][0] if self.stops else BG_DEEP)
        self.flat_alpha = flat_alpha
        self._lay = attach_gradient(self, self.stops, radius=radius,
                                    start=start, end=end, radial=radial)
        if self._lay is None and radius:
            set_corner(self, radius)

    def layout(self):
        if self._lay is not None:
            sync_layer(self._lay, 0, 0, self.width, self.height)

    def draw(self):
        # Только когда CAGradientLayer недоступен: одна ровная заливка.
        if self._lay is not None:
            return
        w, h = self.width, self.height
        if w <= 0 or h <= 0:
            return
        ui.set_color(rgba(self.flat, self.flat_alpha))
        if self.radius:
            ui.Path.rounded_rect(0, 0, w, h,
                                 min(self.radius, min(w, h) / 2.0)).fill()
        else:
            ui.fill_rect(0, 0, w, h)


def fade_overlay(frame, color=BG_DEEP, strength=0.97, horizontal=True):
    """Затемнение поверх обложки — тем же непрерывным градиентом."""
    if horizontal:
        start, end = (0.0, 0.5), (1.0, 0.5)
    else:
        start, end = (0.5, 0.0), (0.5, 1.0)
    return GradientFill([(color, strength, 0.0),
                         (color, strength * 0.45, 0.45),
                         (color, 0.0, 1.0)],
                        start=start, end=end, flat=color, flat_alpha=0.0,
                        frame=frame)


class CTAButtonView(ui.View):
    """
    Кнопка «Скачать»: настоящая капсула, а не прямоугольник с закруглённым
    краем. Градиент deep violet -> electric violet -> lavender blue лежит
    в CAGradientLayer, у которого cornerRadius = h/2 и masksToBounds, так
    что ни градиент, ни specular highlight за капсулу не выходят.
    """

    def __init__(self, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.user_interaction_enabled = False
        r = self.height / 2.0 if self.height else 0.0
        self._grad = attach_gradient(
            self,
            [(ACCENT_DEEP, 1.0, 0.0), (ACCENT, 1.0, 0.5),
             (ACCENT_LIGHT, 1.0, 1.0)],
            radius=r, start=(0.0, 0.15), end=(1.0, 0.85))
        # Specular highlight — отдельный слой, обрезанный тем же радиусом.
        self._spec = None
        if self._grad is not None:
            self._spec = attach_gradient(
                self,
                [(GLASS_HIGHLIGHT, 0.26, 0.0),
                 (GLASS_HIGHLIGHT, 0.05, 0.42),
                 (GLASS_HIGHLIGHT, 0.0, 1.0)],
                radius=r)
        else:
            set_corner(self, r)
        # Тень строго по форме капсулы: без shadowPath CALayer считал её
        # по альфе поддерева и за кнопкой появлялся тёмный прямоугольник.
        apply_shadow(self, ACCENT_DEEP, 0.38, 20.0, (0.0, 8.0), corner=r)

    def layout(self):
        r = self.height / 2.0
        for lay in (self._grad, self._spec):
            if lay is not None:
                sync_layer(lay, 0, 0, self.width, self.height)
                try:
                    lay.setCornerRadius_(float(r))
                except Exception:
                    pass
        if self._grad is None:
            set_corner(self, r)
        set_shadow_path(self, r)

    def draw(self):
        # Fallback без CoreAnimation: одна ровная капсула, без полос.
        if self._grad is not None:
            return
        w, h = self.width, self.height
        if w <= 0 or h <= 0:
            return
        ui.set_color(ACCENT)
        ui.Path.rounded_rect(0, 0, w, h, h / 2.0).fill()


class OrbView(ui.View):
    """Фирменный шар статуса. Круги, а не полосы: banding здесь не бывает."""

    def __init__(self, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.user_interaction_enabled = False
        apply_shadow(self, ACCENT, 0.55, 8.0, (0.0, 0.0))

    def draw(self):
        w, h = self.width, self.height
        s = min(w, h)
        if s <= 0:
            return
        cx, cy = w / 2.0, h / 2.0
        r = s * 0.32
        ui.set_color(ACCENT)
        ui.Path.oval(cx - r, cy - r, r * 2, r * 2).fill()
        r2 = s * 0.16
        ui.set_color(rgba(GLASS_HIGHLIGHT, 0.42))
        ui.Path.oval(cx - r2 * 1.1, cy - r2 * 1.5, r2 * 2, r2 * 2).fill()


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

    def _fill_segment(self, x, seg, w, h, r):
        """Светящийся отрезок: тело плюс мягкий ореол на конце."""
        seg = max(h, min(seg, w - x))
        ui.set_color(rgba(self.fill_color, 0.30))
        ui.Path.rounded_rect(max(0.0, x - 1.0), -1.0, seg + 2.0,
                             h + 2.0, r + 1.0).fill()
        ui.set_color(self.fill_color)
        ui.Path.rounded_rect(x, 0, seg, h, r).fill()
        ui.set_color(rgba(ACCENT_LIGHT, 0.55))
        ui.Path.rounded_rect(x + seg - min(seg, h * 1.6), 0,
                             min(seg, h * 1.6), h, r).fill()

    def draw(self):
        w, h = self.width, self.height
        if w <= 0 or h <= 0:
            return
        r = h / 2.0
        # Тёмная стеклянная дорожка.
        ui.set_color(rgba(GLASS_BG_DEEP, 0.9))
        ui.Path.rounded_rect(0, 0, w, h, r).fill()
        ui.set_color(rgba(GLASS_BORDER, 0.55))
        p = ui.Path.rounded_rect(0.25, 0.25, w - 0.5, h - 0.5, r)
        p.line_width = 0.5
        p.stroke()
        if self.value is None:
            seg = max(28.0, w * 0.28)
            x = (w + seg) * self._phase - seg
            x = max(0.0, min(w - 1.0, x))
            self._fill_segment(x, seg, w, h, r)
        else:
            v = max(0.0, min(1.0, float(self.value)))
            if v > 0:
                self._fill_segment(0.0, max(h, w * v), w, h, r)


class ThumbView(ui.View):
    """
    Обложка. Если реального изображения рядом нет —
    рисуется минималистичная карточка (без выдуманных картинок).
    """

    def __init__(self, image=None, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = GLASS_BG_DEEP
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
        # Ровная тёмная подложка вместо прежних 20 полос градиента.
        ui.set_color(GLASS_BG_DEEP)
        ui.fill_rect(0, 0, w, h)
        # тусклый значок видео по центру
        s = min(w, h) * 0.34
        cx, cy = w / 2.0, h / 2.0
        ui.set_color(rgba(ACCENT, 0.26))
        p = ui.Path.rounded_rect(cx - s / 2, cy - s * 0.34, s, s * 0.68, s * 0.12)
        p.line_width = max(1.0, s * 0.055)
        p.stroke()
        ui.set_color(rgba(ACCENT_LIGHT, 0.36))
        tri = ui.Path()
        tri.move_to(cx - s * 0.08, cy - s * 0.15)
        tri.line_to(cx + s * 0.16, cy)
        tri.line_to(cx - s * 0.08, cy + s * 0.15)
        tri.close()
        tri.fill()


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
    """
    Шапка как на эталонах: слева крупное NOX с разрядкой и подписью,
    справа объёмная стеклянная капсула статуса. Возвращает (view, height).
    """
    h = 62.0
    v = ui.View(frame=(0, y, width, h))
    v.background_color = 'clear'

    logo = make_label(spaced('NOX', ' '), (F_BOLD, 30), TEXT_PRIMARY,
                      frame=(PAD, 2, 230, 36))
    v.add_subview(logo)
    sub = make_label(spaced('офлайн-медиатека'), (F_REG, 8), TEXT_MUTED,
                     frame=(PAD + 3, 38, 260, 12))
    v.add_subview(sub)

    # Стеклянная капсула статуса.
    cap_h = 52.0
    cap_w = min(210.0, max(150.0, width * 0.47))
    cap_x = width - PAD - cap_w
    cap = glass_pill((cap_x, (h - cap_h) / 2.0 + 1, cap_w, cap_h),
                     tint=GLASS_TINT, tint_alpha=0.70, border=GLASS_BORDER,
                     border_alpha=0.20, highlight=GLASS_HL_A,
                     material='glass')
    orb_size = 30.0
    orb_frame = (13, (cap_h - orb_size) / 2.0, orb_size, orb_size)
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
        cap.add_subview(holder)
    else:
        cap.add_subview(OrbView(frame=orb_frame))

    tx = 13 + orb_size + 8
    tw = cap_w - tx - 14
    cap.add_subview(make_label('Рады видеть', (F_REG, 10), TEXT_MUTED,
                               ui.ALIGN_RIGHT, frame=(tx, 11, tw, 13)))
    cap.add_subview(make_label('Всегда офлайн', (F_BOLD, 14), ACCENT_LIGHT,
                               ui.ALIGN_RIGHT, frame=(tx, 25, tw, 18)))
    v.add_subview(cap)
    return v, h


def big_title(sv, width, y, title, subtitle=None):
    """Крупный заголовок экрана: «Загрузчик», «Плеер», «Настройки»."""
    sv.add_subview(make_label(title, (F_BOLD, 32), TEXT_PRIMARY,
                              frame=(PAD, y, width - 100, 40)))
    if subtitle:
        sv.add_subview(make_label(subtitle, (F_REG, 13), TEXT_MUTED,
                                  frame=(PAD + 1, y + 39, width - 100, 18)))
        return y + 64
    return y + 44


def section_header(width, y, title, right_text=None, right_action=None):
    h = 38.0
    v = ui.View(frame=(0, y, width, h))
    v.background_color = 'clear'
    lb = make_label(title, (F_BOLD, 25), TEXT_PRIMARY,
                    frame=(PAD, 0, width - PAD * 2 - 90, h))
    v.add_subview(lb)
    if right_text and callable(right_action):
        btn_w = 74.0
        b = Tappable(action=right_action, press_scale=0.93,
                     frame=(width - PAD - btn_w, 0, btn_w, h))
        b.background_color = 'clear'
        rl = make_label(right_text, (F_REG, 14), TEXT_SECONDARY, ui.ALIGN_RIGHT,
                        frame=(0, 0, btn_w - 18, h))
        b.add_subview(rl)
        ic = Icon('chevron', TEXT_SECONDARY, 1.7,
                  frame=(btn_w - 15, h / 2 - 7, 13, 14))
        b.add_subview(ic)
        v.add_subview(b)
    return v, h


def glass_circle(frame, icon_name, color=TEXT_SECONDARY, line=1.8,
                 icon_size=20.0, action=None, fill=GLASS_BG_STRONG,
                 fill_alpha=0.74, glow=0.0, border=GLASS_BORDER,
                 material='glass'):
    """
    Круглая стеклянная кнопка — шестерёнка, play, пауза, крестик.
    Хит-таргетом всегда служит настоящий прозрачный ui.Button.
    """
    x, y, w, h = frame
    v = GlassView(radius=min(w, h) / 2.0, tint=fill, tint_alpha=fill_alpha,
                  border=border, border_alpha=0.26, highlight=GLASS_HL_A,
                  glow=glow, material=material, frame=(x, y, w, h))
    ic = Icon(icon_name, color, line,
              frame=((w - icon_size) / 2.0, (h - icon_size) / 2.0,
                     icon_size, icon_size))
    v.add_subview(ic)
    if callable(action):
        hit = ui.Button(frame=(0, 0, w, h))
        hit.flex = 'WH'
        hit.background_color = 'clear'
        hit.action = action
        v.add_subview(hit)
    return v, ic


def empty_block(width, y, title, subtitle, icon='film'):
    """Пустое состояние — тоже стекло, а не пустая дыра в композиции."""
    h = 190.0
    v = glass_card((PAD, y, width - PAD * 2, h), GLASS_RADIUS,
                   border_alpha=0.30, highlight=0.07)
    w = v.width
    ring = GlassView(radius=44.0, tint=GLASS_BG_STRONG, tint_alpha=0.72,
                     border=GLASS_BORDER, border_alpha=0.24,
                     highlight=GLASS_HL_A, glow=0.14, material='glass',
                     frame=(w / 2.0 - 44, 30, 88, 88))
    ring.add_subview(Icon(icon, ACCENT_LIGHT, 2.0, frame=(28, 28, 32, 32)))
    v.add_subview(ring)
    t = make_label(title, (F_BOLD, 17), TEXT_PRIMARY, ui.ALIGN_CENTER,
                   frame=(10, 128, w - 20, 22))
    v.add_subview(t)
    s = make_label(subtitle, (F_REG, 12.5), TEXT_MUTED, ui.ALIGN_CENTER,
                   lines=2, frame=(16, 150, w - 32, 32))
    v.add_subview(s)
    return v, h


def status_pill(text, icon_name, color, x, y, w=None, h=28.0, bg=None):
    lbl_font = (F_REG, 11.5)
    if w is None:
        w = 28 + len(text) * 6.4
    v = glass_pill((x, y, w, h), tint=(bg or GLASS_BG_STRONG),
                   border=color, border_alpha=0.34, highlight=0.12)
    v.user_interaction_enabled = False
    ic = Icon(icon_name, color, 1.6, frame=(8, (h - 14) / 2, 14, 14))
    v.add_subview(ic)
    lb = make_label(text, lbl_font, color, frame=(26, 0, w - 30, h))
    v.add_subview(lb)
    return v


def badge_w(text):
    """Ширина плашки длительности — чтобы её можно было ставить слева."""
    return 14.0 + len(text or '') * 7.0


def duration_badge(text, x, y):
    """Тёмная плашка длительности в углу обложки. x — её ПРАВЫЙ край."""
    w = badge_w(text)
    v = ui.View(frame=(x - w, y, w, 22))
    v.background_color = rgba(BG_DEEP, 0.80)
    v.corner_radius = 7.0
    v.user_interaction_enabled = False
    v.add_subview(make_label(text, (F_BOLD, 11), TEXT_PRIMARY, ui.ALIGN_CENTER,
                             frame=(0, 0, w, 22)))
    return v


def check_badge(x, y, size=26.0):
    """Фиолетовый круг с галочкой в углу обложки (эталон, фото 1)."""
    v = ui.View(frame=(x, y, size, size))
    v.background_color = ACCENT
    v.corner_radius = size / 2.0
    v.border_width = 1
    v.border_color = rgba(ACCENT_LIGHT, 0.75)
    v.user_interaction_enabled = False
    v.add_subview(Icon('check', TEXT_PRIMARY, 2.0,
                       frame=(size * 0.24, size * 0.24,
                              size * 0.52, size * 0.52)))
    return v


# Диаметр круглых управляющих кнопок карточки загрузки.
CTRL_SIZE = 40.0
JOB_CARD_H = 100.0


def job_bar_state(job):
    """
    Что показывает полоса задания: (значение, двигать ли бегунок).

    Бегунок разрешён ровно трём состояниям: подготовка, обработка и
    загрузка с ещё неизвестным размером. Пауза, очередь, ошибка,
    остановка и ожидание свежей ссылки полосу НЕ анимируют: бегущая
    полоса на приостановленной карточке читается как идущая загрузка,
    хотя ничего не качается.

    Одно и то же правило используют и сборка карточки, и её обновление
    на каждом такте — раньше обновление анимировало всё, у чего просто
    неизвестен процент.
    """
    value = job.percent
    if job.status == ST_FINISHED:
        return 1.0, False
    if job.status in (ST_PREPARING, ST_PROCESSING):
        return None, True
    if job.status == ST_DOWNLOADING:
        return (value, False) if value is not None else (None, True)
    # Известную долю приостановленное задание показывает как есть,
    # неизвестную — пустой дорожкой. В обоих случаях полоса неподвижна.
    return (value if value is not None else 0.0), False


def job_card(screen, w, y, temp, job):
    """
    ЕДИНАЯ карточка загрузки для «Главной» и «Загрузок» — композиция с
    эталона (фото 2): обложка слева, тексты по центру, две круглые
    стеклянные кнопки в собственной колонке справа.

    Колонка кнопок фиксированная, и текст под неё не заходит никогда:
    именно на этом раньше налезали друг на друга процент, статус и
    крестик. Длинные русские названия обрезаются через safe_name.
    """
    h = JOB_CARD_H
    err = (job is not None and job.status == ST_ERROR)
    paused = (job is not None and job.status == ST_PAUSED)
    card = glass_card((PAD, y, w - PAD * 2, h), CARD_RADIUS,
                      tint_alpha=0.55 if paused else GLASS_THIN_A,
                      border=DANGER if err else GLASS_BORDER,
                      border_alpha=0.45 if err else 0.30,
                      highlight=0.07,
                      glow=0.16 if err else 0.0,
                      glow_color=DANGER if err else GLASS_GLOW)
    cw = card.width

    th_s = 62.0
    thumb = ThumbView(job_thumb_image(job), frame=(12, (h - th_s) / 2.0,
                                                   th_s, th_s))
    thumb.corner_radius = 13
    thumb.border_width = 1
    thumb.border_color = rgba(GLASS_BORDER, 0.18)
    card.add_subview(thumb)

    # Правая колонка кнопок — своя территория, тексты сюда не заходят.
    ctrl_x = cw - 12.0 - CTRL_SIZE
    left = 12.0 + th_s + 12.0
    text_w = max(60.0, ctrl_x - 12.0 - left)

    title = temp.title if temp is not None else job.display_title
    tl = make_label(safe_name(title, 30), (F_BOLD, 14.5), TEXT_PRIMARY,
                    frame=(left, 13, text_w, 19))
    card.add_subview(tl)

    if temp is not None:
        sub = 'Не завершено  •  ' + fmt_size(temp.size)
        detail = ''
        bar_value = temp.progress
        sub_col = TEXT_MUTED
        indeterminate = False
    else:
        sub, detail = job.sub_line(), job.detail_line()
        sub_col = DANGER if err else (ACCENT_LIGHT
                                      if job.status == ST_DOWNLOADING
                                      else TEXT_SECONDARY)
        bar_value, indeterminate = job_bar_state(job)

    sl = make_label(sub, (F_REG, 12), sub_col, frame=(left, 33, text_w, 16))
    card.add_subview(sl)

    bar = ProgressBar(bar_value, frame=(left, 56, text_w, 6))
    card.add_subview(bar)
    if indeterminate:
        # Начальная фаза берётся у общего такта приложения; дальше её
        # двигает NoxApp._tick, собственного таймера у полосы нет.
        bar.set_phase(screen.app.indeterminate_phase)

    dl = make_label(detail, (F_REG, 11), DANGER if err else TEXT_MUTED,
                    lines=2, frame=(left, 68, text_w, 26))
    card.add_subview(dl)

    if job is not None:
        toggle, toggle_icon = screen._toggle_button(job, ctrl_x, 9)
        card.add_subview(toggle)
        card.add_subview(screen._dismiss_button(job, ctrl_x, 9 + CTRL_SIZE + 3))
        screen._job_views[job.id] = {'sub': sl, 'detail': dl, 'bar': bar,
                                     'title': tl, 'toggle': toggle,
                                     'toggle_icon': toggle_icon}
    else:
        card.add_subview(screen._temp_button(temp, ctrl_x,
                                             (h - CTRL_SIZE) / 2.0))
    return card


def job_thumb_image(job):
    """Обложка задания, если ExtrasManager её уже положил рядом. Иначе None."""
    if job is None:
        return None
    try:
        item = LIB.find_by_watch_id(job.video_id) if job.video_id else None
        if item is not None:
            return item.load_thumb_image()
    except Exception:
        pass
    return None


class WatchLine(ui.View):
    """
    Очень тонкая полоса просмотра по нижнему краю обложки. Две
    скруглённые заливки, ни одного цикла — полосам взяться неоткуда.
    """

    def __init__(self, value, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.user_interaction_enabled = False
        self.value = max(0.0, min(1.0, float(value or 0.0)))

    def draw(self):
        w, h = self.width, self.height
        if w <= 0 or h <= 0:
            return
        r = h / 2.0
        ui.set_color(rgba(BG_DEEP, 0.75))
        ui.Path.rounded_rect(0, 0, w, h, r).fill()
        if self.value > 0:
            ui.set_color(ACCENT)
            ui.Path.rounded_rect(0, 0, max(h, w * self.value), h, r).fill()


def watch_fraction(item):
    """Доля просмотренного этого видео или None. Ничего не выдумываем."""
    try:
        entry = STATE.watch_get(item.watch_id)
    except Exception:
        return None
    if not isinstance(entry, dict):
        return None
    try:
        pos = float(entry.get('position') or 0.0)
        total = float(entry.get('duration') or item.duration or 0.0)
    except Exception:
        return None
    if total <= 0 or pos <= 0:
        return None
    return max(0.0, min(1.0, pos / total))


def watch_position(item):
    """Сохранённая позиция в секундах или 0."""
    try:
        entry = STATE.watch_get(item.watch_id)
        return float(entry.get('position') or 0.0) if entry else 0.0
    except Exception:
        return 0.0


def dots_button(action, x, y, h=CTRL_ZONE, w=CTRL_ZONE):
    """
    Троеточие в СОБСТВЕННОЙ зоне CTRL_ZONE x CTRL_ZONE.

    Раньше значок стоял впритык к рамке карточки и читался как случайная
    точка. Теперь у него фиксированная колонка, значок центрирован в ней,
    хит-таргет не меньше 40x40, а сам знак остаётся маленьким.
    """
    holder = ui.View(frame=(x, y, w, h))
    holder.background_color = 'clear'
    holder.add_subview(Icon('dots', TEXT_MUTED, 1.6,
                            frame=(w / 2.0 - 5, h / 2.0 - 9, 10, 18)))
    hit = ui.Button(frame=(0, 0, w, h))
    hit.flex = 'WH'
    hit.background_color = 'clear'
    hit.action = action
    holder.add_subview(hit)
    return holder


def play_orb(cx, cy, size=54.0):
    """Стеклянная круглая кнопка play поверх обложки (эталон, фото 3)."""
    v = GlassView(radius=size / 2.0, tint='#05070F', tint_alpha=0.58,
                  border=GLASS_HIGHLIGHT, border_alpha=0.16,
                  highlight=GLASS_HL_A, material='thin',
                  frame=(cx - size / 2.0, cy - size / 2.0, size, size))
    v.user_interaction_enabled = False
    s = size * 0.40
    v.add_subview(Icon('play', TEXT_PRIMARY, 1.9,
                       frame=(size / 2.0 - s * 0.42, size / 2.0 - s / 2.0,
                              s, s)))
    return v


# =====================================================================
#  NOX SHEET — собственные меню вместо системных окон
# =====================================================================

class NoxSheet(ui.View):
    """
    Единственный модальный компонент NOX.

    БАГ, который здесь исправлен. Раньше __init__ создавал panel и body
    с временным кадром 100x100, а вызывающий код СРАЗУ строил содержимое:
    header/row/chips считали ширину как self.body.width, то есть по 100 pt.
    Настоящую ширину panel получала только позже, в layout(), но уже
    построенные чипы не пересчитывались — отсюда колонка слева и подписи
    «Сн...», «По...», «3...».

    Теперь лист хранит НЕ готовые view, а функцию-строитель, и вызывает её
    из layout(), когда реальная ширина уже известна. Смена ширины (другой
    телефон, поворот) полностью перестраивает содержимое.
    """

    SIDE = 12.0
    ROW_H = 56.0
    MAX_W = 520.0
    TOP_PAD = 26.0          # место под drag handle
    BOTTOM_PAD = 16.0

    def __init__(self, app, builder=None, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.app = app
        self.background_color = 'clear'
        self.builder = builder
        self._closing = False
        self._on_close = None
        self._built_w = -1.0
        self._content_h = 0.0
        self._presented = False

        self.dim = ui.View(frame=self.bounds)
        self.dim.flex = 'WH'
        self.dim.background_color = rgba('#000000', 0.52)
        self.dim.alpha = 0.0
        self.add_subview(self.dim)
        self.dim_hit = ui.Button(frame=self.bounds)
        self.dim_hit.flex = 'WH'
        self.dim_hit.background_color = 'clear'
        self.dim_hit.action = lambda sender: self.close()
        self.dim.add_subview(self.dim_hit)

        # Тёмная стеклянная панель: графитово-синяя, с волосяной рамкой.
        self.panel = GlassView(radius=GLASS_RADIUS + 6, tint='#0A0D18',
                               tint_alpha=0.80, border=GLASS_BORDER,
                               border_alpha=0.26, highlight=GLASS_HL_A,
                               material='glass', shadow=True,
                               frame=(0, 0, 100, 100))
        self.add_subview(self.panel)

        self.handle = ui.View(frame=(0, 10, 40, 5))
        self.handle.background_color = rgba(TEXT_MUTED, 0.5)
        self.handle.corner_radius = 2.5
        self.handle.user_interaction_enabled = False
        self.panel.add_subview(self.handle)

        self.body = ui.View(frame=(0, self.TOP_PAD, 100, 100))
        self.body.background_color = 'clear'
        self.panel.add_subview(self.body)

    # -- вёрстка ---------------------------------------------------
    def panel_width(self):
        return max(120.0, min(self.MAX_W, self.width - self.SIDE * 2))

    def layout(self):
        w, hh = self.width, self.height
        if w <= 1 or hh <= 1:
            return
        pw = self.panel_width()
        if abs(pw - self._built_w) > 0.5:
            # Ширина известна ТОЛЬКО здесь — содержимое строится под неё.
            self._built_w = pw
            self._content_h = self._build_content(pw)
        ph = self._content_h + self.TOP_PAD + self.BOTTOM_PAD
        ph = max(180.0, min(ph, hh - 90.0))
        bottom = getattr(self.app, 'bottom_inset', 20.0) + 10.0
        px = (w - pw) / 2.0
        py = hh - ph - bottom
        if self._presented:
            self.panel.frame = (px, py, pw, ph)
        else:
            self.panel.frame = (px, hh, pw, ph)
        self.handle.frame = (pw / 2.0 - 20, 10, 40, 5)
        self.body.frame = (0, self.TOP_PAD, pw, ph - self.TOP_PAD)

    def resting_frame(self):
        pw = self.panel_width()
        ph = self.panel.frame[3]
        bottom = getattr(self.app, 'bottom_inset', 20.0) + 10.0
        return ((self.width - pw) / 2.0, self.height - ph - bottom, pw, ph)

    def _build_content(self, pw):
        """Полная пересборка содержимого под ширину pw."""
        for v in list(self.body.subviews):
            deactivate_tree(v)
            self.body.remove_subview(v)
        if not callable(self.builder):
            return 0.0
        try:
            return float(self.builder(self, pw) or 0.0)
        except Exception as e:
            log_debug('sheet build: %r' % (e,))
            return 0.0

    def rebuild(self):
        """Перестроить содержимое, сохранив открытое состояние листа."""
        self._built_w = -1.0
        self.layout()

    # -- содержимое (ширина приходит параметром, а не из body) -----
    def add(self, view):
        self.body.add_subview(view)
        return view

    def header(self, w, y, title, subtitle=None, thumb=None):
        """Шапка листа: обложка, полное название, метаданные."""
        pad = 18.0
        h = 62.0 if thumb is not None else 48.0
        v = ui.View(frame=(0, y, w, h))
        v.background_color = 'clear'
        left = pad
        if thumb is not None:
            tw, th_h = 84.0, 52.0
            th = ThumbView(thumb, frame=(pad, (h - th_h) / 2.0, tw, th_h))
            th.corner_radius = 11
            th.border_width = 1
            th.border_color = rgba(GLASS_BORDER, 0.22)
            v.add_subview(th)
            left = pad + tw + 12.0
        tw_text = max(60.0, w - left - pad)
        v.add_subview(make_label(safe_name(title, 46), (F_BOLD, 17),
                                 TEXT_PRIMARY, lines=2,
                                 frame=(left, 2, tw_text, 40)))
        if subtitle:
            v.add_subview(make_label(subtitle, (F_REG, 12.5), TEXT_MUTED,
                                     frame=(left, 42, tw_text, 17)))
        self.add(v)
        return y + h + 12

    def row(self, w, y, icon, title, action, danger=False, subtitle=None):
        """Строка действия почти на всю ширину листа."""
        pad = 14.0
        h = self.ROW_H
        rw = w - pad * 2
        col = DANGER if danger else TEXT_PRIMARY
        v = GlassView(radius=15.0, tint=(DANGER if danger else GLASS_BG_STRONG),
                      tint_alpha=0.14 if danger else 0.68,
                      border=DANGER if danger else GLASS_BORDER,
                      border_alpha=0.28 if danger else GLASS_BORDER_A,
                      highlight=GLASS_HL_A, frame=(pad, y, rw, h))
        v.add_subview(Icon(icon, DANGER if danger else ACCENT_LIGHT, 1.8,
                           frame=(18, h / 2.0 - 10, 20, 20)))
        ty = 0 if not subtitle else 8
        v.add_subview(make_label(title, (F_REG, 16), col,
                                 frame=(52, ty, rw - 70,
                                        h if not subtitle else 20)))
        if subtitle:
            v.add_subview(make_label(subtitle, (F_REG, 12), TEXT_MUTED,
                                     frame=(52, 30, rw - 70, 16)))
        hit = ui.Button(frame=(0, 0, rw, h))
        hit.flex = 'WH'
        hit.background_color = 'clear'
        hit.action = action
        v.add_subview(hit)
        self.add(v)
        return y + h + 9

    def section(self, w, y, title):
        self.add(make_label(title, (F_BOLD, 12.5), TEXT_MUTED,
                            frame=(20, y, w - 40, 18)))
        return y + 26

    def chips(self, w, y, options, current, on_pick, per_row=2):
        """
        Сетка выбора. Ширина колонки считается от РЕАЛЬНОЙ ширины листа,
        поэтому подписи помещаются целиком и «Сн...» больше не бывает.
        """
        pad = 14.0
        gap = 8.0
        avail = w - pad * 2
        cw = (avail - gap * (per_row - 1)) / float(per_row)
        ch = 44.0
        for i, (key, title) in enumerate(options):
            col = i % per_row
            row = i // per_row
            sel = (key == current)
            tile = GlassView(radius=14.0, tint=GLASS_BG_STRONG,
                             tint_alpha=0.68, border=GLASS_BORDER,
                             border_alpha=GLASS_BORDER_A,
                             highlight=GLASS_HL_A,
                             frame=(pad + col * (cw + gap),
                                    y + row * (ch + gap), cw, ch))
            tile.set_active(sel)
            tile.add_subview(make_label(title, (F_BOLD if sel else F_REG, 14),
                                        TEXT_PRIMARY if sel else TEXT_SECONDARY,
                                        ui.ALIGN_CENTER, frame=(4, 0, cw - 8, ch)))
            hit = ui.Button(frame=(0, 0, cw, ch))
            hit.flex = 'WH'
            hit.background_color = 'clear'
            hit.action = self._picker(on_pick, key)
            tile.add_subview(hit)
            self.add(tile)
        rows = (len(options) + per_row - 1) // per_row
        return y + rows * (ch + gap) + 2

    @staticmethod
    def _picker(on_pick, key):
        def _act(sender):
            on_pick(key)
        return _act

    def buttons(self, w, y, left_title, left_action, right_title, right_action,
                right_danger=False):
        """Две широкие кнопки по половине ширины листа."""
        pad = 14.0
        gap = 10.0
        bw = (w - pad * 2 - gap) / 2.0
        h = 52.0
        for i, (title, action, danger) in enumerate(
                ((left_title, left_action, False),
                 (right_title, right_action, right_danger))):
            v = GlassView(radius=h / 2.0,
                          tint=(DANGER if danger else GLASS_BG_STRONG),
                          tint_alpha=0.22 if danger else 0.72,
                          border=DANGER if danger else GLASS_BORDER,
                          border_alpha=0.40 if danger else 0.22,
                          highlight=GLASS_HL_A, material='glass',
                          frame=(pad + i * (bw + gap), y, bw, h))
            v.add_subview(make_label(title, (F_BOLD, 16),
                                     DANGER if danger else TEXT_PRIMARY,
                                     ui.ALIGN_CENTER, frame=(0, 0, bw, h)))
            hit = ui.Button(frame=(0, 0, bw, h))
            hit.flex = 'WH'
            hit.background_color = 'clear'
            hit.action = action
            v.add_subview(hit)
            self.add(v)
        return y + h + 6

    def text_block(self, w, y, text):
        pad = 14.0
        bw = w - pad * 2
        lines = max(1, min(6, len(text) // max(1, int(bw / 7.0)) + 1))
        h = 22.0 + lines * 17.0
        v = GlassView(radius=14.0, tint=GLASS_BG_DEEP, tint_alpha=0.85,
                      border=GLASS_BORDER, border_alpha=0.18,
                      frame=(pad, y, bw, h))
        v.add_subview(make_label(text, (F_REG, 12.5), TEXT_SECONDARY,
                                 lines=lines,
                                 frame=(16, 11, bw - 32, h - 22)))
        self.add(v)
        return y + h + 9

    # -- показ и закрытие ------------------------------------------
    def present(self, on_close=None):
        self._on_close = on_close
        self.layout()                     # содержимое строится здесь
        rest = self.resting_frame()
        f = self.panel.frame
        self.panel.frame = (rest[0], self.height, rest[2], rest[3])
        self.panel.alpha = 0.0
        self._presented = True

        def _in():
            self.dim.alpha = 1.0
            self.panel.frame = rest
            self.panel.alpha = 1.0
        animate(_in, 0.24)

    def close(self, sender=None):
        if self._closing:
            return
        self._closing = True
        f = self.panel.frame

        def _out():
            self.dim.alpha = 0.0
            self.panel.alpha = 0.0
            self.panel.frame = (f[0], self.height, f[2], f[3])

        def _done():
            try:
                deactivate_tree(self)
                if self.superview is not None:
                    self.superview.remove_subview(self)
                if getattr(self.app, 'sheet', None) is self:
                    self.app.sheet = None
            except Exception as e:
                log_debug('sheet close: %r' % (e,))
            cb = self._on_close
            if callable(cb):
                try:
                    cb()
                except Exception as e:
                    log_debug('sheet on_close: %r' % (e,))
        animate(_out, 0.20, 0.0, _done)


# =====================================================================
#  НИЖНЯЯ НАВИГАЦИЯ
# =====================================================================

class ActivePill(ui.View):
    """
    Капсула активной вкладки: ТЁМНОЕ фиолетовое стекло внутри общей
    панели, а не белая таблетка и не прямоугольник.

    Полос нет ни одной: форму задаёт cornerRadius = h/2, тело — тёмная
    подкраска ACCENT_DEEP с малой альфой, край — тонкая lavender-линия,
    ореол — тень CALayer со скруглённым shadowPath.
    """

    def __init__(self, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.user_interaction_enabled = False
        r = max(1.0, self.height / 2.0)
        # Своего blur у капсулы НЕТ намеренно: она лежит поверх уже
        # размытой панели, и второй слой размытия только осветлял бы её.
        self.level = 0
        skin = ui.View(frame=self.bounds)
        skin.flex = 'WH'
        skin.user_interaction_enabled = False
        # Тёмный фиолет: под blur чуть легче, без него плотнее.
        skin.background_color = rgba(ACCENT_DEEP, 0.58)
        set_corner(skin, r)
        skin.border_width = 1.0
        skin.border_color = rgba(ACCENT_LIGHT, 0.34)
        self.add_subview(skin)
        self.skin = skin
        self._spec = attach_gradient(
            skin,
            [(GLASS_HIGHLIGHT, GLASS_HL_A, 0.0),
             (GLASS_HIGHLIGHT, 0.0, 0.7)], radius=r)
        apply_shadow(self, ACCENT, 0.30, 12.0, (0.0, 0.0), corner=r)

    def layout(self):
        r = max(1.0, self.height / 2.0)
        skin = getattr(self, 'skin', None)
        if skin is not None:
            set_corner(skin, r)
        if self._spec is not None:
            sync_layer(self._spec, 0, 0, self.width, self.height)
            try:
                self._spec.setCornerRadius_(float(r))
            except Exception:
                pass
        set_shadow_path(self, r)

    def draw(self):
        if getattr(self, 'skin', None) is not None:
            return
        w, h = self.width, self.height
        if w <= 0 or h <= 0:
            return
        ui.set_color(rgba(ACCENT_DEEP, 0.60))
        ui.Path.rounded_rect(0, 0, w, h, h / 2.0).fill()


class TabItem(Tappable):
    def __init__(self, title, icon_name, index, on_tap, **kwargs):
        Tappable.__init__(self, action=self._tapped, press_scale=0.92, **kwargs)
        self.background_color = 'clear'
        self.index = index
        self.on_tap = on_tap
        self.selected = False
        self.icon = Icon(icon_name, TEXT_MUTED, 1.9, frame=(0, 0, 24, 24))
        self.add_subview(self.icon)
        self.label = make_label(title, (F_REG, 10.5), TEXT_MUTED,
                                ui.ALIGN_CENTER)
        self.add_subview(self.label)

    def _tapped(self, sender):
        handler = self.on_tap
        if callable(handler):
            handler(self.index)

    def layout(self):
        w, h = self.width, self.height
        self.icon.frame = (w / 2 - 12, h * 0.24 - 4, 24, 24)
        self.label.frame = (0, h * 0.24 + 22, w, 14)

    def set_selected(self, flag):
        if self.selected == flag:
            return
        self.selected = flag
        col = TEXT_PRIMARY if flag else TEXT_MUTED
        self.icon.set_icon(color=col)
        self.icon.line = 2.1 if flag else 1.9
        self.label.text_color = col
        self.label.font = (F_BOLD if flag else F_REG, 10.5)


class TabBar(ui.View):
    """
    Плавающая стеклянная панель: не касается краёв экрана и не прижата
    к самому низу. Единственное место в NOX, где просится настоящий
    UIVisualEffectView — она висит поверх прокручиваемого контента.
    """

    def __init__(self, on_tap, **kwargs):
        ui.View.__init__(self, **kwargs)
        self.background_color = 'clear'
        self.items = []
        self.index = 0
        # Главный showcase Liquid Glass: контейнер из UIGlassContainerEffect,
        # внутри которого живёт отдельное стекло активной вкладки.
        # Тёмная плавающая капсула: почти чёрная, прозрачная, с волосяной
        # синеватой рамкой. Белой она быть не должна.
        self.capsule = GlassView(radius=NAV_HEIGHT / 2.0,
                                 tint=GLASS_TINT, tint_alpha=0.72,
                                 border=GLASS_BORDER, border_alpha=0.22,
                                 border_w=1.0, highlight=GLASS_HL_A,
                                 material='glass', container=True,
                                 shadow=True,
                                 frame=(NAV_SIDE, 0, 100, NAV_HEIGHT))
        self.add_subview(self.capsule)
        self.pill = ActivePill(frame=(0, (NAV_HEIGHT - PILL_H) / 2.0,
                                      10, PILL_H))
        self.capsule.add_subview(self.pill)
        specs = [('Главная', 'home'), ('Загрузки', 'download'),
                 ('Плеер', 'play_circle'), ('Настройки', 'gear')]
        for i, (title, icon) in enumerate(specs):
            it = TabItem(title, icon, i, on_tap)
            self.capsule.add_subview(it)
            self.items.append(it)
        self.items[0].set_selected(True)

    def _slot(self, index):
        # Капсула заметно уже своего слота и ниже панели: она не должна
        # перетягивать на себя всё внимание.
        n = max(1, len(self.items))
        iw = self.capsule.width / float(n)
        pw = max(44.0, iw - 16.0)
        return (index * iw + (iw - pw) / 2.0, (NAV_HEIGHT - PILL_H) / 2.0,
                pw, PILL_H)

    def layout(self):
        w = max(80.0, self.width - NAV_SIDE * 2)
        self.capsule.frame = (NAV_SIDE, 0, w, NAV_HEIGHT)
        n = max(1, len(self.items))
        iw = w / float(n)
        for i, it in enumerate(self.items):
            it.frame = (i * iw, 0, iw, NAV_HEIGHT)
        self.pill.frame = self._slot(self.index)

    def select(self, index):
        self.index = index
        for i, it in enumerate(self.items):
            it.set_selected(i == index)
        target = self._slot(index)
        # Капсула ПЕРЕЕЗЖАЕТ, а не создаётся заново.
        animate(lambda: setattr(self.pill, 'frame', target), 0.27)


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
    def _icon_button(icon_name, color, action, x, y, w=CTRL_SIZE,
                     h=CTRL_SIZE, glow=0.0, border=GLASS_BORDER):
        """
        Круглая стеклянная кнопка, как на эталоне. Хит-таргет — настоящий
        прозрачный ui.Button: собственный touch_ended у Tappable на
        устройстве до обработчика не доходил. Возвращает (holder, icon),
        чтобы значок менялся на месте, без пересборки карточки.
        """
        holder, icon = glass_circle((x, y, w, h), icon_name or 'pause',
                                    color, 1.9, w * 0.42, action,
                                    glow=glow, border=border,
                                    material='thin')
        return holder, icon

    def _toggle_button(self, job, x, y, w=CTRL_SIZE, h=CTRL_SIZE):
        """⏸ или ▶ — пауза и продолжение, отдельная кнопка от крестика."""
        name = job.action_icon()
        resume = (name == 'play')
        holder, icon = self._icon_button(
            name, TEXT_PRIMARY if resume else TEXT_SECONDARY,
            self._make_toggle(job), x, y, w, h,
            glow=0.28 if resume else 0.10,
            border=GLASS_BORDER_ACTIVE if resume else GLASS_BORDER)
        holder.hidden = not name
        return holder, icon

    def _dismiss_button(self, job, x, y, w=CTRL_SIZE, h=CTRL_SIZE):
        """× — удалить загрузку полностью вместе с недокачанным файлом."""
        holder, _ = self._icon_button('close', TEXT_SECONDARY,
                                      self._make_dismiss(job), x, y, w, h)
        return holder

    def _temp_button(self, temp, x, y, w=CTRL_SIZE, h=CTRL_SIZE):
        """× у недокачанного файла без карточки: удаляет сам файл."""
        path = getattr(temp, 'path', '')

        def _act(sender):
            self.app.delete_temp(path)
        holder, _ = self._icon_button('close', TEXT_SECONDARY, _act, x, y, w, h)
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
            # Цвет подписи следует за состоянием: красный только у ошибки.
            col = DANGER if job.status == ST_ERROR else (
                ACCENT_LIGHT if job.status == ST_DOWNLOADING else TEXT_SECONDARY)
            if sub.text_color != col:
                sub.text_color = col
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
            value, moving = job_bar_state(job)
            bar.set_value(value)
            if moving:
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

    def _open_filter_menu(self, sender):
        """
        Сортировка и фильтр — СВОЙ стеклянный лист.

        dialogs.list_dialog отсюда удалён совсем: белое системное окно
        выбивалось из дизайна, а на устройстве иногда уносило Pythonista.
        Метод больше не помечен @ui.in_background — лист открывается
        строго на главном потоке.
        """
        self.app.open_filter_sheet(self.rebuild)

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

        # Плавающая панель навигации не должна накрывать последнюю
        # карточку: к её высоте добавляются safe area и зазор.
        y += 28 + self.app.bottom_inset + NAV_HEIGHT
        self.sv.content_size = (0, y)

    # ---------------------------------------------------------------
    def _build_search(self, w, y):
        """Большая стеклянная строка поиска и отдельная круглая кнопка."""
        h = 56.0
        gap = 12.0
        btn = h
        box_w = w - PAD * 2 - btn - gap
        box = glass_pill((PAD, y, box_w, h), tint=GLASS_TINT,
                         tint_alpha=0.70, border_alpha=0.20,
                         highlight=GLASS_HL_A, material='glass')
        box.add_subview(Icon('search', TEXT_MUTED, 2.0,
                             frame=(20, h / 2 - 11, 22, 22)))

        tf = ui.TextField(frame=(52, 7, box_w - 52 - 16, h - 14))
        tf.placeholder = 'Поиск в медиатеке...'
        tf.background_color = 'clear'
        tf.text_color = TEXT_PRIMARY
        tf.tint_color = ACCENT
        tf.font = (F_REG, 16)
        tf.bordered = False
        tf.clear_button_mode = 'while_editing'
        tf.autocorrection_type = False
        tf.text = self.query
        self._delegate = SearchDelegate(self._on_query)
        tf.delegate = self._delegate
        box.add_subview(tf)
        self.sv.add_subview(box)

        active = (STATE.get('sort', 'new') != 'new'
                  or STATE.get('quality_filter', 'all') != 'all')
        circle, _ = glass_circle((w - PAD - btn, y, btn, btn), 'tune',
                                 ACCENT_LIGHT if active else TEXT_SECONDARY,
                                 1.9, 24.0, self._open_filter_menu,
                                 glow=0.26 if active else 0.10,
                                 border=(GLASS_BORDER_ACTIVE if active
                                         else GLASS_BORDER))
        self.sv.add_subview(circle)
        return y + h

    # ---------------------------------------------------------------
    def _build_continue(self, w, y):
        item, watch = self._resume_candidate()
        if item is None:
            return y                       # блок полностью скрыт
        h = 166.0
        card = glass_card((PAD, y, w - PAD * 2, h), GLASS_RADIUS,
                          border_alpha=0.32, highlight=0.08)
        cw = card.width

        img = item.load_thumb_image()
        if img is not None:
            # Обложка занимает правую часть карточки, слева её съедает
            # тёмный градиент — ровно как на эталоне.
            iw = cw * 0.56
            holder = ui.View(frame=(cw - iw, 0, iw, h))
            holder.background_color = 'clear'
            holder.corner_radius = GLASS_RADIUS
            holder.user_interaction_enabled = False
            iv = ui.ImageView(frame=(0, 0, iw, h))
            iv.flex = 'WH'
            iv.content_mode = ui.CONTENT_SCALE_ASPECT_FILL
            iv.image = img
            holder.add_subview(iv)
            holder.add_subview(fade_overlay((0, 0, iw, h)))
            card.add_subview(holder)

        # Капсула «Продолжить» стоит в правом нижнем углу, поэтому текстовая
        # колонка обязана заканчиваться левее неё — иначе метаданные лезут
        # под кнопку на узких экранах.
        bw, bh = min(168.0, cw * 0.48), 46.0
        text_w = max(90.0, cw - 16.0 - bw - 26.0)
        row = Tappable(action=lambda s: self.app.open_media(item),
                       press_scale=0.99, frame=(14, 14, 190, 30))
        row.background_color = 'clear'
        circ = GlassView(radius=15.0, tint=GLASS_BG_STRONG, tint_alpha=0.74,
                         border=GLASS_BORDER, border_alpha=0.26,
                         highlight=GLASS_HL_A, material='glass',
                         frame=(0, 0, 30, 30))
        circ.add_subview(Icon('play', TEXT_PRIMARY, 1.7, frame=(10, 8, 13, 14)))
        row.add_subview(circ)
        row.add_subview(make_label('Продолжить просмотр', (F_REG, 13),
                                   TEXT_SECONDARY, frame=(38, 0, 152, 30)))
        card.add_subview(row)

        title = make_label(safe_name(item.title, 40), (F_BOLD, 19),
                           TEXT_PRIMARY, lines=2,
                           frame=(16, 50, text_w, 46))
        card.add_subview(title)

        # Полоса реальная: доля просмотренного из watch_progress.
        fraction = self._watched_fraction(watch)
        if fraction is not None:
            card.add_subview(ProgressBar(fraction,
                                         frame=(16, 106, text_w, 5)))

        # Строка метаданных живёт слева от капсулы «Продолжить», поэтому
        # она короткая. Если известно, сколько осталось, — это важнее
        # размера файла: размер видно на карточке медиатеки ниже.
        left_time = self._remaining(watch)
        if left_time:
            meta_parts = ['Осталось ' + left_time, item.quality_label]
        else:
            meta_parts = [fmt_duration(item.duration) if item.duration else '',
                          item.quality_label, fmt_size(item.size)]
        meta = make_label(' • '.join([m for m in meta_parts if m]),
                          (F_REG, 12.5), TEXT_SECONDARY,
                          frame=(16, 120, text_w, 18))
        card.add_subview(meta)

        # Капсула «Продолжить» в правом нижнем углу, как на эталоне.
        btn = GlassView(radius=bh / 2.0, tint=ACCENT_DEEP, tint_alpha=0.82,
                        border=ACCENT_LIGHT, border_alpha=0.34,
                        highlight=GLASS_HL_A, glow=0.22, material='glass',
                        frame=(cw - bw - 14, h - bh - 14, bw, bh))
        btn.add_subview(Icon('play', TEXT_PRIMARY, 1.9, frame=(26, 15, 17, 17)))
        btn.add_subview(make_label('Продолжить', (F_BOLD, 16), TEXT_PRIMARY,
                                   frame=(52, 0, bw - 60, bh)))
        hit = ui.Button(frame=(0, 0, bw, bh))
        hit.flex = 'WH'
        hit.background_color = 'clear'
        hit.action = lambda s: self.app.open_media(item)
        btn.add_subview(hit)
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

        # Три карточки в ширину экрана, как на эталоне; остальное
        # уезжает горизонтальной прокруткой.
        gap = 12.0
        card_w = max(96.0, (w - PAD * 2 - gap * 2) / 3.0)
        thumb_h = card_w * 1.16
        row_h = thumb_h + 10 + 17 + 15 + 8 + 30

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
        # Хит-таргетом служит настоящий прозрачный ui.Button: собственный
        # touch_ended у Tappable на устройстве до обработчика не доходил.
        c = Tappable(action=None, press_scale=0.985,
                     frame=(x, y, cw, total_h))
        c.background_color = 'clear'

        th = ThumbView(item.load_thumb_image(), frame=(0, 0, cw, thumb_h))
        th.corner_radius = 16
        th.border_width = 1
        th.border_color = rgba(GLASS_BORDER, 0.18)
        c.add_subview(th)
        c.add_subview(check_badge(cw - 32, 6))
        dur = fmt_clock(item.duration)
        if dur:
            c.add_subview(duration_badge(dur, cw - 6, thumb_h - 28))

        ty = thumb_h + 10
        t = make_label(safe_name(item.title, 18), (F_BOLD, 13), TEXT_PRIMARY,
                       frame=(1, ty, cw - 2, 17))
        c.add_subview(t)
        m = make_label(item.meta_line or item.fmt_label, (F_REG, 10.5),
                       TEXT_MUTED, frame=(1, ty + 17, cw - 2, 14))
        c.add_subview(m)

        py = ty + 40
        pill = glass_pill((0, py, min(84.0, cw - CTRL_ZONE + 4), 30),
                          tint=GLASS_BG_STRONG, border=ACCENT,
                          border_alpha=0.32, highlight=0.12)
        pill.user_interaction_enabled = False
        pill.add_subview(Icon('check', ACCENT_LIGHT, 1.8, frame=(10, 9, 12, 12)))
        pill.add_subview(make_label('Офлайн', (F_REG, 11), ACCENT_LIGHT,
                                    frame=(26, 0, pill.width - 30, 30)))
        c.add_subview(pill)

        hit = ui.Button(frame=(0, 0, cw, thumb_h + 34))
        hit.background_color = 'clear'
        hit.action = lambda s: self.app.open_media(item)
        c.add_subview(hit)
        c.add_subview(dots_button(lambda s: self.app.item_menu(item),
                                  cw - CTRL_ZONE, py - 5, 34.0, CTRL_ZONE))
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

        for j in jobs[:4]:
            card = job_card(self, w, y, None, j)
            self.sv.add_subview(card)
            y += card.height + 10
        for t in temps[:3]:
            card = job_card(self, w, y, t, None)
            self.sv.add_subview(card)
            y += card.height + 10
        return y


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

        # Плавающая панель навигации не должна накрывать последнюю
        # карточку: к её высоте добавляются safe area и зазор.
        y += 28 + self.app.bottom_inset + NAV_HEIGHT
        self.sv.content_size = (0, y)

    # ---------------------------------------------------------------
    def _build_title(self, w, y):
        big_title(self.sv, w, y, 'Загрузчик', 'Сохраняйте видео для тишины')
        # Круглая стеклянная шестерёнка, как на эталоне. Действие прежнее.
        g, _ = glass_circle((w - PAD - 52, y + 4, 52, 52), 'gear',
                            TEXT_SECONDARY, 1.8, 24.0,
                            lambda x: self.app.select_tab(3), glow=0.14)
        self.sv.add_subview(g)
        return y + 70

    # ---------------------------------------------------------------
    def _build_url_box(self, w, y):
        h = 132.0
        # Внешняя панель — тёмное стекло, внутреннее поле ЕЩЁ темнее.
        # Раньше было наоборот: светлая панель и чёрный input.
        box = glass_panel((PAD, y, w - PAD * 2, h), GLASS_RADIUS,
                          tint=GLASS_TINT, tint_alpha=0.62,
                          border_alpha=0.18)
        bw = box.width

        fh = 58.0
        field = GlassView(radius=fh / 2.0, tint=GLASS_BG_DEEP,
                          tint_alpha=0.92, border=GLASS_BORDER,
                          border_alpha=0.22,
                          frame=(14, 14, bw - 28, fh))
        field.add_subview(Icon('link', TEXT_MUTED, 1.8, frame=(16, 19, 22, 20)))

        paste_w = 108.0
        pbh = 42.0
        tf = ui.TextField(frame=(46, 11, field.width - 46 - paste_w - 18, 36))
        tf.placeholder = 'Вставьте ссылку на видео...'
        tf.background_color = 'clear'
        tf.text_color = TEXT_PRIMARY
        tf.tint_color = ACCENT
        tf.font = (F_REG, 15)
        tf.bordered = False
        tf.autocorrection_type = False
        tf.autocapitalization_type = ui.AUTOCAPITALIZE_NONE
        tf.keyboard_type = ui.KEYBOARD_URL
        tf.text = self.url_text
        self._delegate = UrlDelegate(self)
        tf.delegate = self._delegate
        self._url_field = tf
        field.add_subview(tf)

        # Отдельная стеклянная кнопка «Вставить» внутри поля.
        pb = GlassView(radius=pbh / 2.0, tint=GLASS_BG_STRONG,
                       tint_alpha=0.78, border=GLASS_BORDER,
                       border_alpha=0.30, highlight=GLASS_HL_A,
                       material='glass',
                       frame=(field.width - paste_w - 8, (fh - pbh) / 2.0,
                              paste_w, pbh))
        pb.add_subview(Icon('clip', TEXT_PRIMARY, 1.7, frame=(14, 13, 16, 16)))
        pb.add_subview(make_label('Вставить', (F_REG, 13.5), TEXT_PRIMARY,
                                  frame=(36, 0, paste_w - 40, pbh)))
        phit = ui.Button(frame=(0, 0, paste_w, pbh))
        phit.flex = 'WH'
        phit.background_color = 'clear'
        phit.action = self._paste
        pb.add_subview(phit)
        field.add_subview(pb)
        box.add_subview(field)

        box.add_subview(Icon('info', TEXT_FAINT, 1.6, frame=(16, 90, 17, 17)))
        box.add_subview(make_label('Поддерживает VK, YouTube, Vimeo и другие',
                                   (F_REG, 12.5), TEXT_MUTED,
                                   frame=(41, 88, bw - 54, 20)))
        self.sv.add_subview(box)
        return y + h + 20

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
        self.sv.add_subview(make_label('Качество', (F_BOLD, 23), TEXT_PRIMARY,
                                       frame=(PAD, y, 180, 30)))
        self.sv.add_subview(make_label('Выше качество — больше файл',
                                       (F_REG, 11.5), TEXT_MUTED,
                                       ui.ALIGN_RIGHT,
                                       frame=(w - PAD - 220, y + 8, 220, 18)))
        y += 40

        gap = 10.0
        cw = (w - PAD * 2 - gap * 3) / 4.0
        ch = 78.0
        for i, (key, top, bottom) in enumerate(QUALITIES):
            x = PAD + i * (cw + gap)
            # Плитка — стекло, а не просто синяя рамка: у активной меняются
            # и поверхность, и край, и внутреннее свечение.
            # Неактивная плитка — тёмное navy-стекло, активная —
            # тёмное фиолетовое (см. GlassView.set_active).
            tile = GlassView(radius=16.0, tint=GLASS_TINT, tint_alpha=0.70,
                             border=GLASS_BORDER, border_alpha=GLASS_BORDER_A,
                             highlight=GLASS_HL_A, material='glass',
                             frame=(x, y, cw, ch))
            t = make_label(top, (F_BOLD, 17), TEXT_PRIMARY, ui.ALIGN_CENTER,
                           frame=(0, 20, cw, 22))
            tile.add_subview(t)
            b = make_label(bottom, (F_REG, 10.5), TEXT_MUTED, ui.ALIGN_CENTER,
                           frame=(0, 44, cw, 14))
            tile.add_subview(b)
            hit = ui.Button(frame=(0, 0, cw, ch))
            hit.flex = 'WH'
            hit.background_color = 'clear'
            hit.action = self._make_pick(key)
            tile.add_subview(hit)
            self.sv.add_subview(tile)
            self._chips.append((key, tile, t, b, None))
        self._refresh_chips()
        return y + ch + 22

    def _make_pick(self, key):
        def _pick(sender):
            self.quality = key
            STATE.set('quality', key)
            self._refresh_chips()
        return _pick

    def _refresh_chips(self):
        """Выделение меняет параметры стекла, а не пересобирает плитки."""
        for key, tile, t, b, _unused in self._chips:
            sel = (key == self.quality)
            tile.set_active(sel)
            t.text_color = TEXT_PRIMARY if sel else TEXT_SECONDARY
            b.text_color = ACCENT_LIGHT if sel else TEXT_MUTED

    # ---------------------------------------------------------------
    def _build_button(self, w, y):
        h = 70.0
        # Действие снято с Tappable: на устройстве его touch_ended до
        # обработчика не доходил. Хит-таргет — прозрачный ui.Button.
        btn = Tappable(action=None, press_scale=0.97,
                       frame=(PAD, y, w - PAD * 2, h))
        btn.background_color = 'clear'
        bw = btn.width
        # Градиент deep violet -> electric violet -> lavender blue плюс
        # верхний блик и мягкое свечение по краю.
        grad = CTAButtonView(frame=(0, 0, bw, h))
        grad.flex = 'WH'
        btn.add_subview(grad)
        btn.add_subview(Icon('download', TEXT_PRIMARY, 2.2,
                             frame=(bw / 2 - 84, h / 2 - 14, 28, 28)))
        btn.add_subview(make_label('Скачать', (F_BOLD, 22), TEXT_PRIMARY,
                                   frame=(bw / 2 - 46, 0, 180, h)))
        self._dl_button = btn

        hit = ui.Button(frame=(0, 0, bw, h))
        hit.flex = 'WH'
        hit.background_color = 'clear'
        hit.action = self._download
        btn.add_subview(hit)

        self.sv.add_subview(btn)
        y += h + 12
        if ytdlp_ready():
            note = 'Для больших загрузок не закрывайте и не сворачивайте NOX.'
            note_col = TEXT_MUTED
        else:
            note = (nox_download.YTDLP_ERROR
                    or 'Модуль yt-dlp не найден') + \
                   '  •  положите папку yt_dlp рядом с NOX.py'
            note_col = DANGER
        self.sv.add_subview(make_label(note, (F_REG, 11.5), note_col,
                                       ui.ALIGN_CENTER, lines=2,
                                       frame=(PAD, y, w - PAD * 2, 32)))
        return y + 42

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
            # поставит ссылку в очередь разбора.
            run_on_main(self.app.tick_soon)
        else:
            nox_error(msg)

    # ---------------------------------------------------------------
    def _build_queue(self, w, y):
        jobs = DOWNLOADER.visible_jobs()
        temps = LIB.orphan_temps(DOWNLOADER.managed_paths())
        count = len(temps) + len(jobs)
        self.sv.add_subview(make_label('Очередь загрузок', (F_BOLD, 23),
                                       TEXT_PRIMARY,
                                       frame=(PAD, y, w - 140, 30)))
        right = ('%d элем.' % count) if count else 'Пусто'
        self.sv.add_subview(make_label(right, (F_REG, 12.5), TEXT_MUTED,
                                       ui.ALIGN_RIGHT,
                                       frame=(w - PAD - 120, y + 8, 120, 18)))
        y += 42

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
        """Та же карточка, что и на «Главной»: одна композиция на всё NOX."""
        return job_card(self, w, y, temp, job)

    # ---------------------------------------------------------------
    def _build_storage(self, w, y):
        h = 108.0
        c = glass_card((PAD, y, w - PAD * 2, h), GLASS_RADIUS,
                       border_alpha=0.30, highlight=0.08)
        cw = c.width

        box = GlassView(radius=15.0, tint=GLASS_BG_STRONG, tint_alpha=0.74,
                        border=GLASS_BORDER, border_alpha=0.24,
                        highlight=GLASS_HL_A, material='glass',
                        frame=(14, 22, 54, 54))
        box.add_subview(Icon('drive', ACCENT_LIGHT, 1.9, frame=(14, 14, 26, 26)))
        c.add_subview(box)

        total, free = LIB.disk()
        used = LIB.used_bytes()
        left = 82.0
        right_w = 132.0

        c.add_subview(make_label('Офлайн-хранилище', (F_BOLD, 16.5),
                                 TEXT_PRIMARY,
                                 frame=(left, 24, cw - left - right_w, 22)))
        if total:
            sub = '%s из %s занято' % (fmt_size(used), fmt_size(total))
        else:
            sub = '%s в медиатеке' % fmt_size(used)
        c.add_subview(make_label(sub, (F_REG, 12), TEXT_MUTED,
                                 frame=(left, 46, cw - left - right_w, 17)))

        if total and free is not None:
            c.add_subview(make_label(fmt_size(free), (F_BOLD, 19),
                                     TEXT_PRIMARY, ui.ALIGN_RIGHT,
                                     frame=(cw - right_w - 6, 24, right_w, 24)))
            c.add_subview(make_label('Свободно', (F_REG, 11), TEXT_MUTED,
                                     ui.ALIGN_RIGHT,
                                     frame=(cw - right_w - 6, 48, right_w, 16)))
            c.add_subview(Icon('chevron', TEXT_MUTED, 1.6,
                               frame=(cw - 22, 40, 13, 15)))
            ratio = 0.0
            if total > 0:
                ratio = max(0.0, min(1.0, (total - free) / total))
            c.add_subview(ProgressBar(ratio, frame=(left, 76, cw - left - 16, 6)))
        else:
            c.add_subview(make_label('Объём тома недоступен', (F_REG, 11.5),
                                     TEXT_FAINT, ui.ALIGN_RIGHT,
                                     frame=(cw - 170, 40, 156, 18)))
        hit = ui.Button(frame=(0, 0, cw, h))
        hit.flex = 'WH'
        hit.background_color = 'clear'
        hit.action = lambda s: self.app.select_tab(3)
        c.add_subview(hit)
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
        y = big_title(self.sv, w, y, 'Плеер', 'Только локальные файлы') + 6

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
            # Первое видео — крупной карточкой, как на эталоне (фото 3).
            hero = self._featured(w, y, items[0])
            self.sv.add_subview(hero)
            y += hero.height + 12
            for item in items[1:]:
                row = self._row(w, y, item)
                self.sv.add_subview(row)
                y += row.height + 10

        # Плавающая панель навигации не должна накрывать последнюю
        # карточку: к её высоте добавляются safe area и зазор.
        y += 28 + self.app.bottom_inset + NAV_HEIGHT
        self.sv.content_size = (0, y)

    def _featured(self, w, y, item):
        """Большая карточка: обложка, круглый play, метаданные под ней."""
        cw = w - PAD * 2
        th_h = cw * 0.56
        h = th_h + 92.0
        c = glass_card((PAD, y, cw, h), GLASS_RADIUS, border_alpha=0.30,
                       highlight=0.08)

        th = ThumbView(item.load_thumb_image(), frame=(8, 8, cw - 16, th_h))
        th.corner_radius = 15
        th.border_width = 1
        th.border_color = rgba(GLASS_BORDER, 0.18)
        c.add_subview(th)
        c.add_subview(play_orb(cw / 2.0, 8 + th_h / 2.0, 74.0))
        dur = fmt_clock(item.duration)
        if dur:
            # Плашка всегда НИЖЕ круга play: они не пересекаются ни при
            # какой длительности и ни на какой ширине экрана.
            c.add_subview(duration_badge(dur, cw - 20, 8 + th_h - 28))

        ty = th_h + 20
        right = CTRL_ZONE + 12.0
        c.add_subview(make_label(safe_name(item.title, 34), (F_BOLD, 17),
                                 TEXT_PRIMARY,
                                 frame=(16, ty, cw - 16 - right, 22)))
        pos = watch_position(item)
        if pos > 0:
            meta = 'Продолжить с ' + fmt_clock(pos)
            meta_col = ACCENT_LIGHT
        else:
            meta = item.meta_line or item.fmt_label
            meta_col = TEXT_SECONDARY
        c.add_subview(make_label(meta, (F_REG, 12.5), meta_col,
                                 frame=(16, ty + 23, cw - 16 - right, 17)))
        tail = item.uploader or ''
        if tail:
            c.add_subview(make_label(safe_name(tail, 30), (F_REG, 11.5),
                                     TEXT_FAINT,
                                     frame=(16, ty + 42, cw - 16 - right, 16)))

        hit = ui.Button(frame=(8, 8, cw - 16, th_h))
        hit.background_color = 'clear'
        hit.action = lambda s: self.app.open_media(item)
        c.add_subview(hit)

        c.add_subview(dots_button(lambda s: self.app.item_menu(item),
                                  cw - CTRL_ZONE - 6, ty - 6))
        return c

    def _row(self, w, y, item):
        h = 108.0
        c = Tappable(action=None, press_scale=0.985,
                     frame=(PAD, y, w - PAD * 2, h))
        c.background_color = 'clear'
        cw = c.width
        c.add_subview(glass_card((0, 0, cw, h), CARD_RADIUS,
                                 border_alpha=0.28, highlight=0.07))

        tw, th_h, orb = 138.0, 88.0, 34.0
        ty = (h - th_h) / 2.0
        th = ThumbView(item.load_thumb_image(), frame=(10, ty, tw, th_h))
        th.corner_radius = 13
        th.border_width = 1
        th.border_color = rgba(GLASS_BORDER, 0.18)
        c.add_subview(th)
        c.add_subview(play_orb(10 + tw / 2.0, h / 2.0, orb))
        dur = fmt_clock(item.duration)
        if dur:
            # Как на эталоне — в левом нижнем углу обложки. Плашка живёт
            # строго ниже круга play (тот занимает ty+(th_h-orb)/2 ..
            # ty+(th_h+orb)/2), поэтому пересечься они не могут.
            c.add_subview(duration_badge(dur, 10 + 6 + badge_w(dur),
                                         ty + th_h - 26))
        frac = watch_fraction(item)
        if frac is not None:
            c.add_subview(WatchLine(frac, frame=(10, ty + th_h - 4, tw, 3)))

        left = 10.0 + tw + 14.0
        right = CTRL_ZONE + 12.0
        tw_text = max(50.0, cw - left - right)
        c.add_subview(make_label(safe_name(item.title, 26), (F_BOLD, 15),
                                 TEXT_PRIMARY, frame=(left, 24, tw_text, 20)))
        pos = watch_position(item)
        if pos > 0:
            row_meta, row_col = ('Продолжить с ' + fmt_clock(pos), ACCENT_LIGHT)
        else:
            row_meta, row_col = (item.meta_line or item.fmt_label,
                                 TEXT_SECONDARY)
        c.add_subview(make_label(row_meta, (F_REG, 12), row_col,
                                 frame=(left, 46, tw_text, 17)))
        tail = item.uploader or ''
        if tail:
            c.add_subview(make_label(safe_name(tail, 24), (F_REG, 11),
                                     TEXT_FAINT,
                                     frame=(left, 65, tw_text, 16)))

        hit = ui.Button(frame=(0, 0, cw - CTRL_ZONE - 8, h))
        hit.background_color = 'clear'
        hit.action = lambda s: self.app.open_media(item)
        c.add_subview(hit)
        c.add_subview(dots_button(lambda s: self.app.item_menu(item),
                                  cw - CTRL_ZONE - 6,
                                  (h - CTRL_ZONE) / 2.0))
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

        y = big_title(self.sv, w, y, 'Настройки',
                      'Общая папка и параметры загрузки') + 8

        y = self._quality_block(w, y)
        y = self._folder_block(w, y)
        y = self._storage_block(w, y)
        y = self._setup_block(w, y)
        y = self._about_block(w, y)

        # Плавающая панель навигации не должна накрывать последнюю
        # карточку: к её высоте добавляются safe area и зазор.
        y += 28 + self.app.bottom_inset + NAV_HEIGHT
        self.sv.content_size = (0, y)

    # ---------------------------------------------------------------
    def _card(self, w, y, h, title):
        """Раздел настроек — такая же стеклянная группа, как карточки."""
        c = glass_card((PAD, y, w - PAD * 2, h), GLASS_RADIUS,
                       border_alpha=0.30, highlight=0.07)
        c.add_subview(make_label(title, (F_BOLD, 16.5), TEXT_PRIMARY,
                                 frame=(16, 14, c.width - 32, 22)))
        return c

    def _row_button(self, cw, y, h, icon, title, action):
        """Строка-действие внутри раздела: стекло плюс прозрачный ui.Button."""
        b = GlassView(radius=13.0, tint=GLASS_BG_STRONG, tint_alpha=0.72,
                      border=GLASS_BORDER, border_alpha=0.24,
                      highlight=GLASS_HL_A, material='glass',
                      frame=(16, y, cw - 32, h))
        b.add_subview(Icon(icon, ACCENT_LIGHT, 1.7,
                           frame=(15, h / 2.0 - 9, 18, 18)))
        b.add_subview(make_label(title, (F_REG, 14), TEXT_PRIMARY,
                                 frame=(42, 0, cw - 74, h)))
        hit = ui.Button(frame=(0, 0, cw - 32, h))
        hit.flex = 'WH'
        hit.background_color = 'clear'
        hit.action = action
        b.add_subview(hit)
        return b

    def _quality_block(self, w, y):
        h = 132.0
        c = self._card(w, y, h, 'Качество по умолчанию')
        cw = c.width
        gap = 8.0
        chw = (cw - 32 - gap * 3) / 4.0
        cur = STATE.get('quality', '720')
        for i, (key, top, bottom) in enumerate(QUALITIES):
            # Ровно та же плитка, что на экране «Загрузки».
            sel = (key == cur)
            tile = GlassView(radius=14.0, frame=(16 + i * (chw + gap), 48,
                                                 chw, 62))
            tile.set_active(sel)
            tile.add_subview(make_label(top, (F_BOLD, 15),
                                        TEXT_PRIMARY if sel else TEXT_SECONDARY,
                                        ui.ALIGN_CENTER, frame=(0, 14, chw, 20)))
            tile.add_subview(make_label(bottom, (F_REG, 10),
                                        ACCENT_LIGHT if sel else TEXT_MUTED,
                                        ui.ALIGN_CENTER, frame=(0, 34, chw, 14)))
            hit = ui.Button(frame=(0, 0, chw, 62))
            hit.flex = 'WH'
            hit.background_color = 'clear'
            hit.action = self._make_pick(key)
            tile.add_subview(hit)
            c.add_subview(tile)
            self._chips.append(tile)
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
        h = 208.0
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
            ver = nox_download.YTDLP_VERSION
            value = 'Подключён' + (('  ' + ver) if ver else '')
        else:
            value = (nox_download.YTDLP_ERROR
                     or 'Модуль yt-dlp не найден')
        c.add_subview(make_label(value, (F_BOLD, 12.5),
                                 ACCENT_2 if ready else ERR_TXT,
                                 frame=(16, 151, cw - 150, 18)))
        c.add_subview(make_label('Пакет yt_dlp лежит рядом с NOX.py — '
                                 'загрузка идёт внутри приложения',
                                 (F_REG, 10.5), TEXT_FAINT, lines=2,
                                 frame=(16, 172, cw - 32, 30)))
        c.add_subview(status_pill('Готов' if ready else 'Нет модуля',
                                  'check' if ready else 'close',
                                  ACCENT_2 if ready else ERR_TXT,
                                  cw - 16 - 108, 138, w=108))
        self.sv.add_subview(c)
        return y + h + 14

    # ---------------------------------------------------------------
    def _storage_block(self, w, y):
        h = 176.0
        c = self._card(w, y, h, 'Хранилище')
        cw = c.width
        total, free = LIB.disk()
        used = LIB.used_bytes()

        rows = [
            ('Файлов в медиатеке', str(len(LIB.items))),
            ('Занято медиатекой', fmt_size(used)),
            ('Свободно на устройстве', fmt_size(free) if free else '—'),
        ]
        ry = 46.0
        for name, value in rows:
            c.add_subview(make_label(name, (F_REG, 12.5), TXT_2,
                                     frame=(16, ry, cw - 150, 18)))
            c.add_subview(make_label(value, (F_BOLD, 12.5), TXT, ui.ALIGN_RIGHT,
                                     frame=(cw - 150, ry, 134, 18)))
            ry += 24

        c.add_subview(self._row_button(cw, h - 52, 40, 'refresh',
                                       'Обновить медиатеку', self._refresh))
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
        h = 44 + body_h + 64
        c = self._card(w, y, h, 'Разовая настройка')
        cw = c.width
        c.add_subview(make_label(SETUP_TEXT, (F_REG, 11), TXT_2, lines=0,
                                 frame=(16, 40, body_w, body_h)))

        c.add_subview(self._row_button(cw, h - 52, 40, 'clip',
                                       'Скопировать инструкцию',
                                       self._copy_setup))
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
        PLAYER.app = self
        # Незавершённые загрузки прошлого запуска возвращаются как
        # приостановленные. Сами по себе они не стартуют никогда.
        try:
            DOWNLOADER.restore(STATE)
        except Exception as e:
            log_debug('restore: %r' % (e,))

        # Фон-градиент кладётся ПЕРВЫМ и лежит под всеми экранами.
        self._build_backdrop()

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
        self._layout_backdrop()
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

    def _build_backdrop(self):
        """
        ОДНА цельная поверхность фона.

        Раньше поверх градиента лежали три отдельные radial-view: на
        устройстве они читались как большие прямоугольные блоки, потому
        что вид квадратный, а свечение внутри него — нет. Теперь фон
        целиком в одном CAGradientLayer, а холодный оттенок — просто
        промежуточная точка того же градиента.
        """
        self.backdrop = GradientFill([(BG_TOP, 1.0, 0.0),
                                      (BG_GLOW, 0.22, 0.16),
                                      (BG_MID, 1.0, 0.55),
                                      (BG_DEEP, 1.0, 1.0)],
                                     start=(0.12, 0.0), end=(0.88, 1.0),
                                     flat=BG,
                                     frame=self.bounds)
        self.backdrop.flex = 'WH'
        self.add_subview(self.backdrop)

    def _layout_backdrop(self):
        """
        ui.Rect в Pythonista — НЕ кортеж: срез b.frame[2:] поднимал
        TypeError: sequence index must be integer, not 'slice'.
        Исключение прилетало из layout(), и до строки, которая ставит
        нижнюю панель на место, выполнение уже не доходило — TabBar так
        и оставалась со своим стартовым кадром в левом верхнем углу.
        Сравниваем обычные числовые свойства.
        """
        b = self.backdrop
        if b is None:
            return
        if b.width != self.width or b.height != self.height:
            b.frame = (0, 0, self.width, self.height)

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
    def open_media(self, item):
        """
        ЕДИНСТВЕННАЯ точка открытия видео во всём интерфейсе: карточка на
        главной, hero, featured и компактные карточки плеера, пункт меню.
        Все они ведут сюда, а отсюда — в PLAYER.open.

        Не @ui.in_background: AVPlayerViewController показывается строго
        с главного потока.
        """
        PLAYER.open(item)

    def after_player_closed(self):
        """
        Плеер закрыт: позиция уже сохранена в PlayerManager.close().
        Здесь только обновление интерфейса, на главном потоке.
        """
        try:
            self.home.rebuild()
        except Exception as e:
            log_debug('home rebuild: %r' % (e,))
        try:
            if self._tab == 2:
                self.player.rebuild()
            else:
                self.player._built_w = -1.0
        except Exception as e:
            log_debug('player rebuild: %r' % (e,))

    def _refresh_home_after_watch(self):
        """Совместимость: то же самое обновление главной."""
        self.after_player_closed()

    # -- собственные меню NOX --------------------------------------
    def close_sheet(self):
        sheet = getattr(self, 'sheet', None)
        if sheet is not None:
            sheet.close()
            self.sheet = None

    def open_sheet(self, builder):
        """
        Открывает лист. Кадр выставляется ДО построения содержимого, а
        само содержимое строит `builder(sheet, width)` уже из layout(),
        когда реальная ширина известна.
        """
        old = getattr(self, 'sheet', None)
        if old is not None:
            try:
                deactivate_tree(old)
                if old.superview is not None:
                    old.superview.remove_subview(old)
            except Exception as e:
                log_debug('sheet swap: %r' % (e,))
        sheet = NoxSheet(self, builder, frame=self.bounds)
        sheet.flex = 'WH'
        self.add_subview(sheet)
        sheet.frame = self.bounds          # реальная ширина экрана
        self.sheet = sheet
        sheet.present()
        return sheet

    def item_menu(self, item):
        """⋯ у видео: тёмный стеклянный лист NOX, без системных окон."""
        try:
            self.open_sheet(lambda sh, w: self._build_item_menu(sh, w, item))
        except Exception as e:
            log_debug('item_menu: %r' % (e,))
            nox_error('Меню недоступно')

    def _build_item_menu(self, sheet, w, item):
        y = 12.0
        y = sheet.header(w, y, item.title,
                         item.meta_line or item.fmt_label,
                         item.load_thumb_image())
        pos = watch_position(item)
        if pos > 0:
            y = sheet.row(w, y, 'play', 'Продолжить', self._sheet_open(item),
                          subtitle='с ' + fmt_clock(pos))
        else:
            y = sheet.row(w, y, 'play', 'Открыть', self._sheet_open(item))
        y = sheet.row(w, y, 'link', 'Источник', self._sheet_source(item))
        y = sheet.row(w, y, 'trash', 'Удалить', self._sheet_confirm(item),
                      danger=True)
        return y

    def _sheet_open(self, item):
        def _act(sender):
            self.close_sheet()
            run_on_main(lambda: PLAYER.open(item))
        return _act

    def _sheet_source(self, item):
        def _act(sender):
            self.open_sheet(lambda sh, w: self._build_source(sh, w, item))
        return _act

    def _build_source(self, sheet, w, item):
        src = item.webpage_url or ''
        y = 12.0
        y = sheet.header(w, y, 'Источник', safe_name(item.title, 40))
        y = sheet.text_block(w, y, src or 'Источник неизвестен')
        if src and clipboard is not None:
            y = sheet.row(w, y, 'clip', 'Скопировать ссылку',
                          self._sheet_copy(src))
        y = sheet.buttons(w, y, 'Назад', lambda s: self.item_menu(item),
                          'Закрыть', lambda s: self.close_sheet())
        return y

    def _sheet_copy(self, text):
        def _act(sender):
            try:
                clipboard.set(text)
                nox_ok('Скопировано')
            except Exception:
                nox_error('Не удалось скопировать')
        return _act

    def _sheet_confirm(self, item):
        def _act(sender):
            self.open_sheet(lambda sh, w: self._build_confirm(sh, w, item))
        return _act

    def _build_confirm(self, sheet, w, item):
        y = 12.0
        y = sheet.header(w, y, 'Удалить видео?', safe_name(item.title, 46))
        y = sheet.text_block(
            w, y, 'Файл и его обложка с метаданными будут удалены '
                  'с устройства безвозвратно.')
        y = sheet.buttons(w, y, 'Отмена', lambda s: self.close_sheet(),
                          'Удалить', self._sheet_delete(item),
                          right_danger=True)
        return y

    def _sheet_delete(self, item):
        def _act(sender):
            self.close_sheet()
            run_on_main(lambda: self._delete(item))
        return _act

    def open_filter_sheet(self, on_change=None):
        """Сортировка и фильтр — сетка полноразмерных чипов."""
        try:
            self.open_sheet(lambda sh, w: self._build_filter(sh, w, on_change))
        except Exception as e:
            log_debug('filter sheet: %r' % (e,))
            nox_error('Меню недоступно')

    def _build_filter(self, sheet, w, on_change):
        def apply_and_refresh():
            if callable(on_change):
                on_change()
            # Лист остаётся открытым и того же размера: перестраивается
            # только его содержимое, кадр не трогается.
            sheet.rebuild()

        def pick_sort(key):
            STATE.set('sort', key)
            apply_and_refresh()

        def pick_quality(key):
            STATE.set('quality_filter', key)
            apply_and_refresh()

        def reset(sender):
            STATE.set('sort', 'new')
            STATE.set('quality_filter', 'all')
            apply_and_refresh()

        y = 12.0
        y = sheet.header(w, y, 'Сортировка и фильтр')
        y = sheet.section(w, y, 'СОРТИРОВКА')
        y = sheet.chips(w, y, list(SORT_OPTIONS), STATE.get('sort', 'new'),
                        pick_sort, per_row=2)
        y += 10
        y = sheet.section(w, y, 'КАЧЕСТВО')
        # Три колонки: «1080p+» и «MAX/4K» помещаются целиком.
        per_row = 3 if w >= 330 else 2
        y = sheet.chips(w, y, list(QUALITY_FILTERS),
                        STATE.get('quality_filter', 'all'), pick_quality,
                        per_row=per_row)
        y += 8
        y = sheet.buttons(w, y, 'Сбросить', reset,
                          'Готово', lambda s: self.close_sheet())
        return y

    def _delete(self, item):
        """
        Сама файловая логика удаления не менялась: подтверждение теперь
        приходит из NoxSheet, а не из системного окна.
        """
        if PLAYER.is_playing(item):
            # Открытый прямо сейчас файл сначала корректно закрываем.
            PLAYER.close()
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

        Крутится на главном потоке и никогда его не задерживает: ни
        загрузка, ни разбор ссылки отсюда не выполняются.
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
            keep_screen_awake(DOWNLOADER.awake_needed()
                              or PLAYER.is_active)
            if PLAYER.is_active:
                # Никакого своего таймера у плеера нет: позицию снимает
                # этот же единственный такт.
                step = UI_REFRESH
                PLAYER.tick()
            # Свободный слот занимается на том же такте, кто бы его ни
            # освободил. Своего таймера у очереди нет и не появляется:
            # три загрузки по-прежнему обслуживает один этот цикл.
            DOWNLOADER.pump()
            # Чёрный ящик тоже живёт на этом такте и своего таймера не
            # заводит: heartbeat снимает состояние, flush пишет накопленное.
            # Обе функции сами ограничивают себя одним разом в секунду.
            nox_debug.heartbeat(DOWNLOADER)
            nox_debug.flush()
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
        Единственное место, откуда вызывается yt-dlp, и всегда на ГЛАВНОМ
        потоке. За один заход разбирается одна ссылка.

        Фоновый поток разбора пробовали — на устройстве он завершал
        Pythonista нативно, без исключения Python. Вызов блокирующий, но
        профиль разбора короткий, и продолжение уже начатой загрузки
        сюда вообще не заходит: у него есть сохранённый прямой адрес.
        """
        if self._resolving:
            return
        if DOWNLOADER.signature() != self._last_sig:
            return          # карточку ещё не показали — разбор следующим шагом
        self._resolving = True
        try:
            DOWNLOADER.resolve_next()
        except Exception as e:
            log_debug('resolve: %r' % (e,))
        finally:
            self._resolving = False

    def _drive_extras(self, active_downloads):
        """
        Метаданные и обложка ставятся в очередь только для уже finished
        задания и стартуют, лишь когда поток загрузки мёртв. Всё это —
        решения главного потока, сам ExtrasManager к UI не обращается.

        Ждём именно ЖИВЫЕ HTTP-потоки, а не «активные задания»: иначе
        одно задание, стоящее в очереди за свободным слотом, откладывало
        бы обложку и метаданные бесконечно.
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
            EXTRAS.pump(DOWNLOADER.live_workers() > 0)
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
        # Позиция просмотра сохраняется в любом случае, даже если NOX
        # закрыли прямо во время воспроизведения.
        try:
            PLAYER.close()
        except Exception:
            pass
        # Очередь сохраняем последний раз и возвращаем экрану право гаснуть.
        try:
            DOWNLOADER.persist(STATE)
        except Exception:
            pass
        keep_screen_awake(False)
        # Штатное закрытие: следующий запуск не должен принять его за
        # аварийное завершение.
        nox_debug.heartbeat(DOWNLOADER, force=True)
        nox_debug.mark_clean_exit()
        try:
            ui.cancel_delays()
        except Exception:
            pass

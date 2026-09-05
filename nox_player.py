# coding: utf-8
"""
NOX / плеер.

AVPlayer + AVPlayerViewController через objc_util, точный seek по
CMTime, абсолютная позиция просмотра и аварийный Quick Look.

Код перенесён из монолитного NOX.py дословно.
"""

import os
import time

import ui
import console

from nox_core import (
    WATCH_DONE_TAIL, log_debug, run_on_main, nox_error, STATE,
)


# =====================================================================
#  ПЛЕЕР: AVPlayer + AVPlayerViewController
# =====================================================================
#
#  Quick Look позицию воспроизведения не сообщает и всегда начинает
#  сначала. Прежний код засекал, СКОЛЬКО ВРЕМЕНИ просмотрщик был открыт,
#  и прибавлял это к сохранённой позиции — после любой перемотки или
#  паузы число переставало иметь отношение к видео.
#
#  Здесь позиция берётся ровно там, где она есть на самом деле: у
#  AVPlayer, через currentTime. Quick Look остаётся только аварийным
#  запасным вариантом и НЕ притворяется, что умеет продолжение.
# ---------------------------------------------------------------------

# Как часто позиция уходит в state.json во время просмотра.
WATCH_PERSIST_EVERY = 3.0

_AV = {'checked': False, 'ok': False, 'why': '', 'mod': None,
       'player': None, 'item': None, 'vc': None, 'url': None,
       'cm': None, 'seek': None, 'now': None, 'CMTime': None}


def _av():
    """
    Однократная проверка AVFoundation/AVKit и CoreMedia.

    CMTime — структура из 24 байт, и objc_util не умеет её сам, поэтому
    вызовы currentTime/seekToTime: делаются через собственные указатели
    objc_msgSend с явными ctypes-типами. Всё в try/except: не сложилось —
    работает Quick Look.
    """
    if _AV['checked']:
        return _AV['ok']
    _AV['checked'] = True
    try:
        import ctypes
        import objc_util
    except Exception as e:
        _AV['why'] = 'objc_util недоступен: %r' % (e,)
        return False
    for fw in ('AVFoundation', 'AVKit'):
        try:
            objc_util.load_framework(fw)
        except Exception as e:
            _AV['why'] = 'не загрузился %s: %r' % (fw, e)
            return False
    try:
        cls = objc_util.ObjCClass
        _AV['player'] = cls('AVPlayer')
        _AV['item'] = cls('AVPlayerItem')
        _AV['vc'] = cls('AVPlayerViewController')
        _AV['url'] = cls('NSURL')
    except Exception as e:
        _AV['why'] = 'AVKit недоступен: %r' % (e,)
        return False
    try:
        class CMTime(ctypes.Structure):
            _fields_ = [('value', ctypes.c_int64),
                        ('timescale', ctypes.c_int32),
                        ('flags', ctypes.c_uint32),
                        ('epoch', ctypes.c_int64)]
        cm = ctypes.CDLL('/System/Library/Frameworks/'
                         'CoreMedia.framework/CoreMedia')
        cm.CMTimeGetSeconds.argtypes = [CMTime]
        cm.CMTimeGetSeconds.restype = ctypes.c_double
        # Отдельные указатели на objc_msgSend: у каждого свои argtypes,
        # так что настройки не пересекаются с самим objc_util.
        libobjc = ctypes.CDLL('/usr/lib/libobjc.dylib')
        now = libobjc['objc_msgSend']
        now.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
        now.restype = CMTime
        seek = libobjc['objc_msgSend']
        seek.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                         CMTime, CMTime, CMTime]
        seek.restype = None
        _AV['CMTime'] = CMTime
        _AV['cm'] = cm
        _AV['now'] = now
        _AV['seek'] = seek
        _AV['mod'] = objc_util
    except Exception as e:
        _AV['why'] = 'CoreMedia недоступна: %r' % (e,)
        return False
    _AV['ok'] = True
    return True


def av_available():
    return _av()


def av_reason():
    return _AV['why']


def _cmtime(seconds):
    """CMTime с таймшкалой 600 — стандартной для видео."""
    CMTime = _AV['CMTime']
    return CMTime(int(round(float(seconds) * 600.0)), 600, 1, 0)


def _cmzero():
    CMTime = _AV['CMTime']
    return CMTime(0, 600, 1, 0)


class PlayerSession(object):
    """Одна открытая сессия просмотра. Больше одной не бывает."""

    def __init__(self, item, saved_position):
        self.item = item
        self.watch_id = item.watch_id
        self.saved_position = float(saved_position or 0.0)
        self.current_position = float(saved_position or 0.0)
        self.duration = float(item.duration or 0.0)
        self.player = None
        self.vc = None
        self.seeked = False
        self.started_at = time.monotonic()
        self.last_persist = 0.0
        self.is_active = True


class PlayerManager(object):
    """
    Единственная точка открытия видео во всём NOX.

    Своего таймера не заводит: позицию опрашивает общий такт
    NoxApp._tick, пока сессия жива.
    """

    def __init__(self):
        self.session = None
        self.app = None
        self.fallback_used = False

    # -- состояние -------------------------------------------------
    @property
    def is_active(self):
        s = self.session
        return bool(s is not None and s.is_active)

    def is_playing(self, item):
        s = self.session
        return bool(s is not None and s.is_active
                    and getattr(s.item, 'path', None) == getattr(item, 'path', 1))

    def saved_position(self, item):
        """Сохранённая позиция этого КОНКРЕТНОГО видео, а не общая."""
        try:
            entry = STATE.watch_get(item.watch_id)
        except Exception:
            entry = None
        if not isinstance(entry, dict):
            return 0.0
        try:
            pos = float(entry.get('position') or 0.0)
        except Exception:
            return 0.0
        total = 0.0
        try:
            total = float(entry.get('duration') or item.duration or 0.0)
        except Exception:
            total = 0.0
        if total > 0 and pos >= total - WATCH_DONE_TAIL:
            return 0.0            # досмотрено — начинаем сначала
        return max(0.0, pos)

    # -- открытие --------------------------------------------------
    def open(self, item):
        """
        ЕДИНСТВЕННЫЙ путь открытия видео. Только главный поток.
        """
        if item is None:
            return False
        path = getattr(item, 'path', '')
        if not path or not os.path.exists(path):
            nox_error('Файл больше не существует')
            if self.app is not None:
                run_on_main(self.app.reload_all)
            return False
        if self.is_active:
            self.close()
        try:
            STATE.remember_opened(path)
        except Exception as e:
            log_debug('remember_opened: %r' % (e,))
        position = self.saved_position(item)
        if av_available() and self._open_native(item, position):
            return True
        return self._open_fallback(item)

    def _open_native(self, item, position):
        m = _AV['mod']
        try:
            url = _AV['url'].fileURLWithPath_(m.ns(item.path))
            player_item = _AV['item'].playerItemWithURL_(url)
            player = _AV['player'].playerWithPlayerItem_(player_item)
            vc = _AV['vc'].alloc().init()
            vc.setPlayer_(player)
            try:
                vc.setModalPresentationStyle_(0)   # fullScreen
            except Exception:
                pass
            root = self._top_view_controller(m)
            if root is None:
                return False
            session = PlayerSession(item, position)
            session.player = player
            session.vc = vc
            self.session = session
            root.presentViewController_animated_completion_(vc, True, None)
            # Точный seek ДО начала воспроизведения: сначала позиция,
            # только потом play.
            if position > 0.5:
                self._seek(player, position)
            session.seeked = True
            try:
                player.play()
            except Exception:
                pass
            self.fallback_used = False
            return True
        except Exception as e:
            log_debug('AVPlayer: %r' % (e,))
            self.session = None
            return False

    @staticmethod
    def _top_view_controller(m):
        """Самый верхний показанный контроллер — над ним и презентуем."""
        try:
            app = m.ObjCClass('UIApplication').sharedApplication()
            win = app.keyWindow()
            if win is None:
                windows = app.windows()
                win = windows.firstObject() if windows else None
            if win is None:
                return None
            vc = win.rootViewController()
            while vc is not None and vc.presentedViewController() is not None:
                vc = vc.presentedViewController()
            return vc
        except Exception:
            return None

    def _seek(self, player, seconds):
        """Точный seek: обе допуски нулевые, иначе попадём в ключевой кадр."""
        try:
            _AV['seek'](player.ptr, _AV['mod'].sel(
                'seekToTime:toleranceBefore:toleranceAfter:'),
                _cmtime(seconds), _cmzero(), _cmzero())
            return True
        except Exception as e:
            log_debug('seek: %r' % (e,))
            return False

    def _current_time(self, player):
        """Настоящий currentTime AVPlayer в секундах. Иначе None."""
        try:
            t = _AV['now'](player.ptr, _AV['mod'].sel('currentTime'))
            secs = _AV['cm'].CMTimeGetSeconds(t)
            if secs != secs or secs < 0:      # NaN до готовности item
                return None
            return float(secs)
        except Exception:
            return None

    def _open_fallback(self, item):
        """
        Аварийный путь: только если AVKit создать не удалось.

        Продолжения он НЕ умеет и не делает вид, что умеет — никакого
        «прошедшее время = позиция» здесь больше нет.

        console.quicklook блокирует поток до закрытия просмотрщика,
        поэтому он уходит с главного потока разово. Наши ui.View он при
        этом не трогает: перестроение возвращается через run_on_main.
        """
        self.fallback_used = True
        app = self.app
        path = item.path

        def _show():
            try:
                console.quicklook(path)
            except Exception as e:
                log_debug('quicklook: %r' % (e,))
            if app is not None:
                run_on_main(app.after_player_closed)
        try:
            ui.in_background(_show)()
        except Exception:
            _show()
        return True

    # -- такт ------------------------------------------------------
    def tick(self):
        """
        Вызывается из общего NoxApp._tick. Ничего не пересобирает и не
        пишет state чаще, чем раз в WATCH_PERSIST_EVERY секунд.
        """
        s = self.session
        if s is None or not s.is_active:
            return
        pos = self._current_time(s.player)
        if pos is not None and pos > 0:
            s.current_position = pos
        if self._dismissed(s):
            self.close()
            return
        now = time.monotonic()
        if now - s.last_persist >= WATCH_PERSIST_EVERY:
            s.last_persist = now
            self._persist(s)

    @staticmethod
    def _dismissed(session):
        """Контроллер закрыт, если он больше никем не показан."""
        vc = session.vc
        if vc is None:
            return True
        try:
            if vc.presentingViewController() is None:
                return True
            if vc.view().window() is None:
                return True
        except Exception:
            return False
        return False

    def _persist(self, session):
        """Сохраняем АБСОЛЮТНУЮ позицию, а не прошедшее время."""
        try:
            STATE.watch_set(session.watch_id, session.current_position,
                            session.duration or None)
        except Exception as e:
            log_debug('watch_set: %r' % (e,))

    # -- закрытие --------------------------------------------------
    def close(self):
        s = self.session
        if s is None:
            return
        s.is_active = False
        # Позицию берём ещё раз, уже после закрытия: пользователь мог
        # перемотать в последнюю секунду.
        pos = self._current_time(s.player) if s.player is not None else None
        if pos is not None and pos > 0:
            s.current_position = pos
        self._persist(s)
        try:
            if s.player is not None:
                s.player.pause()
        except Exception:
            pass
        try:
            if s.vc is not None:
                s.vc.setPlayer_(None)
                if s.vc.presentingViewController() is not None:
                    s.vc.dismissViewControllerAnimated_completion_(True, None)
        except Exception:
            pass
        try:
            # Контроллер создан через alloc().init(), то есть принадлежит нам.
            # UIKit держит собственную ссылку на время анимации закрытия,
            # поэтому отпускаем свою здесь — и утечки не остаётся.
            if s.vc is not None:
                s.vc.release()
        except Exception:
            pass
        s.player = None
        s.vc = None
        self.session = None
        if self.app is not None:
            run_on_main(self.app.after_player_closed)


PLAYER = PlayerManager()

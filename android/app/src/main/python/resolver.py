# coding: utf-8
"""
Разбор ссылки для NOX Android.

Здесь живёт ровно то, ради чего в приложении есть Python: yt-dlp. Передача
файла, база, уведомления и интерфейс — Kotlin. Python ничего не скачивает:
только extract_info(download=False).

С версии 0.3.0 два шага вместо одного:

  analyze(url) — один extract_info: сведения о видео и полный список его
                 дорожек (nox_catalog). Ответ кэшируется в памяти на время
                 ANALYZE_TTL, чтобы «Скачать» не разбирал страницу заново;
  plan(url, video, audio) — прямые адреса ровно выбранных format ID из
                 кэша (или из свежего разбора, если кэш устарел). Другой
                 формат вместо выбранного не подставляется никогда.

Старый resolve() с лестницей 360/480/720/MAX оставлен для заданий,
созданных версиями 0.2.x и ещё не получивших формат:

    360  -> url360, url240, url144
    480  -> url480, url360, url240, url144
    720  -> url720, url480, url360, url240, url144
    MAX  -> url2160 ... url144
"""

import json
import os
import re
import threading
import time
from collections import OrderedDict

import nox_catalog

RESOLVE_SOCKET_TIMEOUT = 15

HEIGHTS = {'360': 360, '480': 480, '720': 720}

VK_DIRECT = {
    '360': ['url360', 'url240', 'url144'],
    '480': ['url480', 'url360', 'url240', 'url144'],
    '720': ['url720', 'url480', 'url360', 'url240', 'url144'],
    'MAX': ['url2160', 'url1440', 'url1080', 'url720',
            'url480', 'url360', 'url240', 'url144'],
}

SEGMENTED_HINTS = ('m3u8', 'dash', 'ism', 'f4m')

# Только эти заголовки уходят в OkHttp. Тот же белый список, что и в
# доказанном на устройстве фоновом транспорте iPhone.
HEADER_KEYS = ('User-Agent', 'Referer', 'Origin', 'Cookie',
               'Accept', 'Accept-Language')

VK_DIRECT_RE = re.compile(r'url(?:144|240|360|480|720|1080|1440|2160)')


# ---------------------------------------------------------------------
#  Выбор формата (чистые функции, без yt-dlp — их проверяют тесты)
# ---------------------------------------------------------------------

def entry_of(info):
    """Из результата extract_info достаём словарь самого видео."""
    if not isinstance(info, dict):
        return {}
    entries = info.get('entries')
    if isinstance(entries, list) and entries and isinstance(entries[0], dict):
        return entries[0]
    return info


def is_vk_direct(fmt):
    """
    Прямой VK-формат urlXXX. Кодеки у него в info часто не указаны, и это
    не означает «без звука»: файл целый. Проверка кодеков не применяется.
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
    return not any(hint in proto for hint in SEGMENTED_HINTS)


def is_combined(fmt):
    """Готовый файл со звуком: и видео, и аудио, и обычный HTTP."""
    if not isinstance(fmt, dict):
        return False
    url = fmt.get('url')
    if not isinstance(url, str) or not url.startswith('http'):
        return False
    vcodec = (fmt.get('vcodec') or 'none').lower()
    acodec = (fmt.get('acodec') or 'none').lower()
    if vcodec == 'none' or acodec == 'none':
        return False
    blob = (str(fmt.get('protocol') or '') + ' ' +
            str(fmt.get('format_id') or '')).lower()
    return not any(hint in blob for hint in SEGMENTED_HINTS)


def fmt_height(fmt):
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
    Сначала VK urlXXX по лестнице, затем лучший combined в пределах
    высоты. Возвращает словарь формата или None.
    """
    quality = str(quality or '480').upper()
    if quality not in VK_DIRECT:
        quality = '480'
    entry = entry_of(info)
    formats = entry.get('formats')
    if not isinstance(formats, list):
        formats = []

    vk_by_id = {}
    for f in formats:
        if is_vk_direct(f):
            vk_by_id.setdefault(str(f.get('format_id')), f)
    for fid in VK_DIRECT[quality]:
        if fid in vk_by_id:
            return vk_by_id[fid]

    combined = [f for f in formats if is_combined(f)]
    if not combined and is_combined(entry):
        combined = [entry]
    if not combined:
        return None
    cap = HEIGHTS.get(quality)
    pool = combined
    if cap:
        limited = [f for f in combined if 0 < fmt_height(f) <= cap]
        pool = limited or [f for f in combined if fmt_height(f) == 0] or combined

    def rank(f):
        tbr = f.get('tbr')
        return (fmt_height(f),
                tbr if isinstance(tbr, (int, float)) else 0,
                f.get('filesize') or f.get('filesize_approx') or 0)
    return sorted(pool, key=rank)[-1]


def safe_headers(raw):
    """Только белый список, без учёта регистра, канонические имена."""
    out = {}
    if not isinstance(raw, dict):
        return out
    for key in HEADER_KEYS:
        for got, value in raw.items():
            if str(got).lower() == key.lower() and value:
                out[key] = str(value)
                break
    return out


def describe(info, fmt, quality):
    """Маленький словарь обычных значений — то, что уходит в Kotlin."""
    entry = entry_of(info)
    size = fmt.get('filesize') or fmt.get('filesize_approx')
    duration = entry.get('duration')
    return {
        'ok': True,
        'title': str(entry.get('title') or 'Видео'),
        'video_id': str(entry.get('id') or ''),
        'format_id': str(fmt.get('format_id') or ''),
        'direct_url': str(fmt.get('url') or ''),
        'ext': str(fmt.get('ext') or 'mp4'),
        'height': int(fmt_height(fmt) or 0),
        'quality': str(quality or '480').upper(),
        'filesize': int(size) if isinstance(size, (int, float)) and size > 0 else 0,
        'duration': int(duration) if isinstance(duration, (int, float)) and duration > 0 else 0,
        'thumbnail': str(entry.get('thumbnail') or ''),
        'headers': safe_headers(fmt.get('http_headers')),
        'extractor': str(entry.get('extractor') or ''),
        'uploader': str(entry.get('uploader') or entry.get('channel') or entry.get('uploader_id') or ''),
        'mode': 'progressive',
    }


# ---------------------------------------------------------------------
#  Раздельные дорожки (v0.2.0, только по настройке пользователя)
# ---------------------------------------------------------------------
# Склейка на телефоне идёт через MediaMuxer без перекодирования, а он
# принимает в MP4 только H.264/H.265 и AAC. Поэтому выбираются лишь такие
# пары; всё остальное (VP9, Opus, сегментные потоки) не берётся вовсе.

MUX_VIDEO_CODECS = ('avc1', 'avc3', 'h264', 'hev1', 'hvc1', 'h265')
MUX_AUDIO_CODECS = ('mp4a', 'aac')


def _plain_http(fmt):
    url = fmt.get('url')
    if not isinstance(url, str) or not url.startswith(('http://', 'https://')):
        return False
    blob = (str(fmt.get('protocol') or '') + ' ' + str(fmt.get('format_id') or '')).lower()
    return not any(h in blob for h in SEGMENTED_HINTS)


def is_muxable_video(fmt):
    if not isinstance(fmt, dict) or not _plain_http(fmt):
        return False
    v = str(fmt.get('vcodec') or 'none').lower()
    a = str(fmt.get('acodec') or 'none').lower()
    ext = str(fmt.get('ext') or '').lower()
    return a == 'none' and v.startswith(MUX_VIDEO_CODECS) and ext in ('mp4', 'm4v')


def is_muxable_audio(fmt):
    if not isinstance(fmt, dict) or not _plain_http(fmt):
        return False
    v = str(fmt.get('vcodec') or 'none').lower()
    a = str(fmt.get('acodec') or 'none').lower()
    ext = str(fmt.get('ext') or '').lower()
    return v == 'none' and a.startswith(MUX_AUDIO_CODECS) and ext in ('m4a', 'mp4')


def _num(v):
    return v if isinstance(v, (int, float)) else 0


def pick_split_formats(info, quality):
    """Лучшая пара видео+звук в пределах высоты или None."""
    quality = str(quality or '480').upper()
    entry = entry_of(info)
    formats = entry.get('formats')
    if not isinstance(formats, list):
        return None
    cap = HEIGHTS.get(quality)
    videos = [f for f in formats if is_muxable_video(f) and fmt_height(f) > 0
              and (cap is None or fmt_height(f) <= cap)]
    audios = [f for f in formats if is_muxable_audio(f)]
    if not videos or not audios:
        return None
    v = max(videos, key=lambda f: (fmt_height(f), _num(f.get('tbr')), _num(f.get('filesize') or f.get('filesize_approx'))))
    a = max(audios, key=lambda f: (_num(f.get('abr')) or _num(f.get('tbr')), _num(f.get('filesize') or f.get('filesize_approx'))))
    return v, a


def _by_id(info, fid):
    if not fid:
        return None
    for f in entry_of(info).get('formats') or []:
        if isinstance(f, dict) and str(f.get('format_id')) == str(fid):
            return f
    return None


def choose(info, quality, allow_split=False, prefer_format='', prefer_audio=''):
    """
    Итоговый выбор: ('progressive', fmt, None) | ('split', video, audio) | None.

    prefer_* — форматы, из которых уже скачана часть файла. Если они есть в
    свежем ответе, выбираются именно они: иначе .part продолжился бы байтами
    другого файла. Если их больше нет — обычный выбор, а вызывающий сам
    сбросит несовпавшую часть.
    """
    if prefer_format and prefer_audio:
        v, a = _by_id(info, prefer_format), _by_id(info, prefer_audio)
        if v is not None and a is not None:
            return 'split', v, a
    elif prefer_format:
        f = _by_id(info, prefer_format)
        if f is not None and (is_vk_direct(f) or is_combined(f)):
            return 'progressive', f, None
    progressive = pick_direct_format(info, quality)
    if allow_split:
        pair = pick_split_formats(info, quality)
        if pair is not None and (progressive is None or fmt_height(pair[0]) > fmt_height(progressive)):
            return 'split', pair[0], pair[1]
    if progressive is None:
        return None
    return 'progressive', progressive, None


def describe_split(info, video, audio, quality):
    d = describe(info, video, quality)
    asize = audio.get('filesize') or audio.get('filesize_approx')
    d.update({
        'mode': 'split',
        'audio_url': str(audio.get('url') or ''),
        'audio_format_id': str(audio.get('format_id') or ''),
        'audio_headers': safe_headers(audio.get('http_headers')),
        'audio_filesize': int(asize) if isinstance(asize, (int, float)) and asize > 0 else 0,
        'ext': 'mp4',
    })
    return d


# ---------------------------------------------------------------------
#  yt-dlp
# ---------------------------------------------------------------------

def _prepare_ssl():
    """Сертификаты для HTTPS внутри Python на Android — из certifi."""
    try:
        import certifi
        os.environ.setdefault('SSL_CERT_FILE', certifi.where())
        os.environ.setdefault('REQUESTS_CA_BUNDLE', certifi.where())
    except Exception:
        pass


_js_ready = None


def _prepare_js():
    """Регистрирует встроенный JS-движок (QuickJS-NG) для задач YouTube."""
    global _js_ready
    if _js_ready is None:
        try:
            import nox_jsc
            nox_jsc.register()
            _js_ready = True
        except Exception as e:  # без движка остальные сайты работают как раньше
            _js_ready = False
            _JS_STATE['register_error'] = nox_catalog.scrub(repr(e), 200)
    return _js_ready


_JS_STATE = {'register_error': ''}


def ydl_opts():
    """Минимальный набор: разбор ничего не качает и не пишет."""
    opts = {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'socket_timeout': RESOLVE_SOCKET_TIMEOUT,
        'retries': 1,
        'fragment_retries': 0,
        'fixup': 'never',
        'skip_download': True,
        'cachedir': False,
        # Только встроенный движок: внешние программы (deno, node, qjs)
        # NOX не ищет и не запускает, скрипты EJS из сети не скачивает.
        'js_runtimes': {},
        'remote_components': set(),
    }
    if _prepare_js():
        import nox_jsc
        opts['js_runtimes'] = {nox_jsc.RUNTIME_KEY: {}}
    return opts


def ytdlp_version():
    try:
        from yt_dlp.version import __version__
        return str(__version__)
    except Exception as e:
        return 'unavailable: %r' % (e,)


def resolve(url, quality='480', allow_split=False, prefer_format='', prefer_audio=''):
    """
    Точка входа из Kotlin. Всегда возвращает строку JSON и никогда не
    бросает исключение наружу: ошибка — это {'ok': False, 'error': ...}.

    allow_split=False — ровно поведение v0.1.0: только прямой файл со звуком.
    """
    try:
        _prepare_ssl()
        import yt_dlp
        with yt_dlp.YoutubeDL(ydl_opts()) as ydl:
            info = ydl.extract_info(str(url), download=False)
        picked = choose(info, quality, bool(allow_split), str(prefer_format or ''), str(prefer_audio or ''))
        if picked is None:
            return json.dumps({
                'ok': False,
                'error': 'Нет прямого формата со звуком (нужен был бы ffmpeg)',
                'kind': 'no-direct-format',
            }, ensure_ascii=False)
        mode, fmt, audio = picked
        if mode == 'split':
            return json.dumps(describe_split(info, fmt, audio, quality), ensure_ascii=False)
        return json.dumps(describe(info, fmt, quality), ensure_ascii=False)
    except Exception as e:
        text = str(e) or e.__class__.__name__
        # Убираем цветовые коды yt-dlp и служебный префикс.
        text = re.sub(r'\x1b\[[0-9;]*m', '', text)
        text = text.replace('ERROR: ', '')
        return json.dumps({'ok': False, 'error': text[:400],
                           'kind': e.__class__.__name__},
                          ensure_ascii=False)


# ---------------------------------------------------------------------
#  0.3.0: анализ -> каталог, план -> адреса выбранных форматов
# ---------------------------------------------------------------------
# Кэш ответов extract_info: ограничен и по числу, и по времени. Прямые
# адреса YouTube живут около шести часов, но держать их дольше нужного
# незачем: через ANALYZE_TTL plan() разберёт страницу заново.

ANALYZE_TTL = 20 * 60
CACHE_MAX = 6
WARNINGS_MAX = 12


class _InfoCache(object):
    def __init__(self, max_items=CACHE_MAX, ttl=ANALYZE_TTL, clock=time.time):
        self._items = OrderedDict()
        self._lock = threading.Lock()
        self.max_items = max_items
        self.ttl = ttl
        self.clock = clock

    @staticmethod
    def key(url):
        return str(url or '').strip()

    def get(self, url, max_age=None):
        limit = self.ttl if max_age is None else min(self.ttl, max_age)
        with self._lock:
            item = self._items.get(self.key(url))
            if item is None:
                return None
            info, at = item
            if self.clock() - at > limit:
                return None
            return info, at

    def put(self, url, info):
        with self._lock:
            k = self.key(url)
            self._items.pop(k, None)
            self._items[k] = (info, self.clock())
            while len(self._items) > self.max_items:
                self._items.popitem(last=False)

    def drop(self, url):
        with self._lock:
            self._items.pop(self.key(url), None)

    def clear(self):
        with self._lock:
            self._items.clear()

    def __len__(self):
        with self._lock:
            return len(self._items)


_cache = _InfoCache()


class _Collector(object):
    """Логгер yt-dlp: копит предупреждения (без адресов), ничего не печатает."""

    def __init__(self):
        self.warnings = []

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        if len(self.warnings) < WARNINGS_MAX:
            self.warnings.append(nox_catalog.scrub(msg, 240))

    def error(self, msg):
        self.warning(msg)


def _js_info(stats):
    out = {'runs': 0, 'failed': 0, 'ms': 0, 'engine': '', 'error': ''}
    try:
        import nox_jsc
        out['engine'] = nox_jsc.engine_version() if _js_ready else ''
        out['error'] = nox_catalog.scrub(nox_jsc.last_error(), 200) if stats.get('failed') else ''
    except Exception:
        pass
    out['register_error'] = _JS_STATE.get('register_error', '')
    for k in ('runs', 'failed', 'ms'):
        out[k] = int(stats.get(k, 0))
    return out


def _extract(url):
    """Один extract_info. Возвращает (info, warnings, js_stats)."""
    _prepare_ssl()
    import yt_dlp
    collector = _Collector()
    opts = ydl_opts()
    stats = {'runs': 0, 'failed': 0, 'ms': 0}
    opts['logger'] = collector
    opts['nox_js_stats'] = stats
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(str(url), download=False)
    return info, collector.warnings, stats


def _versions():
    ejs = ''
    try:
        import yt_dlp_ejs
        ejs = str(getattr(yt_dlp_ejs, 'version', ''))
    except Exception:
        pass
    return {'yt_dlp': ytdlp_version(), 'ejs': ejs}


def _error_json(exc_or_text, stage, warnings=None, js=None, kind=None, message=None):
    detail = nox_catalog.scrub(exc_or_text, 400)
    k, human = nox_catalog.classify_error(detail)
    if (k == 'extract-failed') and js and js.get('failed'):
        k, human = 'js-runtime', 'Не удалось выполнить проверку YouTube во встроенном JS-движке.'
    return json.dumps({
        'ok': False,
        'kind': kind or k,
        'error': message or human,
        'detail': detail,
        'stage': stage,
        'warnings': warnings or [],
        'js': js or {},
        'versions': _versions(),
    }, ensure_ascii=False)


def _catalog_json(info, warnings, js, analyzed_at, cached):
    details = nox_catalog.details_of(info)
    tracks = nox_catalog.tracks_of(info)
    return {
        'ok': True,
        'details': details,
        'tracks': tracks,
        'analyzed_at': int(analyzed_at),
        'cached': bool(cached),
        'warnings': warnings,
        'js': js,
        'versions': _versions(),
    }


def analyze(url, fresh=False):
    """
    Точка входа «Найти видео». Никогда не бросает исключение: всегда JSON.
    Повторный вызов для той же ссылки в пределах ANALYZE_TTL не ходит в сеть.
    """
    stage = 'page'
    try:
        if not fresh:
            hit = _cache.get(url)
            if hit is not None:
                info, at = hit
                return json.dumps(_catalog_json(info, [], {}, at, True), ensure_ascii=False)
        info, warnings, stats = _extract(url)
        js = _js_info(stats)
        stage = 'catalog'
        details = nox_catalog.details_of(info)
        if details['is_live'] or details['live_status'] in ('is_live', 'is_upcoming'):
            return _error_json('live', stage, warnings, js, 'live',
                               'Это прямая трансляция или премьера, которая ещё не закончилась. Скачать можно после её завершения.')
        tracks = nox_catalog.tracks_of(info)
        if not tracks:
            text = ' '.join(warnings) or 'no formats'
            if stats.get('failed'):
                return _error_json(text, 'js', warnings, js, 'js-runtime',
                                   'Не удалось выполнить проверку YouTube во встроенном JS-движке.')
            return _error_json(text, stage, warnings, js, 'no-formats', 'Источник не отдал ни одного формата видео.')
        now = time.time()
        _cache.put(url, info)
        return json.dumps(_catalog_json(info, warnings, js, now, False), ensure_ascii=False)
    except Exception as e:
        return _error_json(str(e) or e.__class__.__name__, stage)


def _component(fmt):
    t = nox_catalog.normalize_track(fmt)
    size = t['filesize'] if t else 0
    return {
        'format_id': str(fmt.get('format_id') or ''),
        'url': str(fmt.get('url') or ''),
        'headers': safe_headers(fmt.get('http_headers')),
        'ext': str(fmt.get('ext') or ''),
        'container': t['container'] if t else '',
        'vcodec': t['vcodec'] if t else '',
        'acodec': t['acodec'] if t else '',
        'width': t['width'] if t else 0,
        'height': t['height'] if t else 0,
        'fps': t['fps'] if t else 0,
        'filesize': size,
        'filesize_exact': bool(size),
        'filesize_approx': t['filesize_approx'] if t else 0,
        'chunk_size': t['chunk_size'] if t else 0,
        'transport': t['transport'] if t else 'other',
    }


def plan(url, video_format, audio_format='', max_age=None):
    """
    Прямые адреса ровно для video_format (+ audio_format). Если кэш старше
    max_age секунд (или ANALYZE_TTL) — один свежий extract_info. Если
    выбранного формата больше нет, ответ kind='format-gone' с новым каталогом:
    подменять формат молча нельзя, выбирает пользователь.
    """
    stage = 'plan'
    try:
        video_format = str(video_format or '')
        audio_format = str(audio_format or '')
        hit = _cache.get(url, max_age)
        warnings, js = [], {}
        if hit is None:
            stage = 'page'
            info, warnings, stats = _extract(url)
            js = _js_info(stats)
            _cache.put(url, info)
            at = time.time()
        else:
            info, at = hit
        stage = 'plan'
        v = nox_catalog.find_format(info, video_format)
        a = nox_catalog.find_format(info, audio_format) if audio_format else None
        missing = [f for f, got in ((video_format, v), (audio_format, a)) if f and got is None]
        if missing:
            payload = _catalog_json(info, warnings, js, at, hit is not None)
            payload.update({
                'ok': False,
                'kind': 'format-gone',
                'error': 'Выбранный вариант больше не предлагается источником.',
                'missing': missing,
                'stage': stage,
            })
            return json.dumps(payload, ensure_ascii=False)
        comps = [_component(v)] + ([_component(a)] if a is not None else [])
        bad = [c['format_id'] for c in comps if c['transport'] != 'http' or not c['url']]
        if bad:
            return _error_json('transport ' + ','.join(bad), stage, warnings, js, 'unsupported-transport',
                               'Этот вариант передаётся потоком по частям (HLS/DASH) — NOX пока скачивает только цельные файлы.')
        return json.dumps({
            'ok': True,
            'video': comps[0],
            'audio': comps[1] if len(comps) > 1 else None,
            'details': nox_catalog.details_of(info),
            'resolved_at': int(at),
            'cached': hit is not None,
            'warnings': warnings,
            'js': js,
            'versions': _versions(),
        }, ensure_ascii=False)
    except Exception as e:
        return _error_json(str(e) or e.__class__.__name__, stage)


def forget(url):
    """Убрать ссылку из кэша (например, после 403 по адресу из кэша)."""
    _cache.drop(url)
    return True


def js_status():
    """Для диагностики: готов ли встроенный JS-движок и его версия."""
    ready = _prepare_js()
    out = {'ready': bool(ready), 'versions': _versions(), 'register_error': _JS_STATE.get('register_error', '')}
    if ready:
        try:
            import nox_jsc
            out['engine'] = nox_jsc.engine_version()
            out['last_error'] = nox_catalog.scrub(nox_jsc.last_error(), 200)
        except Exception as e:
            out['engine_error'] = nox_catalog.scrub(repr(e), 200)
    return json.dumps(out, ensure_ascii=False)

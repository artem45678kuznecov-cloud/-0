# coding: utf-8
"""
Разбор ссылки для NOX Android.

Здесь живёт ровно то, ради чего в приложении есть Python: yt-dlp и выбор
формата по лестнице качества NOX. Передача файла, база, уведомления и
интерфейс — Kotlin. Python получает ссылку и качество, возвращает JSON
с прямым адресом и заголовками. Больше он ничего не делает: ни сети
сверх extract_info, ни файлов, ни потоков.

Лестница качества перенесена из nox_download.py без изменений:

    360  -> url360, url240, url144
    480  -> url480, url360, url240, url144
    720  -> url720, url480, url360, url240, url144
    MAX  -> url2160 ... url144

Прямые форматы VK urlXXX уже содержат и видео, и звук. Если их нет —
лучший combined-формат в пределах нужной высоты. Video-only и audio-only
не выбираются никогда: склеивать их нечем, ffmpeg на Stage 01 нет.
"""

import json
import os
import re

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
    }


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


def ydl_opts():
    """Минимальный набор: разбор ничего не качает и не пишет."""
    return {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'socket_timeout': RESOLVE_SOCKET_TIMEOUT,
        'retries': 1,
        'fragment_retries': 0,
        'fixup': 'never',
        'skip_download': True,
        'cachedir': False,
    }


def ytdlp_version():
    try:
        from yt_dlp.version import __version__
        return str(__version__)
    except Exception as e:
        return 'unavailable: %r' % (e,)


def resolve(url, quality='480'):
    """
    Точка входа из Kotlin. Всегда возвращает строку JSON и никогда не
    бросает исключение наружу: ошибка — это {'ok': False, 'error': ...}.
    """
    try:
        _prepare_ssl()
        import yt_dlp
        with yt_dlp.YoutubeDL(ydl_opts()) as ydl:
            info = ydl.extract_info(str(url), download=False)
        fmt = pick_direct_format(info, quality)
        if fmt is None:
            return json.dumps({
                'ok': False,
                'error': 'Нет прямого формата со звуком (нужен был бы ffmpeg)',
                'kind': 'no-direct-format',
            }, ensure_ascii=False)
        return json.dumps(describe(info, fmt, quality), ensure_ascii=False)
    except Exception as e:
        text = str(e) or e.__class__.__name__
        # Убираем цветовые коды yt-dlp и служебный префикс.
        text = re.sub(r'\x1b\[[0-9;]*m', '', text)
        text = text.replace('ERROR: ', '')
        return json.dumps({'ok': False, 'error': text[:400],
                           'kind': e.__class__.__name__},
                          ensure_ascii=False)

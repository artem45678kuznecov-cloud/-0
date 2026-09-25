# coding: utf-8
"""
Каталог форматов NOX: из ответа yt-dlp — чистый список дорожек конкретного видео.

Здесь только чистые функции без сети (их проверяют тесты на готовых
ответах yt-dlp). Что именно показать пользователю и как собрать пары
«видео + звук», решает Kotlin (FormatCatalog): он знает версию Android и
декодеры телефона. Python честно описывает, что отдаёт источник.

Правила, ради которых модуль существует:

  * разрешение — только из width/height формата. Цифры в format_id не
    разрешение (у YouTube itag 308 — это 1440p, а itag 137 — 1080p).
    Единственное исключение — прямые VK-форматы urlXXX: там число в имени
    и есть высота кадра, это отдельное правило;
  * транспорт — по protocol и признакам сегментов, а не по слову «dash» в
    имени: у YouTube «dash»-форматы — обычные файлы по HTTPS с Range;
  * размер: filesize — точный, filesize_approx — оценка, и это разные вещи;
  * прямые адреса и заголовки в каталог не попадают: они остаются в памяти
    resolver.py и отдаются только в plan() для скачивания.
"""

import re

VK_DIRECT_RE = re.compile(r'url(144|240|360|480|720|1080|1440|2160)')

VIDEO_CODECS = (
    ('avc1', 'h264'), ('avc3', 'h264'), ('h264', 'h264'),
    ('hev1', 'h265'), ('hvc1', 'h265'), ('h265', 'h265'), ('hevc', 'h265'),
    ('vp09', 'vp9'), ('vp9', 'vp9'), ('vp8', 'vp8'),
    ('av01', 'av1'), ('av1', 'av1'),
    ('dvh1', 'dv'), ('dvhe', 'dv'), ('dav1', 'dv'),
)
AUDIO_CODECS = (
    ('mp4a', 'aac'), ('aac', 'aac'), ('opus', 'opus'), ('vorbis', 'vorbis'),
    ('mp3', 'mp3'), ('ac-3', 'ac3'), ('ac3', 'ac3'), ('ec-3', 'eac3'), ('eac3', 'eac3'),
    ('flac', 'flac'),
)

CONTAINERS = {
    'mp4': 'mp4', 'm4a': 'mp4', 'm4v': 'mp4', 'mov': 'mp4',
    'webm': 'webm', 'weba': 'webm', 'mkv': 'mkv',
    '3gp': '3gp', 'flv': 'flv', 'ts': 'ts', 'mp3': 'mp3', 'ogg': 'ogg', 'opus': 'ogg',
}

SKIP_EXT = ('mhtml', 'jpg', 'png', 'webp', 'vtt', 'srt', 'json')


def _num(v):
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else 0


def _int(v):
    n = _num(v)
    return int(n) if n > 0 else 0


def norm_vcodec(raw):
    s = str(raw or '').strip().lower()
    if not s or s == 'none':
        return ''
    for prefix, name in VIDEO_CODECS:
        if s.startswith(prefix):
            return name
    return s.split('.')[0]


def norm_acodec(raw):
    s = str(raw or '').strip().lower()
    if not s or s == 'none':
        return ''
    for prefix, name in AUDIO_CODECS:
        if s.startswith(prefix):
            return name
    return s.split('.')[0]


def transport_of(fmt):
    """
    'http'  — один файл по HTTP(S), докачка через Range;
    'hls'   — плейлист m3u8 с сегментами;
    'dash'  — сегменты DASH (манифест или список фрагментов);
    'other' — остальное (f4m, ism, rtmp, websocket ...).
    """
    proto = str(fmt.get('protocol') or '').lower()
    url = fmt.get('url')
    if fmt.get('fragments') or proto.startswith('http_dash_segments') or proto in ('dash', 'dash_frag_urls'):
        return 'dash'
    if proto.startswith('m3u8'):
        return 'hls'
    if proto in ('http', 'https', ''):
        if isinstance(url, str) and url.startswith(('http://', 'https://')):
            # Прямой адрес на .m3u8/.mpd — это манифест, а не файл.
            path = url.split('?', 1)[0].lower()
            if path.endswith('.m3u8'):
                return 'hls'
            if path.endswith('.mpd'):
                return 'dash'
            return 'http'
        return 'other'
    return 'other'


def container_of(fmt):
    ext = str(fmt.get('ext') or '').lower()
    return CONTAINERS.get(ext, ext)


def is_vk_direct(fmt):
    fid = str(fmt.get('format_id') or '')
    return bool(VK_DIRECT_RE.fullmatch(fid)) and transport_of(fmt) == 'http'


def track_kind(fmt):
    """'av' | 'video' | 'audio' | '' (не медиадорожка или непонятно что)."""
    ext = str(fmt.get('ext') or '').lower()
    if ext in SKIP_EXT:
        return ''
    note = str(fmt.get('format_note') or '').lower()
    if 'storyboard' in note:
        return ''
    if is_vk_direct(fmt):
        # Прямые VK-файлы целые (видео со звуком), даже если кодеки не указаны.
        return 'av'
    v = str(fmt.get('vcodec') or '').lower()
    a = str(fmt.get('acodec') or '').lower()
    has_v = bool(v) and v != 'none'
    has_a = bool(a) and a != 'none'
    if has_v and has_a:
        return 'av'
    if has_v and a == 'none':
        return 'video'
    if has_a and v == 'none':
        return 'audio'
    if v == 'none' and not a and (_num(fmt.get('abr')) or ext in ('m4a', 'mp3', 'opus', 'weba', 'ogg')):
        return 'audio'
    # Кодеки неизвестны: не угадываем, есть ли звук.
    return ''


def dims(fmt):
    """(ширина, высота) кадра. Без угадывания по format_id, кроме VK urlXXX."""
    w, h = _int(fmt.get('width')), _int(fmt.get('height'))
    if not h and is_vk_direct(fmt):
        h = int(VK_DIRECT_RE.fullmatch(str(fmt.get('format_id'))).group(1))
    if not h and not w:
        res = str(fmt.get('resolution') or '')
        m = re.fullmatch(r'(\d{2,5})x(\d{2,5})', res)
        if m:
            w, h = int(m.group(1)), int(m.group(2))
    return w, h


def _is_drc(fmt):
    fid = str(fmt.get('format_id') or '').lower()
    note = str(fmt.get('format_note') or '').lower()
    return fid.endswith('-drc') or ' drc' in (' ' + note.replace(',', ' '))


def _audio_role(fmt):
    """'original' | 'default' | 'descriptive' | 'dubbed' | '' по данным yt-dlp."""
    note = str(fmt.get('format_note') or '').lower()
    if 'descriptive' in note:
        return 'descriptive'
    if 'original' in note:
        return 'original'
    if 'default' in note:
        return 'default'
    if 'dubbed' in note or 'dub' in note.split(','):
        return 'dubbed'
    return ''


def normalize_track(fmt):
    """Словарь дорожки для Kotlin или None, если это не медиадорожка."""
    if not isinstance(fmt, dict):
        return None
    fid = str(fmt.get('format_id') or '').strip()
    if not fid:
        return None
    kind = track_kind(fmt)
    if not kind:
        return None
    w, h = dims(fmt)
    size = _int(fmt.get('filesize'))
    approx = _int(fmt.get('filesize_approx'))
    chunk = 0
    opts = fmt.get('downloader_options')
    if isinstance(opts, dict):
        chunk = _int(opts.get('http_chunk_size'))
    fps = _num(fmt.get('fps'))
    dr = str(fmt.get('dynamic_range') or '')
    if kind == 'audio':
        dr = ''
    elif not dr:
        dr = 'SDR'
    return {
        'id': fid,
        'kind': kind,
        'ext': str(fmt.get('ext') or ''),
        'container': container_of(fmt),
        'vcodec': norm_vcodec(fmt.get('vcodec')) if kind != 'audio' else '',
        'vcodec_raw': str(fmt.get('vcodec') or '') if kind != 'audio' else '',
        'acodec': norm_acodec(fmt.get('acodec')) if kind != 'video' else '',
        'acodec_raw': str(fmt.get('acodec') or '') if kind != 'video' else '',
        'width': w if kind != 'audio' else 0,
        'height': h if kind != 'audio' else 0,
        'fps': round(float(fps), 3) if fps else 0,
        'dynamic_range': dr,
        'tbr': round(float(_num(fmt.get('tbr'))), 1),
        'vbr': round(float(_num(fmt.get('vbr'))), 1),
        'abr': round(float(_num(fmt.get('abr'))), 1),
        'asr': _int(fmt.get('asr')),
        'channels': _int(fmt.get('audio_channels')),
        'language': str(fmt.get('language') or '') if kind != 'video' else '',
        'language_preference': int(_num(fmt.get('language_preference'))) if kind != 'video' else 0,
        'audio_role': _audio_role(fmt) if kind != 'video' else '',
        'drc': _is_drc(fmt) if kind != 'video' else False,
        'filesize': size,
        'filesize_exact': bool(size),
        'filesize_approx': approx if not size else 0,
        'transport': transport_of(fmt),
        'protocol': str(fmt.get('protocol') or ''),
        'chunk_size': chunk,
        'drm': bool(fmt.get('has_drm')),
        'note': str(fmt.get('format_note') or '')[:80],
        'vk_direct': is_vk_direct(fmt),
    }


def entry_of(info):
    if not isinstance(info, dict):
        return {}
    entries = info.get('entries')
    if isinstance(entries, list) and entries and isinstance(entries[0], dict):
        return entries[0]
    return info


def formats_of(entry):
    formats = entry.get('formats')
    if isinstance(formats, list) and formats:
        return [f for f in formats if isinstance(f, dict)]
    # Страница с единственным файлом без списка форматов.
    if entry.get('url'):
        return [entry]
    return []


def tracks_of(info):
    """Нормализованные дорожки без повторов format_id (в порядке yt-dlp)."""
    seen = set()
    out = []
    for fmt in formats_of(entry_of(info)):
        t = normalize_track(fmt)
        if t is None or t['id'] in seen:
            continue
        seen.add(t['id'])
        out.append(t)
    return out


def details_of(info):
    e = entry_of(info)
    duration = _num(e.get('duration'))
    return {
        'extractor': str(e.get('extractor_key') or e.get('ie_key') or e.get('extractor') or ''),
        'extractor_name': str(e.get('extractor') or ''),
        'video_id': str(e.get('id') or ''),
        'title': str(e.get('title') or e.get('fulltitle') or 'Видео'),
        'uploader': str(e.get('channel') or e.get('uploader') or e.get('uploader_id') or ''),
        'duration': int(duration) if duration > 0 else 0,
        'thumbnail': str(e.get('thumbnail') or ''),
        'webpage_url': str(e.get('webpage_url') or ''),
        'is_live': bool(e.get('is_live')),
        'live_status': str(e.get('live_status') or ''),
        'availability': str(e.get('availability') or ''),
    }


def find_format(info, fid):
    fid = str(fid or '')
    if not fid:
        return None
    for fmt in formats_of(entry_of(info)):
        if str(fmt.get('format_id') or '') == fid:
            return fmt
    return None


# ---------------------------------------------------------------------
#  Понятные причины ошибок
# ---------------------------------------------------------------------

_ERRORS = (
    ('private', ('private video', 'this video is private'),
     'Это закрытое видео. NOX скачивает только видео, доступные без входа в аккаунт.'),
    ('age-restricted', ('confirm your age', 'age-restricted', 'age restricted', 'inappropriate for some users'),
     'У видео возрастное ограничение: YouTube показывает его только после входа в аккаунт. NOX не запрашивает пароль Google.'),
    ('members-only', ('members-only', 'join this channel', 'available to this channel\'s members'),
     'Видео доступно только спонсорам канала.'),
    ('bot-check', ('not a bot', 'confirm you’re not a bot', "confirm you're not a bot"),
     'YouTube временно требует подтвердить, что запрос не от бота (так бывает в некоторых сетях). Попробуйте позже или через другую сеть.'),
    ('rate-limited', ('http error 429', 'too many requests'),
     'Источник временно ограничил частоту запросов. Подождите несколько минут и повторите.'),
    ('geo-blocked', ('not available in your country', 'geo restrict', 'geo-restrict', 'from your location'),
     'Видео недоступно в вашей стране.'),
    ('live', ('is live', 'live event', 'premieres in', 'will begin in', 'this live stream'),
     'Это прямая трансляция или премьера, которая ещё не закончилась. Скачать можно после её завершения.'),
    ('drm', ('drm',),
     'Видео защищено DRM — такое NOX не скачивает.'),
    ('unsupported', ('unsupported url',),
     'Эту ссылку NOX не умеет разбирать. Нужна ссылка на страницу видео.'),
    ('unavailable', ('video unavailable', 'has been removed', 'is not available', 'does not exist',
                     'no longer available', 'account associated with this video has been terminated', 'http error 404'),
     'Видео недоступно: удалено, скрыто или ссылка неверна.'),
    ('network', ('unable to download', 'timed out', 'name resolution', 'connection reset', 'network is unreachable',
                 'failed to resolve', 'connection refused', 'ssl', 'remote end closed', 'connection aborted'),
     'Не удалось связаться с сайтом. Проверьте интернет и повторите.'),
)


def classify_error(text):
    """(kind, понятное сообщение) по тексту ошибки yt-dlp."""
    low = str(text or '').lower()
    for kind, needles, message in _ERRORS:
        if any(n in low for n in needles):
            return kind, message
    return 'extract-failed', 'Не удалось получить сведения о видео.'


_URL_RE = re.compile(r'https?://[^\s\'"<>]+')


def scrub(text, limit=300):
    """Текст для журнала: без адресов с подписями и без управляющих кодов."""
    s = re.sub(r'\x1b\[[0-9;]*m', '', str(text or ''))
    s = _URL_RE.sub(lambda m: m.group(0).split('?', 1)[0][:80] + ('?…' if '?' in m.group(0) else ''), s)
    s = s.replace('ERROR: ', '').replace('WARNING: ', '')
    return s.strip()[:limit]

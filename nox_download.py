# coding: utf-8
"""
NOX / подсистема загрузок.

yt-dlp resolve(download=False) в своём последовательном потоке, выбор
прямого формата, HTTP-передача через urllib с Range и .part, очередь,
пауза, продолжение, удаление, сохранение состояния, обложки и метаданные.

Модуль НЕ импортирует ui и не знает, что интерфейс существует: он лишь
меняет поля DownloadJob, а показывает их кто угодно.

Код перенесён из монолитного NOX.py дословно.
"""

import os
import io
import re
import sys
import json
import time
import threading
import urllib.request
import urllib.error

# Чёрный ящик. Внутри — только очередь в памяти: рабочий поток отсюда
# ни одного файла не открывает. Алгоритм загрузки эти вызовы не трогают.
import nox_debug

from nox_core import (
    JOBS_KEY, SIDECAR_EXT, QUALITIES, fmt_size, fmt_speed, fmt_eta,
    safe_name, PROJECT_DIR, MEDIA_DIR, YT_DLP_DIR, ensure_dirs, STATE,
)


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

DIRECT_LADDER = ['url2160', 'url1440', 'url1080', 'url720',
                 'url480', 'url360', 'url240', 'url144']


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


class _StaleResolve(Exception):
    """
    Разбор ссылки вернулся, но применять его уже некуда: задание сняли,
    приостановили или переспросили заново, пока мы ждали ответа.
    """
    pass


class RangeNotSupported(Exception):
    """
    На запрос с Range сервер ответил не 206: продолжить по этому адресу
    нельзя. Файл при этом целый, и обрезать его нельзя тем более —
    задание уходит за свежим прямым адресом.
    """
    pass


# Сколько видео реально качается ОДНОВРЕМЕННО. Очередь при этом не
# ограничена: остальные задания ждут свободного слота.
MAX_CONCURRENT_DOWNLOADS = 3

# Параметры прямой HTTP-загрузки.
HTTP_CHUNK = 256 * 1024
HTTP_TIMEOUT = 60
HTTP_RETRIES = 6
HTTP_RETRY_PAUSE = 3.0
SPEED_WINDOW = 1.2          # окно усреднения скорости, секунды

# Диапазон ОДНОГО HTTP-запроса. Большой файл забирается не одним
# бесконечным ответом на гигабайты, а чередой конечных блоков: VK-CDN
# закрывает длинное соединение через считанные сотни килобайт, и весь
# смысл в том, чтобы это было нормальным ходом дела, а не сбоем.
# Это НЕ размер resp.read() — тот остаётся HTTP_CHUNK.
HTTP_RANGE_BLOCK = 1024 * 1024

# Сколько попыток ПОДРЯД должны не дать НИ ОДНОГО НОВОГО БАЙТА, чтобы
# прямой адрес был признан негодным и задание пошло за свежим. Ответ,
# после которого файл вырос хотя бы на байт, — это прогресс, а не сбой,
# и счётчик обнуляется, сколько бы раз соединение ни закрывалось.
URL_DEAD_STRIKES = 2
NO_PROGRESS_STRIKES = 3

# Разбор ссылки — своя подсистема со своими повторами. Сеть у metadata и
# у самого файла ведёт себя по-разному: страница VK может не ответить
# из-за SSL handshake, а уже открытая передача при этом идёт нормально.
RESOLVE_RETRIES = 3
RESOLVE_RETRY_PAUSE = 2.0
# Свой таймаут сокета для metadata, отдельно от HTTP_TIMEOUT загрузки.
# Шестьдесят секунд ожидания одного handshake — это минута, которую
# человек ждёт впустую; двадцати достаточно, а отказ лечит повтор.
RESOLVE_SOCKET_TIMEOUT = 20

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
        # Поколение разбора ссылки. Растёт на каждом действии человека —
        # ▶, ⏸, ×. Результат разбора, начатого в прошлом поколении,
        # применять уже нельзя: за минуту ожидания VK задание могли
        # приостановить или удалить.
        self.resolve_generation = 0
        self.resolve_attempt = 0
        # Почему понадобился свежий адрес: expired | network | range-ignored.
        # От этого зависит текст ошибки, если обновления не помогли.
        self.refresh_reason = ''
        # Разбор ПОСЛЕДНЕЙ HTTP-попытки: только строка в памяти, никакого
        # своего файла у рабочего потока нет. Показывается в карточке при
        # ошибке и уходит в download_debug.txt главным потоком.
        self.http_diag = ''
        self.attempt_no = 0        # сколько попыток сделал текущий worker
        self.last_read = 0         # байт, полученных последней попыткой
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
        """Прямая ссылка протухла: нужен свежий resolve."""
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
        if self.status == ST_QUEUED_DOWNLOAD:
            # Четвёртое и следующие задания ждут свободного слота — это
            # должно быть видно, иначе карточка выглядит зависшей.
            try:
                n = DOWNLOADER.queue_position(self)
            except Exception:
                n = 0
            if n > 0:
                return 'В очереди  ·  %d' % n
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
        # Состояние потока ИМЕННО этого задания: при трёх параллельных
        # загрузках общий ответ ничего не говорит про эту карточку.
        alive = 'alive' if DOWNLOADER.worker_alive(self.id) else 'dead'
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
        parts = [p for p in (self.error_stage, self.http_diag,
                             self.debug_error) if p]
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

    Разбор идёт в ЕДИНСТВЕННОМ потоке nox-resolver, строго по одному за
    раз. На главном потоке его больше нет: журнал с iPhone показал, что
    один SSL handshake VK на 60 секунд замораживал весь интерфейс, пока
    соседняя загрузка спокойно качалась своим потоком.

    Рабочий поток загрузки получает уже готовый прямой HTTPS-адрес и
    качает его обычным urllib — yt-dlp он не импортирует и не вызывает.
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
        # Реестр рабочих потоков: job.id -> threading.Thread. Слот занят
        # ТОЛЬКО живым HTTP-потоком конкретного задания, а не статусом
        # задания: приостановленные и ошибочные слот не держат.
        self._workers = {}
        # Последний сбой заполнения очереди, если он вообще был. Строка,
        # а не запись в файл: pump() зовут и рабочие потоки.
        self.pump_error = ''
        # Разбор ссылок: СВОЯ очередь и РОВНО ОДИН последовательный поток.
        # Слотов загрузки он не занимает — три файла качаются, пока он
        # ждёт ответа VK.
        self._resolve_queue = []      # [(job_id, generation), ...]
        self._resolve_waiting = set()  # job.id, уже стоящие в очереди
        self._resolve_active = None   # job.id, который сейчас в extract_info
        self._resolver = None         # единственный поток nox-resolver

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

    def worker_alive(self, job_id=None):
        """
        job_id задан — жив ли поток ИМЕННО этого задания.
        job_id не задан — жив ли хоть один поток загрузки.

        При трёх параллельных загрузках общего ответа мало: пауза A не
        должна зависеть от того, качается ли сейчас B.
        """
        with self._lock:
            if job_id is not None:
                t = self._workers.get(job_id)
                try:
                    return bool(t is not None and t.is_alive())
                except Exception:
                    return False
            items = list(self._workers.values())
        for t in items:
            try:
                if t is not None and t.is_alive():
                    return True
            except Exception:
                continue
        return False

    def live_workers(self):
        """Сколько слотов занято прямо сейчас. Только живые потоки."""
        with self._lock:
            self._reap_workers()
            return len(self._workers)

    def free_slots(self):
        return max(0, MAX_CONCURRENT_DOWNLOADS - self.live_workers())

    def _reap_workers(self):
        """Убрать из реестра завершившиеся потоки. Только под _lock."""
        dead = []
        for job_id, t in self._workers.items():
            try:
                if t is None or not t.is_alive():
                    dead.append(job_id)
            except Exception:
                dead.append(job_id)
        for job_id in dead:
            self._workers.pop(job_id, None)

    def queue_position(self, job):
        """
        Какой по счёту в очереди ожидания. 0 — не ждёт.

        Реестр снимается ОДИН раз: строку рисует главный поток для каждой
        карточки, и брать замок на каждое задание значило бы толкаться с
        рабочими потоками на каждом такте.
        """
        if job.status != ST_QUEUED_DOWNLOAD:
            return 0
        with self._lock:
            running = set()
            for job_id, t in self._workers.items():
                try:
                    if t is not None and t.is_alive():
                        running.add(job_id)
                except Exception:
                    continue
            jobs = list(self.jobs)
        n = 0
        for j in jobs:
            if j.status == ST_QUEUED_DOWNLOAD and j.id not in running:
                n += 1
                if j.id == job.id:
                    return n
        return 0

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
        nox_debug.job_event('job-added', job, url=url, quality=quality)
        nox_debug.job_event('queued', job)
        # Поток загрузки здесь НЕ запускается: сначала разбор ссылки,
        # и им займётся отдельный поток nox-resolver.
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
        # Разбор, начатый до паузы, применять уже нельзя.
        self.bump_generation(job)
        if job.status == ST_DOWNLOADING and self.worker_alive(job.id):
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
        nox_debug.job_event('paused', job)
        # Слот мог освободиться прямо сейчас — следующее задание из
        # очереди обязано стартовать немедленно, а не ждать чужого события.
        self.pump()
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
        # Новое поколение: за свежей ссылкой пойдёт именно этот ▶, а
        # результат прошлого разбора будет отброшен.
        self.bump_generation(job)
        job.error = ''
        job.debug_error = ''
        job.error_stage = ''
        job.speed = None
        job.eta = None
        job.finished_at = None
        job.refresh_resolve_attempts = 0
        job.refresh_reason = ''
        job.http_diag = ''
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
        nox_debug.job_event('resumed', job, part_size=self.part_size(job))
        # Соседей это не трогает: их потоки продолжают качать свои файлы.
        # Само задание уходит за свежей ссылкой и встаёт в общую очередь.
        self.pump()
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
            # Из очереди разбора снятое задание убираем сразу; уже
            # начатый для него extract_info прервать нельзя, но его
            # результат будет отброшен как устаревший.
            self._resolve_waiting.discard(job_id)
            self._resolve_queue = [(i, g) for i, g in self._resolve_queue
                                   if i != job_id]
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
        self.bump_generation(job)
        nox_debug.job_event('delete-request', job)
        if job.status == ST_DOWNLOADING and self.worker_alive(job.id):
            job.status = ST_DELETING
            job.speed = None
            job.eta = None
            with self._lock:
                self.revision += 1
            self.tick += 1
            # Слот этого задания освободит его собственный поток, когда
            # закроет файл; очередь тронется там же, в его finally.
            return True
        # Ни один поток этот файл не держит — удаляем прямо сейчас.
        self._remove_part(job)
        self._drop_job(job_id)
        self.tick += 1
        nox_debug.job_event('deleted', job)
        self.pump()
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

    # -- РАЗБОР ССЫЛКИ: своя очередь и ОДИН последовательный поток ---
    def bump_generation(self, job):
        """
        Человек что-то сделал с заданием: ▶, ⏸ или ×. Всё, что разбор
        принесёт из прошлого поколения, применять уже нельзя.
        """
        with self._lock:
            job.resolve_generation += 1
            self._resolve_waiting.discard(job.id)
        return job.resolve_generation

    def resolver_busy(self):
        """Идёт ли сейчас extract_info. Слотом загрузки это не является."""
        with self._lock:
            return self._resolve_active is not None

    def resolve_queue_length(self):
        with self._lock:
            return len(self._resolve_queue)

    def enqueue_resolve(self, job):
        """
        Поставить задание в очередь разбора. Идемпотентно: одно задание
        живёт в очереди максимум один раз, сколько бы тактов интерфейса
        ни прошло.
        """
        if job is None:
            return False
        with self._lock:
            if job.id in self._resolve_waiting \
                    or self._resolve_active == job.id:
                return False
            self._resolve_waiting.add(job.id)
            self._resolve_queue.append((job.id, job.resolve_generation))
            depth = len(self._resolve_queue)
        nox_debug.job_event('resolve-queued', job,
                            generation=job.resolve_generation, queue=depth,
                            part_size=self.part_size(job))
        self._start_resolver()
        return True

    def resolve_pump(self):
        """
        Единственное, что делает интерфейс: складывает нуждающиеся в
        разборе задания в очередь и сразу возвращает управление. Сеть
        отсюда не трогается вообще — ни DNS, ни SSL, ни VK.
        """
        try:
            for job in self.pending_resolve():
                self.enqueue_resolve(job)
            self._start_resolver()
        except Exception as e:
            self.pump_error = 'resolve_pump: %r' % (e,)

    def _start_resolver(self):
        """Поднять поток разбора, если очередь не пуста, а его нет."""
        try:
            with self._lock:
                if not self._resolve_queue:
                    return False
                alive = False
                try:
                    alive = self._resolver is not None and \
                        self._resolver.is_alive()
                except Exception:
                    alive = False
                if alive:
                    return False
                t = threading.Thread(target=self._resolve_worker,
                                     name='nox-resolver', daemon=True)
                self._resolver = t
                try:
                    t.start()
                except Exception as e:
                    # Поток не создался: очередь остаётся на месте и
                    # поедет на следующем такте интерфейса.
                    self._resolver = None
                    self.pump_error = 'resolver: %r' % (e,)
                    return False
            nox_debug.event('resolve-worker-start',
                            queue=self.resolve_queue_length())
            return True
        except Exception as e:
            self.pump_error = 'resolver: %r' % (e,)
            return False

    def _next_resolve(self):
        """Следующее задание очереди разбора. Только под _lock."""
        while self._resolve_queue:
            job_id, generation = self._resolve_queue.pop(0)
            self._resolve_waiting.discard(job_id)
            return job_id, generation
        return None, None

    def _resolve_worker(self):
        """
        ЕДИНСТВЕННЫЙ поток разбора. Ни ui, ни console, ни файлов журнала:
        только yt-dlp, поля DownloadJob и структуры менеджера под _lock.

        Работает строго последовательно: одновременных extract_info не
        бывает никогда, сколько бы заданий ни ждало. HTTP-потоки на это
        время продолжают качать свои файлы.
        """
        try:
            while True:
                with self._lock:
                    job_id, generation = self._next_resolve()
                    if job_id is None:
                        self._resolver = None
                        break
                    self._resolve_active = job_id
                try:
                    job = self.find(job_id)
                    if job is None or self._stale_resolve(job, generation):
                        nox_debug.event('resolve-stale-result', job_id=job_id,
                                        generation=generation, reason='before')
                        continue
                    self.resolve(job, generation)
                except Exception as e:
                    self.pump_error = 'resolve_worker: %r' % (e,)
                finally:
                    with self._lock:
                        self._resolve_active = None
        finally:
            with self._lock:
                self._resolver = None
                more = bool(self._resolve_queue)
            nox_debug.event('resolve-worker-idle', queue=int(more))
            if more:
                # Пока мы выходили, интерфейс успел положить ещё одну
                # ссылку: поднимаем поток заново, чтобы она не зависла.
                self._start_resolver()

    def _stale_resolve(self, job, generation):
        """
        Можно ли ещё применять результат разбора. Пока VK думал минуту,
        задание могли удалить, приостановить или переспросить заново.
        """
        if generation is None:
            return False
        try:
            if self.find(job.id) is None:
                return True
            if job.delete_requested or job.cancel_requested:
                return True
            if job.resolve_generation != generation:
                return True
            if job.status not in (ST_QUEUED, ST_PREPARING, ST_NEEDS_RESOLVE):
                return True
        except Exception:
            return True
        return False

    def _resolve_wait(self, job, generation):
        """Пауза между попытками разбора, прерываемая действием человека."""
        slept = 0.0
        while slept < RESOLVE_RETRY_PAUSE:
            if self._stale_resolve(job, generation):
                return False
            time.sleep(0.2)
            slept += 0.2
        return not self._stale_resolve(job, generation)

    def resolve_opts(self):
        """Минимальный набор: разбор ничего не качает и не пишет."""
        return {
            'quiet': True,
            'no_warnings': True,
            'noplaylist': True,
            # Свой таймаут и всего два внутренних повтора: видимый
            # слой повторов — наш, RESOLVE_RETRIES, и он прерывается
            # паузой или удалением между попытками.
            'socket_timeout': RESOLVE_SOCKET_TIMEOUT,
            'retries': 2,
            'fragment_retries': 2,
            'ffmpeg_location': NO_FFMPEG_PATH,
            'fixup': 'never',
        }

    def _extract(self, job, generation):
        """
        extract_info с собственными повторами. None — результат больше не
        нужен: задание сняли, приостановили или переспросили заново.

        Повтор здесь свой, отдельный от повторов HTTP-передачи: metadata
        и уже открытая передача файла отказывают по разным причинам.
        """
        last = None
        for attempt in range(1, RESOLVE_RETRIES + 1):
            if self._stale_resolve(job, generation):
                return None
            job.resolve_attempt = attempt
            began = time.monotonic()
            try:
                mod = _load_yt_dlp()
                if mod is None:
                    raise RuntimeError(YTDLP_ERROR or 'Модуль yt-dlp не найден')
                with mod.YoutubeDL(self.resolve_opts()) as ydl:
                    return ydl.extract_info(job.url, download=False)
            except Exception as e:
                last = e
                elapsed = round(time.monotonic() - began, 3)
                if self._stale_resolve(job, generation):
                    return None
                if attempt >= RESOLVE_RETRIES or not is_network_error(e):
                    raise
                nox_debug.job_event('resolve-retry', job, attempt=attempt,
                                    generation=generation, elapsed=elapsed,
                                    exc=repr(e))
                if not self._resolve_wait(job, generation):
                    return None
        if last is not None:
            raise last
        return None

    def resolve(self, job, generation=None):
        """
        Единственный вызов yt-dlp во всём приложении, и всегда с
        download=False.

        Выполняется в потоке nox-resolver, а НЕ на главном. Раньше он шёл
        синхронно из такта интерфейса, и один SSL handshake VK на
        60 секунд замораживал всё приложение, пока соседняя загрузка
        спокойно качалась своим потоком.
        """
        refreshing = job.needs_refresh
        if not (job.needs_resolve or refreshing):
            return False
        if refreshing:
            job.refresh_resolve_attempts += 1
            if job.refresh_resolve_attempts > MAX_URL_REFRESH:
                job.status = ST_ERROR
                if job.refresh_reason == 'range-ignored':
                    # Честный диагноз вместо «оборвалось»: свежие адреса
                    # один за другим отказываются продолжать с середины.
                    job.error = 'Сервер не поддерживает продолжение этой загрузки'
                    job.error_stage = 'range-give-up'
                else:
                    job.error = 'Не удалось обновить ссылку'
                    job.error_stage = 'refresh-give-up'
                job.debug_error = ' | '.join([p for p in (
                    job.http_diag,
                    'refresh(%s): %d попыток подряд не дали рабочую ссылку'
                    % (job.refresh_reason or '-',
                       job.refresh_resolve_attempts - 1)) if p])
                job.finished_at = time.time()
                nox_debug.job_event('refresh-give-up', job,
                                    error=job.error,
                                    error_stage=job.error_stage,
                                    http_diag=job.http_diag)
                nox_debug.job_event('error', job, error=job.error,
                                    error_stage=job.error_stage)
                with self._lock:
                    self.revision += 1
                self.tick += 1
                self.mark_dirty()
                return False
            nox_debug.job_event('refresh-start', job,
                                reason=job.refresh_reason,
                                part_size=self.part_size(job))
        job.status = ST_PREPARING
        job.set_stage('refresh-enter' if refreshing else 'resolve-enter')
        job.resolve_attempt = 0
        self.tick += 1
        # Запись только в память: файлы журнала пишет главный поток на
        # своём такте, и теперь он для этого свободен.
        nox_debug.job_event('resolve-start', job, url=job.url,
                            quality=job.quality, refreshing=refreshing,
                            generation=generation,
                            part_size=self.part_size(job))
        try:
            info = self._extract(job, generation)
            if info is None:
                # Задание сняли, приостановили или переспросили заново,
                # пока мы ждали ответа.
                return False
            # Проверка ДО первого присваивания: пока VK думал, задание
            # могли удалить или переспросить заново, и записывать в него
            # чужой адрес нельзя даже временно.
            if self._stale_resolve(job, generation):
                raise _StaleResolve()
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
            if self._stale_resolve(job, generation):
                raise _StaleResolve()
            job.set_stage('format-selected')
            job.status = ST_QUEUED_DOWNLOAD
            job.set_stage('http-queued')
            self.mark_dirty()
            nox_debug.job_event('resolve-success', job,
                                format_id=job.resolved_format_id,
                                expected_size=job.expected_size,
                                resolved_host=nox_debug._host_of(
                                    job.resolved_url))
            nox_debug.job_event('queued-download', job,
                                filename=os.path.basename(job.filename or ''))
        except _StaleResolve:
            nox_debug.event('resolve-stale-result', job_id=job.id,
                            generation=generation, reason='after')
            return False
        except Exception as e:
            job.error_stage = job.debug_stage
            job.set_stage('exception')
            job.status = ST_ERROR
            job.error = short_error(e)
            job.debug_error = 'resolve: %r' % (e,)
            job.finished_at = time.time()
            nox_debug.job_event('resolve-error', job,
                                error=job.error, error_stage=job.error_stage,
                                exc=repr(e))
            with self._lock:
                self.revision += 1
            self.tick += 1
            self.mark_dirty()
            return False
        with self._lock:
            self.revision += 1
        self.tick += 1
        self.pump()
        return True

    # -- рабочий поток: только HTTP --------------------------------
    def pump(self):
        """
        Занять все свободные слоты. Единственная публичная точка входа.

        Наружу отсюда не уходит НИ ОДНО исключение: сорванный запуск
        одного задания не имеет права остановить очередь целиком. Вызов
        дешёвый и идемпотентный, поэтому его делает и каждое изменение
        очереди, и единственный периодический такт интерфейса — так
        освободившийся слот занимается независимо от того, кто именно его
        освободил.
        """
        try:
            self._pump()
        except Exception as e:
            # Причину запоминаем строкой, а не пишем в файл: pump()
            # вызывают и рабочие потоки, а log_debug — только главный.
            self.pump_error = 'pump: %r' % (e,)

    def _next_queued(self, skip):
        """
        Первое задание очереди, которое можно запустить прямо сейчас.
        FIFO по порядку добавления. Только под _lock.
        """
        for j in self.jobs:
            if j.id in skip:
                continue
            if j.status != ST_QUEUED_DOWNLOAD:
                continue
            if j.cancel_requested or j.pause_requested \
                    or j.delete_requested:
                continue
            if j.id in self._workers:
                continue
            if self._path_busy(j):
                # Два задания не должны писать в один и тот же .part:
                # второе ждёт, пока первое освободит файл. Остальным
                # заданиям это ожидание не мешает — цикл идёт дальше.
                continue
            return j
        return None

    def _pump(self):
        """
        Заполняет ВСЕ свободные слоты, а не один.

        За один вызов может стартовать до MAX_CONCURRENT_DOWNLOADS
        заданий. Слот считается занятым только живым потоком конкретного
        задания: приостановленные, упавшие и ждущие разбора ссылки слот
        не держат. yt-dlp здесь по-прежнему не вызывается — поток
        получает уже готовый resolved_url.

        Регистрация в реестре и старт потока идут под одним и тем же
        _lock: иначе между ними успел бы вклиниться _reap_workers, увидеть
        ещё не запущенный поток мёртвым и отдать тот же слот второй раз.
        """
        started = []
        skip = set()
        with self._lock:
            self._reap_workers()
            free = MAX_CONCURRENT_DOWNLOADS - len(self._workers)
            while free > 0:
                nxt = self._next_queued(skip)
                if nxt is None:
                    break
                nxt.set_stage('before-thread-create')
                nox_debug.job_event('worker-create', nxt,
                                    live_workers=len(self._workers))
                t = threading.Thread(target=self._run, args=(nxt,),
                                     name='nox-download', daemon=True)
                self._workers[nxt.id] = t
                try:
                    t.start()
                except Exception as e:
                    # Поток не создался — на iOS такое бывает под нехваткой
                    # памяти. Слот при этом НЕ занят: снимаем регистрацию,
                    # задание остаётся в очереди и поедет на следующем
                    # такте. Остальные свободные слоты заполняем дальше —
                    # раньше исключение выходило наружу и вся очередь
                    # вставала до следующего разбора ссылки.
                    self._workers.pop(nxt.id, None)
                    nxt.set_stage('thread-start-failed')
                    detail = 'thread: %r' % (e,)
                    nxt.debug_error = ((nxt.debug_error + ' | ' + detail)
                                       if nxt.debug_error else detail)
                    skip.add(nxt.id)
                    nox_debug.job_event('worker-error', nxt, exc=repr(e))
                    continue
                started.append(nxt)
                nox_debug.job_event('worker-started', nxt,
                                    live_workers=len(self._workers))
                free -= 1
        for job in started:
            # Поток мог успеть шагнуть дальше — не затираем более поздний этап.
            if job.debug_stage == 'before-thread-create':
                job.set_stage('thread-start-called')

    def _path_busy(self, job):
        """
        Пишет ли уже кто-то в этот же файл. Только под _lock.

        Обычно video id в имени такое исключает, но одинаковое имя
        возможно (два разных URL одного видео), и параллельная запись
        двух потоков в один .part испортила бы файл.
        """
        if not job.filename:
            return False
        for other in self.jobs:
            if other.id == job.id or other.filename != job.filename:
                continue
            t = self._workers.get(other.id)
            try:
                if t is not None and t.is_alive():
                    return True
            except Exception:
                continue
        return False

    def _idle_pause(self, job, idle):
        """
        Пауза перед повтором — ТОЛЬКО после попытки, не давшей ни байта.
        Продуктивный блок продолжается сразу: ждать между кусками файла,
        которые реально приходят, незачем.
        """
        job.set_stage('http-retry')
        self.tick += 1
        nox_debug.job_event('http-retry', job, idle=idle)
        slept = 0.0
        while slept < HTTP_RETRY_PAUSE and not job.cancel_requested \
                and not job.pause_requested:
            time.sleep(0.2)
            slept += 0.2

    @staticmethod
    def _note_refresh(job, detail):
        """
        Почему задание идёт за свежим адресом. Строка в памяти: рабочий
        поток к файлам журнала не ходит, в download_debug.txt её позже
        перенесёт главный. Не накапливается — интересна последняя попытка.
        """
        job.debug_error = ' | '.join([p for p in (job.http_diag, detail) if p])

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
        nox_debug.job_event('worker-enter', job,
                            part_size=self.part_size(job),
                            resolved_host=nox_debug._host_of(job.resolved_url))
        final = job.filename
        part = job.part_path
        state = {'bytes': 0, 'time': time.monotonic()}
        last_error = None
        refresh = ''          # почему нужен свежий прямой адрес
        # Попыток ПОДРЯД без единого нового байта. Любой полученный байт
        # обнуляет счётчик: обрыв соединения после реальных данных —
        # обычный ход дела, а не повод менять адрес или сдаваться.
        idle = 0
        try:
            while True:
                if job.cancel_requested or job.pause_requested:
                    break
                try:
                    done = self._transfer(job, part, state)
                except DirectUrlExpired as e:
                    # Ссылка протухла. Лечит это только новый resolve, а
                    # им занимается поток nox-resolver: yt-dlp здесь
                    # по-прежнему не импортируется и не вызывается.
                    refresh = 'expired'
                    last_error = None
                    self._note_refresh(job, 'expired: %r' % (e,))
                    nox_debug.job_event('http-error', job, reason='expired',
                                        exc=repr(e),
                                        http_diag=job.http_diag)
                    break
                except RangeNotSupported as e:
                    # Докачку по этому адресу не принимают. .part цел,
                    # обрезать его нельзя — идём за свежим адресом.
                    refresh = 'range-ignored'
                    last_error = None
                    self._note_refresh(job, 'range-ignored: %r' % (e,))
                    nox_debug.job_event('range-ignored', job, exc=repr(e),
                                        part_size=self.part_size(job),
                                        http_diag=job.http_diag)
                    break
                except Exception as e:
                    last_error = e
                    if job.cancel_requested or job.pause_requested:
                        break
                    nox_debug.job_event('http-error', job, exc=repr(e),
                                        idle=idle, last_read=job.last_read,
                                        http_diag=job.http_diag)
                    if job.last_read > 0:
                        # Байты дошли, а потом соединение оборвалось. Это
                        # НЕ ошибка: следующий блок пойдёт с нового размера
                        # .part, и так хоть тысячу раз подряд.
                        last_error = None
                        idle = 0
                        nox_debug.job_event('range-next', job,
                                            part_size=self.part_size(job),
                                            after='drop')
                        continue
                    if not is_network_error(e) and job.attempt_no >= 2:
                        raise
                    idle += 1
                    if idle >= NO_PROGRESS_STRIKES and is_network_error(e) \
                            and not isinstance(e, urllib.error.HTTPError):
                        # Ни одного байта несколько попыток подряд: адрес
                        # мёртв, дальше его мучить бессмысленно.
                        refresh = 'network'
                        last_error = None
                        self._note_refresh(job, 'no-progress x%d: %r'
                                           % (idle, e))
                        nox_debug.job_event('range-refresh', job,
                                            reason='network', idle=idle,
                                            exc=repr(e))
                        break
                    if idle >= HTTP_RETRIES:
                        # 429/5xx и прочее, что новым адресом не лечится.
                        raise
                    self._idle_pause(job, idle)
                    continue
                if done:
                    last_error = None
                    break
                if job.last_read > 0:
                    # Блок закончился штатно — сразу следующий, без пауз.
                    idle = 0
                    last_error = None
                    nox_debug.job_event('range-next', job,
                                        part_size=self.part_size(job),
                                        after='block')
                    continue
                # 206 пришёл, а тело оказалось пустым: вот это и есть
                # настоящий отказ адреса.
                idle += 1
                if idle >= NO_PROGRESS_STRIKES:
                    refresh = 'network'
                    self._note_refresh(job, 'no-progress x%d: пустое тело'
                                       % (idle,))
                    nox_debug.job_event('range-refresh', job,
                                        reason='empty-body', idle=idle)
                    break
                if idle >= HTTP_RETRIES:
                    raise IOError('сервер не отдаёт данные')
                self._idle_pause(job, idle)
            if job.delete_requested:
                job.set_stage('deleting')            # .part уберём в finally
            elif refresh:
                # .part НЕ трогаем: свежий адрес продолжит его через Range.
                job.resolved_url = ''
                job.refresh_reason = refresh
                job.status = ST_NEEDS_RESOLVE
                job.set_stage('needs-resolve')
                nox_debug.job_event('needs-resolve', job, reason=refresh,
                                    part_size=self.part_size(job))
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
                nox_debug.job_event('finished', job)
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
                job.debug_error = ' | '.join(
                    [p for p in (job.http_diag, 'http: %r' % (e,)) if p])
                nox_debug.job_event('error', job, error=job.error,
                                    error_stage=job.error_stage, exc=repr(e),
                                    http_diag=job.http_diag)
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
                # Освобождается слот ИМЕННО этого задания. Общего
                # self._thread больше нет: чужие потоки продолжают жить.
                self._workers.pop(job.id, None)
            self.tick += 1
            self.mark_dirty()
            nox_debug.job_event('worker-exit', job,
                                part_size=self.part_size(job))
            # Слот освобождён — следующее задание стартует отсюда же.
            # Ошибка этого задания на очередь не влияет: pump() исключения
            # наружу не выпускает.
            self.pump()

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

    def _block_end(self, job, offset):
        """
        Конец запрашиваемого блока. None — размер ещё неизвестен, и тогда
        это самый первый запрос: он идёт без Range и сам приносит размер.
        """
        total = job.exact_total
        if not total:
            return None
        end = min(int(total) - 1, offset + HTTP_RANGE_BLOCK - 1)
        return end if end >= offset else None

    def _transfer(self, job, part, state):
        """
        ОДИН конечный Range-блок. True — файл собран целиком, False —
        блок кончился, нужен следующий.

        Длинного ответа на весь файл здесь больше нет. VK-CDN закрывает
        соединение через 256 КБ независимо от того, что обещал в
        Content-Length, и на длинном потоке загрузка навсегда замирала на
        первом блоке. Теперь короткий ответ — обычное дело: сколько байт
        дошло, столько и записали, а следующий запрос идёт с нового
        размера .part. Файл — единственный источник истины про offset.

        Разбор попытки складывается в job.http_diag — строку в памяти.
        Своего файла журнала у рабочего потока нет.
        """
        job.set_stage('http-opening')
        job.attempt_no += 1
        job.last_read = 0
        offset = self.part_size(job)
        # Счётчик всегда равен тому, что реально лежит на диске: переход
        # между блоками не должен выглядеть как начатая заново загрузка.
        job.downloaded_bytes = offset
        end = self._block_end(job, offset)
        headers = dict(job.resolved_headers or {})
        # Заголовки формата от yt-dlp (User-Agent, Referer, Origin) идут
        # как есть и на первой попытке, и на любом следующем блоке.
        headers.setdefault('User-Agent', 'NOX/1.0')
        # Без сжатия: и границы Range, и Content-Length должны считаться в
        # тех же байтах, которые лягут на диск и будут сверены перед
        # переименованием.
        headers.setdefault('Accept-Encoding', 'identity')
        rng = ''
        if offset > 0 or end is not None:
            rng = 'bytes=%d-%s' % (offset, end if end is not None else '')
            headers['Range'] = rng
        want = (end - offset + 1) if end is not None else None
        diag = {'attempt': job.attempt_no,
                'refresh': job.refresh_resolve_attempts,
                'offset': offset, 'range': rng or '-',
                'host': _host_of(job.resolved_url),
                'code': '-', 'len': '-', 'crange': '-', 'aranges': '-',
                'read': 0, 'ignored': False, 'err': '-'}
        job.http_diag = _diag_text(diag)
        nox_debug.http_event('range-block-open', job, offset=offset,
                             part_size=offset, range=rng or None,
                             requested_start=offset, requested_end=end,
                             requested=want, exact_total=job.exact_total,
                             bytes_before=job.downloaded_bytes)
        nox_debug.http_event('http-open', job, offset=offset,
                             part_size=offset, range=rng or None)
        req = urllib.request.Request(job.resolved_url, headers=headers)
        try:
            resp = urllib.request.urlopen(req, timeout=HTTP_TIMEOUT)
        except urllib.error.HTTPError as e:
            code = _int_or_none(getattr(e, 'code', None)) or 0
            diag['code'] = code
            diag['err'] = repr(e)
            job.http_diag = _diag_text(diag)
            nox_debug.http_event('http-response', job, resp=e, offset=offset,
                                 range=rng or None, exc=repr(e))
            if code == 416:
                return self._handle_416(job, offset, e)
            if code in EXPIRED_CODES:
                # 429, таймауты и 5xx сюда НЕ попадают: это обычные сбои,
                # их лечит обычный повтор, а не новый разбор ссылки.
                raise DirectUrlExpired('HTTP %d' % code)
            raise
        except Exception as e:
            diag['err'] = repr(e)
            job.http_diag = _diag_text(diag)
            nox_debug.http_event('http-error', job, offset=offset,
                                 range=rng or None, exc=repr(e))
            raise
        try:
            code = resp.getcode()
            diag['code'] = code
            diag['len'] = resp.headers.get('Content-Length') or '-'
            diag['crange'] = resp.headers.get('Content-Range') or '-'
            diag['aranges'] = resp.headers.get('Accept-Ranges') or '-'
            job.http_diag = _diag_text(diag)
            nox_debug.http_event('http-response', job, resp=resp,
                                 offset=offset, range=rng or None,
                                 requested_start=offset, requested_end=end,
                                 bytes_before=job.downloaded_bytes)
            if offset > 0 and code != 206:
                # Докачку не приняли. Существующий .part остаётся целым:
                # свежий прямой адрес продолжит его с того же места.
                diag['ignored'] = True
                job.http_diag = _diag_text(diag)
                nox_debug.http_event('range-ignored', job, resp=resp,
                                     offset=offset, range=rng or None,
                                     part_size=offset)
                raise RangeNotSupported(
                    'на Range получен ответ %s вместо 206' % code)
            if code == 206:
                nox_debug.http_event('range-206', job, resp=resp,
                                     offset=offset, range=rng or None)
                total = _total_from_content_range(
                    resp.headers.get('Content-Range'))
                if total is None:
                    length = _int_or_none(resp.headers.get('Content-Length'))
                    total = (offset + length) if length else None
            else:
                total = _int_or_none(resp.headers.get('Content-Length'))
            if total:
                # Размер пришёл от сервера — он точный, в отличие от
                # filesize_approx, и именно по нему проверяем файл в конце.
                job.total_bytes = int(total)
                job.exact_total = int(total)
            # offset > 0 -> только дописывание. Обрезать уже скачанное
            # нельзя ни при каких ответах сервера.
            mode = 'ab' if offset > 0 else 'wb'
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
                        nox_debug.job_event('http-eof', job,
                                            bytes_read_this_attempt=
                                            job.last_read)
                        break
                    f.write(chunk)
                    job.last_read += len(chunk)
                    nox_debug.first_bytes(job, len(chunk))
                    if job.refresh_resolve_attempts:
                        # Свежий адрес отдаёт байты — значит, обновление
                        # ссылки сработало, и счётчик обновлений начинается
                        # заново. Иначе многочасовая загрузка умирала бы
                        # после третьей смены адреса CDN, хотя каждая смена
                        # была успешной.
                        job.refresh_resolve_attempts = 0
                    self._note_progress(job, state, len(chunk))
        finally:
            diag['read'] = job.last_read
            job.http_diag = _diag_text(diag)
            try:
                resp.close()
            except Exception:
                pass
        if job.cancel_requested or job.pause_requested:
            return False
        size = self.part_size(job)
        job.downloaded_bytes = size
        nox_debug.http_event(
            'range-block-complete' if want and job.last_read >= want else (
                'range-block-short' if job.last_read > 0
                else 'range-zero-progress'),
            job, offset=offset, range=rng or None, requested=want,
            bytes_read_this_attempt=job.last_read, part_size_after=size,
            exact_total=job.exact_total)
        total = job.exact_total or job.total_bytes
        if total:
            if size >= int(total):
                return True          # файл собран целиком
            return False             # блок кончился, нужен следующий
        # Размера сервер не назвал: тело кончилось — кончился и файл.
        return True

    def _handle_416(self, job, offset, err):
        """
        416 Range Not Satisfiable. В заголовке приходит 'bytes */TOTAL'.

        Три разных случая, и удаление допустимо ровно в одном:

        .part == TOTAL   файл уже полный, серверу просто нечего отдать —
                         это успех, дальше сверка размера и rename;
        .part >  TOTAL   на диске больше, чем есть у источника: такой
                         файл этому источнику не соответствует ДОКАЗАННО,
                         только тут .part удаляется и качается заново;
        .part <  TOTAL   сервер отказал в диапазоне, который обязан был
                         отдать. Несовместимость НЕ доказана, файл не
                         трогаем — идём за свежим прямым адресом.
        """
        try:
            total = _total_from_content_range(err.headers.get('Content-Range'))
        except Exception:
            total = None
        nox_debug.http_event('range-416', job, offset=offset,
                             content_range=total, part_size=offset)
        if total and offset == int(total):
            job.exact_total = int(total)
            job.total_bytes = int(total)
            job.downloaded_bytes = offset
            job.set_stage('http-416-complete')
            self.tick += 1
            return True
        if total and offset < int(total):
            job.set_stage('http-416-refresh')
            self.tick += 1
            raise RangeNotSupported(
                'сервер отклонил докачку (416) на %d из %d байт'
                % (offset, int(total)))
        self._remove_part(job)
        job.downloaded_bytes = 0
        job.exact_total = None
        job.total_bytes = None
        job.set_stage('http-416-restart')
        self.tick += 1
        raise IOError('сервер отклонил докачку (416), файл будет скачан заново')


def _host_of(url):
    """Только хост прямой ссылки: сама она длинная и с подписью."""
    m = re.match(r'[a-zA-Z][\w+.-]*://([^/?#]+)', str(url or ''))
    return m.group(1) if m else '-'


def _diag_text(d):
    """
    Однострочный разбор HTTP-попытки: что попросили и что ответили.
    Нужен, чтобы по одной строке в карточке было видно поведение CDN.
    """
    return ('attempt=%(attempt)s refresh=%(refresh)s offset=%(offset)s '
            'range=%(range)s code=%(code)s len=%(len)s crange=%(crange)s '
            'aranges=%(aranges)s read=%(read)s range_ignored=%(ignored)s '
            'host=%(host)s err=%(err)s' % d)


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

# coding: utf-8
"""
NOX / ЭКСПЕРИМЕНТ: фоновая загрузка средствами iOS.

Этот модуль НЕ участвует в работе NOX. Он ничего не импортирует из
NOX.py, nox_core.py, nox_download.py, nox_player.py, nox_ui.py и
nox_debug.py, не трогает их состояние и не пишет в их файлы. Единственное
общее с приложением — папка проекта и лежащий в ней пакет yt_dlp.

Что проверяется. Сейчас NOX читает видео сам, Python-циклом поверх
urllib. Пока Pythonista на экране, это работает; что происходит с
Python-потоком после сворачивания приложения — вопрос открытый, и
Python-код здесь ничего не решает: приостанавливает процесс iOS.

Единственный публичный путь, при котором передачу файла ведёт СИСТЕМА, а
не наш код:

    NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:
        -> NSURLSession
            -> NSURLSessionDownloadTask

После task.resume() байты качает демон iOS. Python может быть
приостановлен, убит планировщиком, перезапущен — задача от этого не
исчезает, а при следующем запуске поднимается по тому же identifier.

ТАК ЭТО УСТРОЕНО В iOS. Работает ли это ВНУТРИ PYTHONISTA — ровно то, что
проверяет эксперимент. Заранее ничего не утверждается: у Pythonista нет
собственного background-режима для произвольных скриптов, и жизненный
цикл делегата может оказаться неполным. Модуль обязан честно показать
любой исход, включая полную недоступность API.

Чего здесь нет и быть не должно: подмены эксперимента обычной загрузкой,
приватных API, subprocess, signal, multiprocessing, Executor, своего
цикла чтения MP4 и любого while True вокруг передачи. yt-dlp вызывается
ровно один раз и только ради прямого адреса.
"""

import os
import io
import re
import sys
import json
import time
import shutil
import threading


# =====================================================================
#  ПУТИ. Всё своё, рядом с проектом, но в отдельных файлах.
# =====================================================================

def _project_dir():
    """Папка, в которой лежит этот файл — та же, что у NOX.py."""
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
DATA_DIR = os.path.join(PROJECT_DIR, 'NOX_Data')
YT_DLP_DIR = os.path.join(PROJECT_DIR, 'yt_dlp')

# Всё, что пишет эксперимент. Ни одного файла основного NOX здесь нет:
# ни state.json, ни download_jobs_v2, ни NoxMedia, ни чужих .part.
BG_STATE_PATH = os.path.join(DATA_DIR, 'nox_bg_state.json')
BG_LOG_PATH = os.path.join(DATA_DIR, 'nox_bg_test.jsonl')
BG_MEDIA_DIR = os.path.join(DATA_DIR, 'BG_TEST')

# Идентификатор фоновой сессии. Стабильный: именно по нему iOS отдаёт
# обратно задачи, пережившие suspension и перезапуск скрипта.
BG_SESSION_ID = 'com.nox.pythonista.bgtest.v1'

# Журнал ограничен: при превышении уезжает в .previous, текущий начинается
# заново. Бесконечный файл на телефоне никому не нужен.
LOG_LIMIT = 512 * 1024
LOG_TAIL = 400            # сколько последних событий держим в памяти

QUALITIES = ['360', '480', '720', 'MAX']


def ensure_dirs():
    ok = True
    for path in (DATA_DIR, BG_MEDIA_DIR):
        try:
            os.makedirs(path, exist_ok=True)
        except Exception:
            ok = False
        if not os.path.isdir(path):
            ok = False
    return ok


# =====================================================================
#  ЖУРНАЛ
# =====================================================================
_LOG_LOCK = threading.RLock()
_LOG_TAIL = []


def log(kind, **fields):
    """
    Событие в NOX_Data/nox_bg_test.jsonl и в память.

    Зовётся в том числе из ObjC-колбэков, то есть с чужого потока,
    поэтому под замком и с полным подавлением ошибок: журнал не имеет
    права уронить эксперимент.
    """
    rec = {'t': round(time.time(), 3),
           'kind': str(kind),
           'thread': threading.current_thread().name}
    try:
        for key, value in fields.items():
            if isinstance(value, (str, int, float, bool)) or value is None:
                rec[key] = value
            else:
                rec[key] = repr(value)
    except Exception:
        pass
    try:
        with _LOG_LOCK:
            _LOG_TAIL.append(rec)
            del _LOG_TAIL[:-LOG_TAIL]
            _rotate_locked()
            with io.open(BG_LOG_PATH, 'a', encoding='utf-8') as f:
                f.write(json.dumps(rec, ensure_ascii=False) + '\n')
    except Exception:
        pass
    return rec


def _rotate_locked():
    try:
        if os.path.getsize(BG_LOG_PATH) < LOG_LIMIT:
            return
    except Exception:
        return
    try:
        os.replace(BG_LOG_PATH, BG_LOG_PATH + '.previous')
    except Exception:
        try:
            os.remove(BG_LOG_PATH)
        except Exception:
            pass


def recent(limit=40):
    with _LOG_LOCK:
        return list(_LOG_TAIL[-int(limit):])


# =====================================================================
#  СОСТОЯНИЕ ЭКСПЕРИМЕНТА
# =====================================================================
# RLock, а не Lock: save_state() зовёт state() уже под замком, и на
# обычном Lock это был бы вечный сон вместо сохранения.
_STATE_LOCK = threading.RLock()
_STATE = None

_EMPTY_STATE = {
    'session_id': BG_SESSION_ID,
    'page_url': '',
    'quality': '480',
    'title': '',
    'video_id': '',
    'format_id': '',
    'direct_url': '',
    'direct_host': '',
    'headers': {},
    'expected_size': None,
    'task_identifier': None,
    'started_at': None,
    'final_path': '',
    'last_error': '',
    # Замеры Test A / Test B / Test C.
    'marks': {},
    'current_test': 'A',
}


def state():
    global _STATE
    with _STATE_LOCK:
        if _STATE is None:
            _STATE = dict(_EMPTY_STATE)
            try:
                with io.open(BG_STATE_PATH, encoding='utf-8') as f:
                    raw = json.load(f)
                if isinstance(raw, dict):
                    for key in _EMPTY_STATE:
                        if key in raw:
                            _STATE[key] = raw[key]
            except Exception:
                pass
            # identifier всегда наш: эксперимент не должен зависеть от
            # того, что кто-то положил в файл руками.
            _STATE['session_id'] = BG_SESSION_ID
        return _STATE


def save_state():
    ensure_dirs()
    try:
        with _STATE_LOCK:
            data = json.dumps(state(), ensure_ascii=False, indent=1)
        tmp = BG_STATE_PATH + '.tmp'
        with io.open(tmp, 'w', encoding='utf-8') as f:
            f.write(data)
        os.replace(tmp, BG_STATE_PATH)
        return True
    except Exception as e:
        log('state-error', exc=repr(e))
        return False


def reset_state():
    global _STATE
    with _STATE_LOCK:
        _STATE = dict(_EMPTY_STATE)
    save_state()


# =====================================================================
#  ЛОКАЛЬНЫЙ yt-dlp: ТОЛЬКО РАДИ ПРЯМОГО АДРЕСА
# =====================================================================
_YTDLP = None
YTDLP_ERROR = ''
YTDLP_VERSION = ''

# Разбор идёт на главном потоке, значит профиль короткий: интерфейс не
# должен ждать минуту. Ровно один вызов, без внутренних повторов.
RESOLVE_SOCKET_TIMEOUT = 12

NO_FFMPEG_PATH = os.path.join(DATA_DIR, 'no-ffmpeg-here')


def load_yt_dlp():
    """Импортирует ИМЕННО тот yt_dlp, что лежит рядом со скриптом."""
    global _YTDLP, YTDLP_ERROR, YTDLP_VERSION
    if _YTDLP is not None:
        return _YTDLP
    if PROJECT_DIR not in sys.path:
        sys.path.insert(0, PROJECT_DIR)
    if not os.path.isdir(YT_DLP_DIR):
        YTDLP_ERROR = 'Папка yt_dlp не найдена рядом со скриптом'
        return None
    try:
        import yt_dlp as _mod
    except Exception as e:
        YTDLP_ERROR = 'yt_dlp не импортируется: %r' % (e,)
        return None
    _YTDLP = _mod
    YTDLP_ERROR = ''
    try:
        YTDLP_VERSION = str(getattr(_mod.version, '__version__', '') or '')
    except Exception:
        YTDLP_VERSION = ''
    return _YTDLP


URL_RE = re.compile(r'^https?://[^\s"\'`\\]+$', re.IGNORECASE)

# Та же лестница качества, что доказана в рабочем NOX. Здесь она
# ПОВТОРЕНА, а не импортирована: рабочие модули эксперимент не трогает.
VK_DIRECT = {
    '360': ['url360', 'url240', 'url144'],
    '480': ['url480', 'url360', 'url240', 'url144'],
    '720': ['url720', 'url480', 'url360', 'url240', 'url144'],
    'MAX': ['url2160', 'url1440', 'url1080', 'url720',
            'url480', 'url360', 'url240', 'url144'],
}
HEIGHTS = {'360': 360, '480': 480, '720': 720}
VK_DIRECT_RE = re.compile(r'url(?:144|240|360|480|720|1080|1440|2160)')
SEGMENTED_HINTS = ('m3u8', 'dash', 'ism', 'f4m')

# Заголовки, которые имеет смысл перенести в NSURLRequest. Берём только
# реально пришедшие от yt-dlp — ничего не выдумываем.
HEADER_KEYS = ('User-Agent', 'Referer', 'Origin', 'Cookie',
               'Accept', 'Accept-Language')


def _entry_of(info):
    if not isinstance(info, dict):
        return {}
    entries = info.get('entries')
    if isinstance(entries, list) and entries and isinstance(entries[0], dict):
        return entries[0]
    return info


def _is_vk_direct(fmt):
    """
    Прямой VK-формат urlXXX. У него часто нет vcodec/acodec, и это НЕ
    признак video-only: такой поток качается целиком, со звуком.
    """
    if not isinstance(fmt, dict):
        return False
    if not VK_DIRECT_RE.fullmatch(str(fmt.get('format_id') or '')):
        return False
    url = fmt.get('url')
    if not isinstance(url, str) or not url.startswith(('http://', 'https://')):
        return False
    proto = str(fmt.get('protocol') or '').lower()
    return not any(hint in proto for hint in SEGMENTED_HINTS)


def _is_combined(fmt):
    """Готовый файл со звуком и обычным HTTP: склеивать нечем и не нужно."""
    if not isinstance(fmt, dict):
        return False
    url = fmt.get('url')
    if not isinstance(url, str) or not url.startswith('http'):
        return False
    if (fmt.get('vcodec') or 'none').lower() == 'none':
        return False
    if (fmt.get('acodec') or 'none').lower() == 'none':
        return False
    blob = ((fmt.get('protocol') or '') + ' ' +
            str(fmt.get('format_id') or '')).lower()
    return not any(hint in blob for hint in SEGMENTED_HINTS)


def _fmt_height(fmt):
    h = fmt.get('height')
    if isinstance(h, (int, float)) and h > 0:
        return int(h)
    m = re.search(r'(\d{3,4})', str(fmt.get('format_id') or ''))
    try:
        return int(m.group(1)) if m else 0
    except Exception:
        return 0


def pick_direct_format(info, quality):
    """
    Прямой combined-формат: сначала VK urlXXX по лестнице, затем лучший
    combined в пределах нужной высоты. DASH video-only + audio-only не
    берём никогда — ffmpeg в эксперименте нет.
    """
    entry = _entry_of(info)
    formats = entry.get('formats')
    if not isinstance(formats, list):
        formats = []
    vk_by_id = {}
    for f in formats:
        if _is_vk_direct(f):
            vk_by_id.setdefault(str(f.get('format_id')), f)
    for fid in VK_DIRECT.get(quality, VK_DIRECT['MAX']):
        if fid in vk_by_id:
            return vk_by_id[fid]
    combined = [f for f in formats if _is_combined(f)]
    if not combined and _is_combined(entry):
        combined = [entry]
    if not combined:
        return None
    cap = HEIGHTS.get(quality)
    pool = combined
    if cap:
        limited = [f for f in combined if 0 < _fmt_height(f) <= cap]
        pool = limited or [f for f in combined
                           if _fmt_height(f) == 0] or combined

    def rank(f):
        tbr = f.get('tbr')
        return (_fmt_height(f),
                tbr if isinstance(tbr, (int, float)) else 0,
                f.get('filesize') or f.get('filesize_approx') or 0)

    return sorted(pool, key=rank)[-1]


def safe_name(text, limit=80):
    text = re.sub(r'[\\/:*?"<>|]', '_', str(text or '').strip())
    text = re.sub(r'\s+', ' ', text).strip(' .')
    return (text or 'video')[:limit].strip(' .')


def host_of(url):
    m = re.match(r'[a-zA-Z][\w+.-]*://([^/?#]+)', str(url or ''))
    return m.group(1) if m else '-'


def resolve(page_url, quality):
    """
    ОДИН extract_info(url, download=False) и выбор прямого формата.

    Ни ffmpeg, ни postprocessor, ни download=True. Дальше Python видео
    не читает вообще: адрес уходит в NSURLSessionDownloadTask.

    Возвращает (data, error). data — маленький обычный dict, огромный
    info от yt-dlp никуда не сохраняется.
    """
    url = str(page_url or '').strip()
    if not URL_RE.match(url):
        return None, 'Это не похоже на ссылку'
    if quality not in VK_DIRECT:
        quality = '480'
    mod = load_yt_dlp()
    if mod is None:
        return None, YTDLP_ERROR or 'yt-dlp недоступен'
    log('resolve-start', url=url, quality=quality, host=host_of(url))
    opts = {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'socket_timeout': RESOLVE_SOCKET_TIMEOUT,
        'retries': 0,
        'fragment_retries': 0,
        'ffmpeg_location': NO_FFMPEG_PATH,
        'fixup': 'never',
    }
    began = time.monotonic()
    try:
        with mod.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        log('resolve-error', exc=repr(e),
            elapsed=round(time.monotonic() - began, 2))
        return None, 'Разбор не удался: %r' % (e,)
    fmt = pick_direct_format(info, quality)
    if not fmt:
        log('resolve-error', reason='no-direct-format',
            elapsed=round(time.monotonic() - began, 2))
        return None, 'Нет прямого формата со звуком (нужен был бы ffmpeg)'
    entry = _entry_of(info)
    headers = {}
    raw = fmt.get('http_headers')
    if isinstance(raw, dict):
        for key in HEADER_KEYS:
            for got, value in raw.items():
                if str(got).lower() == key.lower() and value:
                    headers[key] = str(value)
                    break
    size = fmt.get('filesize') or fmt.get('filesize_approx')
    data = {
        'page_url': url,
        'quality': quality,
        'title': str(entry.get('title') or 'Видео'),
        'video_id': str(entry.get('id') or ''),
        'format_id': str(fmt.get('format_id') or ''),
        'direct_url': str(fmt.get('url') or ''),
        'direct_host': host_of(fmt.get('url')),
        'headers': headers,
        'expected_size': int(size) if isinstance(size, (int, float)) else None,
    }
    log('resolve-success', format_id=data['format_id'],
        host=data['direct_host'], title=data['title'],
        expected_size=data['expected_size'], headers=len(headers),
        elapsed=round(time.monotonic() - began, 2))
    return data, ''


# =====================================================================
#  ОБВЯЗКА objc_util
# =====================================================================
# Импорт отдельно от всего: на настольном Python его нет, и это не
# авария — это честный ответ «API недоступен».

objc_util = None
OBJC_ERROR = ''
try:
    import objc_util as _objc
    objc_util = _objc
except Exception as _e:
    OBJC_ERROR = repr(_e)

try:
    import ctypes
except Exception:
    ctypes = None


# Состояния NSURLSessionTask.
TASK_STATES = {0: 'running', 1: 'suspended', 2: 'canceling', 3: 'completed'}


def task_state_name(value):
    try:
        return TASK_STATES.get(int(value), 'unknown(%s)' % (value,))
    except Exception:
        return 'unknown'


def objc_available():
    return objc_util is not None and ctypes is not None


def _ns(text):
    return objc_util.ns(str(text))


def _try_set(obj, selector, value):
    """
    Необязательное свойство конфигурации. Разные версии iOS и разные
    сборки Pythonista поддерживают разный набор, поэтому каждое ставится
    отдельно и молча пропускается, если селектора нет.
    """
    try:
        if not obj.respondsToSelector_(objc_util.sel(selector)):
            return False, 'нет селектора'
    except Exception as e:
        return False, repr(e)
    try:
        getattr(obj, selector.replace(':', '_'))(value)
        return True, ''
    except Exception as e:
        return False, repr(e)


# =====================================================================
#  ДЕЛЕГАТ
# =====================================================================
# Каждый колбэк обёрнут в try/except целиком: исключение, выпущенное из
# ObjC-колбэка, роняет процесс, а эксперимент не имеет права ронять
# Pythonista.

_DELEGATE_CLASS = None
_DELEGATE = None
_DELEGATE_NAME = 'NOXBackgroundDelegate'

# Прогресс не пишем на каждый пакет: не чаще раза в 5 секунд или при
# заметном сдвиге процента.
PROGRESS_EVERY = 5.0
PROGRESS_STEP = 2.0
_PROGRESS = {'time': 0.0, 'pct': -1.0}

# Последнее, что сказала система. Читает интерфейс, пишут колбэки.
LAST = {
    'received': 0,
    'expected': 0,
    'task_id': None,
    'state': None,
    'stage': '',
    'error': '',
    'final_path': '',
    'callbacks': 0,
    'updated': 0.0,
}
_LAST_LOCK = threading.Lock()


def last_snapshot():
    with _LAST_LOCK:
        return dict(LAST)


def _note(**fields):
    with _LAST_LOCK:
        LAST.update(fields)
        LAST['updated'] = time.time()
        LAST['callbacks'] = int(LAST.get('callbacks') or 0) + 1


def _move_finished_file(location, video_id):
    """
    Перенос из временного места системы в NOX_Data/BG_TEST.

    Сделать это надо ПРЯМО в колбэке: как только didFinishDownloadingToURL
    возвращает управление, iOS удаляет временный файл. Порядок попыток:
    NSFileManager, затем обычный shutil — разделы у контейнера приложения
    и у папки проекта могут быть разными.
    """
    ensure_dirs()
    name = 'BG_TEST_%s.mp4' % (safe_name(video_id or 'video', 40),)
    dest = os.path.join(BG_MEDIA_DIR, name)
    src = ''
    try:
        src = str(location.path())
    except Exception as e:
        log('file-error', stage='path', exc=repr(e))
        return ''
    # Старый результат не мешает новому и не подменяет его молча.
    try:
        if os.path.exists(dest):
            os.remove(dest)
    except Exception:
        pass
    try:
        fm = objc_util.ObjCClass('NSFileManager').defaultManager()
        dest_url = objc_util.ObjCClass('NSURL').fileURLWithPath_(_ns(dest))
        if fm.moveItemAtURL_toURL_error_(location, dest_url, None):
            log('file-moved', how='NSFileManager', path=dest,
                size=_size_of(dest))
            return dest
    except Exception as e:
        log('file-error', stage='NSFileManager', exc=repr(e))
    try:
        shutil.copyfile(src, dest)
        log('file-moved', how='shutil.copyfile', path=dest,
            size=_size_of(dest))
        return dest
    except Exception as e:
        log('file-error', stage='shutil', exc=repr(e), src=src)
    return ''


def _size_of(path):
    try:
        return int(os.path.getsize(path))
    except Exception:
        return 0


def _build_delegate_class():
    """Регистрирует ObjC-класс делегата. Ровно один раз на процесс."""
    global _DELEGATE_CLASS
    if _DELEGATE_CLASS is not None:
        return _DELEGATE_CLASS

    def URLSession_downloadTask_didWriteData_totalBytesWritten_totalBytesExpectedToWrite_(
            _self, _cmd, session, task, written, total_written, total_expected):
        try:
            got = int(total_written)
            expect = int(total_expected)
            tid = None
            try:
                tid = int(objc_util.ObjCInstance(task).taskIdentifier())
            except Exception:
                pass
            _note(received=got, expected=expect, task_id=tid,
                  stage='downloading')
            now = time.monotonic()
            pct = (got * 100.0 / expect) if expect > 0 else -1.0
            if now - _PROGRESS['time'] >= PROGRESS_EVERY or \
                    (pct >= 0 and abs(pct - _PROGRESS['pct']) >= PROGRESS_STEP):
                _PROGRESS['time'] = now
                _PROGRESS['pct'] = pct
                log('task-progress', task_id=tid, received=got,
                    expected=expect, pct=round(pct, 2) if pct >= 0 else None)
        except Exception:
            pass

    def URLSession_downloadTask_didFinishDownloadingToURL_(
            _self, _cmd, session, task, location):
        try:
            loc = objc_util.ObjCInstance(location)
            tid = None
            try:
                tid = int(objc_util.ObjCInstance(task).taskIdentifier())
            except Exception:
                pass
            log('task-complete', task_id=tid, tmp=str(loc.path()))
            path = _move_finished_file(loc, state().get('video_id'))
            _note(stage='finished', final_path=path, task_id=tid)
            if path:
                state()['final_path'] = path
                save_state()
        except Exception:
            pass

    def URLSession_task_didCompleteWithError_(_self, _cmd, session, task, err):
        try:
            tid = None
            st = None
            try:
                t = objc_util.ObjCInstance(task)
                tid = int(t.taskIdentifier())
                st = int(t.state())
            except Exception:
                pass
            text = ''
            if err:
                try:
                    text = str(objc_util.ObjCInstance(err).localizedDescription())
                except Exception:
                    text = 'ошибка без описания'
            if text:
                log('task-error', task_id=tid, state=st, error=text)
                _note(stage='error', error=text, task_id=tid, state=st)
                state()['last_error'] = text
                save_state()
            else:
                log('task-complete-ok', task_id=tid, state=st)
                _note(stage='finished', task_id=tid, state=st)
        except Exception:
            pass

    def URLSessionDidFinishEventsForBackgroundURLSession_(_self, _cmd, session):
        try:
            log('session-finished-events')
            _note(stage='session-events-done')
        except Exception:
            pass

    def URLSession_downloadTask_didResumeAtOffset_expectedTotalBytes_(
            _self, _cmd, session, task, offset, expected):
        try:
            log('task-resume', offset=int(offset), expected=int(expected))
            _note(stage='downloading', received=int(offset),
                  expected=int(expected))
        except Exception:
            pass

    methods = [
        URLSession_downloadTask_didWriteData_totalBytesWritten_totalBytesExpectedToWrite_,
        URLSession_downloadTask_didFinishDownloadingToURL_,
        URLSession_task_didCompleteWithError_,
        URLSessionDidFinishEventsForBackgroundURLSession_,
        URLSession_downloadTask_didResumeAtOffset_expectedTotalBytes_,
    ]
    # Кодировки обязательны: у didWriteData три аргумента int64_t, и без
    # явного описания мост принял бы их за указатели на объекты.
    encodings = {
        methods[0]: b'v@:@@qqq',
        methods[1]: b'v@:@@@',
        methods[2]: b'v@:@@@',
        methods[3]: b'v@:@',
        methods[4]: b'v@:@@qq',
    }
    for fn, enc in encodings.items():
        fn.encoding = enc
    try:
        _DELEGATE_CLASS = objc_util.create_objc_class(
            _DELEGATE_NAME,
            objc_util.ObjCClass('NSObject'),
            methods=methods,
            protocols=['NSURLSessionDelegate', 'NSURLSessionTaskDelegate',
                       'NSURLSessionDownloadDelegate'])
    except Exception as e:
        # Класс уже зарегистрирован (повторный запуск скрипта в живом
        # интерпретаторе) — берём существующий.
        try:
            _DELEGATE_CLASS = objc_util.ObjCClass(_DELEGATE_NAME)
        except Exception:
            raise e
    return _DELEGATE_CLASS


def delegate():
    global _DELEGATE
    if _DELEGATE is None:
        _DELEGATE = _build_delegate_class().alloc().init()
    return _DELEGATE


# =====================================================================
#  СЕССИЯ
# =====================================================================
_SESSION = None
SESSION_ERROR = ''
CONFIG_REPORT = {}
# Блоки completion-handler держим за ссылку: сборщик мусора Python не
# должен убрать блок раньше, чем его позовёт система.
_BLOCKS = []


def session_error():
    if not objc_available():
        return 'objc_util недоступен: %s' % (OBJC_ERROR or 'нет модуля',)
    return SESSION_ERROR


def open_session():
    """
    Фоновая сессия с ПОСТОЯННЫМ identifier.

    Второй раз в одном процессе такую сессию создавать нельзя, поэтому
    объект кэшируется. При повторном ЗАПУСКЕ скрипта сессия создаётся
    заново с тем же identifier — и именно так iOS возвращает задачи,
    которые продолжали качаться, пока Python не работал.

    Возвращает (session, error). session=None — API недоступен, и никакой
    подмены обычной загрузкой не происходит: эксперимент честно
    заканчивается отрицательным ответом.
    """
    global _SESSION, SESSION_ERROR
    if _SESSION is not None:
        return _SESSION, ''
    if not objc_available():
        SESSION_ERROR = 'objc_util недоступен: %s' % (OBJC_ERROR or 'нет модуля',)
        log('session-error', reason='no-objc', exc=OBJC_ERROR)
        return None, SESSION_ERROR
    try:
        NSURLSessionConfiguration = objc_util.ObjCClass(
            'NSURLSessionConfiguration')
        cfg = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier_(
            _ns(BG_SESSION_ID))
        if cfg is None:
            raise RuntimeError('backgroundSessionConfigurationWithIdentifier: '
                               'вернул nil')
    except Exception as e:
        SESSION_ERROR = repr(e)
        log('session-error', stage='configuration', exc=repr(e))
        return None, SESSION_ERROR
    # Необязательные свойства — каждое отдельно и безопасно.
    for selector, value in (
            ('setAllowsCellularAccess:', True),
            ('setDiscretionary:', False),
            ('setSessionSendsLaunchEvents:', True),
            ('setWaitsForConnectivity:', True)):
        ok, why = _try_set(cfg, selector, value)
        CONFIG_REPORT[selector] = 'ok' if ok else why
    try:
        NSURLSession = objc_util.ObjCClass('NSURLSession')
        _SESSION = NSURLSession.sessionWithConfiguration_delegate_delegateQueue_(
            cfg, delegate(), None)
        if _SESSION is None:
            raise RuntimeError('sessionWithConfiguration: вернул nil')
    except Exception as e:
        SESSION_ERROR = repr(e)
        log('session-error', stage='session', exc=repr(e))
        _SESSION = None
        return None, SESSION_ERROR
    SESSION_ERROR = ''
    log('session-created', identifier=BG_SESSION_ID, config=str(CONFIG_REPORT))
    return _SESSION, ''


def _task_snapshot(task):
    """Чистый Python-словарь: ссылки на ObjC наружу не отдаём."""
    snap = {'task_id': None, 'state': None, 'state_name': '',
            'received': 0, 'expected': 0, 'url_host': ''}
    for key, call in (('task_id', 'taskIdentifier'),
                      ('state', 'state'),
                      ('received', 'countOfBytesReceived'),
                      ('expected', 'countOfBytesExpectedToReceive')):
        try:
            snap[key] = int(getattr(task, call)())
        except Exception:
            pass
    snap['state_name'] = task_state_name(snap['state'])
    try:
        snap['url_host'] = host_of(
            str(task.originalRequest().URL().absoluteString()))
    except Exception:
        pass
    return snap


def all_tasks(timeout=6.0):
    """
    Настоящие задачи живой NSURLSession — источник правды.

    getAllTasksWithCompletionHandler: асинхронный, его блок выполняется
    на очереди сессии. Ждём результат событием с ограниченным таймаутом:
    это ожидание ответа системы, а не свой цикл передачи.
    """
    sess, err = open_session()
    if sess is None:
        return [], err
    box = {'tasks': [], 'error': ''}
    done = threading.Event()

    def handler(_cmd, arr_ptr):
        try:
            arr = objc_util.ObjCInstance(arr_ptr)
            count = int(arr.count()) if arr else 0
            for i in range(count):
                box['tasks'].append(_task_snapshot(arr.objectAtIndex_(i)))
        except Exception as e:
            box['error'] = repr(e)
        finally:
            done.set()

    try:
        block = objc_util.ObjCBlock(
            handler, restype=None,
            argtypes=[ctypes.c_void_p, ctypes.c_void_p])
        _BLOCKS.append(block)
        del _BLOCKS[:-8]
        sess.getAllTasksWithCompletionHandler_(block)
    except Exception as e:
        log('session-error', stage='getAllTasks', exc=repr(e))
        return [], repr(e)
    if not done.wait(timeout):
        return [], 'система не ответила за %.0f c' % timeout
    return box['tasks'], box['error']


def current_task():
    """
    Задача этого эксперимента среди живых. Если сохранённый
    taskIdentifier не нашёлся, но задача в сессии ровно одна — считаем
    её нашей: сессия своя и посторонних задач в ней быть не может.
    """
    tasks, err = all_tasks()
    if err and not tasks:
        return None, err
    want = state().get('task_identifier')
    for snap in tasks:
        if want is not None and snap.get('task_id') == want:
            return snap, ''
    if len(tasks) == 1:
        return tasks[0], ''
    if tasks:
        return tasks[0], ''
    return None, ''


def start_download(data):
    """
    NSMutableURLRequest -> NSURLSessionDownloadTask -> resume().

    Именно downloadTask, а не dataTask: только он умеет писать в файл
    силами системы и переживать приостановку процесса. После resume()
    Python в передаче не участвует.
    """
    sess, err = open_session()
    if sess is None:
        return None, err or 'Фоновая сессия недоступна'
    direct = str(data.get('direct_url') or '')
    if not direct:
        return None, 'Прямой адрес пуст'
    try:
        NSMutableURLRequest = objc_util.ObjCClass('NSMutableURLRequest')
        req = NSMutableURLRequest.requestWithURL_(objc_util.nsurl(direct))
        req.setHTTPMethod_(_ns('GET'))
        sent = []
        for key, value in (data.get('headers') or {}).items():
            try:
                req.setValue_forHTTPHeaderField_(_ns(value), _ns(key))
                sent.append(key)
            except Exception as e:
                log('header-skip', header=str(key), exc=repr(e))
    except Exception as e:
        log('task-error', stage='request', exc=repr(e))
        return None, 'Не удалось собрать запрос: %r' % (e,)
    try:
        task = sess.downloadTaskWithRequest_(req)
        if task is None:
            raise RuntimeError('downloadTaskWithRequest: вернул nil')
        tid = int(task.taskIdentifier())
        task.resume()
    except Exception as e:
        log('task-error', stage='resume', exc=repr(e))
        return None, 'Не удалось создать задачу: %r' % (e,)
    log('task-created', task_id=tid, host=data.get('direct_host'),
        format_id=data.get('format_id'), headers=','.join(sent),
        expected_size=data.get('expected_size'))
    _note(task_id=tid, stage='downloading', received=0,
          expected=int(data.get('expected_size') or 0), error='',
          final_path='')
    st = state()
    st.update({k: data.get(k) for k in
               ('page_url', 'quality', 'title', 'video_id', 'format_id',
                'direct_url', 'direct_host', 'headers', 'expected_size')})
    st['task_identifier'] = tid
    st['started_at'] = time.time()
    st['final_path'] = ''
    st['last_error'] = ''
    save_state()
    return tid, ''


def cancel_all():
    """Отмена задач ЭТОЙ сессии. Чужого здесь нет по построению."""
    sess, err = open_session()
    if sess is None:
        return 0, err
    box = {'n': 0, 'error': ''}
    done = threading.Event()

    def handler(_cmd, arr_ptr):
        try:
            arr = objc_util.ObjCInstance(arr_ptr)
            count = int(arr.count()) if arr else 0
            for i in range(count):
                try:
                    arr.objectAtIndex_(i).cancel()
                    box['n'] += 1
                except Exception:
                    pass
        except Exception as e:
            box['error'] = repr(e)
        finally:
            done.set()

    try:
        block = objc_util.ObjCBlock(
            handler, restype=None,
            argtypes=[ctypes.c_void_p, ctypes.c_void_p])
        _BLOCKS.append(block)
        sess.getAllTasksWithCompletionHandler_(block)
        done.wait(6.0)
    except Exception as e:
        return 0, repr(e)
    log('test-cancel', cancelled=box['n'])
    _note(stage='cancelled')
    return box['n'], box['error']


# =====================================================================
#  ЗАМЕРЫ TEST A / B / C
# =====================================================================

def mark_before(name):
    """Точка ДО сворачивания: время и байты по данным самой системы."""
    snap, err = current_task()
    mark = {
        'time_before': time.time(),
        'bytes_before': int(snap.get('received') or 0) if snap else 0,
        'expected_before': int(snap.get('expected') or 0) if snap else 0,
        'task_state_before': snap.get('state_name') if snap else 'нет задачи',
        'task_id': snap.get('task_id') if snap else None,
        'error_before': err,
    }
    state()['marks'][name] = mark
    state()['current_test'] = name
    save_state()
    log('mark-background', test=name, bytes_before=mark['bytes_before'],
        task_state=mark['task_state_before'], task_id=mark['task_id'])
    return mark


def mark_after(name):
    """
    Точка ПОСЛЕ возвращения. Байты спрашиваем у NSURLSessionTask, а не
    берём то, что Python запомнил перед сворачиванием: пока процесс был
    приостановлен, колбэки могли не приходить вовсе, и старое значение
    не говорит вообще ни о чём.
    """
    mark = state()['marks'].get(name)
    if not mark:
        return None, 'Точка «до» не зафиксирована'
    snap, err = current_task()
    after = int(snap.get('received') or 0) if snap else 0
    finished = os.path.exists(state().get('final_path') or '')
    if snap is None and finished:
        # Задача уже завершилась и ушла из сессии — это тоже прогресс,
        # и притом максимальный.
        after = _size_of(state()['final_path'])
    mark.update({
        'time_after': time.time(),
        'bytes_after': after,
        'expected_after': int(snap.get('expected') or 0) if snap else
                          mark.get('expected_before') or 0,
        'task_state_after': snap.get('state_name') if snap else
                            ('завершена, файл на диске' if finished
                             else 'задача не найдена'),
        'error_after': err,
    })
    mark['delta_bytes'] = mark['bytes_after'] - mark['bytes_before']
    mark['elapsed'] = round(mark['time_after'] - mark['time_before'], 1)
    save_state()
    log('check-return', test=name, bytes_after=mark['bytes_after'],
        delta=mark['delta_bytes'], elapsed=mark['elapsed'],
        task_state=mark['task_state_after'])
    return mark, ''


def verdict(mark):
    """
    Вывод ТОЛЬКО о факте: прибавились байты или нет.

    Причину отсутствия прогресса здесь не называют. Ноль может значить
    что угодно — от приостановки задачи системой до пропавшей сети, — и
    выдумывать объяснение эксперименту незачем.
    """
    if not mark or 'delta_bytes' not in mark:
        return 'Замер не завершён'
    delta = int(mark['delta_bytes'])
    if delta > 0:
        return ('ФОНОВАЯ ПЕРЕДАЧА ПОДТВЕРЖДЕНА\n+%s за %s с'
                % (fmt_mb(delta), mark.get('elapsed')))
    return ('ФОНОВЫЙ ПРОГРЕСС НЕ ОБНАРУЖЕН\nбыло %s\nстало %s\nза %s с'
            % (fmt_mb(mark['bytes_before']), fmt_mb(mark['bytes_after']),
               mark.get('elapsed')))


def fmt_mb(n):
    try:
        return '%.1f MB' % (float(n) / (1024.0 * 1024.0),)
    except Exception:
        return '?'


def pct_of(received, expected):
    try:
        if expected and expected > 0:
            return max(0.0, min(100.0, received * 100.0 / expected))
    except Exception:
        pass
    return None


# =====================================================================
#  ОТЧЁТ
# =====================================================================

def environment():
    env = {'python': sys.version.split()[0], 'platform': sys.platform}
    try:
        import platform
        env['platform_release'] = platform.platform()
    except Exception:
        pass
    try:
        env['objc'] = 'есть' if objc_available() else ('нет: %s' % OBJC_ERROR)
    except Exception:
        pass
    if objc_available():
        for key, call in (('ios', 'systemVersion'), ('device', 'model')):
            try:
                dev = objc_util.ObjCClass('UIDevice').currentDevice()
                env[key] = str(getattr(dev, call)())
            except Exception:
                pass
    try:
        env['yt_dlp'] = YTDLP_VERSION or ('нет: %s' % YTDLP_ERROR)
    except Exception:
        pass
    return env


def build_report():
    """Человекочитаемый отчёт целиком, для кнопки «Скопировать отчёт»."""
    st = state()
    snap, err = (None, '')
    try:
        snap, err = current_task()
    except Exception as e:
        err = repr(e)
    last = last_snapshot()
    lines = ['NOX BACKGROUND TEST REPORT',
             '=' * 46,
             time.strftime('%Y-%m-%d %H:%M:%S'),
             '']
    env = environment()
    for key in ('ios', 'device', 'python', 'platform_release', 'objc',
                'yt_dlp'):
        if env.get(key):
            lines.append('%-16s %s' % (key + ':', env[key]))
    lines += ['',
              'session identifier: %s' % BG_SESSION_ID,
              'task type:          NSURLSessionDownloadTask',
              'configuration:      backgroundSessionConfigurationWithIdentifier:']
    for key, value in sorted(CONFIG_REPORT.items()):
        lines.append('  %-32s %s' % (key, value))
    if session_error():
        lines.append('SESSION ERROR:      %s' % session_error())
    lines += ['',
              'page url host:      %s' % host_of(st.get('page_url')),
              'title:              %s' % (st.get('title') or '-'),
              'video id:           %s' % (st.get('video_id') or '-'),
              'format:             %s' % (st.get('format_id') or '-'),
              'direct url host:    %s' % (st.get('direct_host') or '-'),
              'headers sent:       %s' % (', '.join(
                  sorted(st.get('headers') or {})) or 'нет'),
              'expected size:      %s' % (
                  fmt_mb(st['expected_size']) if st.get('expected_size')
                  else 'неизвестен'),
              'saved task id:      %s' % (st.get('task_identifier'),),
              '']
    lines.append('ПОСЛЕДНЕЕ СОСТОЯНИЕ NSURLSessionTask')
    if snap:
        pct = pct_of(snap['received'], snap['expected'])
        lines += ['  task id:          %s' % snap['task_id'],
                  '  state:            %s (%s)' % (snap['state_name'],
                                                   snap['state']),
                  '  received:         %s' % fmt_mb(snap['received']),
                  '  expected:         %s' % (fmt_mb(snap['expected'])
                                              if snap['expected'] > 0
                                              else 'неизвестно'),
                  '  percent:          %s' % ('%.1f%%' % pct if pct is not None
                                              else '-')]
    else:
        lines.append('  задача в сессии не найдена%s'
                     % (' (%s)' % err if err else ''))
    lines += ['', 'ПОСЛЕДНЕЕ, ЧТО СООБЩИЛ ДЕЛЕГАТ',
              '  stage:            %s' % (last.get('stage') or '-'),
              '  callbacks:        %s' % last.get('callbacks'),
              '  received:         %s' % fmt_mb(last.get('received')),
              '  error:            %s' % (last.get('error') or '-'),
              '']
    lines.append('ТЕСТЫ')
    for name, caption in (('A', 'свёрнута на Home Screen 2-3 мин'),
                          ('B', 'экран заблокирован ~5 мин'),
                          ('C', 'другое приложение 10-15 мин')):
        mark = (st.get('marks') or {}).get(name)
        lines.append('')
        lines.append('  TEST %s  (%s)' % (name, caption))
        if not mark:
            lines.append('    не проводился')
            continue
        lines.append('    before:  %s   state=%s'
                     % (fmt_mb(mark.get('bytes_before')),
                        mark.get('task_state_before')))
        if 'bytes_after' in mark:
            lines.append('    after:   %s   state=%s'
                         % (fmt_mb(mark.get('bytes_after')),
                            mark.get('task_state_after')))
            lines.append('    delta:   %s   elapsed=%s c'
                         % (fmt_mb(mark.get('delta_bytes')),
                            mark.get('elapsed')))
            lines.append('    verdict: %s'
                         % verdict(mark).replace('\n', ' | '))
        else:
            lines.append('    возвращение не отмечено')
    final = st.get('final_path') or ''
    lines += ['', 'ИТОГОВЫЙ ФАЙЛ',
              '  %s' % (final or 'ещё нет'),
              '  %s' % (fmt_mb(_size_of(final)) if final and
                        os.path.exists(final) else '')]
    lines += ['', 'ПОСЛЕДНИЕ СОБЫТИЯ']
    for rec in recent(25):
        extra = ' '.join('%s=%s' % (k, v) for k, v in sorted(rec.items())
                         if k not in ('t', 'kind', 'thread'))
        lines.append('  %s  %-22s %s'
                     % (time.strftime('%H:%M:%S', time.localtime(rec['t'])),
                        rec['kind'], extra))
    lines += ['',
              'ВНИМАНИЕ: во время фонового теста Pythonista нельзя смахивать',
              'из App Switcher и нельзя нажимать Stop. Проверяется',
              'suspension/background, а не force quit пользователем.',
              '']
    return '\n'.join(lines)

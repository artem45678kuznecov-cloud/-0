# coding: utf-8
"""
NOX / ЭКСПЕРИМЕНТ: поэтапный probe фоновой NSURLSession.

Этот модуль НЕ участвует в работе NOX. Он ничего не импортирует из
NOX.py, nox_core.py, nox_download.py, nox_player.py, nox_ui.py и
nox_debug.py, не трогает их состояние и не пишет в их файлы. Единственное
общее с приложением — папка проекта и лежащий в ней пакет yt_dlp.

ЗАЧЕМ ПЕРЕПИСАНО. Прошлая версия на реальном iPhone завершала Pythonista
нативно сразу после Run, до появления интерфейса и без единого Python
traceback. Причина такого поведения по определению не видна изнутри
Python: процесс умирает целиком. Значит, надо не угадывать, а
локализовать — по одному мосту за нажатие.

ПРАВИЛА ЭТОГО МОДУЛЯ.

1. При импорте не выполняется НИ ОДНОГО нативного вызова. Даже
   `import objc_util` отложен: он делается только на первом этапе, и то
   под маркером. Пока человек не нажал кнопку, ObjC не трогается.

2. Каждый опасный вызов обёрнут парой отметок в NOX_Data/nox_bg_boot.json
   с flush + os.fsync. Если процесс умрёт между `before` и `after`,
   следующий запуск прочитает файл и назовёт этап, на котором это
   случилось. Это не доказательство причины — это точная последняя
   нативная операция.

3. Делегат получает колбэки на NSOperationQueue.mainQueue(), а не на
   собственной фоновой очереди сессии. Python-объект, который зовут с
   произвольного нативного потока, — заведомо худший вариант для
   Pythonista. Скорость тут не важна: файл всё равно качает система.

4. Никакого threading.Event.wait вокруг ObjCBlock. getAllTasks полностью
   асинхронный: вызов возвращает управление сразу, результат кладётся в
   память, интерфейс подхватывает его своим тактом.

5. Подмены эксперимента обычной загрузкой нет и быть не может: ни
   urllib, ни requests, ни своего цикла чтения. yt-dlp вызывается ровно
   один раз и только ради прямого адреса.
"""

import os
import io
import re
import sys
import json
import time
import shutil
import threading
import traceback


# =====================================================================
#  ПУТИ
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

# Всё, что пишет эксперимент. Ни одного файла основного NOX здесь нет.
BG_BOOT_PATH = os.path.join(DATA_DIR, 'nox_bg_boot.json')
BG_STATE_PATH = os.path.join(DATA_DIR, 'nox_bg_state.json')
BG_LOG_PATH = os.path.join(DATA_DIR, 'nox_bg_test.jsonl')
BG_MEDIA_DIR = os.path.join(DATA_DIR, 'BG_TEST')

BG_SESSION_ID = 'com.nox.pythonista.bgtest.v1'

LOG_LIMIT = 512 * 1024
LOG_TAIL = 400
BOOT_HISTORY = 24

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
#  ЭТАПЫ
# =====================================================================
# Порядок ровно тот, в котором их нажимает человек. Ни один этап не
# запускает следующий сам.

STAGES = [
    ('api', '1. Проверить Objective-C API'),
    ('configuration', '2. Создать background configuration'),
    ('delegate', '3. Создать delegate class'),
    ('session', '4. Создать NSURLSession'),
    ('get-all-tasks', '5. Проверить getAllTasks'),
    ('resolve', '6. Разобрать VK-ссылку'),
    ('create-task', '7. Создать download task'),
    ('resume', '8. Resume task'),
]

STAGE_TITLES = {
    'import-objc': 'IMPORT OBJC_UTIL',
    'api': 'CHECK OBJECTIVE-C API',
    'configuration': 'CREATE BACKGROUND CONFIGURATION',
    'delegate': 'CREATE DELEGATE CLASS',
    'session': 'CREATE NSURLSESSION',
    'get-all-tasks': 'GET ALL TASKS (вызов)',
    'get-all-tasks-completion': 'GET ALL TASKS (completion block)',
    'resolve': 'RESOLVE VK URL',
    'create-task': 'CREATE DOWNLOAD TASK',
    'resume': 'RESUME TASK',
    'restore': 'RESTORE PREVIOUS SESSION',
    'restore-completion': 'RESTORE (completion block)',
    'cancel': 'CANCEL TASK',
    'invalidate': 'INVALIDATE SESSION',
}


def stage_title(stage):
    return STAGE_TITLES.get(stage, str(stage or '?').upper())


# =====================================================================
#  BOOT MARKER
# =====================================================================
# Крошечный файл, который переживает нативное завершение процесса.
# Пишется синхронно: write -> flush -> os.fsync. Без fsync запись могла
# бы остаться в буфере ядра и пропасть ровно тогда, когда она нужна.

_BOOT_LOCK = threading.RLock()
_BOOT_SEQ = [0]
_BOOT_HISTORY = []


def _write_boot(record):
    """
    Строку СНАЧАЛА собираем, и только потом открываем файл.

    Наоборот делать нельзя: открытие на 'w' обрезает файл, и сбой
    сериализации оставил бы вместо маркера пустоту — ровно в тот момент,
    когда маркер и нужен.
    """
    try:
        text = json.dumps(record, ensure_ascii=False)
    except Exception:
        try:
            text = json.dumps({'stage': str(record.get('stage')),
                               'phase': str(record.get('phase')),
                               'timestamp': record.get('timestamp')})
        except Exception:
            return False
    try:
        ensure_dirs()
        with io.open(BG_BOOT_PATH, 'w', encoding='utf-8') as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        return True
    except Exception:
        return False


def boot_mark(stage, phase, **extra):
    """Одна отметка. phase: 'before' | 'after' | 'failed'."""
    with _BOOT_LOCK:
        _BOOT_SEQ[0] += 1
        rec = {'stage': str(stage), 'phase': str(phase),
               'timestamp': round(time.time(), 3),
               'seq': _BOOT_SEQ[0],
               'thread': threading.current_thread().name}
        for key, value in extra.items():
            if isinstance(value, (str, int, float, bool)) or value is None:
                rec[key] = value
            else:
                rec[key] = repr(value)
        # В историю кладём КОПИЮ: если положить сам rec, а потом
        # приписать ему history, получится ссылка на самого себя, и
        # json.dumps молча провалится вместе со всем маркером.
        _BOOT_HISTORY.append(dict(rec))
        del _BOOT_HISTORY[:-BOOT_HISTORY]
        payload = dict(rec)
        payload['history'] = [dict(r) for r in _BOOT_HISTORY]
        _write_boot(payload)
        return rec


def boot_before(stage, **extra):
    return boot_mark(stage, 'before', **extra)


def boot_after(stage, **extra):
    return boot_mark(stage, 'after', **extra)


def boot_failed(stage, error):
    """Python-исключение: этап закрыт, процесс жив, причина известна."""
    return boot_mark(stage, 'failed', error=str(error)[:400])


def read_boot():
    """Что осталось в файле от прошлого запуска. Ошибку разбора терпим."""
    try:
        with io.open(BG_BOOT_PATH, encoding='utf-8') as f:
            raw = f.read()
    except Exception:
        return None
    try:
        data = json.loads(raw)
        return data if isinstance(data, dict) else {'raw': raw[:400]}
    except Exception:
        return {'raw': raw[:400], 'broken': True}


def pending_stage(boot=None):
    """
    Незавершённый этап прошлого запуска.

    'before' без 'after' — процесс не вернулся из нативного вызова. Это
    НЕ доказательство того, что виноват именно он, но это точная
    последняя нативная операция, которую мы успели записать на диск.
    """
    data = read_boot() if boot is None else boot
    if not isinstance(data, dict):
        return None
    if data.get('phase') == 'before':
        return data.get('stage')
    return None


def clear_boot():
    try:
        os.remove(BG_BOOT_PATH)
    except Exception:
        pass
    with _BOOT_LOCK:
        del _BOOT_HISTORY[:]


# =====================================================================
#  ЖУРНАЛ
# =====================================================================
_LOG_LOCK = threading.RLock()
_LOG_TAIL = []


def log(kind, **fields):
    """Событие в NOX_Data/nox_bg_test.jsonl и в память. Никогда не падает."""
    rec = {'t': round(time.time(), 3), 'kind': str(kind),
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
            ensure_dirs()
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
#  СОСТОЯНИЕ
# =====================================================================
# RLock, а не Lock: save_state() зовёт state() уже под замком.
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
            _STATE['session_id'] = BG_SESSION_ID
        return _STATE


def save_state():
    try:
        ensure_dirs()
        with _STATE_LOCK:
            data = json.dumps(state(), ensure_ascii=False, indent=1)
        tmp = BG_STATE_PATH + '.tmp'
        with io.open(tmp, 'w', encoding='utf-8') as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
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
#  РЕЗУЛЬТАТЫ ЭТАПОВ
# =====================================================================
# Что произошло на каждом этапе: пройден, упал с Python-исключением, ещё
# не запускался. Интерфейс читает только это.

_RESULTS_LOCK = threading.RLock()
RESULTS = {}


def stage_result(stage):
    with _RESULTS_LOCK:
        return dict(RESULTS.get(stage) or {})


def all_results():
    with _RESULTS_LOCK:
        return {k: dict(v) for k, v in RESULTS.items()}


def _set_result(stage, ok, text='', error='', tb=''):
    with _RESULTS_LOCK:
        RESULTS[stage] = {'ok': bool(ok), 'text': str(text),
                          'error': str(error), 'traceback': str(tb),
                          'time': time.time()}
    return dict(RESULTS[stage])


def _fail(stage, exc):
    """Python-исключение: тип, repr и traceback целиком — в интерфейс."""
    tb = traceback.format_exc()
    boot_failed(stage, repr(exc))
    log('stage-error', stage=stage, exc=repr(exc),
        exc_type=type(exc).__name__)
    return _set_result(stage, False,
                       text='%s: %r' % (type(exc).__name__, exc),
                       error=repr(exc), tb=tb)


# =====================================================================
#  ЛЕНИВЫЙ objc_util
# =====================================================================
# Модуль НЕ импортируется при загрузке этого файла. Импорт — тоже
# нативная операция (загрузка расширения), и он тоже под маркером.

objc_util = None
ctypes = None
OBJC_ERROR = ''


def objc_ready():
    return objc_util is not None and ctypes is not None


def load_objc():
    """Импорт objc_util. Только по нажатию первого этапа."""
    global objc_util, ctypes, OBJC_ERROR
    if objc_ready():
        return True, ''
    boot_before('import-objc')
    try:
        import ctypes as _ct
        import objc_util as _objc
    except Exception as e:
        OBJC_ERROR = repr(e)
        boot_failed('import-objc', repr(e))
        return False, OBJC_ERROR
    boot_after('import-objc')
    objc_util = _objc
    ctypes = _ct
    OBJC_ERROR = ''
    return True, ''


def _ns(text):
    return objc_util.ns(str(text))


def _responds(obj, selector):
    try:
        return bool(obj.respondsToSelector_(objc_util.sel(selector)))
    except Exception:
        return False


def _try_set(obj, selector, value):
    """Необязательное свойство конфигурации: нет селектора — пропускаем."""
    if not _responds(obj, selector):
        return False, 'нет селектора'
    try:
        getattr(obj, selector.replace(':', '_'))(value)
        return True, ''
    except Exception as e:
        return False, repr(e)


# =====================================================================
#  СИЛЬНЫЕ ССЫЛКИ
# =====================================================================
# Всё, что создано нативно, живёт здесь до конца процесса. Сборщик
# мусора Python не должен освободить объект, которым ещё пользуется iOS.

_CONFIG = None
_DELEGATE_CLASS = None
_DELEGATE = None
_SESSION = None
_TASK = None
_BLOCKS = []          # ObjCBlock-и completion-handler'ов, навсегда

CONFIG_REPORT = {}
API_REPORT = {}
DEVICE = {}

_DELEGATE_NAME = 'NOXBackgroundDelegate'


def have(stage):
    """Готов ли объект соответствующего этапа."""
    return {'configuration': _CONFIG is not None,
            'delegate': _DELEGATE is not None,
            'session': _SESSION is not None,
            'task': _TASK is not None}.get(stage, False)


# =====================================================================
#  ЭТАП 1: ТОЛЬКО ПРОВЕРКА API
# =====================================================================

PROBE_CLASSES = ('NSURLSession', 'NSURLSessionConfiguration',
                 'NSOperationQueue', 'NSObject', 'NSMutableURLRequest',
                 'NSFileManager', 'NSURL')

# Селекторы, которые нам понадобятся дальше. Здесь они только
# проверяются на существование — ничего не создаётся и не вызывается.
PROBE_SELECTORS = (
    ('NSURLSessionConfiguration',
     'backgroundSessionConfigurationWithIdentifier:'),
    ('NSURLSession', 'sessionWithConfiguration:delegate:delegateQueue:'),
    ('NSOperationQueue', 'mainQueue'),
)


def probe_api():
    """
    Только чтение таблицы классов и проверка селекторов.

    Ничего не создаётся: ни configuration, ни delegate, ни session, ни
    block, ни task. Если Pythonista умирает уже здесь — дело не в
    NSURLSession вообще.
    """
    stage = 'api'
    boot_before(stage)
    try:
        ok, err = load_objc()
        if not ok:
            boot_after(stage, result='no-objc')
            return _set_result(stage, False,
                               text='objc_util не импортируется',
                               error=err)
        lines = []
        API_REPORT.clear()
        for name in PROBE_CLASSES:
            try:
                cls = objc_util.ObjCClass(name)
                got = cls is not None
            except Exception as e:
                got = False
                API_REPORT[name] = repr(e)
            if got:
                API_REPORT[name] = 'есть'
            lines.append('%-28s %s' % (name, API_REPORT[name]))
        for cls_name, selector in PROBE_SELECTORS:
            key = '%s %s' % (cls_name, selector)
            try:
                cls = objc_util.ObjCClass(cls_name)
                # Классовые селекторы ищем у самого класса.
                got = _responds(cls, selector)
                API_REPORT[key] = 'есть' if got else 'НЕТ'
            except Exception as e:
                API_REPORT[key] = repr(e)
            lines.append('%-28s %s' % (selector, API_REPORT[key]))
        DEVICE.clear()
        for key, call in (('ios', 'systemVersion'), ('device', 'model')):
            try:
                dev = objc_util.ObjCClass('UIDevice').currentDevice()
                DEVICE[key] = str(getattr(dev, call)())
            except Exception:
                pass
        if DEVICE:
            lines.append('%-28s %s / %s' % ('устройство',
                                            DEVICE.get('device', '?'),
                                            DEVICE.get('ios', '?')))
        boot_after(stage)
        log('probe-api', **{k: v for k, v in API_REPORT.items()
                            if ' ' not in k})
        missing = [k for k, v in API_REPORT.items() if v not in ('есть',)]
        return _set_result(stage, not missing, text='\n'.join(lines),
                           error=('нет: %s' % ', '.join(missing))
                           if missing else '')
    except Exception as e:
        return _fail(stage, e)


# =====================================================================
#  ЭТАП 2: ТОЛЬКО CONFIGURATION
# =====================================================================

def probe_configuration():
    """
    Ровно один вызов: backgroundSessionConfigurationWithIdentifier:.

    Ни делегата, ни сессии, ни блока, ни задачи. Если процесс умирает
    здесь, дальше идти незачем: этот путь в Pythonista закрыт.
    """
    global _CONFIG
    stage = 'configuration'
    if not objc_ready():
        return _set_result(stage, False,
                           text='Сначала этап 1: objc_util не загружен')
    boot_before(stage, identifier=BG_SESSION_ID)
    try:
        cls = objc_util.ObjCClass('NSURLSessionConfiguration')
        cfg = cls.backgroundSessionConfigurationWithIdentifier_(
            _ns(BG_SESSION_ID))
        boot_after(stage)
    except Exception as e:
        return _fail(stage, e)
    if cfg is None:
        return _set_result(stage, False,
                           text='backgroundSessionConfigurationWithIdentifier:'
                                ' вернул nil')
    _CONFIG = cfg
    # Необязательные свойства ставятся по одному и после того, как сам
    # объект уже создан: так падение на свойстве отличимо от падения на
    # создании конфигурации.
    CONFIG_REPORT.clear()
    for selector, value in (('setAllowsCellularAccess:', True),
                            ('setDiscretionary:', False),
                            ('setSessionSendsLaunchEvents:', True),
                            ('setWaitsForConnectivity:', True)):
        boot_before('configuration', property=selector)
        ok, why = _try_set(cfg, selector, value)
        boot_after('configuration', property=selector)
        CONFIG_REPORT[selector] = 'ok' if ok else why
    log('session-config-created', identifier=BG_SESSION_ID,
        config=str(CONFIG_REPORT))
    lines = ['identifier: %s' % BG_SESSION_ID, 'объект: получен']
    lines += ['%-32s %s' % (k, v) for k, v in sorted(CONFIG_REPORT.items())]
    return _set_result(stage, True, text='\n'.join(lines))


# =====================================================================
#  ЭТАП 3: ТОЛЬКО DELEGATE CLASS
# =====================================================================
# objc_util выводит кодировку сама только для случая «все аргументы —
# объекты». У трёх наших селекторов есть int64_t, поэтому кодировка для
# них задана вручную. Каждая сверена с сигнатурой Apple:
#
# - (void)URLSession:(NSURLSession *)s
#        downloadTask:(NSURLSessionDownloadTask *)t
#        didWriteData:(int64_t)bytesWritten
#   totalBytesWritten:(int64_t)totalBytesWritten
# totalBytesExpectedToWrite:(int64_t)totalBytesExpectedToWrite;   v@:@@qqq
#
# - (void)URLSession:(NSURLSession *)s
#        downloadTask:(NSURLSessionDownloadTask *)t
# didFinishDownloadingToURL:(NSURL *)location;                    v@:@@@
#
# - (void)URLSession:(NSURLSession *)s
#                task:(NSURLSessionTask *)t
# didCompleteWithError:(NSError *)error;                          v@:@@@
#
# - (void)URLSessionDidFinishEventsForBackgroundURLSession:
#                     (NSURLSession *)s;                          v@:@
#
# - (void)URLSession:(NSURLSession *)s
#        downloadTask:(NSURLSessionDownloadTask *)t
#    didResumeAtOffset:(int64_t)fileOffset
#   expectedTotalBytes:(int64_t)expectedTotalBytes;               v@:@@qq

PROGRESS_EVERY = 5.0
PROGRESS_STEP = 2.0
_PROGRESS = {'time': 0.0, 'pct': -1.0}

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
_LAST_LOCK = threading.RLock()


def last_snapshot():
    with _LAST_LOCK:
        return dict(LAST)


def _note(**fields):
    with _LAST_LOCK:
        LAST.update(fields)
        LAST['updated'] = time.time()
        LAST['callbacks'] = int(LAST.get('callbacks') or 0) + 1


def _size_of(path):
    try:
        return int(os.path.getsize(path))
    except Exception:
        return 0


def _move_finished_file(location, video_id):
    """
    Перенос из временного места системы в NOX_Data/BG_TEST.

    Делается ПРЯМО в колбэке: как только didFinishDownloadingToURL
    вернёт управление, iOS удалит временный файл.
    """
    ensure_dirs()
    name = 'BG_TEST_%s.mp4' % (safe_name(video_id or 'video', 40),)
    dest = os.path.join(BG_MEDIA_DIR, name)
    try:
        src = str(location.path())
    except Exception as e:
        log('file-error', stage='path', exc=repr(e))
        return ''
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


def _build_delegate_class():
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
            log('task-resume-offset', offset=int(offset),
                expected=int(expected))
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
    for fn, enc in zip(methods, (b'v@:@@qqq', b'v@:@@@', b'v@:@@@',
                                 b'v@:@', b'v@:@@qq')):
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
        # интерпретаторе) — берём существующий, а не падаем.
        try:
            _DELEGATE_CLASS = objc_util.ObjCClass(_DELEGATE_NAME)
        except Exception:
            raise e
    return _DELEGATE_CLASS


def probe_delegate():
    """
    Только регистрация ObjC-класса и один alloc/init.

    NSURLSession здесь НЕ создаётся. Если процесс умирает тут — виноват
    мост create_objc_class, а не сессия.
    """
    global _DELEGATE
    stage = 'delegate'
    if not objc_ready():
        return _set_result(stage, False,
                           text='Сначала этап 1: objc_util не загружен')
    boot_before(stage, name=_DELEGATE_NAME)
    try:
        cls = _build_delegate_class()
        boot_after(stage, part='class')
    except Exception as e:
        return _fail(stage, e)
    boot_before(stage, part='alloc-init')
    try:
        _DELEGATE = cls.alloc().init()
        boot_after(stage, part='alloc-init')
    except Exception as e:
        return _fail(stage, e)
    log('delegate-created', name=_DELEGATE_NAME)
    lines = ['класс: %s' % _DELEGATE_NAME,
             'экземпляр: создан',
             'селекторы и кодировки:']
    for selector, enc in (
            ('URLSession:downloadTask:didWriteData:'
             'totalBytesWritten:totalBytesExpectedToWrite:', 'v@:@@qqq'),
            ('URLSession:downloadTask:didFinishDownloadingToURL:', 'v@:@@@'),
            ('URLSession:task:didCompleteWithError:', 'v@:@@@'),
            ('URLSessionDidFinishEventsForBackgroundURLSession:', 'v@:@'),
            ('URLSession:downloadTask:didResumeAtOffset:'
             'expectedTotalBytes:', 'v@:@@qq')):
        got = _responds(_DELEGATE, selector)
        lines.append('  %-8s %s  %s' % (enc, 'есть' if got else 'НЕТ',
                                        selector))
    return _set_result(stage, True, text='\n'.join(lines))


# =====================================================================
#  ЭТАП 4: ТОЛЬКО NSURLSESSION
# =====================================================================

def probe_session():
    """
    sessionWithConfiguration:delegate:delegateQueue: и больше ничего.

    delegateQueue — ГЛАВНАЯ очередь, а не nil. С nil система создаёт
    свою фоновую NSOperationQueue и зовёт Python-объект с произвольного
    нативного потока; для Pythonista это заведомо худший вариант. Файл
    всё равно качает система, так что производительность тут не при чём.

    Ни getAllTasks, ни downloadTaskWithRequest:, ни resume здесь нет.
    """
    global _SESSION
    stage = 'session'
    if not objc_ready():
        return _set_result(stage, False,
                           text='Сначала этап 1: objc_util не загружен')
    if _CONFIG is None:
        return _set_result(stage, False, text='Сначала этап 2: нет configuration')
    if _DELEGATE is None:
        return _set_result(stage, False, text='Сначала этап 3: нет delegate')
    boot_before(stage, queue='mainQueue')
    try:
        queue = objc_util.ObjCClass('NSOperationQueue').mainQueue()
        session = objc_util.ObjCClass(
            'NSURLSession').sessionWithConfiguration_delegate_delegateQueue_(
                _CONFIG, _DELEGATE, queue)
        boot_after(stage)
    except Exception as e:
        return _fail(stage, e)
    if session is None:
        return _set_result(stage, False,
                           text='sessionWithConfiguration:... вернул nil')
    _SESSION = session
    log('session-created', identifier=BG_SESSION_ID, queue='mainQueue')
    return _set_result(stage, True,
                       text='\n'.join([
                           'session: создана и удерживается ссылкой',
                           'identifier: %s' % BG_SESSION_ID,
                           'delegateQueue: NSOperationQueue.mainQueue()',
                           '',
                           'Подождите 10 секунд. Если Pythonista жива —',
                           'этап пройден.']))


# =====================================================================
#  ЭТАП 5: getAllTasks, ПОЛНОСТЬЮ АСИНХРОННО
# =====================================================================
# Ни Event.wait, ни while, ни sleep. Вызов возвращает управление сразу,
# результат кладётся в TASKS, интерфейс подхватывает его своим тактом.

_TASKS_LOCK = threading.RLock()
TASKS = {'items': [], 'error': '', 'time': 0.0, 'pending': False,
         'stage': ''}


def tasks_snapshot():
    with _TASKS_LOCK:
        data = dict(TASKS)
        data['items'] = [dict(t) for t in TASKS['items']]
        return data


def _task_snapshot(task):
    """Только простые значения: ссылки на ObjC наружу не отдаём."""
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


TASK_STATES = {0: 'running', 1: 'suspended', 2: 'canceling', 3: 'completed'}


def task_state_name(value):
    try:
        return TASK_STATES.get(int(value), 'unknown(%s)' % (value,))
    except Exception:
        return 'unknown'


def probe_get_all_tasks(stage='get-all-tasks', on_done=None):
    """
    Асинхронный запрос живых задач сессии.

    Возвращает управление СРАЗУ. Когда придёт completion, он положит
    результат в TASKS, закроет маркер этапа и, если задан, вызовет
    on_done(items, error) — простыми значениями, без ObjC.

    ObjCBlock в Pythonista экспериментален, поэтому этап отдельный: если
    процесс умрёт здесь, в boot-файле останется именно он.
    """
    completion = stage + '-completion'
    if not objc_ready():
        return _set_result(stage, False,
                           text='Сначала этап 1: objc_util не загружен')
    if _SESSION is None:
        return _set_result(stage, False, text='Сначала этап 4: нет session')
    with _TASKS_LOCK:
        TASKS['pending'] = True
        TASKS['stage'] = stage

    def handler(_cmd, arr_ptr):
        items, error = [], ''
        try:
            arr = objc_util.ObjCInstance(arr_ptr)
            count = int(arr.count()) if arr else 0
            for i in range(count):
                items.append(_task_snapshot(arr.objectAtIndex_(i)))
        except Exception as e:
            error = repr(e)
        try:
            with _TASKS_LOCK:
                TASKS['items'] = items
                TASKS['error'] = error
                TASKS['time'] = time.time()
                TASKS['pending'] = False
            boot_after(completion, tasks=len(items), error=error or None)
            log('tasks-listed', count=len(items), error=error or None,
                stage=stage)
            _set_result(stage, not error,
                        text=_tasks_text(items, error), error=error)
        except Exception:
            pass
        # ObjCBlock НЕ освобождаем здесь: он держится в _BLOCKS до конца
        # процесса. Освобождение изнутри собственного колбэка — прямой
        # путь к use-after-free.
        try:
            if on_done is not None:
                on_done(items, error)
        except Exception:
            pass

    boot_before(stage)
    try:
        block = objc_util.ObjCBlock(
            handler, restype=None,
            argtypes=[ctypes.c_void_p, ctypes.c_void_p])
        _BLOCKS.append(block)
        boot_after(stage, part='block-created')
    except Exception as e:
        with _TASKS_LOCK:
            TASKS['pending'] = False
        return _fail(stage, e)
    # Маркер ожидания: вызов вернулся, completion ещё не пришёл.
    boot_before(completion)
    try:
        _SESSION.getAllTasksWithCompletionHandler_(block)
    except Exception as e:
        with _TASKS_LOCK:
            TASKS['pending'] = False
        return _fail(completion, e)
    return _set_result(stage, True,
                       text='Запрос отправлен. Ответ придёт на главную '
                            'очередь.\nЖдём completion...')


def _tasks_text(items, error):
    if error:
        return 'Ошибка completion: %s' % error
    if not items:
        return 'Задач в сессии нет.\n(Это «не обнаружено», а не вывод о причине.)'
    lines = ['Задач: %d' % len(items)]
    for t in items:
        lines.append('  #%s  %s  %s / %s  %s'
                     % (t['task_id'], t['state_name'],
                        fmt_mb(t['received']),
                        fmt_mb(t['expected']) if t['expected'] else '?',
                        t['url_host']))
    return '\n'.join(lines)


def known_task():
    """Наша задача среди последнего асинхронного среза. Без нативных вызовов."""
    snap = tasks_snapshot()
    items = snap['items']
    want = state().get('task_identifier')
    for t in items:
        if want is not None and t.get('task_id') == want:
            return t
    return items[0] if len(items) >= 1 else None


# =====================================================================
#  ЭТАП 6: yt-dlp
# =====================================================================
_YTDLP = None
YTDLP_ERROR = ''
YTDLP_VERSION = ''

RESOLVE_SOCKET_TIMEOUT = 12
NO_FFMPEG_PATH = os.path.join(DATA_DIR, 'no-ffmpeg-here')

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
HEADER_KEYS = ('User-Agent', 'Referer', 'Origin', 'Cookie',
               'Accept', 'Accept-Language')


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


RESOLVED = {}


def probe_resolve(page_url, quality):
    """
    ОДИН extract_info(url, download=False) и выбор прямого формата.

    Никакой background session здесь ещё не трогается. Ни ffmpeg, ни
    postprocessor, ни download=True. Дальше Python видео не читает: адрес
    уходит в NSURLSessionDownloadTask.
    """
    stage = 'resolve'
    url = str(page_url or '').strip()
    if not URL_RE.match(url):
        return _set_result(stage, False, text='Это не похоже на ссылку')
    if quality not in VK_DIRECT:
        quality = '480'
    mod = load_yt_dlp()
    if mod is None:
        return _set_result(stage, False,
                           text=YTDLP_ERROR or 'yt-dlp недоступен')
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
    boot_before(stage, host=host_of(url))
    log('resolve-start', url=url, quality=quality, host=host_of(url))
    began = time.monotonic()
    try:
        with mod.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        boot_after(stage)
    except Exception as e:
        log('resolve-error', exc=repr(e),
            elapsed=round(time.monotonic() - began, 2))
        return _fail(stage, e)
    fmt = pick_direct_format(info, quality)
    if not fmt:
        log('resolve-error', reason='no-direct-format')
        return _set_result(stage, False,
                           text='Нет прямого формата со звуком '
                                '(нужен был бы ffmpeg)')
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
    RESOLVED.clear()
    RESOLVED.update(data)
    st = state()
    st.update(data)
    save_state()
    log('resolve-success', format_id=data['format_id'],
        host=data['direct_host'], title=data['title'],
        expected_size=data['expected_size'], headers=len(headers),
        elapsed=round(time.monotonic() - began, 2))
    return _set_result(stage, True, text='\n'.join([
        'название      %s' % data['title'],
        'video_id      %s' % data['video_id'],
        'format_id     %s' % data['format_id'],
        'direct host   %s' % data['direct_host'],
        'expected      %s' % (fmt_mb(data['expected_size'])
                              if data['expected_size'] else 'неизвестен'),
        'headers       %s' % (', '.join(sorted(headers)) or 'нет'),
    ]))


# =====================================================================
#  ЭТАП 7: DOWNLOAD TASK БЕЗ RESUME
# =====================================================================

def probe_create_task():
    """
    NSMutableURLRequest + downloadTaskWithRequest:. resume() НЕ зовётся.

    Именно downloadTask, а не dataTask: только он умеет писать в файл
    силами системы и переживать приостановку процесса.
    """
    global _TASK
    stage = 'create-task'
    if not objc_ready():
        return _set_result(stage, False,
                           text='Сначала этап 1: objc_util не загружен')
    if _SESSION is None:
        return _set_result(stage, False, text='Сначала этап 4: нет session')
    data = dict(RESOLVED) or dict(state())
    direct = str(data.get('direct_url') or '')
    if not direct:
        return _set_result(stage, False,
                           text='Сначала этап 6: прямого адреса нет')
    boot_before(stage, part='request', host=data.get('direct_host'))
    try:
        req = objc_util.ObjCClass('NSMutableURLRequest').requestWithURL_(
            objc_util.nsurl(direct))
        req.setHTTPMethod_(_ns('GET'))
        sent = []
        for key, value in (data.get('headers') or {}).items():
            try:
                req.setValue_forHTTPHeaderField_(_ns(value), _ns(key))
                sent.append(key)
            except Exception as e:
                log('header-skip', header=str(key), exc=repr(e))
        boot_after(stage, part='request')
    except Exception as e:
        return _fail(stage, e)
    boot_before(stage, part='download-task')
    try:
        task = _SESSION.downloadTaskWithRequest_(req)
        boot_after(stage, part='download-task')
    except Exception as e:
        return _fail(stage, e)
    if task is None:
        return _set_result(stage, False,
                           text='downloadTaskWithRequest: вернул nil')
    _TASK = task
    boot_before(stage, part='read-identifier')
    try:
        tid = int(task.taskIdentifier())
        st_value = int(task.state())
        boot_after(stage, part='read-identifier')
    except Exception as e:
        return _fail(stage, e)
    st = state()
    st['task_identifier'] = tid
    st['started_at'] = None
    st['final_path'] = ''
    st['last_error'] = ''
    save_state()
    _note(task_id=tid, stage='created', received=0,
          expected=int(data.get('expected_size') or 0), error='',
          final_path='')
    log('task-created', task_id=tid, host=data.get('direct_host'),
        format_id=data.get('format_id'), headers=','.join(sent),
        expected_size=data.get('expected_size'), resumed=False)
    return _set_result(stage, True, text='\n'.join([
        'taskIdentifier  %s' % tid,
        'state           %s (%s)' % (task_state_name(st_value), st_value),
        'заголовки       %s' % (', '.join(sent) or 'нет'),
        '',
        'resume() ещё НЕ вызван. Это отдельная кнопка 8.']))


# =====================================================================
#  ЭТАП 8: RESUME
# =====================================================================

def probe_resume():
    """Только task.resume(). Больше ничего."""
    stage = 'resume'
    if _TASK is None:
        return _set_result(stage, False, text='Сначала этап 7: нет task')
    boot_before(stage)
    try:
        _TASK.resume()
        boot_after(stage)
    except Exception as e:
        return _fail(stage, e)
    st = state()
    st['started_at'] = time.time()
    save_state()
    _note(stage='downloading')
    log('task-resume', task_id=st.get('task_identifier'))
    return _set_result(stage, True, text='\n'.join([
        'resume() выполнен.',
        'Дальше файл качает iOS, а не Python.',
        '',
        'Нажимайте «5. Проверить getAllTasks», чтобы увидеть,',
        'сколько байт задача получила по данным самой системы.']))


# =====================================================================
#  ВОССТАНОВЛЕНИЕ ПРОШЛОЙ СЕССИИ (тоже этапами)
# =====================================================================

def probe_restore(on_done=None):
    """
    Поднять ТУ ЖЕ сессию и спросить систему о живых задачах.

    Автоматически при запуске это не делается: пока стабильность не
    доказана, любой нативный вызов на старте лишает нас информации.
    Требует уже пройденных этапов 2-4 — цепочку человек нажимает сам.
    """
    stage = 'restore'
    if _SESSION is None:
        return _set_result(stage, False,
                           text='Сначала этапы 1-4: сессия не создана.\n'
                                'Восстановление — это та же сессия с тем же\n'
                                'identifier, но собирается она по шагам.')
    return probe_get_all_tasks(stage=stage, on_done=on_done)


# =====================================================================
#  ОТМЕНА И СБРОС
# =====================================================================

def probe_cancel():
    """Отмена нашей задачи. Отдельный этап со своим маркером."""
    stage = 'cancel'
    if _TASK is None:
        return _set_result(stage, False, text='Задачи нет')
    boot_before(stage)
    try:
        _TASK.cancel()
        boot_after(stage)
    except Exception as e:
        return _fail(stage, e)
    log('test-cancel', task_id=state().get('task_identifier'))
    _note(stage='cancelled')
    return _set_result(stage, True, text='cancel() выполнен.')


def reset_experiment(invalidate=True):
    """
    Сброс эксперимента.

    Трогает ТОЛЬКО свои файлы: nox_bg_boot.json, nox_bg_state.json,
    nox_bg_test.jsonl и папку BG_TEST. Ни NoxMedia, ни state.json, ни
    download_jobs, ни один файл основного NOX не затрагиваются.

    invalidate: звать ли invalidateAndCancel у сессии. Это нативный
    вызов, поэтому он под своим маркером и его можно не делать.
    """
    global _TASK
    done = []
    if invalidate and _SESSION is not None:
        boot_before('invalidate')
        try:
            _SESSION.invalidateAndCancel()
            boot_after('invalidate')
            done.append('session invalidateAndCancel')
        except Exception as e:
            boot_failed('invalidate', repr(e))
            done.append('invalidate не удался: %r' % (e,))
    _TASK = None
    for path in (BG_BOOT_PATH, BG_STATE_PATH, BG_LOG_PATH,
                 BG_LOG_PATH + '.previous'):
        try:
            if os.path.exists(path):
                os.remove(path)
                done.append('удалён %s' % os.path.basename(path))
        except Exception as e:
            done.append('не удалён %s: %r' % (os.path.basename(path), e))
    try:
        for name in os.listdir(BG_MEDIA_DIR):
            if name.startswith('BG_TEST_'):
                os.remove(os.path.join(BG_MEDIA_DIR, name))
                done.append('удалён %s' % name)
    except Exception:
        pass
    with _BOOT_LOCK:
        del _BOOT_HISTORY[:]
    with _TASKS_LOCK:
        TASKS['items'] = []
        TASKS['error'] = ''
        TASKS['pending'] = False
    with _RESULTS_LOCK:
        RESULTS.clear()
    with _LAST_LOCK:
        LAST.update({'received': 0, 'expected': 0, 'task_id': None,
                     'state': None, 'stage': '', 'error': '',
                     'final_path': '', 'callbacks': 0})
    RESOLVED.clear()
    reset_state()
    return done


# =====================================================================
#  ЗАМЕРЫ TEST A / B / C
# =====================================================================
# Байты берутся из последнего АСИНХРОННОГО среза NSURLSessionTask, а не
# из того, что Python запомнил перед сворачиванием: во время
# приостановки колбэки не приходят вовсе, и запомненное значение не
# говорит ни о чём.

def mark_before(name):
    snap = known_task()
    got = tasks_snapshot()
    mark = {
        'time_before': time.time(),
        'bytes_before': int(snap.get('received') or 0) if snap else 0,
        'expected_before': int(snap.get('expected') or 0) if snap else 0,
        'task_state_before': snap.get('state_name') if snap else 'нет задачи',
        'task_id': snap.get('task_id') if snap else None,
        'snapshot_age_before': round(time.time() - (got['time'] or 0), 1)
                               if got['time'] else None,
    }
    state()['marks'][name] = mark
    state()['current_test'] = name
    save_state()
    log('mark-background', test=name, bytes_before=mark['bytes_before'],
        task_state=mark['task_state_before'], task_id=mark['task_id'])
    return mark


def mark_after(name):
    mark = state()['marks'].get(name)
    if not mark:
        return None, 'Точка «до» не зафиксирована'
    snap = known_task()
    got = tasks_snapshot()
    after = int(snap.get('received') or 0) if snap else 0
    final = state().get('final_path') or ''
    finished = bool(final) and os.path.exists(final)
    if snap is None and finished:
        # Задача завершилась и ушла из сессии — это тоже прогресс, и
        # притом максимальный.
        after = _size_of(final)
    mark.update({
        'time_after': time.time(),
        'bytes_after': after,
        'expected_after': int(snap.get('expected') or 0) if snap else
                          mark.get('expected_before') or 0,
        'task_state_after': snap.get('state_name') if snap else
                            ('завершена, файл на диске' if finished
                             else 'задача не найдена'),
        'snapshot_age_after': round(time.time() - (got['time'] or 0), 1)
                              if got['time'] else None,
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
    что угодно, и выдумывать объяснение эксперименту незачем.
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

def environment(include_native=False):
    """
    Окружение. По умолчанию БЕЗ единого нативного вызова: сведения об
    устройстве берутся из того, что уже собрал этап 1.
    """
    env = {'python': sys.version.split()[0], 'platform': sys.platform}
    try:
        import platform
        env['platform_release'] = platform.platform()
    except Exception:
        pass
    env['objc'] = ('загружен' if objc_ready()
                   else ('НЕ загружен: %s' % (OBJC_ERROR or 'этап 1 не пройден')))
    env['yt_dlp'] = YTDLP_VERSION or ('не загружен: %s' % YTDLP_ERROR
                                      if YTDLP_ERROR else 'не загружен')
    if DEVICE:
        env.update(DEVICE)
    return env


def build_report():
    """Человекочитаемый отчёт целиком. Ни одного нативного вызова."""
    st = state()
    last = last_snapshot()
    got = tasks_snapshot()
    boot = read_boot()
    pending = pending_stage(boot)
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
              'configuration:      backgroundSessionConfigurationWithIdentifier:',
              'delegateQueue:      NSOperationQueue.mainQueue()',
              '']
    lines.append('ЭТАПЫ')
    results = all_results()
    for stage, caption in STAGES:
        res = results.get(stage) or {}
        if not res:
            mark = 'не запускался'
        elif res.get('ok'):
            mark = 'пройден'
        else:
            mark = 'ОШИБКА: %s' % (res.get('error') or res.get('text') or '?')
        lines.append('  %-34s %s' % (caption, mark))
    if CONFIG_REPORT:
        lines += ['', 'СВОЙСТВА КОНФИГУРАЦИИ']
        for key, value in sorted(CONFIG_REPORT.items()):
            lines.append('  %-32s %s' % (key, value))
    lines += ['', 'BOOT MARKER']
    if not boot:
        lines.append('  файла нет (эксперимент ещё не запускал native)')
    else:
        lines.append('  последняя отметка: %s / %s'
                     % (boot.get('stage'), boot.get('phase')))
        if pending:
            lines.append('  ВОЗМОЖНОЕ НАТИВНОЕ ЗАВЕРШЕНИЕ НА ЭТАПЕ %s'
                         % stage_title(pending))
            lines.append('  (это последняя записанная native-операция,')
            lines.append('   а не доказанная причина)')
        for rec in (boot.get('history') or [])[-12:]:
            lines.append('    %s  %-28s %s'
                         % (time.strftime('%H:%M:%S',
                                          time.localtime(rec.get('timestamp')
                                                         or 0)),
                            rec.get('stage'), rec.get('phase')))
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
    lines.append('ПОСЛЕДНИЙ СРЕЗ NSURLSessionTask')
    if not got['time']:
        lines.append('  ещё не запрашивался (кнопка 5)')
    else:
        lines.append('  получен %s назад'
                     % ('%.0f c' % (time.time() - got['time'])))
        lines.append('  ' + _tasks_text(got['items'],
                                        got['error']).replace('\n', '\n  '))
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
    errors = [(s, r) for s, r in results.items()
              if r and not r.get('ok') and r.get('traceback')]
    if errors:
        lines += ['', 'PYTHON TRACEBACK']
        for stage, res in errors:
            lines.append('  --- %s ---' % stage_title(stage))
            for row in str(res.get('traceback') or '').splitlines():
                lines.append('  ' + row)
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

# coding: utf-8
"""
NOX / PROBE: resumeData у NSURLSessionDownloadTask.

ЭТО НЕ ЧАСТЬ РАБОЧЕГО NOX. Ни nox_download.py, ни nox_native_download.py
этот файл не импортируют и не знают о нём. Его можно удалить, и на
приложении это никак не скажется.

ЗАЧЕМ. Сейчас снять приостановленную нативную задачу с сессии можно
только через task.cancel(), а он уничтожает временный файл системы:
всё, что задача скачала и что ещё не попало в .part, пропадает. Это
известная и записанная цена текущей версии.

Единственный публичный способ сохранить эти байты — resumeData. К нему
ведут ровно две дороги, и обе надо проверить НА УСТРОЙСТВЕ:

  A. cancelByProducingResumeData: — принимает Python-блок. Именно на
     Python-блоке completion (getAllTasks) процесс уже один раз умер
     нативно, поэтому в рабочий путь это не идёт, пока не проверено.

  B. NSError.userInfo[NSURLSessionDownloadTaskResumeData] в обычном
     колбэке didCompleteWithError. БЛОК ЗДЕСЬ НЕ НУЖЕН вовсе — данные
     приходят в тот же делегат, который уже работает на устройстве.
     Если дорога B работает, она предпочтительнее во всех отношениях.

Дальше обе проверяются одинаково: получить resumeData, положить на диск,
создать downloadTaskWithResumeData: и досмотреть, доедет ли файл.

Как и в предыдущем probe, каждый опасный вызов обёрнут отметкой в
NOX_Data/nox_resume_boot.json с flush + os.fsync: если процесс умрёт
внутри вызова, следующий запуск назовёт этап.
"""

import os
import io
import re
import sys
import json
import time
import threading


def _project_dir():
    try:
        return os.path.dirname(os.path.abspath(__file__))
    except Exception:
        return os.path.abspath(os.getcwd())


PROJECT_DIR = _project_dir()
DATA_DIR = os.path.join(PROJECT_DIR, 'NOX_Data')
PROBE_DIR = os.path.join(DATA_DIR, 'RESUME_TEST')
BOOT_PATH = os.path.join(DATA_DIR, 'nox_resume_boot.json')
LOG_PATH = os.path.join(DATA_DIR, 'nox_resume_test.jsonl')
RESUME_PATH = os.path.join(PROBE_DIR, 'resume_data.bin')

# Своя сессия, не та, которой пользуется NOX: probe не должен пересечься
# с рабочими загрузками ни одним объектом.
PROBE_SESSION_ID = 'com.nox.pythonista.resumeprobe.v1'

LOG_LIMIT = 256 * 1024
LOG_TAIL = 200

STAGES = [
    ('api', '1. Проверить Objective-C API'),
    ('session', '2. Создать сессию и делегат'),
    ('task', '3. Создать задачу и скачать кусок'),
    ('cancel-error', '4a. resumeData из NSError (без блока)'),
    ('cancel-block', '4b. cancelByProducingResumeData: (с блоком)'),
    ('restore', '5. downloadTaskWithResumeData: и докачать'),
]

STAGE_TITLES = {
    'import-objc': 'IMPORT OBJC_UTIL',
    'api': 'CHECK API',
    'session': 'CREATE SESSION',
    'task': 'CREATE AND RESUME TASK',
    'cancel-error': 'CANCEL, RESUME DATA FROM NSError',
    'cancel-block': 'cancelByProducingResumeData: (ObjCBlock)',
    'restore': 'downloadTaskWithResumeData:',
}


def ensure_dirs():
    for path in (DATA_DIR, PROBE_DIR):
        try:
            os.makedirs(path, exist_ok=True)
        except Exception:
            pass
    return os.path.isdir(PROBE_DIR)


# =====================================================================
#  BOOT MARKER
# =====================================================================
_BOOT_LOCK = threading.RLock()
_BOOT_HISTORY = []


def _write_boot(record):
    try:
        text = json.dumps(record, ensure_ascii=False)
    except Exception:
        return False
    try:
        ensure_dirs()
        with io.open(BOOT_PATH, 'w', encoding='utf-8') as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        return True
    except Exception:
        return False


def boot_mark(stage, phase, **extra):
    with _BOOT_LOCK:
        rec = {'stage': str(stage), 'phase': str(phase),
               'timestamp': round(time.time(), 3)}
        for key, value in extra.items():
            rec[key] = value if isinstance(
                value, (str, int, float, bool, type(None))) else repr(value)
        _BOOT_HISTORY.append(dict(rec))
        del _BOOT_HISTORY[:-20]
        payload = dict(rec)
        payload['history'] = [dict(r) for r in _BOOT_HISTORY]
        _write_boot(payload)
        return rec


def boot_before(stage, **extra):
    return boot_mark(stage, 'before', **extra)


def boot_after(stage, **extra):
    return boot_mark(stage, 'after', **extra)


def boot_failed(stage, error):
    return boot_mark(stage, 'failed', error=str(error)[:400])


def read_boot():
    try:
        with io.open(BOOT_PATH, encoding='utf-8') as f:
            data = json.loads(f.read())
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def pending_stage(boot=None):
    data = read_boot() if boot is None else boot
    if isinstance(data, dict) and data.get('phase') == 'before':
        return data.get('stage')
    return None


def stage_title(stage):
    return STAGE_TITLES.get(stage, str(stage or '?').upper())


def clear_boot():
    try:
        os.remove(BOOT_PATH)
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
    rec = {'t': round(time.time(), 3), 'kind': str(kind),
           'thread': threading.current_thread().name}
    try:
        for key, value in fields.items():
            rec[key] = value if isinstance(
                value, (str, int, float, bool, type(None))) else repr(value)
    except Exception:
        pass
    try:
        with _LOG_LOCK:
            _LOG_TAIL.append(rec)
            del _LOG_TAIL[:-LOG_TAIL]
            try:
                if os.path.getsize(LOG_PATH) > LOG_LIMIT:
                    os.replace(LOG_PATH, LOG_PATH + '.previous')
            except Exception:
                pass
            ensure_dirs()
            with io.open(LOG_PATH, 'a', encoding='utf-8') as f:
                f.write(json.dumps(rec, ensure_ascii=False) + '\n')
    except Exception:
        pass
    return rec


def recent(limit=30):
    with _LOG_LOCK:
        return list(_LOG_TAIL[-int(limit):])


# =====================================================================
#  ЛЕНИВЫЙ objc_util
# =====================================================================
objc_util = None
ctypes = None
OBJC_ERROR = ''


def objc_ready():
    return objc_util is not None and ctypes is not None


def load_objc():
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
    return True, ''


def _ns(text):
    return objc_util.ns(str(text))


# =====================================================================
#  РЕЗУЛЬТАТЫ ЭТАПОВ
# =====================================================================
RESULTS = {}
STATE = {
    'url': '',
    'received': 0,
    'expected': 0,
    'resume_bytes': 0,
    'resume_source': '',       # 'nserror' | 'block'
    'final_path': '',
    'error': '',
    'restored_received': 0,
    'callbacks': 0,
}
_STATE_LOCK = threading.RLock()

# Сильные ссылки: сборщик мусора Python не должен освободить объект,
# которым ещё пользуется iOS.
_SESSION = None
_DELEGATE = None
_TASK = None
_BLOCKS = []


def note(**fields):
    with _STATE_LOCK:
        STATE.update(fields)
        STATE['callbacks'] = int(STATE.get('callbacks') or 0) + 1


def snapshot():
    with _STATE_LOCK:
        return dict(STATE)


def result(stage, ok, text='', error='', tb=''):
    RESULTS[stage] = {'ok': bool(ok), 'text': str(text), 'error': str(error),
                      'traceback': str(tb), 'time': time.time()}
    return dict(RESULTS[stage])


def stage_result(stage):
    return dict(RESULTS.get(stage) or {})


def _fail(stage, exc):
    import traceback
    tb = traceback.format_exc()
    boot_failed(stage, repr(exc))
    log('stage-error', stage=stage, exc=repr(exc))
    return result(stage, False, '%s: %r' % (type(exc).__name__, exc),
                  repr(exc), tb)


# =====================================================================
#  ЭТАП 1: API
# =====================================================================
NEEDED = ('NSURLSession', 'NSURLSessionConfiguration', 'NSOperationQueue',
          'NSMutableURLRequest', 'NSObject', 'NSData', 'NSFileManager',
          'NSURL')


def probe_api():
    stage = 'api'
    boot_before(stage)
    try:
        ok, err = load_objc()
        if not ok:
            boot_after(stage, result='no-objc')
            return result(stage, False, 'objc_util не импортируется', err)
        lines = []
        missing = []
        for name in NEEDED:
            try:
                got = objc_util.ObjCClass(name) is not None
            except Exception as e:
                got = False
                lines.append('%-28s %r' % (name, e))
            if got:
                lines.append('%-28s есть' % name)
            else:
                missing.append(name)
        # Ключевые селекторы resumeData.
        for cls_name, selector in (
                ('NSURLSession', 'downloadTaskWithResumeData:'),):
            try:
                cls = objc_util.ObjCClass(cls_name)
                got = bool(cls.instancesRespondToSelector_(
                    objc_util.sel(selector)))
            except Exception as e:
                got = False
                lines.append('%-28s %r' % (selector, e))
            lines.append('%-28s %s' % (selector, 'есть' if got else 'НЕТ'))
            if not got:
                missing.append(selector)
        boot_after(stage)
        return result(stage, not missing, '\n'.join(lines),
                      ('нет: %s' % ', '.join(missing)) if missing else '')
    except Exception as e:
        return _fail(stage, e)


# =====================================================================
#  ЭТАП 2: СЕССИЯ И ДЕЛЕГАТ
# =====================================================================
_DELEGATE_NAME = 'NOXResumeProbeDelegate'

# Ключ, под которым Foundation кладёт resumeData в NSError.userInfo.
RESUME_KEY = 'NSURLSessionDownloadTaskResumeData'


def _build_delegate():
    global _DELEGATE

    def URLSession_downloadTask_didWriteData_totalBytesWritten_totalBytesExpectedToWrite_(
            _self, _cmd, session, task, written, total_written, total_expected):
        try:
            note(received=int(total_written),
                 expected=int(total_expected) if int(total_expected) > 0 else 0)
        except Exception:
            pass

    def URLSession_downloadTask_didFinishDownloadingToURL_(
            _self, _cmd, session, task, location):
        try:
            src = str(objc_util.ObjCInstance(location).path())
            dest = os.path.join(PROBE_DIR, 'resume_probe.bin')
            moved = ''
            try:
                fm = objc_util.ObjCClass('NSFileManager').defaultManager()
                url_cls = objc_util.ObjCClass('NSURL')
                if os.path.exists(dest):
                    os.remove(dest)
                if fm.moveItemAtURL_toURL_error_(
                        objc_util.ObjCInstance(location),
                        url_cls.fileURLWithPath_(_ns(dest)), None):
                    moved = dest
            except Exception:
                pass
            if not moved:
                try:
                    os.replace(src, dest)
                    moved = dest
                except Exception:
                    moved = ''
            size = 0
            try:
                size = int(os.path.getsize(moved)) if moved else 0
            except Exception:
                size = 0
            note(final_path=moved, restored_received=size)
            log('probe-finished', path=moved, size=size)
        except Exception:
            pass

    def URLSession_task_didCompleteWithError_(_self, _cmd, session, task, err):
        try:
            text = ''
            saved = 0
            if err:
                e = objc_util.ObjCInstance(err)
                try:
                    text = str(e.localizedDescription())
                except Exception:
                    text = 'ошибка без описания'
                # ДОРОГА B: resumeData прямо в userInfo, без единого блока.
                try:
                    info = e.userInfo()
                    data = info.objectForKey_(_ns(RESUME_KEY)) if info else None
                    if data is not None:
                        saved = _save_resume_data(data, source='nserror')
                except Exception as inner:
                    log('probe-userinfo-error', exc=repr(inner))
            note(error=text)
            log('probe-complete', error=text or None, resume_bytes=saved)
        except Exception:
            pass

    def URLSessionDidFinishEventsForBackgroundURLSession_(_self, _cmd, session):
        try:
            log('probe-session-events')
        except Exception:
            pass

    methods = [
        URLSession_downloadTask_didWriteData_totalBytesWritten_totalBytesExpectedToWrite_,
        URLSession_downloadTask_didFinishDownloadingToURL_,
        URLSession_task_didCompleteWithError_,
        URLSessionDidFinishEventsForBackgroundURLSession_,
    ]
    for fn, enc in zip(methods, (b'v@:@@qqq', b'v@:@@@', b'v@:@@@', b'v@:@')):
        fn.encoding = enc
    try:
        cls = objc_util.create_objc_class(
            _DELEGATE_NAME, objc_util.ObjCClass('NSObject'), methods=methods,
            protocols=['NSURLSessionDelegate', 'NSURLSessionTaskDelegate',
                       'NSURLSessionDownloadDelegate'])
    except Exception:
        cls = objc_util.ObjCClass(_DELEGATE_NAME)
    _DELEGATE = cls.alloc().init()
    return _DELEGATE


def _save_resume_data(data, source):
    """NSData -> файл. Возвращает размер."""
    try:
        ensure_dirs()
        ok = bool(data.writeToFile_atomically_(_ns(RESUME_PATH), True))
        size = int(os.path.getsize(RESUME_PATH)) if ok else 0
    except Exception as e:
        log('probe-resume-save-error', exc=repr(e), source=source)
        return 0
    if size > 0:
        note(resume_bytes=size, resume_source=source)
        log('probe-resume-saved', size=size, source=source)
    return size


def probe_session():
    """Фоновая сессия probe. delegateQueue — только главная очередь."""
    global _SESSION
    stage = 'session'
    if not objc_ready():
        return result(stage, False, 'Сначала этап 1')
    if _SESSION is not None:
        return result(stage, True, 'сессия уже создана')
    boot_before(stage, identifier=PROBE_SESSION_ID)
    try:
        cfg = objc_util.ObjCClass(
            'NSURLSessionConfiguration'
        ).backgroundSessionConfigurationWithIdentifier_(_ns(PROBE_SESSION_ID))
        if cfg is None:
            raise RuntimeError('configuration вернул nil')
        delegate = _build_delegate()
        queue = objc_util.ObjCClass('NSOperationQueue').mainQueue()
        _SESSION = objc_util.ObjCClass(
            'NSURLSession').sessionWithConfiguration_delegate_delegateQueue_(
                cfg, delegate, queue)
        if _SESSION is None:
            raise RuntimeError('session вернул nil')
        boot_after(stage)
    except Exception as e:
        return _fail(stage, e)
    log('probe-session-created', identifier=PROBE_SESSION_ID)
    return result(stage, True, 'сессия и делегат созданы\n'
                               'delegateQueue: mainQueue')


# =====================================================================
#  ЭТАП 3: ЗАДАЧА
# =====================================================================

def probe_task(url):
    """Создать задачу на прямой адрес и качать, пока не наберётся кусок."""
    global _TASK
    stage = 'task'
    if _SESSION is None:
        return result(stage, False, 'Сначала этап 2')
    url = str(url or '').strip()
    if not re.match(r'^https?://', url):
        return result(stage, False, 'Нужен прямой адрес файла (http/https)')
    boot_before(stage)
    try:
        req = objc_util.ObjCClass('NSMutableURLRequest').requestWithURL_(
            objc_util.nsurl(url))
        req.setHTTPMethod_(_ns('GET'))
        _TASK = _SESSION.downloadTaskWithRequest_(req)
        if _TASK is None:
            raise RuntimeError('downloadTaskWithRequest вернул nil')
        tid = int(_TASK.taskIdentifier())
        _TASK.resume()
        boot_after(stage, task_id=tid)
    except Exception as e:
        return _fail(stage, e)
    note(url=url, received=0, expected=0, resume_bytes=0, resume_source='',
         final_path='', error='')
    log('probe-task-created', task_id=tid, url=url)
    return result(stage, True, 'Task #%s запущен.\n'
                               'Дождитесь 20-50 МБ и переходите к 4a.' % tid)


# =====================================================================
#  ЭТАП 4a: resumeData ИЗ NSError — БЕЗ БЛОКА
# =====================================================================

def probe_cancel_via_error():
    """
    Обычный task.cancel(), а resumeData ждём в userInfo ошибки.

    Ни одного Python-блока: данные приходят в тот самый делегат, который
    на устройстве уже работает. Если дорога сработает, она и есть
    правильное решение для паузы в рабочем NOX.
    """
    stage = 'cancel-error'
    if _TASK is None:
        return result(stage, False, 'Сначала этап 3')
    before = snapshot()
    if before['received'] <= 0:
        return result(stage, False, 'Задача ещё не получила ни байта')
    try:
        os.remove(RESUME_PATH)
    except Exception:
        pass
    boot_before(stage, received=before['received'])
    try:
        _TASK.cancel()
        boot_after(stage)
    except Exception as e:
        return _fail(stage, e)
    return result(stage, True, '\n'.join([
        'cancel() выполнен на %s байт.' % before['received'],
        '',
        'Ответ придёт в didCompleteWithError. Подождите пару секунд и',
        'посмотрите строку resume data ниже:',
        '  > 0   — Foundation отдала resumeData БЕЗ блока, это лучший исход;',
        '  = 0   — обычный cancel данных не сохраняет, нужен этап 4b.']))


# =====================================================================
#  ЭТАП 4b: cancelByProducingResumeData: — С БЛОКОМ
# =====================================================================

def probe_cancel_via_block():
    """
    Тот самый опасный вариант, ради которого probe и написан отдельно.

    Python-блок completion уже один раз завершил Pythonista нативно (на
    getAllTasks). Поэтому этап стоит последним, отдельной кнопкой, под
    своим boot-маркером: если процесс умрёт здесь, следующий запуск это
    покажет, и в рабочий NOX эта дорога не пойдёт.
    """
    stage = 'cancel-block'
    if _TASK is None:
        return result(stage, False, 'Сначала этап 3')
    before = snapshot()
    try:
        os.remove(RESUME_PATH)
    except Exception:
        pass

    def handler(_cmd, data_ptr):
        try:
            if data_ptr:
                _save_resume_data(objc_util.ObjCInstance(data_ptr),
                                  source='block')
            else:
                log('probe-resume-empty', source='block')
        except Exception as e:
            log('probe-resume-block-error', exc=repr(e))

    boot_before(stage, received=before['received'], part='block-create')
    try:
        block = objc_util.ObjCBlock(handler, restype=None,
                                    argtypes=[ctypes.c_void_p,
                                              ctypes.c_void_p])
        _BLOCKS.append(block)          # держим до конца процесса
        boot_after(stage, part='block-create')
    except Exception as e:
        return _fail(stage, e)
    boot_before(stage, part='cancel-call')
    try:
        _TASK.cancelByProducingResumeData_(block)
    except Exception as e:
        return _fail(stage, e)
    boot_after(stage, part='cancel-call')
    return result(stage, True, '\n'.join([
        'cancelByProducingResumeData: вызван на %s байт.' % before['received'],
        'Процесс жив — уже это результат.',
        'Через пару секунд посмотрите строку resume data.']))


# =====================================================================
#  ЭТАП 5: ВОССТАНОВЛЕНИЕ
# =====================================================================

def probe_restore():
    """downloadTaskWithResumeData: и досмотреть, доедет ли файл."""
    global _TASK
    stage = 'restore'
    if _SESSION is None:
        return result(stage, False, 'Сначала этап 2')
    if not os.path.exists(RESUME_PATH) or os.path.getsize(RESUME_PATH) <= 0:
        return result(stage, False, 'resumeData нет: этапы 4a/4b не дали данных')
    boot_before(stage, size=os.path.getsize(RESUME_PATH))
    try:
        data = objc_util.ObjCClass('NSData').dataWithContentsOfFile_(
            _ns(RESUME_PATH))
        if data is None:
            raise RuntimeError('NSData не прочиталась')
        _TASK = _SESSION.downloadTaskWithResumeData_(data)
        if _TASK is None:
            raise RuntimeError('downloadTaskWithResumeData вернул nil')
        tid = int(_TASK.taskIdentifier())
        _TASK.resume()
        boot_after(stage, task_id=tid)
    except Exception as e:
        return _fail(stage, e)
    log('probe-restored', task_id=tid, resume_bytes=snapshot()['resume_bytes'])
    return result(stage, True, '\n'.join([
        'Задача #%s создана из resumeData и запущена.' % tid,
        'Смотрите, растёт ли счётчик и доедет ли файл.']))


def reset_probe():
    """Убрать за собой. Рабочего NOX это не касается вообще."""
    global _SESSION, _TASK
    done = []
    if _TASK is not None:
        try:
            _TASK.cancel()
            done.append('task cancel')
        except Exception:
            pass
        _TASK = None
    if _SESSION is not None:
        try:
            _SESSION.invalidateAndCancel()
            done.append('session invalidateAndCancel')
        except Exception:
            pass
        _SESSION = None
    for path in (BOOT_PATH, LOG_PATH, LOG_PATH + '.previous', RESUME_PATH):
        try:
            if os.path.exists(path):
                os.remove(path)
                done.append('удалён %s' % os.path.basename(path))
        except Exception:
            pass
    try:
        for name in os.listdir(PROBE_DIR):
            os.remove(os.path.join(PROBE_DIR, name))
    except Exception:
        pass
    RESULTS.clear()
    with _STATE_LOCK:
        STATE.update({'url': '', 'received': 0, 'expected': 0,
                      'resume_bytes': 0, 'resume_source': '',
                      'final_path': '', 'error': '', 'restored_received': 0,
                      'callbacks': 0})
    clear_boot()
    return done


# =====================================================================
#  ОТЧЁТ
# =====================================================================

def fmt_mb(n):
    try:
        return '%.1f MB' % (float(n) / (1024.0 * 1024.0),)
    except Exception:
        return '?'


def build_report():
    st = snapshot()
    boot = read_boot()
    pending = pending_stage(boot)
    lines = ['NOX RESUME DATA PROBE REPORT',
             '=' * 46,
             time.strftime('%Y-%m-%d %H:%M:%S'),
             '',
             'python:            %s' % sys.version.split()[0],
             'session id:        %s' % PROBE_SESSION_ID,
             'delegateQueue:     NSOperationQueue.mainQueue()',
             '']
    lines.append('ЭТАПЫ')
    for stage, caption in STAGES:
        res = RESULTS.get(stage) or {}
        if not res:
            mark = 'не запускался'
        elif res.get('ok'):
            mark = 'пройден'
        else:
            mark = 'ОШИБКА: %s' % (res.get('error') or res.get('text') or '?')
        lines.append('  %-38s %s' % (caption, mark))
    lines += ['', 'BOOT MARKER']
    if not boot:
        lines.append('  файла нет')
    else:
        lines.append('  последняя отметка: %s / %s'
                     % (boot.get('stage'), boot.get('phase')))
        if pending:
            lines.append('  ВОЗМОЖНОЕ НАТИВНОЕ ЗАВЕРШЕНИЕ НА ЭТАПЕ %s'
                         % stage_title(pending))
            lines.append('  (последняя записанная операция, не доказанная')
            lines.append('   причина)')
    lines += ['', 'СОСТОЯНИЕ',
              '  url host:        %s' % _host_of(st['url']),
              '  получено:        %s' % fmt_mb(st['received']),
              '  всего:           %s' % (fmt_mb(st['expected'])
                                         if st['expected'] else 'неизвестно'),
              '  resume data:     %s%s' % (
                  fmt_mb(st['resume_bytes']) if st['resume_bytes']
                  else 'НЕТ',
                  (' (источник: %s)' % st['resume_source'])
                  if st['resume_source'] else ''),
              '  после resume:    %s' % fmt_mb(st['restored_received']),
              '  итоговый файл:   %s' % (st['final_path'] or 'нет'),
              '  ошибка:          %s' % (st['error'] or '-'),
              '  callbacks:       %s' % st['callbacks'],
              '']
    lines.append('ВЫВОД')
    if st['resume_bytes'] and st['resume_source'] == 'nserror':
        lines.append('  resumeData пришла БЕЗ Python-блока, из NSError.')
        lines.append('  Это лучший исход: рабочий NOX сможет отпускать')
        lines.append('  приостановленную задачу, не теряя байты.')
    elif st['resume_bytes'] and st['resume_source'] == 'block':
        lines.append('  resumeData пришла только через ObjCBlock.')
        lines.append('  Значит вопрос упирается в устойчивость блока,')
        lines.append('  и решать его должен отдельный длительный прогон.')
    else:
        lines.append('  resumeData пока не получена. Пока её нет, снятие')
        lines.append('  приостановленной задачи в рабочем NOX означает')
        lines.append('  потерю временных байт — это и записано как')
        lines.append('  известное ограничение.')
    lines += ['', 'ПОСЛЕДНИЕ СОБЫТИЯ']
    for rec in recent(20):
        extra = ' '.join('%s=%s' % (k, v) for k, v in sorted(rec.items())
                         if k not in ('t', 'kind', 'thread'))
        lines.append('  %s  %-22s %s'
                     % (time.strftime('%H:%M:%S', time.localtime(rec['t'])),
                        rec['kind'], extra))
    lines += ['',
              'Это ОТДЕЛЬНЫЙ probe. Рабочий NOX от него не зависит и',
              'ничего из него не импортирует.',
              '']
    return '\n'.join(lines)


def _host_of(url):
    m = re.match(r'[a-zA-Z][\w+.-]*://([^/?#]+)', str(url or ''))
    return m.group(1) if m else '-'

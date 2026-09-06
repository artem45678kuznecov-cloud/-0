# coding: utf-8
"""
NOX / чёрный ящик.

Три роли в одном файле:

    библиотека   — import nox_debug из NOX.py, nox_download.py, nox_ui.py
    журнал       — NOX_Data/nox_diagnostics.jsonl
    просмотрщик  — запустите nox_debug.py в Pythonista и нажмите Run

Главное правило: диагностика НЕ имеет права ухудшить стабильность NOX.
Поэтому здесь нет ни своего потока, ни своего таймера, ни сети, ни
subprocess, а event() из рабочего потока только кладёт маленький словарь
в память. На диск пишет ТОЛЬКО главный поток, вызывая flush() из уже
существующего единственного такта NoxApp._tick.

Просмотрщику не нужны ни nox_ui, ни nox_download, ни nox_player: он
читает уже записанные файлы и запускается сам по себе, даже если
остальное приложение сломано.
"""

import io
import os
import gc
import sys
import json
import time
import random
import platform
import threading
import traceback
from collections import deque

# Событий держим в памяти ровно столько: очередь с maxlen сама вытесняет
# старые, поэтому 10 000 вызовов event() занимают столько же места,
# сколько 400. Расти бесконечно она не может по определению.
MAX_EVENTS = 400

# Журнал крупнее этого ротируется. Максимум на диске — два таких файла.
MAX_LOG_SIZE = 1024 * 1024

# Как часто главный поток вообще касается диска и снимает состояние.
FLUSH_INTERVAL = 1.0
HEARTBEAT_INTERVAL = 1.0

# Длина строкового значения в записи. Огромные info dict сюда не попадают.
MAX_VALUE_LEN = 400

# Пути считаются от папки с этим файлом — ровно как в nox_core, но без
# импорта: просмотрщик обязан открываться, даже если остальные модули
# не грузятся. Путей контейнера Pythonista здесь нет.
_HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(_HERE, 'NOX_Data')
LOG_PATH = os.path.join(DATA_DIR, 'nox_diagnostics.jsonl')
PREV_LOG_PATH = os.path.join(DATA_DIR, 'nox_diagnostics.previous.jsonl')
SESSION_PATH = os.path.join(DATA_DIR, 'nox_last_session.json')

_LOCK = threading.RLock()
_EVENTS = deque(maxlen=MAX_EVENTS)
_PENDING = deque(maxlen=MAX_EVENTS)      # ещё не записанные на диск

_STATE = {
    'session_id': '',
    'started_at': None,
    'last_heartbeat': None,
    'clean_exit': False,
    'previous_session_unclean': False,
    'previous_session': None,
    'last_event': None,
    'last_exception': None,
    'jobs': [],
    'pool': {},
    'threads': [],
    'process_info': {},
}

_MARKS = {'flush': 0.0, 'heartbeat': 0.0, 'state_dirty': True}

# job_id -> момент начала блокирующего разбора ссылки.
_STARTED = {}


# ---------------------------------------------------------------------
#  Мелочи
# ---------------------------------------------------------------------
def _now():
    return time.time()


def _stamp(ts=None):
    try:
        return time.strftime('%H:%M:%S', time.localtime(ts or _now()))
    except Exception:
        return '--:--:--'


def _plain(value):
    """
    В запись попадают только простые значения. Всё остальное — repr с
    обрезкой: огромный info dict в журнал не пролезет никогда.
    """
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value
    try:
        text = value if isinstance(value, str) else repr(value)
    except Exception:
        return '<repr failed>'
    if len(text) > MAX_VALUE_LEN:
        text = text[:MAX_VALUE_LEN] + '...'
    return text


def _host_of(url):
    """Только хост: сама прямая ссылка длинная и с подписью."""
    text = str(url or '')
    if '//' not in text:
        return ''
    try:
        return text.split('//', 1)[1].split('/', 1)[0]
    except Exception:
        return ''


def _ensure_dir():
    try:
        if not os.path.isdir(DATA_DIR):
            os.makedirs(DATA_DIR)
        return True
    except Exception:
        return False


# ---------------------------------------------------------------------
#  Запись события: вызывается из ЛЮБОГО потока
# ---------------------------------------------------------------------
def event(kind, **data):
    """
    Положить событие в память. Ни файлов, ни UI, ни console, ни сети.

    Рабочий поток загрузки вызывает только это. Наружу отсюда не уходит
    ни одно исключение: диагностика не имеет права уронить загрузку.
    """
    try:
        rec = {'ts': _now(), 'kind': str(kind),
               'session': _STATE['session_id'],
               'thread': threading.current_thread().name}
        for key, value in data.items():
            rec[str(key)] = _plain(value)
        with _LOCK:
            _EVENTS.append(rec)
            _PENDING.append(rec)
            _STATE['last_event'] = rec
    except Exception:
        pass


def job_event(kind, job, **extra):
    """
    Событие с обычным набором полей задания. Тоже из любого потока.

    Длительность блокирующего разбора ссылки считается ЗДЕСЬ: в самом
    загрузчике не должно появиться ни одной строки, кроме вызова
    диагностики, иначе «алгоритм не изменён» перестанет быть правдой.
    """
    try:
        job_id = getattr(job, 'id', None)
        if kind == 'resolve-start':
            _STARTED[job_id] = time.monotonic()
        elif kind in ('resolve-success', 'resolve-error'):
            began = _STARTED.pop(job_id, None)
            if began is not None and 'elapsed' not in extra:
                extra['elapsed'] = round(time.monotonic() - began, 3)
        data = {
            'job_id': job_id,
            'status': getattr(job, 'status', None),
            'stage': getattr(job, 'debug_stage', None),
            'attempt_no': getattr(job, 'attempt_no', None),
            'refresh': getattr(job, 'refresh_resolve_attempts', None),
            'refresh_reason': getattr(job, 'refresh_reason', None),
            'bytes': getattr(job, 'downloaded_bytes', None),
            'total': getattr(job, 'total_bytes', None),
        }
        data.update(extra)
        event(kind, **data)
    except Exception:
        pass


def http_event(kind, job, resp=None, **extra):
    """
    HTTP-событие: что попросили и что ответили. resp — объект с
    .getcode() и .headers, но обращение к нему целиком защищено.
    """
    try:
        data = {
            'offset': extra.pop('offset', None),
            'part_size': extra.pop('part_size', None),
            'range': extra.pop('range', None),
            'resolved_host': _host_of(getattr(job, 'resolved_url', '')),
            'http_diag': getattr(job, 'http_diag', None),
        }
        if resp is not None:
            try:
                data['code'] = resp.getcode()
            except Exception:
                data['code'] = None
            for key, header in (('content_length', 'Content-Length'),
                                ('content_range', 'Content-Range'),
                                ('accept_ranges', 'Accept-Ranges')):
                try:
                    data[key] = resp.headers.get(header)
                except Exception:
                    data[key] = None
        data.update(extra)
        job_event(kind, job, **data)
    except Exception:
        pass


def first_bytes(job, chunk_len):
    """
    Первые байты попытки — событие ровно одно, а не на каждый блок.
    Условие внутри специально: в загрузчике это одна строка вызова.
    """
    try:
        if int(getattr(job, 'last_read', 0) or 0) == int(chunk_len):
            job_event('http-first-bytes', job, chunk=chunk_len)
    except Exception:
        pass


def exception(where, exc=None, thread_name=None):
    """Полное исключение с трассировкой — в память и в последнее состояние."""
    try:
        exc = exc if exc is not None else sys.exc_info()[1]
        rec = {
            'where': str(where),
            'ts': _now(),
            'time': _stamp(),
            'session': _STATE['session_id'],
            'thread': thread_name or threading.current_thread().name,
            'type': type(exc).__name__ if exc is not None else None,
            'repr': _plain(exc),
            'traceback': ''.join(traceback.format_exception(
                type(exc), exc, getattr(exc, '__traceback__', None)))[-4000:],
        }
        with _LOCK:
            _STATE['last_exception'] = rec
            _MARKS['state_dirty'] = True
        event('exception', where=where, type=rec['type'], repr=rec['repr'])
    except Exception:
        pass


# ---------------------------------------------------------------------
#  Сессия
# ---------------------------------------------------------------------
def _new_session_id():
    try:
        return '%s-%04d' % (time.strftime('%Y%m%d-%H%M%S'),
                            random.randint(0, 9999))
    except Exception:
        return 'session-%d' % int(_now())


def read_last_session():
    """Прочитать записанное состояние. Ничего не импортирует."""
    try:
        with io.open(SESSION_PATH, encoding='utf-8') as f:
            data = json.load(f)
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def start_session():
    """
    Начало запуска NOX. Смотрит, чем кончился прошлый: если там осталось
    clean_exit=False, значит процесс не закрывали — его прервали.
    """
    previous = read_last_session()
    unclean = bool(previous) and not previous.get('clean_exit')
    with _LOCK:
        _STATE['session_id'] = _new_session_id()
        _STATE['started_at'] = _now()
        _STATE['last_heartbeat'] = None
        _STATE['clean_exit'] = False
        _STATE['previous_session_unclean'] = unclean
        _STATE['previous_session'] = _previous_summary(previous)
        _STATE['last_exception'] = None
        _STATE['jobs'] = []
        _STATE['pool'] = {}
        _STATE['threads'] = []
        _STATE['process_info'] = process_info()
        _MARKS['state_dirty'] = True
    event('session-start', previous_session_unclean=unclean,
          previous_session=(previous or {}).get('session_id'))
    flush(force=True)
    return _STATE['session_id']


def _previous_summary(previous):
    """
    Что осталось от прошлого запуска. Только факты: было ли исключение
    и каким было последнее состояние. Причину, по которой iOS убил
    процесс, отсюда узнать нельзя, и придумывать её мы не будем.
    """
    if not isinstance(previous, dict):
        return None
    return {
        'session_id': previous.get('session_id'),
        'started_at': previous.get('started_at'),
        'last_heartbeat': previous.get('last_heartbeat'),
        'clean_exit': bool(previous.get('clean_exit')),
        'had_exception': bool(previous.get('last_exception')),
        'last_event': previous.get('last_event'),
        'jobs': previous.get('jobs') or [],
        'pool': previous.get('pool') or {},
    }


def mark_clean_exit():
    """Штатное закрытие: следующий запуск не должен считать это аварией."""
    with _LOCK:
        _STATE['clean_exit'] = True
        _MARKS['state_dirty'] = True
    event('session-end', clean_exit=True)
    flush(force=True)


# ---------------------------------------------------------------------
#  Крючки на необработанные исключения
# ---------------------------------------------------------------------
def install_exception_hooks():
    """
    Записать любое необработанное исключение и передать его дальше.
    Исключение НЕ подавляется: диагностика не превращает сбой в тишину.
    """
    installed = []
    try:
        previous = sys.excepthook

        def hook(exc_type, exc, tb):
            try:
                if exc is not None and getattr(exc, '__traceback__', None) is None:
                    exc.__traceback__ = tb
                exception('sys.excepthook', exc)
                flush(force=True)
            except Exception:
                pass
            return previous(exc_type, exc, tb)

        sys.excepthook = hook
        installed.append('sys.excepthook')
    except Exception:
        pass
    try:
        previous_thread = threading.excepthook

        def thread_hook(args):
            try:
                exc = getattr(args, 'exc_value', None)
                thread = getattr(args, 'thread', None)
                if exc is not None and getattr(exc, '__traceback__', None) is None:
                    exc.__traceback__ = getattr(args, 'exc_traceback', None)
                exception('threading.excepthook', exc,
                          thread_name=getattr(thread, 'name', None))
            except Exception:
                pass
            return previous_thread(args)

        threading.excepthook = thread_hook
        installed.append('threading.excepthook')
    except Exception:
        pass          # на старых сборках threading.excepthook может не быть
    event('hooks-installed', hooks=', '.join(installed) or 'none')
    return installed


# ---------------------------------------------------------------------
#  Снимок заданий и пула
# ---------------------------------------------------------------------
def snapshot_jobs(jobs, manager=None):
    """
    Простые значения из уже существующих заданий. Никаких info dict,
    никаких ссылок на объекты — только то, что переживёт json.dump.
    """
    out = []
    for job in (jobs or []):
        try:
            part = getattr(job, 'part_path', '') or ''
            try:
                part_size = os.path.getsize(part) if part and \
                    os.path.exists(part) else None
            except Exception:
                part_size = None
            row = {
                'job_id': getattr(job, 'id', None),
                'title': _plain(getattr(job, 'title', '') or
                                getattr(job, 'display_title', '')),
                'url': _plain(getattr(job, 'url', '')),
                'quality': _plain(getattr(job, 'quality', '')),
                'status': getattr(job, 'status', None),
                'debug_stage': getattr(job, 'debug_stage', None),
                'error_stage': getattr(job, 'error_stage', None),
                'error': _plain(getattr(job, 'error', '')),
                'debug_error': _plain(getattr(job, 'debug_error', '')),
                'downloaded_bytes': getattr(job, 'downloaded_bytes', None),
                'total_bytes': getattr(job, 'total_bytes', None),
                'total_bytes_estimate': getattr(job, 'total_bytes_estimate',
                                                None),
                'part_size': part_size,
                'speed': getattr(job, 'speed', None),
                'eta': getattr(job, 'eta', None),
                'attempt_no': getattr(job, 'attempt_no', None),
                'refresh_resolve_attempts': getattr(
                    job, 'refresh_resolve_attempts', None),
                'refresh_reason': getattr(job, 'refresh_reason', None),
                'resolved_host': _host_of(getattr(job, 'resolved_url', '')),
                'http_diag': _plain(getattr(job, 'http_diag', '')),
                'pause_requested': bool(getattr(job, 'pause_requested', False)),
                'delete_requested': bool(getattr(job, 'delete_requested',
                                                 False)),
                'cancel_requested': bool(getattr(job, 'cancel_requested',
                                                 False)),
                'worker_alive': None,
                'queue_position': None,
            }
            if manager is not None:
                try:
                    row['worker_alive'] = bool(manager.worker_alive(job.id))
                except Exception:
                    pass
                try:
                    row['queue_position'] = manager.queue_position(job)
                except Exception:
                    pass
            out.append(row)
        except Exception:
            continue
    return out


def _pool_state(manager, jobs):
    """Сколько слотов занято и в каких состояниях висят задания."""
    pool = {'live_workers': None, 'free_slots': None, 'max_workers': None,
            'worker_job_ids': [], 'by_status': {}}
    try:
        pool['live_workers'] = manager.live_workers()
    except Exception:
        pass
    try:
        pool['free_slots'] = manager.free_slots()
    except Exception:
        pass
    try:
        # Константу берём у модуля менеджера, а не импортом: просмотрщик
        # обязан работать без nox_download.
        module = sys.modules.get(type(manager).__module__)
        pool['max_workers'] = getattr(module, 'MAX_CONCURRENT_DOWNLOADS', None)
    except Exception:
        pass
    try:
        pool['worker_job_ids'] = sorted(manager._workers.keys())
    except Exception:
        pass
    counts = {}
    for job in (jobs or []):
        try:
            key = str(getattr(job, 'status', '?'))
            counts[key] = counts.get(key, 0) + 1
        except Exception:
            continue
    pool['by_status'] = counts
    return pool


def _threads_state():
    out = []
    try:
        for t in threading.enumerate():
            try:
                out.append({'name': t.name, 'alive': bool(t.is_alive()),
                            'daemon': bool(t.daemon)})
            except Exception:
                continue
    except Exception:
        pass
    return out


def process_info():
    """
    Всё по отдельности и всё в try/except: что не удалось получить —
    остаётся null. Никаких выводов на этих числах мы не строим.
    """
    info = {'python': None, 'platform': None, 'threads': None,
            'gc_counts': None, 'max_rss': None}
    try:
        info['python'] = sys.version.split()[0]
    except Exception:
        pass
    try:
        info['platform'] = '%s %s' % (platform.system(), platform.release())
    except Exception:
        pass
    try:
        info['threads'] = threading.active_count()
    except Exception:
        pass
    try:
        info['gc_counts'] = list(gc.get_count())
    except Exception:
        pass
    try:
        import resource
        info['max_rss'] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    except Exception:
        pass
    return info


def heartbeat(manager=None, jobs=None, force=False):
    """
    Снимок состояния. Зовётся из существующего NoxApp._tick и сам себя
    ограничивает: не чаще раза в секунду. Своего таймера не создаёт.
    """
    try:
        now = _now()
        if not force and now - _MARKS['heartbeat'] < HEARTBEAT_INTERVAL:
            return False
        _MARKS['heartbeat'] = now
        if jobs is None and manager is not None:
            try:
                jobs = manager.all_jobs()
            except Exception:
                jobs = []
        rows = snapshot_jobs(jobs, manager)
        pool = _pool_state(manager, jobs) if manager is not None else {}
        with _LOCK:
            _STATE['last_heartbeat'] = now
            _STATE['jobs'] = rows
            _STATE['pool'] = pool
            _STATE['threads'] = _threads_state()
            _STATE['process_info'] = process_info()
            _MARKS['state_dirty'] = True
        return True
    except Exception:
        return False


# ---------------------------------------------------------------------
#  Запись на диск: ТОЛЬКО главный поток
# ---------------------------------------------------------------------
def _rotate_if_needed():
    try:
        if os.path.getsize(LOG_PATH) <= MAX_LOG_SIZE:
            return False
    except Exception:
        return False
    try:
        # os.replace сам затирает прошлый previous: истории всегда
        # ровно два файла, больше на диске не накапливается.
        os.replace(LOG_PATH, PREV_LOG_PATH)
        return True
    except Exception:
        return False


def flush(force=False):
    """
    Сбросить накопленное на диск. Вызывает ТОЛЬКО главный поток.

    Одно короткое действие: дописать накопленные строки и, если состояние
    менялось, перезаписать маленький файл последнего состояния.
    """
    try:
        now = _now()
        if not force and now - _MARKS['flush'] < FLUSH_INTERVAL:
            return False
        _MARKS['flush'] = now
        with _LOCK:
            pending = list(_PENDING)
            _PENDING.clear()
            dirty = _MARKS['state_dirty']
            state = dict(_STATE) if dirty else None
            _MARKS['state_dirty'] = False
        if not pending and not dirty:
            return False
        if not _ensure_dir():
            return False
        if pending:
            lines = []
            for rec in pending:
                try:
                    lines.append(json.dumps(rec, ensure_ascii=False))
                except Exception:
                    continue
            if lines:
                with io.open(LOG_PATH, 'a', encoding='utf-8') as f:
                    f.write('\n'.join(lines) + '\n')
                _rotate_if_needed()
        if state is not None:
            tmp = SESSION_PATH + '.tmp'
            with io.open(tmp, 'w', encoding='utf-8') as f:
                f.write(json.dumps(state, ensure_ascii=False, indent=1))
            os.replace(tmp, SESSION_PATH)
        return True
    except Exception:
        return False


def read_events(limit=50, path=None):
    """Последние события из журнала, от новых к старым."""
    out = []
    for target in ([path] if path else [LOG_PATH, PREV_LOG_PATH]):
        try:
            with io.open(target, encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        out.append(json.loads(line))
                    except Exception:
                        continue
        except Exception:
            continue
        if len(out) >= limit:
            break
    out.reverse()
    return out[:limit]


def clear_log():
    """Очистить журнал. Только по явному подтверждению из просмотрщика."""
    removed = 0
    for path in (LOG_PATH, PREV_LOG_PATH):
        try:
            if os.path.exists(path):
                os.remove(path)
                removed += 1
        except Exception:
            continue
    with _LOCK:
        _EVENTS.clear()
        _PENDING.clear()
    return removed


# ---------------------------------------------------------------------
#  Текстовый отчёт
# ---------------------------------------------------------------------
def _fmt_size(value):
    try:
        n = float(value)
    except Exception:
        return '—'
    for unit in ('B', 'KB', 'MB', 'GB', 'TB'):
        if n < 1024.0 or unit == 'TB':
            return ('%d %s' % (n, unit)) if unit == 'B' else \
                ('%.1f %s' % (n, unit))
        n /= 1024.0
    return '—'


def _fmt_time(ts):
    try:
        return time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(float(ts)))
    except Exception:
        return '—'


def session_verdict(session):
    """
    Чем кончилась сессия — ровно тем, что известно, без догадок.

    Пойманное исключение Python — факт. Отсутствие отметки о штатном
    закрытии фактом убийства процесса не является: её точно так же не
    будет, если человек просто запустил другой скрипт.
    """
    if not isinstance(session, dict):
        return 'НЕТ ДАННЫХ'
    if session.get('last_exception'):
        return 'PYTHON EXCEPTION'
    if session.get('clean_exit'):
        return 'NORMAL EXIT'
    return 'НЕ ОТМЕЧЕНО ШТАТНОЕ ЗАВЕРШЕНИЕ'


def report_text(session=None, events=None, limit=50):
    """
    Читаемая сводка последней сессии. Ничего не импортирует из NOX и
    работает даже тогда, когда остальное приложение не запускается.
    """
    session = session if session is not None else read_last_session()
    events = events if events is not None else read_events(limit)
    lines = ['NOX DEBUG', '=' * 52]
    if not session:
        lines.append('')
        lines.append('Журнала пока нет: запустите NOX хотя бы один раз.')
        return '\n'.join(lines)

    clean = bool(session.get('clean_exit'))
    exc = session.get('last_exception')
    lines.append('')
    lines.append('ПОСЛЕДНЯЯ СЕССИЯ: %s' % session_verdict(session))
    lines.append('')
    lines.append('Python exception: ' +
                 ('captured' if exc else 'none captured'))
    if not clean and not exc:
        # Не выдаём догадку за доказанный kill: отметку о штатном
        # закрытии могло не быть и просто потому, что человек запустил
        # другой скрипт Pythonista.
        lines.append('Предыдущая сессия не успела отметить штатное закрытие.')
        lines.append('Это может означать принудительное завершение процесса,')
        lines.append('перезапуск скрипта или остановку Pythonista.')
        lines.append('Точную причину без системного crash report iOS')
        lines.append('определить нельзя.')
    lines.append('')
    lines.append('Session ID     : %s' % session.get('session_id'))
    lines.append('Начало         : %s' % _fmt_time(session.get('started_at')))
    lines.append('Последний такт : %s' % _fmt_time(session.get(
        'last_heartbeat')))
    lines.append('Exception      : %s' % ('YES' if exc else 'NO'))
    if session.get('previous_session_unclean'):
        lines.append('Прошлый запуск : завершился аварийно')

    info = session.get('process_info') or {}
    lines.append('Python         : %s' % (info.get('python') or '—'))
    lines.append('Платформа      : %s' % (info.get('platform') or '—'))
    lines.append('max_rss        : %s' % (info.get('max_rss')
                                          if info.get('max_rss') is not None
                                          else '—'))

    pool = session.get('pool') or {}
    lines.append('')
    lines.append('ПОСЛЕДНЕЕ СОСТОЯНИЕ')
    lines.append('-' * 52)
    lines.append('live workers : %s / %s' % (pool.get('live_workers', '—'),
                                             pool.get('max_workers', '—')))
    lines.append('free slots   : %s' % pool.get('free_slots', '—'))
    lines.append('threads      : %s' % len(session.get('threads') or []))
    by_status = pool.get('by_status') or {}
    if by_status:
        lines.append('состояния    : %s' % ', '.join(
            '%s=%d' % (k, v) for k, v in sorted(by_status.items())))
    worker_ids = pool.get('worker_job_ids') or []
    if worker_ids:
        lines.append('workers      : %s' % ', '.join(worker_ids))

    jobs = session.get('jobs') or []
    lines.append('')
    if not jobs:
        lines.append('Заданий в очереди не было.')
    for row in jobs:
        lines.append('JOB %s' % row.get('job_id'))
        lines.append('  %s' % (row.get('title') or row.get('url') or ''))
        lines.append('  status : %s' % row.get('status'))
        lines.append('  stage  : %s' % (row.get('error_stage') or
                                        row.get('debug_stage')))
        lines.append('  bytes  : %s / %s  (.part %s)' % (
            _fmt_size(row.get('downloaded_bytes')),
            _fmt_size(row.get('total_bytes')),
            _fmt_size(row.get('part_size'))))
        lines.append('  worker : %s   queue: %s' % (
            'alive' if row.get('worker_alive') else 'dead',
            row.get('queue_position')))
        lines.append('  attempt: %s   refresh: %s   reason: %s' % (
            row.get('attempt_no'), row.get('refresh_resolve_attempts'),
            row.get('refresh_reason') or '—'))
        if row.get('resolved_host'):
            lines.append('  host   : %s' % row.get('resolved_host'))
        if row.get('error'):
            lines.append('  error  : %s' % row.get('error'))
        if row.get('http_diag'):
            lines.append('  http   : %s' % row.get('http_diag'))
        lines.append('')

    if exc:
        lines.append('ИСКЛЮЧЕНИЕ')
        lines.append('-' * 52)
        lines.append('%s в %s (%s)' % (exc.get('type'), exc.get('where'),
                                       exc.get('thread')))
        lines.append(exc.get('repr') or '')
        lines.append('')
        lines.append(exc.get('traceback') or '')
        lines.append('')

    lines.append('ПОСЛЕДНИЕ СОБЫТИЯ')
    lines.append('-' * 52)
    if not events:
        lines.append('Событий не записано.')
    for rec in events:
        lines.append('%s  %s' % (_stamp(rec.get('ts')), rec.get('kind')))
        detail = ' '.join(
            '%s=%s' % (k, rec[k]) for k in sorted(rec)
            if k not in ('ts', 'kind', 'session', 'thread')
            and rec[k] is not None)
        if rec.get('job_id'):
            lines.append('    %s [%s]' % (rec.get('job_id'),
                                          rec.get('thread')))
        if detail:
            lines.append('    %s' % detail[:300])
    return '\n'.join(lines)


# ---------------------------------------------------------------------
#  Просмотрщик: запуск самого nox_debug.py
# ---------------------------------------------------------------------
# Цвета просмотрщика. К теме NOX отношения не имеют: это отдельный
# служебный экран, и системная светлая тема ему запрещена.
BG = '#07080f'
FG = '#d8dcf0'
PANEL = '#161a33'


def _safe_insets(width, height):
    """
    Отступы под чёлку и домашний индикатор. Точных значений Pythonista
    не даёт, а тянуть objc_util в чёрный ящик незачем: у высоких экранов
    берём типовые 44/34, у остальных — обычные поля.
    """
    if height >= 800 or width >= 800:
        return 44.0, 34.0
    return 20.0, 8.0


_VIEW_CLASS = []


def _debug_view_class():
    """
    Класс экрана создаётся при первом обращении: ui нужен ТОЛЬКО
    просмотрщику, а nox_debug импортируют и загрузчик, и интерфейс.
    Держать import ui на уровне модуля ради этого нельзя.
    """
    if _VIEW_CLASS:
        return _VIEW_CLASS[0]
    import ui

    class DebugView(ui.View):
        """
        Экран диагностики. Отдельный класс, а НЕ view.layout = функция:
        присвоенная объекту функция в Pythonista не вызывается как метод, и
        TextView так и оставался стандартным прямоугольником 100x100 в углу.
        Здесь layout — настоящий метод, и он же срабатывает при повороте.
        """

        def __init__(self, width=393.0, height=852.0, **kwargs):
            ui.View.__init__(self, **kwargs)
            # Пока не собраны все части, layout не считает: присвоение
            # frame само дёргает layout, и без этого флага первый же вызов
            # приходил бы на полупустой объект.
            self._built = False
            self.name = 'NOX DEBUG'
            self.background_color = BG
            self.frame = (0, 0, width, height)

            self.title = ui.Label(frame=(16, 20, width - 100, 26))
            self.title.text = 'NOX DEBUG'
            self.title.text_color = FG
            self.title.font = ('Menlo-Bold', 17)
            self.add_subview(self.title)

            self.close_button = ui.Button(title='Закрыть')
            self.close_button.frame = (width - 92, 18, 76, 30)
            self.close_button.background_color = PANEL
            self.close_button.tint_color = FG
            self.close_button.corner_radius = 8
            self.close_button.action = self.do_close
            self.add_subview(self.close_button)

            # Рамка задаётся сразу: даже если layout по какой-то причине не
            # позовут, текст всё равно займёт экран, а не угол.
            self.text = ui.TextView(frame=(8, 56, width - 16, height - 110))
            self.text.background_color = BG
            self.text.text_color = FG
            self.text.font = ('Menlo', 11)
            self.text.editable = False
            self.text.selectable = True
            self.text.flex = 'WH'
            self.add_subview(self.text)

            self.buttons = []
            for title, action in (('Обновить', self.do_refresh),
                                  ('Скопировать отчёт', self.do_copy),
                                  ('Очистить журнал', self.do_clear)):
                b = ui.Button(title=title)
                b.background_color = PANEL
                b.tint_color = FG
                b.corner_radius = 8
                b.font = ('Menlo', 12)
                b.action = action
                self.add_subview(b)
                self.buttons.append(b)

            self._built = True
            self.layout()
            self.do_refresh(None)

        def layout(self):
            """Пересчёт при первом показе, при повороте и при смене размера."""
            if not getattr(self, '_built', False):
                return
            w, h = float(self.width), float(self.height)
            if w <= 1 or h <= 1:
                return
            top, bottom = _safe_insets(w, h)
            pad, head, bh = 8.0, 30.0, 36.0
            self.title.frame = (pad + 8, top, max(60.0, w - 116.0), head)
            self.close_button.frame = (w - 84.0, top - 2.0, 76.0, head + 4.0)
            row = h - bottom - bh
            count = len(self.buttons) or 1
            bw = (w - pad * (count + 1)) / count
            for i, b in enumerate(self.buttons):
                b.frame = (pad + i * (bw + pad), row, bw, bh)
            text_top = top + head + pad
            self.text.frame = (pad, text_top, w - pad * 2,
                               max(40.0, row - text_top - pad))

        # -- кнопки ----------------------------------------------------
        def do_refresh(self, sender):
            self.text.text = report_text()

        def do_copy(self, sender):
            try:
                import clipboard
                clipboard.set(self.text.text)
                sender.title = 'Скопировано'
            except Exception:
                if sender is not None:
                    sender.title = 'Буфер недоступен'

        def do_clear(self, sender):
            # Очистка — только со второго нажатия.
            if sender is not None and sender.title != 'Точно очистить?':
                sender.title = 'Точно очистить?'
                return
            clear_log()
            if sender is not None:
                sender.title = 'Очистить журнал'
            self.do_refresh(None)

        def do_close(self, sender):
            try:
                self.close()
            except Exception:
                pass



    _VIEW_CLASS.append(DebugView)
    return DebugView


def build_view(width=393.0, height=852.0):
    """Собрать экран без показа — этим же путём его проверяет стенд."""
    return _debug_view_class()(width=width, height=height)


def _viewer():
    """Тёмный экран во весь iPhone. Красота не нужна, нужна читаемость."""
    try:
        import ui
        width, height = ui.get_screen_size()
    except Exception:
        width, height = 393.0, 852.0
    view = build_view(width, height)
    view.present('fullscreen', hide_title_bar=False)


def main():
    try:
        _viewer()
    except Exception:
        # Ни ui, ни экрана — отчёт всё равно должен дойти до человека.
        print(report_text())


if __name__ == '__main__':
    main()

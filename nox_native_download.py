# coding: utf-8
"""
NOX / нативный транспорт загрузок: NSURLSessionDownloadTask.

ЭТО ТОЛЬКО МОСТ. Здесь нет ни очереди, ни состояний DownloadJob, ни
решений «протухла ссылка» или «пора повторить». Вся эта логика была и
остаётся в nox_download.py. Модуль умеет ровно четыре вещи: создать
фоновую сессию, запустить в ней задачу, приостановить/продолжить/отменить
её и складывать то, что сообщает система, в простые числовые поля.

ПОЧЕМУ ИМЕННО ТАК — по результатам испытаний на реальном iPhone.

1. Порядок обязателен: сначала yt-dlp extract_info, и только ПОСЛЕ него
   создание живой сессии. Обратный порядок завершал Pythonista нативно.
   Модуль этого сам не гарантирует — гарантирует вызывающий, — но
   session_live() специально существует, чтобы ему было чем проверить.

2. getAllTasksWithCompletionHandler: не используется НИГДЕ. На устройстве
   процесс погибал внутри Python-блока completion: в журнале осталось
   get-all-tasks-completion before и ничего после. Перечисление задач
   заменено собственным реестром, живущим в этом процессе.

3. delegateQueue — только NSOperationQueue.mainQueue(). С nil система
   зовёт Python-объект со своей фоновой очереди; проверенный стабильный
   вариант — главная очередь.

4. Колбэки не принимают ни одного решения и не трогают интерфейс. Они
   пишут числа в NativeContext, а решает потом nox_download на своём
   такте.
"""

import os
import io
import re
import sys
import time
import shutil
import tempfile
import threading


# Идентификатор фоновой сессии NOX. Постоянный: именно по нему iOS
# отличает нашу сессию от чужих.
# Префикс идентификатора фоновой сессии. Сам идентификатор к нему
# добавляет токен ЗАПУСКА и номер пачки, и получается вроде
# com.nox.pythonista.download.v2.a7f28c41.1
#
# Почему не один постоянный идентификатор, как было в v1. Reattach к
# задачам прошлой жизни процесса мы не делаем и не можем: getAllTasks
# запрещён, он уже нативно ронял Pythonista. А постоянный идентификатор
# ровно это и провоцировал — новый запуск поднимал сессию, к которой iOS
# честно доставляла колбэки задач ПРОШЛОГО запуска. Реестр их не знал,
# каждая писала native-stale-callback, и десятки таких колбэков в секунду
# заваливали главную очередь. Разные идентификаторы разводят запуски
# полностью.
NATIVE_SESSION_PREFIX = 'com.nox.pythonista.download.v2'

# Не чаще одного сообщения в столько секунд на одну и ту же чужую задачу.
STALE_LOG_EVERY = 10.0


def _boot_token():
    """Короткий идентификатор ЗАПУСКА. Годится и в имя ObjC-класса."""
    try:
        import uuid
        return uuid.uuid4().hex[:8]
    except Exception:
        return '%08x' % (int(time.time() * 1000) & 0xFFFFFFFF)

# Куда складывается скачанный системой кусок до слияния с .part.
STAGING_DIRNAME = 'native_staging'

# Коды, при которых тело ответа — это НЕ видео. Решение, что с ними
# делать, принимает nox_download; здесь только фиксируется факт.
OK_CODES = (200, 206)

# Единственные заголовки, которые уходят в NSURLSession.
#
# Список не выдуман: ровно с ним фоновый эксперимент на этом же iPhone
# реально качал файл — и с Home Screen, и с заблокированным экраном, и
# из другого приложения. Рабочий же NOX отправлял всё, что вернул
# yt-dlp, вместе с Sec-Fetch-Mode и прочими браузерными полями, и задача
# вставала на нуле байт: соединение создавалось, а данные не шли.
#
# Транспортные заголовки (Connection, Host, Content-Length,
# Transfer-Encoding) сюда не входят намеренно: их ставит сама
# NSURLSession, и чужая копия ей только мешает.
#
# Это правило ТОЛЬКО для нативного запроса. Прежний urllib-путь
# по-прежнему шлёт полный набор заголовков и не меняется.
NATIVE_HEADER_WHITELIST = ('User-Agent', 'Referer', 'Origin', 'Cookie',
                           'Accept', 'Accept-Language')

# Человеческие имена состояний NSURLSessionTask.
TASK_STATE_NAMES = {0: 'running', 1: 'suspended', 2: 'canceling',
                    3: 'completed'}


def safe_headers(headers):
    """
    Оставить из набора yt-dlp только те заголовки, что доказанно ходят.

    Сравнение имён без учёта регистра, наружу идёт каноническое имя из
    белого списка. Порядок — как в списке: так набор получается один и
    тот же при любом порядке словаря.
    """
    out = {}
    if not isinstance(headers, dict):
        return out
    for name in NATIVE_HEADER_WHITELIST:
        for got, value in headers.items():
            if str(got).lower() == name.lower() and value:
                out[name] = str(value)
                break
    return out


# =====================================================================
#  ЛЕНИВЫЙ objc_util
# =====================================================================
# Импорт расширения — тоже нативная операция, и делать её на импорте
# модуля незачем: NOX должен подниматься одинаково и там, где ObjC нет.

objc_util = None
ctypes = None
OBJC_ERROR = ''
_LOAD_TRIED = False


def load_objc():
    global objc_util, ctypes, OBJC_ERROR, _LOAD_TRIED
    if objc_util is not None and ctypes is not None:
        return True
    if _LOAD_TRIED:
        return False
    _LOAD_TRIED = True
    try:
        import ctypes as _ct
        import objc_util as _objc
    except Exception as e:
        OBJC_ERROR = repr(e)
        return False
    objc_util = _objc
    ctypes = _ct
    OBJC_ERROR = ''
    return True


def available():
    """Есть ли вообще нативный путь. Ничего не создаёт."""
    return load_objc()


def unavailable_reason():
    if available():
        return ''
    return OBJC_ERROR or 'objc_util недоступен'


def is_main_thread():
    """
    Главный ли это поток С ТОЧКИ ЗРЕНИЯ iOS.

    Спрашивать об этом Python нельзя, и это стоило одной сломанной
    сборки. Pythonista вызывает такт интерфейса с настоящего главного
    потока UIKit, но Python видит его как чужой поток и называет
    Dummy-1: threading.main_thread() — это поток, в котором стартовал
    интерпретатор, а не тот, на котором UIKit крутит цикл событий.
    Проверка `current_thread() is main_thread()` возвращала False на
    самом что ни на есть главном потоке, нативный старт не выполнялся
    никогда, а HTTP-путь такие задания намеренно пропускает — очередь
    вставала целиком.

    Ответ даёт сама система: +[NSThread isMainThread]. На рабочем потоке
    nox-download он вернёт False, и ObjC оттуда по-прежнему не тронут.

    Имя Dummy-N само по себе не значит НИЧЕГО и главным потоком не
    считается: спрашивается всегда NSThread.
    """
    try:
        if threading.current_thread() is threading.main_thread():
            return True
    except Exception:
        pass
    if not available():
        return False
    try:
        return bool(objc_util.ObjCClass('NSThread').isMainThread())
    except Exception:
        return False


def thread_report():
    """
    Что именно видно про текущий поток. Для одного диагностического
    события перед первым нативным стартом — не для каждого такта.
    """
    report = {'python_thread': '?', 'python_main': False, 'objc_main': False}
    try:
        report['python_thread'] = threading.current_thread().name
        report['python_main'] = (threading.current_thread()
                                 is threading.main_thread())
    except Exception:
        pass
    if available():
        try:
            report['objc_main'] = bool(
                objc_util.ObjCClass('NSThread').isMainThread())
        except Exception:
            pass
    return report


def _ns(text):
    return objc_util.ns(str(text))


def _responds(obj, selector):
    try:
        return bool(obj.respondsToSelector_(objc_util.sel(selector)))
    except Exception:
        return False


def _try_set(obj, selector, value):
    if not _responds(obj, selector):
        return False, 'нет селектора'
    try:
        getattr(obj, selector.replace(':', '_'))(value)
        return True, ''
    except Exception as e:
        return False, repr(e)


# =====================================================================
#  КОНТЕКСТ ОДНОЙ ЗАДАЧИ
# =====================================================================

ST_RUNNING = 'running'
ST_SUSPENDED = 'suspended'
ST_DONE = 'done'
ST_FAILED = 'failed'
ST_CANCELLED = 'cancelled'


class NativeContext(object):
    """
    Простые поля одной нативной задачи. Ни одного объекта ObjC наружу:
    и колбэк, и вызывающий работают только с числами и строками.

    key — (session_id, generation, taskIdentifier). Поколение обязательно:
    в новой сессии taskIdentifier снова начинается с единицы, и без
    поколения колбэк старой задачи попал бы в новую.
    """

    __slots__ = ('key', 'job_id', 'base_offset', 'received', 'expected',
                 'http_status', 'state', 'segment_path', 'error',
                 'range_ignored', 'updated', 'created', 'resumed_at',
                 'has_response', 'task_state', 'waiting_logged')

    def __init__(self, key, job_id, base_offset):
        self.key = key
        self.job_id = job_id
        self.base_offset = int(base_offset or 0)
        self.received = 0
        self.expected = 0
        self.http_status = 0
        self.state = ST_SUSPENDED
        self.segment_path = ''
        self.error = ''
        self.range_ignored = False
        self.created = time.time()
        self.updated = time.time()
        # Момент удавшегося resume(). От него, а не от создания задачи,
        # считается «сколько уже стоим на нуле»: пока задача не запущена,
        # ждать от неё байт не с чего.
        self.resumed_at = 0.0
        # Что говорит о задаче САМА система. Заполняется snapshot().
        self.has_response = False
        self.task_state = ''
        self.waiting_logged = False

    def copy(self):
        out = NativeContext(self.key, self.job_id, self.base_offset)
        for name in self.__slots__:
            setattr(out, name, getattr(self, name))
        return out

    @property
    def effective(self):
        """Сколько всего байт файла собрано: было на диске плюс принято."""
        return int(self.base_offset) + int(self.received or 0)

    @property
    def total(self):
        """
        Полный размер файла, если система его назвала.

        countOfBytesExpectedToReceive у Range-запроса — это размер ОСТАТКА,
        поэтому к нему прибавляется base_offset.
        """
        if self.expected and self.expected > 0:
            return int(self.base_offset) + int(self.expected)
        return 0


# =====================================================================
#  СОБЫТИЯ ДЛЯ ЖУРНАЛА
# =====================================================================
# Колбэк не зовёт nox_debug напрямую: чем меньше кода в ObjC-колбэке,
# тем лучше. События складываются в очередь, а разбирает её вызывающий
# на своём такте — уже обычным Python.

_EVENTS = []
_EVENTS_LOCK = threading.RLock()
_EVENTS_LIMIT = 200


def _emit(kind, **fields):
    try:
        with _EVENTS_LOCK:
            _EVENTS.append((kind, fields))
            del _EVENTS[:-_EVENTS_LIMIT]
    except Exception:
        pass


def drain_events():
    """Забрать накопленные события. Зовёт главный поток."""
    with _EVENTS_LOCK:
        out = list(_EVENTS)
        del _EVENTS[:]
    return out


# =====================================================================
#  STAGING
# =====================================================================

def _container_root():
    """
    Папка внутри контейнера приложения. Системный временный файл
    NSURLSession лежит там же, поэтому переименование в неё — операция
    за постоянное время, без копирования гигабайтов.
    """
    for path in (os.path.expanduser('~/Documents'), tempfile.gettempdir()):
        try:
            if path and os.path.isdir(path):
                return path
        except Exception:
            continue
    return tempfile.gettempdir()


def _same_device(a, b):
    try:
        return os.stat(a).st_dev == os.stat(b).st_dev
    except Exception:
        return False


def staging_dir(near=None, source=None):
    """
    Куда переносить готовый кусок.

    Правило одно: том назначения обязан совпадать с томом системного
    временного файла, иначе «перенос» превратится в копирование
    гигабайтов, а сделать его надо в колбэке на ГЛАВНОЙ очереди.

    Сначала пробуется папка рядом с данными NOX (так удобнее человеку),
    и только если она на другом томе — папка контейнера приложения.
    """
    candidates = []
    if near:
        candidates.append(os.path.join(near, STAGING_DIRNAME))
    candidates.append(os.path.join(_container_root(), 'NOX_' + STAGING_DIRNAME))
    made = []
    for path in candidates:
        try:
            os.makedirs(path, exist_ok=True)
        except Exception:
            continue
        if not os.path.isdir(path):
            continue
        made.append(path)
        if source is None or _same_device(path, source):
            return path
    return made[-1] if made else candidates[-1]


def safe_key_name(key):
    text = '-'.join(str(p) for p in key)
    return re.sub(r'[^A-Za-z0-9_.-]', '_', text)[:80]


# =====================================================================
#  ТРАНСПОРТ
# =====================================================================

class NativeTransport(object):
    """
    Одна фоновая сессия и до нескольких задач в ней.

    Сессия создаётся ЛЕНИВО, только когда есть что качать, и закрывается,
    когда качать больше нечего. Так вызывающий получает окно, в котором
    живой сессии нет и можно безопасно позвать yt-dlp.
    """

    def __init__(self, session_id=None, data_dir=None):
        # Токен этого запуска. Всё, что создаётся ниже — имя класса
        # делегата и идентификаторы сессий — включает его, поэтому чужой
        # жизни процесса здесь принадлежать не может ничто.
        self.boot_token = _boot_token()
        self.session_prefix = session_id or NATIVE_SESSION_PREFIX
        self.session_id = ''      # идентификатор ТЕКУЩЕЙ сессии
        self.data_dir = data_dir
        self.generation = 0
        self.config_report = {}
        self.last_error = ''
        self._lock = threading.RLock()
        # Сильные ссылки: сборщик мусора Python не должен освободить то,
        # чем ещё пользуется iOS.
        self._session = None
        self._delegate = None
        self._delegate_class = None
        self.delegate_name = ''
        self._config = None
        self._tasks = {}          # key -> ObjC task
        self._ctx = {}            # key -> NativeContext
        self._by_job = {}         # job_id -> key
        # Указатель ObjC-сессии -> её идентификатор. По нему колбэк
        # находит ТУ сессию, которая пришла ему аргументом, а не ту, что
        # у транспорта считается текущей.
        self._sessions_by_ptr = {}
        # Учёт чужих колбэков: (сессия, номер задачи) -> [когда писали,
        # сколько подавили]. Нужен, чтобы поток чужих колбэков не
        # превращался в поток записей в журнал.
        self._stale = {}

    # -- состояние -------------------------------------------------
    def session_live(self):
        with self._lock:
            return self._session is not None

    def contexts(self):
        with self._lock:
            return [c.copy() for c in self._ctx.values()]

    def context(self, key):
        with self._lock:
            c = self._ctx.get(key)
            return c.copy() if c is not None else None

    def context_of_job(self, job_id):
        with self._lock:
            key = self._by_job.get(job_id)
            c = self._ctx.get(key) if key else None
            return c.copy() if c is not None else None

    def key_of_job(self, job_id):
        with self._lock:
            return self._by_job.get(job_id)

    # Три РАЗНЫХ вопроса, которые раньше отвечались одним числом и от
    # этого путались:
    #
    #   running_count()  — сколько задач реально тянут байты. Это и есть
    #                      занятые слоты передачи.
    #   paused_count()   — сколько задач приостановлено. Слот они не
    #                      занимают, но сессию держат: iOS не считает
    #                      сессию завершённой, пока у неё есть
    #                      незаконченные задачи, в том числе suspended.
    #   session_live()   — существует ли объект сессии. Пока существует,
    #                      yt-dlp звать нельзя.
    #   transfer_active()— идёт ли передача прямо сейчас.
    #
    # Смешивать их в busy_count() было ошибкой: приостановленное задание
    # запрещало разбор новых ссылок ровно так же, как работающее.
    def running_count(self):
        with self._lock:
            return len([c for c in self._ctx.values()
                        if c.state == ST_RUNNING])

    def paused_count(self):
        with self._lock:
            return len([c for c in self._ctx.values()
                        if c.state == ST_SUSPENDED])

    def transfer_active(self):
        return self.running_count() > 0

    def pinned_contexts(self):
        """
        Приостановленные задачи, которые держат сессию, и сколько байт у
        каждой лежит только во временном файле системы.

        Эти байты нигде больше не существуют: в .part они попадают лишь
        после того, как задача ЗАВЕРШИТСЯ и её файл будет слит. Поэтому
        решение «отпустить задачу ради разбора новой ссылки» — это всегда
        решение потерять ровно столько байт, и принимать его должен
        менеджер, зная число.
        """
        with self._lock:
            return [c.copy() for c in self._ctx.values()
                    if c.state == ST_SUSPENDED]

    def release(self, job_id):
        """
        Отпустить задачу и снять её с сессии.

        task.cancel() без resumeData уничтожает временный файл системы:
        всё, что задача успела скачать и не успело попасть в .part,
        пропадает. Поэтому метод называется release, а не pause, и зовут
        его только осознанно — когда сессию нужно освободить.

        Возвращает, сколько байт при этом потеряно.
        """
        key, task = self._task_of(job_id)
        if task is None:
            return 0, 'задачи нет'
        with self._lock:
            ctx = self._ctx.get(key)
            lost = int(ctx.received or 0) if ctx is not None else 0
        try:
            task.cancel()
        except Exception as e:
            self.forget(job_id)
            return lost, repr(e)
        self.forget(job_id)
        _emit('native-checkpoint-drop', job_id=job_id, task_id=key[1],
              lost_bytes=lost)
        return lost, ''

    def busy_count(self):
        """
        Устаревшее: сумма работающих и приостановленных. Оставлено, чтобы
        не ломать внешних читателей, но в решениях НЕ участвует.
        """
        return self.running_count() + self.paused_count()

    # -- сессия ----------------------------------------------------
    def open_session(self):
        """
        Создать фоновую сессию. Зовётся ТОЛЬКО когда разбор ссылок уже
        закончен: живая сессия и yt-dlp вместе не уживаются.
        """
        with self._lock:
            if self._session is not None:
                return True, ''
        if not available():
            self.last_error = unavailable_reason()
            return False, self.last_error
        # Идентификатор новой пачки: префикс, токен запуска, номер.
        # Один и тот же на всю жизнь этой сессии — Home Screen, блокировка
        # экрана и переход в другое приложение её не меняют.
        session_id = '%s.%s.%d' % (self.session_prefix, self.boot_token,
                                   self.generation + 1)
        try:
            cfg_cls = objc_util.ObjCClass('NSURLSessionConfiguration')
            cfg = cfg_cls.backgroundSessionConfigurationWithIdentifier_(
                _ns(session_id))
            if cfg is None:
                raise RuntimeError('backgroundSessionConfiguration вернул nil')
        except Exception as e:
            self.last_error = repr(e)
            _emit('native-session-error', stage='configuration', exc=repr(e))
            return False, self.last_error
        report = {}
        for selector, value in (('setAllowsCellularAccess:', True),
                                ('setDiscretionary:', False),
                                ('setSessionSendsLaunchEvents:', True),
                                ('setWaitsForConnectivity:', True)):
            ok, why = _try_set(cfg, selector, value)
            report[selector] = 'ok' if ok else why
        try:
            delegate = self._build_delegate()
            queue = objc_util.ObjCClass('NSOperationQueue').mainQueue()
            session = objc_util.ObjCClass(
                'NSURLSession').sessionWithConfiguration_delegate_delegateQueue_(
                    cfg, delegate, queue)
            if session is None:
                raise RuntimeError('sessionWithConfiguration вернул nil')
        except Exception as e:
            self.last_error = repr(e)
            _emit('native-session-error', stage='session', exc=repr(e))
            return False, self.last_error
        with self._lock:
            self._config = cfg
            self._session = session
            self.generation += 1
            self.session_id = session_id
            self._sessions_by_ptr[_ptr_of(session)] = session_id
            self.config_report = report
        self.last_error = ''
        _emit('native-session-created', identifier=session_id,
              generation=self.generation, queue='mainQueue')
        return True, ''

    def invalidate(self):
        """
        Закрыть сессию, когда качать нечего.

        Именно этим открывается окно для yt-dlp: пока сессии нет, разбор
        ссылки безопасен. Задачи к этому моменту уже завершены — вызывать
        при живых задачах нельзя, и это проверяет вызывающий.
        """
        with self._lock:
            session = self._session
            self._session = None
            self._config = None
            self._tasks.clear()
            self._ctx.clear()
            self._by_job.clear()
            self._stale.clear()
            # Указатель закрытой сессии снимаем с учёта: её поздние
            # колбэки должны считаться чужими, а не попадать в новую пачку.
            if session is not None:
                self._sessions_by_ptr.pop(_ptr_of(session), None)
            generation = self.generation
            self.session_id = ''
        if session is None:
            return True, ''
        try:
            # finishTasksAndInvalidate, а не invalidateAndCancel: отменять
            # тут нечего, а грубая отмена могла бы оборвать хвост записи.
            session.finishTasksAndInvalidate()
        except Exception as e:
            _emit('native-session-error', stage='invalidate', exc=repr(e))
            return False, repr(e)
        _emit('native-session-invalidated', generation=generation)
        return True, ''

    # -- задачи ----------------------------------------------------
    def start(self, job_id, url, headers=None, base_offset=0):
        """
        NSMutableURLRequest -> downloadTaskWithRequest: -> resume().

        base_offset > 0 — значит на диске уже лежит .part, и система
        должна привезти ТОЛЬКО остаток: в запрос идёт Range с этого
        смещения. Существующий файл при этом не трогается вообще.
        """
        ok, err = self.open_session()
        if not ok:
            return None, err
        offset = int(base_offset or 0)
        try:
            req = objc_util.ObjCClass('NSMutableURLRequest').requestWithURL_(
                objc_util.nsurl(str(url)))
            req.setHTTPMethod_(_ns('GET'))
            # В запрос идёт только белый список — тот самый набор, с
            # которым фоновый эксперимент на этом устройстве качал файл.
            allowed = safe_headers(headers)
            dropped = sorted(str(k) for k in (headers or {})
                             if str(k).lower() not in
                             [n.lower() for n in allowed])
            sent = []
            for name, value in allowed.items():
                try:
                    req.setValue_forHTTPHeaderField_(_ns(value), _ns(name))
                    sent.append(str(name))
                except Exception:
                    continue
            if offset > 0:
                # Продолжение: границы Range и размер на диске обязаны
                # считаться в одних и тех же байтах, поэтому сжатие
                # запрещается явно.
                req.setValue_forHTTPHeaderField_(_ns('identity'),
                                                 _ns('Accept-Encoding'))
                req.setValue_forHTTPHeaderField_(
                    _ns('bytes=%d-' % offset), _ns('Range'))
            # Для загрузки с нуля Accept-Encoding не ставится вовсе:
            # именно так выглядел проверенный на устройстве запрос, и
            # придумывать ему отличия незачем.
        except Exception as e:
            _emit('native-task-error', job_id=job_id, stage='request',
                  exc=repr(e))
            return None, repr(e)
        try:
            with self._lock:
                session = self._session
            if session is None:
                return None, 'сессия закрыта'
            task = session.downloadTaskWithRequest_(req)
            if task is None:
                raise RuntimeError('downloadTaskWithRequest вернул nil')
            tid = int(task.taskIdentifier())
        except Exception as e:
            _emit('native-task-error', job_id=job_id, stage='create',
                  exc=repr(e))
            return None, repr(e)
        # Ключ включает идентификатор ИМЕННО ЭТОЙ сессии. Номера задач в
        # новой сессии снова начинаются с единицы, и без идентификатора
        # колбэк чужой задачи мог бы попасть в чужое задание.
        with self._lock:
            session_id = self.session_id
        key = (session_id, tid)
        ctx = NativeContext(key, job_id, offset)
        with self._lock:
            self._tasks[key] = task
            self._ctx[key] = ctx
            self._by_job[job_id] = key
        _emit('native-task-created', job_id=job_id, task_id=tid,
              session=session_id, base_offset=offset,
              headers=','.join(sent), dropped=','.join(dropped) or None,
              ranged=bool(offset))
        try:
            task.resume()
            with self._lock:
                ctx.state = ST_RUNNING
                ctx.resumed_at = time.monotonic()
                ctx.updated = time.time()
        except Exception as e:
            _emit('native-task-error', job_id=job_id, stage='resume',
                  exc=repr(e))
            return key, repr(e)
        _emit('native-task-resume', job_id=job_id, task_id=tid,
              base_offset=offset)
        return key, ''

    def suspend(self, job_id):
        """Пауза: task.suspend(). Ни файл, ни задача не уничтожаются."""
        key, task = self._task_of(job_id)
        if task is None:
            return False, 'задачи нет'
        try:
            task.suspend()
        except Exception as e:
            return False, repr(e)
        with self._lock:
            ctx = self._ctx.get(key)
            if ctx is not None:
                ctx.state = ST_SUSPENDED
                ctx.updated = time.time()
        _emit('native-task-pause', job_id=job_id, task_id=key[1])
        return True, ''

    def resume(self, job_id):
        """Продолжение уже существующей задачи. yt-dlp тут ни при чём."""
        key, task = self._task_of(job_id)
        if task is None:
            return False, 'задачи нет'
        try:
            task.resume()
        except Exception as e:
            return False, repr(e)
        with self._lock:
            ctx = self._ctx.get(key)
            if ctx is not None:
                ctx.state = ST_RUNNING
                # Отсчёт «стоим на нуле» начинается заново с каждого
                # запуска: пауза в этот срок не входит.
                ctx.resumed_at = time.monotonic()
                ctx.updated = time.time()
        _emit('native-task-resume', job_id=job_id, task_id=key[1])
        return True, ''

    def cancel(self, job_id):
        """
        Отмена. cancelByProducingResumeData: НЕ используется: он снова
        потребовал бы Python-блок completion, а именно на таком блоке
        процесс погибал на устройстве.
        """
        key, task = self._task_of(job_id)
        if task is None:
            return False, 'задачи нет'
        try:
            task.cancel()
        except Exception as e:
            return False, repr(e)
        with self._lock:
            ctx = self._ctx.get(key)
            if ctx is not None:
                ctx.state = ST_CANCELLED
                ctx.updated = time.time()
        _emit('native-task-cancel', job_id=job_id, task_id=key[1])
        return True, ''

    def snapshot(self, job_id, absorb=True):
        """
        Спросить о задаче САМУ систему, а не только колбэк.

        Зачем. До сих пор единственным источником прогресса был делегат:
        пока не пришёл didWriteData, задание стояло на нуле. На устройстве
        это и вышло наружу — задача создана, resume() прошёл, а в журнале
        received=0 раз за разом, и понять, идут ли байты вообще, было
        нечем. У NSURLSessionTask есть собственные счётчики, и они
        отвечают независимо от того, доехал ли колбэк.

        Наружу отдаются ТОЛЬКО обычные значения Python: ни одного объекта
        ObjC отсюда не уходит. Зовёт метод главный поток на своём такте,
        своих потоков он не заводит.

        absorb=True — заодно подтянуть в контекст всё, что система уже
        насчитала. Значения только растут: показание системы никогда не
        уменьшает то, что уже принёс колбэк.
        """
        key, task = self._task_of(job_id)
        if task is None:
            return {}
        out = {'task_id': key[1], 'task_state': '', 'received': 0,
               'expected': 0, 'http_status': 0, 'has_response': False,
               'error_text': ''}
        try:
            got = int(task.countOfBytesReceived())
            out['received'] = got if got > 0 else 0
        except Exception:
            pass
        try:
            # -1 (NSURLSessionTransferSizeUnknown) означает «размер
            # неизвестен», и выдавать его за число байт нельзя.
            exp = int(task.countOfBytesExpectedToReceive())
            out['expected'] = exp if exp > 0 else 0
        except Exception:
            pass
        try:
            out['task_state'] = TASK_STATE_NAMES.get(int(task.state()),
                                                     str(task.state()))
        except Exception:
            pass
        try:
            resp = task.response()
            if resp is not None:
                out['has_response'] = True
                try:
                    code = int(resp.statusCode())
                    out['http_status'] = code if code > 0 else 0
                except Exception:
                    pass                # не HTTP-ответ: кода просто нет
        except Exception:
            pass
        try:
            err = task.error()
            if err is not None:
                out['error_text'] = str(err.localizedDescription())
        except Exception:
            pass
        if absorb:
            with self._lock:
                ctx = self._ctx.get(key)
                if ctx is not None:
                    if out['received'] > int(ctx.received or 0):
                        ctx.received = out['received']
                        ctx.updated = time.time()
                    if out['expected'] > int(ctx.expected or 0):
                        ctx.expected = out['expected']
                    if out['http_status'] and not ctx.http_status:
                        ctx.http_status = out['http_status']
                    ctx.has_response = bool(out['has_response'])
                    ctx.task_state = out['task_state']
        return out

    def mark_waiting_logged(self, job_id):
        """
        Отметить, что об ожидании первых байт уже написано.

        Нужно ровно для того, чтобы не писать об этом на каждом такте:
        одно сообщение на задачу, а дальше молча.
        """
        key, _task = self._task_of(job_id)
        if key is None:
            return False
        with self._lock:
            ctx = self._ctx.get(key)
            if ctx is None or ctx.waiting_logged:
                return False
            ctx.waiting_logged = True
        return True

    def forget(self, job_id):
        """Убрать задание из реестра. Поздние колбэки станут stale."""
        with self._lock:
            key = self._by_job.pop(job_id, None)
            if key is None:
                return False
            self._tasks.pop(key, None)
            self._ctx.pop(key, None)
        return True

    def _task_of(self, job_id):
        with self._lock:
            key = self._by_job.get(job_id)
            if key is None:
                return None, None
            return key, self._tasks.get(key)

    # -- делегат ---------------------------------------------------
    def _lookup(self, session_ptr, task_ptr):
        """
        Связать колбэк с заданием по ТОЙ сессии, которая пришла ему
        аргументом, — а не по той, что транспорт считает текущей сейчас.

        Так было не всегда, и это стоило лавины чужих колбэков после
        перезапуска: ключ строился из self.generation НА МОМЕНТ ПОЛУЧЕНИЯ
        колбэка, поэтому поздний колбэк закрытой сессии сравнивался с
        номерами живой. Теперь указатель сессии переводится в её
        собственный идентификатор, и чужая сессия просто не находится.

        Ни current_job, ни last_job здесь нет и быть не может.
        """
        try:
            tid = int(objc_util.ObjCInstance(task_ptr).taskIdentifier())
        except Exception:
            tid = -1
        try:
            with self._lock:
                token = self._sessions_by_ptr.get(_ptr_of(session_ptr))
        except Exception:
            token = None
        if token is None:
            self._note_stale(None, tid)
            return None, None
        key = (token, tid)
        with self._lock:
            ctx = self._ctx.get(key)
        if ctx is None:
            self._note_stale(token, tid)
            return None, None
        return key, ctx

    def _note_stale(self, token, tid):
        """
        Чужой колбэк. Ничего не меняем и почти ничего не пишем.

        Считать это ошибкой нельзя: система вправе досылать колбэки
        задачам, которых мы больше не ведём. А вот писать о каждом в
        журнал — нельзя тем более: на устройстве такие сообщения шли
        десятками в секунду и сами по себе нагружали главную очередь.
        Пишем первый раз и потом не чаще раза в STALE_LOG_EVERY, указывая,
        сколько сообщений было подавлено.
        """
        pair = (token or 'unknown', tid)
        now = time.monotonic()
        report = None
        try:
            with self._lock:
                seen = self._stale.get(pair)
                if seen is None:
                    if len(self._stale) > 64:
                        self._stale.clear()
                    self._stale[pair] = [now, 0]
                    report = 0
                else:
                    seen[1] += 1
                    if now - seen[0] >= STALE_LOG_EVERY:
                        report = seen[1]
                        seen[0] = now
                        seen[1] = 0
        except Exception:
            return
        if report is None:
            return
        if report:
            _emit('native-stale-callback', session=pair[0], task_id=tid,
                  suppressed=report)
        else:
            _emit('native-stale-callback', session=pair[0], task_id=tid)

    def _build_delegate(self):
        if self._delegate is not None:
            return self._delegate
        transport = self

        def URLSession_downloadTask_didWriteData_totalBytesWritten_totalBytesExpectedToWrite_(
                _self, _cmd, session, task, written, total_written,
                total_expected):
            try:
                key, ctx = transport._lookup(session, task)
                if ctx is None:
                    return
                with transport._lock:
                    ctx.received = int(total_written)
                    ctx.expected = int(total_expected) \
                        if int(total_expected) > 0 else 0
                    ctx.state = ST_RUNNING
                    ctx.updated = time.time()
            except Exception:
                pass

        def URLSession_downloadTask_didFinishDownloadingToURL_(
                _self, _cmd, session, task, location):
            try:
                key, ctx = transport._lookup(session, task)
                if ctx is None:
                    return
                transport._finish(key, ctx, task, location)
            except Exception:
                pass

        def URLSession_task_didCompleteWithError_(_self, _cmd, session, task,
                                                  err):
            try:
                key, ctx = transport._lookup(session, task)
                if ctx is None:
                    return
                text = ''
                if err:
                    try:
                        text = str(objc_util.ObjCInstance(
                            err).localizedDescription())
                    except Exception:
                        text = 'ошибка без описания'
                code = transport._status_of(task)
                with transport._lock:
                    if code:
                        ctx.http_status = code
                    if text:
                        ctx.error = text
                        if ctx.state not in (ST_DONE, ST_CANCELLED):
                            ctx.state = ST_FAILED
                    elif ctx.state == ST_RUNNING:
                        # Ошибки нет, а файла мы не увидели — считаем
                        # неудачей: решение примет nox_download.
                        ctx.state = ST_FAILED if not ctx.segment_path \
                            else ST_DONE
                    ctx.updated = time.time()
                _emit('native-task-complete', job_id=ctx.job_id,
                      task_id=key[1], http=code, error=text or None,
                      received=ctx.received)
            except Exception:
                pass

        def URLSessionDidFinishEventsForBackgroundURLSession_(_self, _cmd,
                                                              session):
            try:
                _emit('native-session-events-done')
            except Exception:
                pass

        def URLSession_downloadTask_didResumeAtOffset_expectedTotalBytes_(
                _self, _cmd, session, task, offset, expected):
            try:
                key, ctx = transport._lookup(session, task)
                if ctx is None:
                    return
                with transport._lock:
                    ctx.received = int(offset)
                    ctx.expected = int(expected) if int(expected) > 0 else 0
                    ctx.state = ST_RUNNING
                    ctx.updated = time.time()
            except Exception:
                pass

        methods = [
            URLSession_downloadTask_didWriteData_totalBytesWritten_totalBytesExpectedToWrite_,
            URLSession_downloadTask_didFinishDownloadingToURL_,
            URLSession_task_didCompleteWithError_,
            URLSessionDidFinishEventsForBackgroundURLSession_,
            URLSession_downloadTask_didResumeAtOffset_expectedTotalBytes_,
        ]
        # Кодировки заданы вручную и сверены с сигнатурами Apple: у трёх
        # селекторов аргументы int64_t, и без явного описания мост принял
        # бы их за указатели на объекты.
        for fn, enc in zip(methods, (b'v@:@@qqq', b'v@:@@@', b'v@:@@@',
                                     b'v@:@', b'v@:@@qq')):
            fn.encoding = enc
        # Имя КЛАССА включает токен запуска, и это принципиально.
        #
        # Objective-C класс остаётся зарегистрированным в рантайме и после
        # того, как Python-скрипт перезапустили. Раньше имя было
        # постоянным, и при повторном запуске сюда приходило исключение
        # «класс уже есть», а код молча брал старый класс. Его методы —
        # это Python-функции ПРОШЛОГО запуска, замкнутые на ПРОШЛЫЙ
        # transport. Колбэки уходили в объект, которого в новой жизни
        # приложения уже нет.
        #
        # Уникальное имя убирает это целиком: каждый запуск получает свой
        # класс, свои функции и свой transport. Подхвата чужого класса
        # здесь больше нет — если создать не удалось, это управляемый
        # отказ, и работает запасной HTTP-путь.
        name = 'NOXDownloadDelegate_%s' % self.boot_token.upper()
        cls = objc_util.create_objc_class(
            name, objc_util.ObjCClass('NSObject'), methods=methods,
            protocols=['NSURLSessionDelegate', 'NSURLSessionTaskDelegate',
                       'NSURLSessionDownloadDelegate'])
        self._delegate_class = cls
        self.delegate_name = name
        self._delegate = cls.alloc().init()
        return self._delegate

    @staticmethod
    def _status_of(task):
        try:
            resp = objc_util.ObjCInstance(task).response()
            if resp is None:
                return 0
            return int(resp.statusCode())
        except Exception:
            return 0

    def _finish(self, key, ctx, task, location):
        """
        Готовый кусок надо ВЫВЕСТИ из системной временной папки прямо
        здесь: как только колбэк вернёт управление, iOS её очистит.

        Но это главная очередь, и копировать гигабайты тут нельзя. Спасает
        выбор места: staging_dir подбирает папку на ТОМ ЖЕ томе, что и
        временный файл, и тогда перенос — переименование за постоянное
        время. Слияние с .part потом делает рабочий поток, кусками.
        """
        code = self._status_of(task)
        try:
            src = str(objc_util.ObjCInstance(location).path())
        except Exception as e:
            with self._lock:
                ctx.state = ST_FAILED
                ctx.error = 'нет пути временного файла: %r' % (e,)
                ctx.updated = time.time()
            return
        ranged = ctx.base_offset > 0
        if code and code not in OK_CODES:
            # Тело есть, но это не видео, а страница ошибки. Забирать
            # такой файл нельзя ни при каких обстоятельствах.
            with self._lock:
                ctx.http_status = code
                ctx.state = ST_FAILED
                ctx.error = 'HTTP %d' % code
                ctx.updated = time.time()
            _emit('native-http-expired', job_id=ctx.job_id, task_id=key[1],
                  http=code)
            return
        if ranged and code == 200:
            # Range проигнорирован: приехал ВЕСЬ файл, а не остаток.
            # Дописать его в .part значило бы испортить файл.
            with self._lock:
                ctx.http_status = code
                ctx.range_ignored = True
                ctx.state = ST_FAILED
                ctx.error = 'сервер ответил 200 на Range'
                ctx.updated = time.time()
            _emit('native-range-ignored', job_id=ctx.job_id, task_id=key[1])
            return
        target_dir = staging_dir(near=self.data_dir, source=src)
        dest = os.path.join(target_dir,
                            'seg-%s.part' % safe_key_name(key))
        moved = ''
        try:
            if os.path.exists(dest):
                os.remove(dest)
        except Exception:
            pass
        try:
            fm = objc_util.ObjCClass('NSFileManager').defaultManager()
            url_cls = objc_util.ObjCClass('NSURL')
            if fm.moveItemAtURL_toURL_error_(
                    objc_util.ObjCInstance(location),
                    url_cls.fileURLWithPath_(_ns(dest)), None):
                moved = dest
        except Exception as e:
            _emit('native-temp-move-error', job_id=ctx.job_id, exc=repr(e))
        if not moved:
            # Запасной путь. Он на том же томе, значит os.replace — это
            # тоже переименование, а не копирование.
            try:
                os.replace(src, dest)
                moved = dest
            except Exception as e:
                _emit('native-temp-move-error', job_id=ctx.job_id,
                      exc=repr(e), stage='os.replace')
        with self._lock:
            if moved:
                ctx.segment_path = moved
                ctx.http_status = code or ctx.http_status
                ctx.state = ST_DONE
            else:
                ctx.state = ST_FAILED
                ctx.error = 'не удалось забрать временный файл'
            ctx.updated = time.time()
        if moved:
            _emit('native-temp-moved', job_id=ctx.job_id, task_id=key[1],
                  path=moved, size=_size_of(moved),
                  same_device=_same_device(target_dir, os.path.dirname(src)))


def _ptr_of(obj):
    """
    Устойчивый числовой идентификатор ObjC-объекта.

    Нужен ровно для одного: понять, ОТ КАКОЙ сессии пришёл колбэк.
    Указатель для этого годится и стоит дёшево; если моста нет или у
    объекта нет ptr, берём id() — в подделке стенда и в тестах этого
    достаточно, а на устройстве работает первый путь.
    """
    try:
        inst = objc_util.ObjCInstance(obj) if objc_util is not None else obj
    except Exception:
        inst = obj
    try:
        raw = getattr(inst, 'ptr', None)
        if raw is not None:
            value = getattr(raw, 'value', raw)
            if value is not None:
                return int(value)
    except Exception:
        pass
    try:
        return int(id(inst))
    except Exception:
        return 0


def _size_of(path):
    try:
        return int(os.path.getsize(path))
    except Exception:
        return 0
